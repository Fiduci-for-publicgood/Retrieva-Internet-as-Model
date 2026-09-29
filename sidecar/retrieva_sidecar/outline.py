"""Parse the route outline (same grammar as retrieva-core's Outline)."""
from __future__ import annotations

import re

import pandas as pd

_ROUTE = re.compile(r"^- \[\d+\] (.+?) :: verdict=(\S+) for=([\d.]+) against=([\d.]+) neutral=([\d.]+) t=(\d+) hits=(\d+)$")
_STEP = re.compile(r"^  - \d+\. (.+)$")
_LINK = re.compile(r"^    - (\S+)$")
_TOPIC = re.compile(r"^- (.+?) :: (\d+)$")


def parse_outline(text: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    routes, topics, section, cur = [], [], "", None
    for ln in text.split("\n"):
        if ln.startswith("## "):
            section = ln[3:].strip()
        elif section == "topics" and (m := _TOPIC.match(ln)):
            topics.append({"topic": m[1], "count": int(m[2])})
        elif section == "routes":
            if m := _ROUTE.match(ln):
                cur = {"key": m[1], "verdict": m[2], "for_share": float(m[3]), "against_share": float(m[4]),
                       "neutral_share": float(m[5]), "t": int(m[6]), "hits": int(m[7]), "n_steps": 0, "n_links": 0}
                routes.append(cur)
            elif cur is not None and _STEP.match(ln):
                cur["n_steps"] += 1
            elif cur is not None and _LINK.match(ln):
                cur["n_links"] += 1
    r = pd.DataFrame(routes, columns=["key", "verdict", "for_share", "against_share", "neutral_share", "t", "hits", "n_steps", "n_links"])
    r["t"] = pd.to_datetime(r["t"], unit="s", utc=True)
    return r, pd.DataFrame(topics, columns=["topic", "count"])
