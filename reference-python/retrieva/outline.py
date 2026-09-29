"""Outliner-style route index (default 2 MB): remembers the *path* that led to each answer.

Triples are the condensed knowledge; routes are the map. A saved route lets the agent re-derive
an answer by revisiting only the topics/sources that mattered, instead of re-crawling widely.

Format (plain indented text, human-editable):

    # retrieva outline v1
    ## topics
    - coffee memory :: 3
    ## routes
    - [1] coffee memory :: verdict=for for=0.71 against=0.12 neutral=0.17 t=1727600000 hits=2
      - 1. coffee memory   (step number = layer-1 crawler position for the first 32 steps; '-' = no result)
        - journal.example.org/coffee-and-memory
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field

from .extract import content
from .store import MB

_ROUTE = re.compile(r"^- \[\d+\] (.+?) :: verdict=(\S+) for=([\d.]+) against=([\d.]+) neutral=([\d.]+) t=(\d+) hits=(\d+)$")
_STEP = re.compile(r"^  - \d+\. (.+)$")
_LINK = re.compile(r"^    - (\S+)$")
_TOPIC = re.compile(r"^- (.+?) :: (\d+)$")


@dataclass
class Route:
    key: str
    verdict: str
    shares: tuple[float, float, float]
    t: int
    hits: int = 0
    steps: list[tuple[str, list[str]]] = field(default_factory=list)  # (topic, source locators)


def route_key(text: str) -> str:
    return " ".join(sorted(content(text)))


class Outline:
    def __init__(self, cap_bytes: int = 2 * MB):
        self.cap = cap_bytes
        self.routes: dict[str, Route] = {}
        self.topic_hits: Counter[str] = Counter()

    def lookup(self, key: str) -> Route | None:
        return self.routes.get(key)

    def related(self, stems: set[str]) -> list[Route]:
        return [r for r in self.routes.values() if stems & set(r.key.split())]

    def record_topic(self, topic: str) -> None:
        self.topic_hits[topic] += 1

    def frequency(self, topic: str) -> int:
        return self.topic_hits.get(topic, 0)

    def save_route(self, route: Route) -> None:
        old = self.routes.get(route.key)
        if old:
            route.hits = old.hits + 1
        self.routes[route.key] = route

    def render(self) -> str:
        out = ["# retrieva outline v1", "## topics"]
        out += [f"- {t} :: {n}" for t, n in self.topic_hits.most_common()]
        out.append("## routes")
        for i, r in enumerate(self.routes.values(), 1):
            f, a, n = r.shares
            out.append(f"- [{i}] {r.key} :: verdict={r.verdict} for={f:.3f} against={a:.3f} neutral={n:.3f} t={r.t} hits={r.hits}")
            for j, (topic, links) in enumerate(r.steps, 1):
                out.append(f"  - {j}. {topic}")
                out += [f"    - {lk}" for lk in links]
        return "\n".join(out) + "\n"

    def _shrink(self) -> None:
        """Evict least-used, oldest routes (and rare topics) until the render fits the cap."""
        while len(self.render().encode()) > self.cap and (self.routes or self.topic_hits):
            if self.routes:
                worst = sorted(self.routes.values(), key=lambda r: (r.hits, r.t))[: max(1, len(self.routes) // 10)]
                for r in worst:
                    del self.routes[r.key]
            else:
                for t, _ in self.topic_hits.most_common()[-max(1, len(self.topic_hits) // 10):]:
                    del self.topic_hits[t]

    def save(self, path) -> int:
        self._shrink()
        text = self.render()
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return len(text.encode())

    @classmethod
    def parse(cls, text: str, cap_bytes: int = 2 * MB) -> "Outline":
        ol, section, cur = cls(cap_bytes), "", None
        for ln in text.split("\n"):
            if ln.startswith("## "):
                section = ln[3:].strip()
            elif section == "topics" and (m := _TOPIC.match(ln)):
                ol.topic_hits[m[1]] = int(m[2])
            elif section == "routes":
                if m := _ROUTE.match(ln):
                    cur = Route(m[1], m[2], (float(m[3]), float(m[4]), float(m[5])), int(m[6]), int(m[7]))
                    ol.routes[cur.key] = cur
                elif cur and (m := _STEP.match(ln)):
                    cur.steps.append((m[1], []))
                elif cur and cur.steps and (m := _LINK.match(ln)):
                    cur.steps[-1][1].append(m[1])
        return ol

    @classmethod
    def load(cls, path, cap_bytes: int = 2 * MB) -> "Outline":
        try:
            with open(path, encoding="utf-8") as f:
                return cls.parse(f.read(), cap_bytes)
        except FileNotFoundError:
            return cls(cap_bytes)
