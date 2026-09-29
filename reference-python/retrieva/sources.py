"""Document sources. Everything they return is untrusted until it passes the ingestion gate."""
from __future__ import annotations

import json
import math
import os
import re
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

from .extract import content, stem, tokens


@dataclass(frozen=True)
class Doc:
    locator: str   # host/path form, no scheme
    text: str      # raw, possibly hostile; never shown to a model
    added: float   # epoch seconds the source was published/added


class Source(Protocol):
    name: str

    def search(self, query: str, limit: int) -> list[Doc]: ...


def _epoch(x) -> float:
    if isinstance(x, (int, float)):
        return float(x)
    dt = datetime.fromisoformat(str(x).replace("Z", "+00:00"))
    return (dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)).timestamp()


class CorpusSource:
    """Offline source over a fixed set of docs (tests, demos, air-gapped use). TF-IDF-ish ranking."""
    name = "corpus"

    def __init__(self, docs: list[Doc]):
        self.docs = docs
        self._stems = [content(tokens(d.text)) | content(tokens(d.locator.replace("/", " ").replace("-", " "))) for d in docs]
        n = len(docs)
        df: dict[str, int] = {}
        for st in self._stems:
            for w in st:
                df[w] = df.get(w, 0) + 1
        self._idf = {w: math.log(1 + n / c) for w, c in df.items()}

    @classmethod
    def from_json(cls, path) -> tuple["CorpusSource", dict[str, float]]:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        docs = [Doc(d["locator"], d["text"], _epoch(d["added"])) for d in data["docs"]]
        return cls(docs), {k: float(v) for k, v in data.get("trust", {}).items()}

    def search(self, query: str, limit: int) -> list[Doc]:
        q = {stem(w) for w in content(tokens(query))}
        scored = sorted(((sum(self._idf.get(w, 0) for w in q & st), i) for i, st in enumerate(self._stems)), reverse=True)
        return [self.docs[i] for sc, i in scored[:limit] if sc > 0]


class WikipediaSource:
    """Live source: MediaWiki search + plain-text extracts. Bounded size and timeout."""
    name = "wikipedia"
    API = "https://en.wikipedia.org/w/api.php"

    def __init__(self, timeout: float = 0.8, max_bytes: int = 512 * 1024):
        self.timeout, self.max_bytes = timeout, max_bytes

    def search(self, query: str, limit: int) -> list[Doc]:
        qs = urllib.parse.urlencode({
            "action": "query", "generator": "search", "gsrsearch": query, "gsrlimit": limit,
            "prop": "extracts|info", "exintro": 1, "explaintext": 1, "exlimit": "max",
            "format": "json", "formatversion": 2})
        req = urllib.request.Request(f"{self.API}?{qs}", headers={"User-Agent": "retrieva/0.1 (research agent)"})
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            data = json.loads(r.read(self.max_bytes))
        docs = []
        for pg in data.get("query", {}).get("pages", []):
            title = re.sub(r"[^A-Za-z0-9._~%()-]", "_", pg.get("title", ""))[:120]
            if not title or not pg.get("extract"):
                continue
            docs.append(Doc(f"en.wikipedia.org/wiki/{title}", pg["extract"], _epoch(pg.get("touched", "1970-01-01T00:00:00"))))
        return docs


def load_trust_env() -> dict[str, float]:
    """Optional RETRIEVA_TRUST='host=0.9,other.org=0.6' override."""
    out = {}
    for pair in filter(None, os.environ.get("RETRIEVA_TRUST", "").split(",")):
        h, _, v = pair.partition("=")
        out[h.strip().lower()] = float(v)
    return out
