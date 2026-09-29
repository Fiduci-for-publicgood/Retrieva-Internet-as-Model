"""The restricted ingestion line: allowlist gate -> sanitize -> injection filter -> triples only.

Nothing but validated (subject, predicate, object) text crosses this boundary; raw text is
discarded, and answers are composed from triples by templates, never by feeding source text to a model.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .parsers import extract_all, qa_triples
from .sources import Doc
from .store import TripleStore
from .sanitize import clean, sentences

MAX_DOC_BYTES = 256 * 1024
MAX_TRIPLES_PER_DOC = 200


@dataclass
class IngestStats:
    docs: int = 0
    rejected_docs: int = 0
    dropped_injection: int = 0
    triples: int = 0
    new_triples: int = 0
    locators: list[str] = field(default_factory=list)


class Gate:
    """Only hosts on the allowlist are ingested; the value is the prior trust in that host (0..1)."""

    def __init__(self, allow: dict[str, float], default_trust: float = 0.0):
        self.allow = {h.lower(): float(t) for h, t in allow.items()}
        self.default = default_trust

    def trust(self, locator: str) -> float:
        host = locator.split("/", 1)[0].lower()
        parts = host.split(".")
        for i in range(len(parts)):  # a.b.c matches allow entries a.b.c, b.c, c
            t = self.allow.get(".".join(parts[i:]))
            if t is not None:
                return t
        return self.default


def ingest(docs: list[Doc], gate: Gate, store: TripleStore, stats: IngestStats | None = None) -> IngestStats:
    st = stats or IngestStats()
    for doc in docs:
        trust = gate.trust(doc.locator)
        if trust <= 0 or len(doc.text.encode("utf-8", "ignore")) > MAX_DOC_BYTES:
            st.rejected_docs += 1
            continue
        sents, dropped = sentences(doc.text)
        st.dropped_injection += dropped
        st.docs += 1
        st.locators.append(doc.locator)
        found = [t for sent in sents for t in extract_all(sent)]        # every parse shape, voted
        found += qa_triples(clean(doc.text))                          # "Does X improve Y? No."
        for s, p, o in found[:MAX_TRIPLES_PER_DOC]:
            st.triples += 1
            st.new_triples += store.add(s, p, o, doc.locator, doc.added, trust)
    return st
