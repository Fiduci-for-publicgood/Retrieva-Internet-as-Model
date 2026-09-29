"""Text -> plain declarative sentences. Strips HTML, URLs, formatting, invisible chars, directives."""
from __future__ import annotations

import html
import re
import unicodedata

from .lexicon import IMPERATIVE_START, INJECTION_PATTERNS

_SCRIPT = re.compile(r"<(script|style|iframe|object|embed|template|noscript|svg)\b.*?</\1\s*>", re.I | re.S)
_COMMENT = re.compile(r"<!--.*?-->", re.S)
_TAG = re.compile(r"<[^>]{0,2000}>")
_MDLINK = re.compile(r"!?\[([^\]]*)\]\([^)]*\)")
_URL = re.compile(
    r"(?:\b[a-z][a-z0-9+.-]{1,15}://|\bwww\.)\S+"
    r"|\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+"
    r"|\b(?:[a-z0-9-]+\.)+(?:com|org|net|io|gov|edu|co|uk|ai|dev|info|xyz|ru|cn)\b(?:/\S*)?",
    re.I,
)
_FMT = re.compile(r"[*_`#>|~\[\]{}\\^=<]+")
_SPACE = re.compile(r"[ \t\r\f\v]+")
_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+|[;:]")
_INJ = [re.compile(p, re.I) for p in INJECTION_PATTERNS]

MAX_CHARS = 200_000
_DROP_CATS = {"Cc", "Cf", "Co", "Cs", "Cn"}


def clean(raw: str) -> str:
    """Remove markup, links, formatting and invisible/bidi characters. Idempotent."""
    text = raw[:MAX_CHARS].replace("\t", " ")
    for _ in range(3):  # unescape can reveal new tags; iterate to a fixpoint
        prev = text
        text = _TAG.sub(" ", _SCRIPT.sub(" ", _COMMENT.sub(" ", html.unescape(text))))
        if text == prev:
            break
    text = unicodedata.normalize("NFKC", text)
    text = "".join(c for c in text if c == "\n" or unicodedata.category(c) not in _DROP_CATS)
    text = _MDLINK.sub(r"\1", text)
    text = _URL.sub(" ", text)
    text = _FMT.sub(" ", text)
    return "\n".join(_SPACE.sub(" ", ln).strip() for ln in text.split("\n"))


def is_injection(sentence: str) -> bool:
    s = sentence.strip().lower()
    if not s:
        return False
    first = s.split(" ", 1)[0].strip(",.")
    if first in IMPERATIVE_START or s.startswith(("please ", "note:", "important:")):
        return True
    return any(p.search(s) for p in _INJ)


def sentences(raw: str) -> tuple[list[str], int]:
    """Return (safe declarative sentences, number dropped as suspected injection)."""
    out, dropped = [], 0
    for sent in _SPLIT.split(clean(raw)):
        sent = sent.strip()
        if len(sent) < 8 or len(sent) > 400:
            continue
        if is_injection(sent):
            dropped += 1
            continue
        out.append(sent)
    return out, dropped
