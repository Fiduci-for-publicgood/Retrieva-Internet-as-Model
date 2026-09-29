"""Many parse shapes over one sentence; an ensemble votes.

Each parser maps a sentence to (subject, predicate, object) triples using a different strategy, so
each is right where the others are wrong:

  ll       left-to-right, leftmost verb wins                       (extract.py, the baseline)
  llr      LL + right-context lookahead: skips noun-ish verbs ("the increase in sales reduces ...")
  lr       shift-reduce (bottom-up): NP V NP -> S, coordinated VPs share a subject, relative clauses
  rl       backwards: rightmost verb first; elliptical verbs inherit the subject to their left
  qar      question -> claim ("Does X improve Y?", "Is X safe?") and question+answer pairs
  passive  "Y is improved by X" -> (X, improve, Y)
  pattern  phrase table: "leads to", "is good for", "is associated with", ...
  center   the verb nearest the middle of the clause
  head     head-noun triples: nearest content word each side of each verb

extract_all() clusters what they emit, counts votes, and drops a reading only when an equally- or
better-voted parser contradicts its polarity.
"""
from __future__ import annotations

import re
from functools import lru_cache

from .extract import MAX_SIDE, content, extract, polarity, tokens
from .lexicon import ADJ, AUX, BREAKS, NEGATORS, STOP, VERBS

DET = {"the", "a", "an", "this", "that", "these", "those", "its", "their", "his", "her", "our",
       "your", "my", "any", "some", "many", "most", "all", "more", "less", "no"}
NOUNISH = {"increase", "decrease", "use", "cause", "benefit", "harm", "damage", "aid", "support",
           "delay", "help", "influence", "produce"}
NOUN_FOLLOW = {"in", "of", "from"}
COPULA = {"is", "are", "was", "were", "be", "been", "being"}
QAUX = {"does", "do", "did", "is", "are", "was", "were", "can", "could", "will", "would", "should",
        "has", "have", "had", "may", "might"}
OPEN_Q = {"what", "who", "whom", "which", "when", "where", "whose"}
RELATIVE = {"which", "that", "who"}
PRIORITY = ["ll", "lr", "llr", "rl", "passive", "pattern", "qar", "center", "head"]

# phrase -> lemma (polarity comes from the lexicon's lemma table, so these compose with negation)
PHRASES = [
    (("leads", "to"), "cause"), (("lead", "to"), "cause"), (("led", "to"), "cause"),
    (("results", "in"), "cause"), (("result", "in"), "cause"), (("resulted", "in"), "cause"),
    (("contributes", "to"), "cause"), (("contribute", "to"), "cause"),
    (("is", "good", "for"), "improve"), (("are", "good", "for"), "improve"),
    (("is", "bad", "for"), "harm"), (("are", "bad", "for"), "harm"),
    (("is", "essential", "for"), "support"), (("is", "linked", "to"), "associate"),
    (("is", "associated", "with"), "associate"), (("are", "associated", "with"), "associate"),
]


# -- shared machinery --------------------------------------------------------------------------
def _prep(sentence: str) -> tuple[bool, list[list[str]]]:
    """(negated-by-'no evidence' prefix, token lists per clause)."""
    from .extract import _NO_EVIDENCE
    s = sentence.lower().strip().rstrip("?")
    neg = False
    m = _NO_EVIDENCE.match(s)
    if m:
        neg, s = True, s[m.end():]
    return neg, [t for t in (tokens(c) for c in re.split(r"[,()]", s)) if t]


def _np_left(toks: list[str]) -> list[str]:
    """Noun phrase ending at the right edge of toks (after the last break/verb-free boundary)."""
    if "that" in toks:
        toks = toks[len(toks) - toks[::-1].index("that"):]
    for i in range(len(toks) - 1, -1, -1):
        if toks[i] in BREAKS:
            toks = toks[i + 1:]
            break
    return [t for t in toks if t not in STOP and t not in AUX and t not in NEGATORS and t not in BREAKS][-4:]


def _np_right(toks: list[str]) -> tuple[list[str], bool]:
    """Noun phrase starting at the left edge of toks; also reports a leading negation."""
    neg, out = False, []
    for i, t in enumerate(toks):
        if t in NEGATORS and i < 2:
            neg = True
            continue
        if t in BREAKS:
            break
        if t in AUX or (t in STOP and t not in ADJ):
            continue
        out.append(t)
        if len(out) >= MAX_SIDE:
            break
    return out, neg


def _mk(subj: list[str], lemma: str, neg: bool, obj: list[str]):
    if not subj or not obj:
        return None
    return " ".join(subj), ("not " if neg else "") + lemma, " ".join(obj)


def _has_neg(toks: list[str]) -> bool:
    return any(t in NEGATORS for t in toks)


def _triple_at(toks, vi, neg_prefix, subj=None):
    lemma = VERBS[toks[vi]][0]
    s = subj if subj is not None else _np_left(toks[:vi])
    o, oneg = _np_right(toks[vi + 1:])
    return _mk(s, lemma, neg_prefix or _has_neg(toks[max(0, vi - 3):vi]) or oneg, o)


def _verbs(toks):
    return [i for i, t in enumerate(toks) if t in VERBS]


# -- the parsers ---------------------------------------------------------------------------------
def ll(sentence):
    return extract(sentence)


def llr(sentence):
    """Left-to-right, but the verb choice looks right: a noun-ish verb after a determiner, or before
    'in/of/from', is a noun. Then commit to the first surviving verb."""
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        for vi in _verbs(toks):
            noun_use = toks[vi] in {f for f, (l, _) in VERBS.items() if l in NOUNISH} and (
                (vi > 0 and toks[vi - 1] in DET) or (vi + 1 < len(toks) and toks[vi + 1] in NOUN_FOLLOW))
            if noun_use:
                continue
            t = _triple_at(toks, vi, neg_p)
            if t:
                out.append(t)
            break
    return out


def lr(sentence):
    """Shift-reduce. Stack symbols: NP, V (with negation), S. Rules applied after every shift:
         [AUX|NEG] V -> V      V NEG -> V(neg)      (DET|N)+ -> NP
         NP V NP -> S          S BREAK V NP -> S S'   (coordinated VP: subject carried over)
       A clause opening with which/that/who takes the previous clause's subject (relative clause)."""
    neg_p, clauses = _prep(sentence)
    out, carry = [], None
    for toks in clauses:
        stack: list[list] = []          # entries: [sym, tokens, neg]
        subj_of_clause = None
        pending_subj = carry if toks and toks[0] in RELATIVE else None

        def reduce_np():
            while len(stack) >= 2 and stack[-1][0] == "NP" and stack[-2][0] == "NP":
                b = stack.pop()
                stack[-1][1] += b[1]

        for t in toks:
            if t in VERBS:
                reduce_np()
                if stack and stack[-1][0] == "NEGAUX":
                    stack.pop()
                    stack.append(["V", [t], True])
                else:
                    stack.append(["V", [t], False])
            elif t in NEGATORS:
                if stack and stack[-1][0] == "V":
                    stack[-1][2] = True
                else:
                    stack.append(["NEGAUX", [t], True])
            elif t in AUX or t in DET or t in STOP:
                if t in ADJ:
                    stack.append(["NP", [t], False])
                    reduce_np()
                continue
            elif t in BREAKS:
                stack.append(["BRK", [t], False])
            else:
                stack.append(["NP", [t], False])
                reduce_np()
            # reduce NP V NP -> S  /  S BRK V NP -> S S'
            if len(stack) >= 3 and [x[0] for x in stack[-3:]] == ["NP", "V", "NP"]:
                s, v, o = stack[-3:]
                del stack[-3:]
                tr = _mk(s[1][-4:], VERBS[v[1][0]][0], neg_p or v[2], o[1][:MAX_SIDE])
                if tr:
                    out.append(tr)
                    subj_of_clause = s[1][-4:]
                stack.append(["S", s[1], False])
            elif len(stack) >= 4 and [x[0] for x in stack[-4:]] == ["S", "BRK", "V", "NP"]:
                s, _, v, o = stack[-4:]
                del stack[-4:]
                tr = _mk(s[1][-4:], VERBS[v[1][0]][0], neg_p or v[2], o[1][:MAX_SIDE])
                if tr:
                    out.append(tr)
                stack.append(["S", s[1], False])
        headless = bool(stack) and stack[0][0] in ("BRK", "V")
        if not subj_of_clause and carry and (pending_subj or headless):   # "which improves memory" / "is safe"
            vs = [x for x in stack if x[0] == "V"]
            nps = [x for x in stack if x[0] == "NP"]
            if vs and nps:
                tr = _mk(carry, VERBS[vs[0][1][0]][0], neg_p or vs[0][2], nps[-1][1][:MAX_SIDE])
                if tr:
                    out.append(tr)
                    subj_of_clause = carry
        if not any(x[0] == "V" for x in stack) and toks:
            carry = _np_left(toks) or carry                     # a bare NP clause becomes the topic
        else:
            carry = subj_of_clause or carry
    return out


def rl(sentence):
    """Backwards: verbs are resolved right to left; a verb with no subject of its own (ellipsis:
    '... improves memory and reduces stress') inherits the subject of the verb on its left."""
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        vis = _verbs(toks)
        rows = []
        for k in range(len(vis) - 1, -1, -1):
            vi = vis[k]
            left = toks[(vis[k - 1] + 1 if k else 0):vi]
            cut = max([i for i, t in enumerate(left) if t in BREAKS], default=-1)
            s = _np_left(left[cut + 1:])                      # empty after a break = elliptical verb
            end = vis[k + 1] if k + 1 < len(vis) else len(toks)
            o, oneg = _np_right(toks[vi + 1:end] if end > vi + 1 else [])
            rows.append([vi, s, o, neg_p or oneg or _has_neg(toks[max(0, vi - 3):vi])])
        rows.reverse()
        last_s = None
        for vi, s, o, neg in rows:
            s = s or last_s
            if s:
                last_s = s
                tr = _mk(s, VERBS[toks[vi]][0], neg, o)
                if tr:
                    out.append(tr)
    return out


def qar(sentence):
    """Question -> claim. Polar and why/how questions only; who/what/which questions have an unknown
    subject and are not claims."""
    s = sentence.lower().strip()
    toks = tokens(s)
    if not toks or not (s.endswith("?") or toks[0] in QAUX | OPEN_Q | {"why", "how"}):
        return []
    if toks[0] in {"why", "how"} and len(toks) > 2 and toks[1] in QAUX:
        toks = toks[1:]
    if toks[0] in OPEN_Q or toks[0] not in QAUX:
        return []
    aux, rest = toks[0], toks[1:]
    if aux in COPULA:                                          # "is coffee safe" -> coffee is safe
        ai = next((i for i, t in enumerate(rest) if t in ADJ), None)
        if ai is None:
            return []
        o, neg = _np_right(rest[ai:])
        return [t] if (t := _mk(_np_left(rest[:ai]), "is", neg or _has_neg(rest[:ai]), o)) else []
    vi = next((i for i, t in enumerate(rest) if t in VERBS), None)   # "does coffee improve memory"
    if vi is None:
        return []
    t = _triple_at(rest, vi, False)
    return [t] if t else []


def passive(sentence):
    """'Memory is not improved by coffee' -> (coffee, not improve, memory)."""
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        for j, t in enumerate(toks):
            if t != "by" or j < 2 or toks[j - 1] not in VERBS:
                continue
            past = toks[j - 1]
            if not past.endswith(("ed", "d")):
                continue
            ci = next((i for i in range(j - 2, -1, -1) if toks[i] in COPULA), None)
            if ci is None:
                continue
            subj, _ = _np_right(toks[j + 1:])
            tr = _mk(subj[:4], VERBS[past][0], neg_p or _has_neg(toks[ci:j]),
                     [w for w in _np_left(toks[:ci])[-MAX_SIDE:]])
            if tr:
                out.append(tr)
    return out


def pattern(sentence):
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        for phrase, lemma in PHRASES:
            n = len(phrase)
            for i in range(len(toks) - n + 1):
                if tuple(toks[i:i + n]) == phrase:
                    o, oneg = _np_right(toks[i + n:])
                    t = _mk(_np_left(toks[:i]), lemma, neg_p or oneg or _has_neg(toks[max(0, i - 2):i]), o)
                    if t:
                        out.append(t)
    return out


def center(sentence):
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        vis = _verbs(toks)
        if vis:
            vi = min(vis, key=lambda i: abs(i - len(toks) / 2))
            t = _triple_at(toks, vi, neg_p)
            if t:
                out.append(t)
    return out


def head(sentence):
    """Head words only: the nearest content word on each side of every verb."""
    neg_p, clauses = _prep(sentence)
    out = []
    for toks in clauses:
        for vi in _verbs(toks):
            seg = toks[:vi]
            cut = max([i for i, t in enumerate(seg) if t in BREAKS], default=-1)
            left = [t for t in seg[cut + 1:] if t not in STOP and t not in AUX and t not in NEGATORS
                    and t not in VERBS]
            right, oneg = _np_right(toks[vi + 1:])
            t = _mk(left[-1:], VERBS[toks[vi]][0], neg_p or oneg or _has_neg(toks[max(0, vi - 3):vi]), right[:1])
            if t:
                out.append(t)
    return out


PARSERS = {"ll": ll, "lr": lr, "llr": llr, "rl": rl, "passive": passive, "pattern": pattern,
           "qar": qar, "center": center, "head": head}


# -- question + answer pairs ---------------------------------------------------------------------
_QA = re.compile(r"([^.?!\n]{6,200}\?)\s*(?:a:|answer:)?\s*(yes|no|not really|probably not)\b", re.I)


def _flip(t):
    s, p, o = t
    return s, (p[4:] if p.startswith("not ") else "not " + p), o


def qa_triples(cleaned_text: str) -> list[tuple[str, str, str]]:
    """'Does coffee improve memory? No.' -> (coffee, not improve, memory)."""
    from .sanitize import is_injection
    out = []
    for m in _QA.finditer(cleaned_text):
        q = re.sub(r"^\s*q:\s*", "", m[1].strip(), flags=re.I)
        if is_injection(q):
            continue
        neg = m[2].lower().startswith(("no", "not", "probably not"))
        out += [_flip(t) if neg else t for t in qar(q)]
    return out


# -- ensemble ------------------------------------------------------------------------------------
def parse_report(sentence: str) -> dict[str, list]:
    """Every parser's raw reading, for inspection."""
    rep = {}
    for name in PRIORITY:
        try:
            rep[name] = PARSERS[name](sentence)
        except Exception:
            rep[name] = []
    return rep


def _same_topic(ss, os_, c_ss, c_os) -> bool:
    return bool(ss & c_ss) and (bool(os_ & c_os) or (not os_ and not c_os))


@lru_cache(maxsize=100_000)
def _ensemble(sentence: str) -> tuple:
    rep = parse_report(sentence)
    if rep["passive"]:      # a passive clause outranks other readings that swap its roles or use 'is'
        for name in PRIORITY:
            if name == "passive":
                continue
            keep = []
            for s, p, o in rep[name]:
                swapped = any(content(s) & content(po) and content(o) & content(ps) for ps, _, po in rep["passive"])
                copular = p in ("is", "not is") and any(content(s) & content(po) for _, _, po in rep["passive"])
                if not swapped and not copular:
                    keep.append((s, p, o))
            rep[name] = keep
    clusters: list[dict] = []
    for name, trips in rep.items():
        for s, p, o in trips:
            ss, os_ = content(s), content(o)
            for c in clusters:
                if c["p"] == p and ss & c["ss"] and ((os_ & c["os"]) or (not os_ and not c["os"] and o == c["t"][2])):
                    c["votes"].add(name)
                    break
            else:
                clusters.append({"t": (s, p, o), "p": p, "ss": ss, "os": os_, "votes": {name}, "i": len(clusters)})
    keep = []
    for c in clusters:
        pol = polarity(c["p"], c["t"][2])
        rivals = [d for d in clusters if d is not c and _same_topic(d["ss"], d["os"], c["ss"], c["os"])
                  and polarity(d["p"], d["t"][2]) == -pol != 0]
        if all(len(c["votes"]) > len(d["votes"]) for d in rivals):
            keep.append(c)
    keep.sort(key=lambda c: (-len(c["votes"]), c["i"]))
    return tuple(c["t"] for c in keep)


def extract_all(sentence: str) -> list[tuple[str, str, str]]:
    return list(_ensemble(sentence))


def votes(sentence: str) -> list[tuple[tuple, list[str]]]:
    """(triple, agreeing parsers) for each surviving reading; for diagnostics."""
    rep = parse_report(sentence)
    out = []
    for t in _ensemble(sentence):
        ss, os_ = content(t[0]), content(t[2])
        out.append((t, [n for n, ts in rep.items()
                        if any(p == t[1] and content(s) & ss and ((content(o) & os_) or (not os_ and o == t[2]))
                               for s, p, o in ts)]))
    return out


if __name__ == "__main__":
    import sys
    text = " ".join(sys.argv[1:]) or "Coffee improves memory and reduces stress"
    for name, trips in parse_report(text).items():
        print(f"{name:8}", trips)
    print("ensemble", votes(text))
