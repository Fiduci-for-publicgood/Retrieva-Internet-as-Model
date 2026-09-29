"""Small built-in lexicon: predicate lemmas with polarity, evaluative words, stop words.

Polarity is the only "semantics" the agent trusts: +1 (supports / positive), -1 (opposes /
negative), 0 (descriptive). Extend these tables to widen coverage; nothing else changes.
"""


def _forms(base: str) -> set[str]:
    out = {base}
    out.add(base + "es" if base.endswith(("s", "sh", "ch", "x")) else base + "s")
    out.add(base + "d" if base.endswith("e") else base + "ed")
    if base.endswith("y") and base[-2] not in "aeiou":
        out |= {base[:-1] + "ies", base[:-1] + "ied"}
    return out


_POS = """improve increase raise boost enhance promote support strengthen help protect benefit aid
enable accelerate stimulate reinforce confirm improve""".split()
_NEG = """reduce decrease lower harm worsen impair weaken inhibit prevent damage undermine hinder
diminish suppress disrupt contradict refute delay""".split()
_NEUTRAL = "cause contain include affect influence involve produce require use".split()

# surface form -> (lemma, polarity)
VERBS: dict[str, tuple[str, int]] = {}
for _lemma, _pol in [(w, 1) for w in _POS] + [(w, -1) for w in _NEG] + [(w, 0) for w in _NEUTRAL]:
    for _f in _forms(_lemma):
        VERBS[_f] = (_lemma, _pol)
for _f in ("is", "are", "was", "were", "be", "been", "being"):
    VERBS[_f] = ("is", 0)
for _f in ("has", "have", "had"):
    VERBS[_f] = ("has", 0)

# Evaluative words: polarity of an object/complement ("X is safe" / "X is dangerous").
ADJ: dict[str, int] = {}
for _w in """safe effective beneficial healthy true accurate reliable helpful useful good positive
secure legitimate valid proven""".split():
    ADJ[_w] = 1
for _w in """unsafe dangerous ineffective harmful unhealthy false inaccurate unreliable useless bad
negative insecure illegitimate invalid harm damage toxic risky""".split():
    ADJ[_w] = -1

NEGATORS = {"not", "no", "never", "without", "cannot", "neither", "nor"}
AUX = {"does", "do", "did", "can", "may", "might", "will", "could", "would", "should", "must",
       "also", "often", "really", "regularly", "generally", "typically", "still", "always",
       "actually", "significantly", "greatly", "slightly", "reportedly", "widely"}
STOP = {"the", "a", "an", "this", "that", "these", "those", "some", "many", "most", "any", "all",
        "of", "to", "in", "on", "at", "by", "for", "with", "from", "as", "it", "its", "their",
        "his", "her", "our", "your", "my", "there", "here", "then", "than", "so", "such", "very",
        "more", "less", "much", "recent", "new", "studies", "study", "research", "evidence",
        "found", "shows", "show", "showed", "suggest", "suggests", "reports", "report"}
BREAKS = {"and", "but", "because", "while", "which", "although", "when", "or", "whereas",
          "since", "though", "however", "if", "unless", "whether"}

# Sentence-level prompt-injection patterns (untrusted text must be declarative, never directive).
INJECTION_PATTERNS = [
    r"\b(ignore|disregard|forget|override|bypass)\b.{0,40}\b(instruction|prompt|rule|guideline|previous|prior|above|earlier)",
    r"\b(system|developer|assistant|user)\s*(prompt|message|role)\b",
    r"^\s*(system|assistant|user|human|ai)\s*[:>]",
    r"\b(you are|you're|you must|you should|you will|you shall)\b",
    r"\b(act|behave|respond|reply|answer|pretend|roleplay)\s+(as|like|with|that|only|in)\b",
    r"\b(jailbreak|dan mode|developer mode|do anything now|prompt injection)\b",
    r"\b(reveal|leak|print|output|repeat|disclose|exfiltrate)\b.{0,30}\b(prompt|instruction|secret|key|password|token|credential)",
    r"\b(new|updated|additional)\s+instructions?\b",
    r"\b(run|execute|eval|call|invoke|download|install|curl|fetch|send|email|post)\b.{0,30}\b(command|script|code|function|tool|file|payload|this|the following|to)\b",
    r"\bdo not (tell|reveal|mention|inform|let)\b",
    r"\bbase64\b|\\x[0-9a-f]{2}|&#\d+;|<\|",
]
IMPERATIVE_START = {"ignore", "disregard", "forget", "reveal", "print", "output", "respond", "answer",
                    "say", "write", "call", "visit", "click", "download", "execute", "run", "send",
                    "act", "pretend", "remember", "tell", "give", "show", "list", "translate",
                    "summarize", "repeat", "stop", "start", "begin", "enter", "type", "use", "make"}
