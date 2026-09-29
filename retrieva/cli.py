from __future__ import annotations

import argparse
import json
import sys

from .engine import Agent, ClaimError
from .ingest import Gate
from .sources import CorpusSource, WikipediaSource, load_trust_env


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="retrieva", description="Ephemeral agent, persistent context.")
    ap.add_argument("claim", help='e.g. "coffee improves memory"')
    ap.add_argument("--corpus", help="offline JSON corpus (see examples/corpus.json)")
    ap.add_argument("--live", action="store_true", help="also query Wikipedia (trust 0.7)")
    ap.add_argument("--memory", default=".retrieva", help="directory for triples.bin + outline.md")
    ap.add_argument("--budget", type=float, default=1.0, help="deadline in seconds (default 1.0)")
    ap.add_argument("--threshold", type=float, default=0.60)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)

    sources, trust = [], {}
    if a.corpus:
        src, trust = CorpusSource.from_json(a.corpus)
        sources.append(src)
    if a.live:
        sources.append(WikipediaSource(timeout=a.budget * 0.8))
        trust["wikipedia.org"] = 0.7
    if not sources:
        ap.error("give --corpus and/or --live")
    trust.update(load_trust_env())
    agent = Agent(sources, Gate(trust), a.memory, budget=a.budget, threshold=a.threshold)
    try:
        ans = agent.ask(a.claim)
    except ClaimError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    if a.json:
        print(json.dumps({"verdict": ans.verdict, "resolved": ans.resolved, "shares": ans.shares,
                          "elapsed_ms": round(ans.elapsed_ms, 2), "from_memory": ans.from_memory,
                          "rounds": ans.rounds, "route": ans.route, "prose": ans.prose}, indent=2))
    else:
        print(ans.prose)
        print(f"\n[{ans.elapsed_ms:.1f} ms | rounds={ans.rounds} | from_memory={ans.from_memory} | "
              f"docs={ans.stats.docs} rejected={ans.stats.rejected_docs} injection_dropped={ans.stats.dropped_injection}]")
    return 0
