from __future__ import annotations

import argparse
import json
import sys

from . import analytics
from .memory import MemoryError_, load


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="retrieva-sidecar", description="Analytics over Retrieva Arrow memory.")
    ap.add_argument("memory_dir")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    a = ap.parse_args(argv)
    try:
        m = load(a.memory_dir)
    except MemoryError_ as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    report = {
        "generation": m.generation,
        "warnings": m.warnings,
        "triples": int(len(m.triples)),
        "hosts": analytics.host_stats(m.triples).reset_index().astype({"newest": str, "oldest": str}).to_dict("records") if len(m.triples) else [],
        "contested": analytics.contested(m.triples).reset_index().to_dict("records") if len(m.triples) else [],
        "routes": analytics.route_summary(m.routes),
    }
    if a.json:
        print(json.dumps(report, indent=2, default=str))
    else:
        print(f"generation {report['generation']}: {report['triples']} triples, {report['routes'].get('routes', 0)} routes")
        for w in report["warnings"]:
            print("warning:", w)
        for h in report["hosts"]:
            print(f"  {h['host']:32} {h['triples']:>7} triples  trust {h['mean_trust']:.2f}")
        for c in report["contested"]:
            print(f"  contested: {c['subject']} / {c['object']}: {c['for_hosts']} hosts for, {c['against_hosts']} against")
    return 0
