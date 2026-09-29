"""Sentence -> (subject, predicate, object) triples, plus polarity and topic stems."""
from __future__ import annotations

import re

from .lexicon import ADJ, AUX, BREAKS, NEGATORS, STOP, VERBS

_WORD = re.compile(r"[a-z0-9]+(?:'[a-z]+)?")
_NO_EVIDENCE = re.compile(r"^(?:there (?:is|was|are) )?(?:no|little|scant) (?:clear |strong |good |reliable )?evidence (?:that|for|of|to suggest) ")
_CONTRACT = [("won't", "will not"), ("can't", "cannot"), ("n't", " not")]

MAX_SIDE = 6  # tokens per subject/object


def tokens(text: str) -> list[str]:
    text = text.lower()
    for a, b in _CONTRACT:
        text = text.replace(a, b)
    return _WORD.findall(text)


def stem(w: str) -> str:
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def content(text_or_tokens) -> frozenset[str]:
    """Topic stems: tokens minus stop words, polarity words and verbs."""
    toks = tokens(text_or_tokens) if isinstance(text_or_tokens, str) else text_or_tokens
    return frozenset(stem(t) for t in toks
                     if t not in STOP and t not in AUX and t not in NEGATORS
                     and t not in ADJ and t not in VERBS and t not in BREAKS and len(t) > 1)


_LEMMA_POL = {l: p for l, p in VERBS.values()}


def polarity(pred: str, obj: str) -> int:
    neg = pred.startswith("not ")
    base = _LEMMA_POL.get(pred[4:] if neg else pred, 0)
    adj = next((ADJ[w] for w in obj.split() if w in ADJ), 0)
    pol = base * adj if base and adj else (base or adj)
    return -pol if neg else pol


def extract(sentence: str) -> list[tuple[str, str, str]]:
    """Return zero or one (s, p, o) triples per clause; strings are lowercase token runs."""
    s = sentence.lower().strip()
    neg_prefix = False
    m = _NO_EVIDENCE.match(s)
    if m:
        neg_prefix, s = True, s[m.end():]
    out = []
    for clause in re.split(r"[,()]", s):
        toks = tokens(clause)
        pi = next((i for i, t in enumerate(toks) if t in VERBS), None)
        if pi is None:
            continue
        lemma, _ = VERBS[toks[pi]]
        prefix = toks[:pi]
        if "that" in prefix:
            prefix = prefix[len(prefix) - prefix[::-1].index("that"):]
        neg = neg_prefix or any(t in NEGATORS for t in prefix[-3:])
        subj = [t for t in prefix if t not in STOP and t not in AUX and t not in NEGATORS and t not in BREAKS][-4:]
        rest, obj = toks[pi + 1:], []
        for i, t in enumerate(rest):
            if t in NEGATORS and i < 2:
                neg = True
                continue
            if t in BREAKS:
                break
            if t in AUX or (t in STOP and t not in ADJ):
                continue
            obj.append(t)
            if len(obj) >= MAX_SIDE:
                break
        if not subj or not obj:
            continue
        pred = ("not " if neg else "") + lemma
        out.append((" ".join(subj), pred, " ".join(obj)))
    return out
