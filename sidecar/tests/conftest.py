import hashlib
import json
from pathlib import Path

import pyarrow as pa
import pyarrow.ipc as ipc
import pytest


def write_generation(directory: Path, number: int, triples, outline="# retrieva outline v1\n## topics\n## routes\n", current=True):
    """Write a memory generation exactly like the Java repository does. triples: (s, p, o, src, added, trust_q, hits)."""
    g = directory / f"gen-{number:06d}"
    g.mkdir(parents=True)
    strings, ids = [], {}

    def sid(x):
        if x not in ids:
            ids[x] = len(strings)
            strings.append(x)
        return ids[x]

    rows = [(sid(s), sid(p), sid(o), sid(src), float(t), int(q), int(h)) for s, p, o, src, t, q, h in triples]
    st = pa.table({"value": pa.array(strings, pa.utf8())})
    tt = pa.table({
        "s": pa.array([r[0] for r in rows], pa.int32()), "p": pa.array([r[1] for r in rows], pa.int32()),
        "o": pa.array([r[2] for r in rows], pa.int32()), "src": pa.array([r[3] for r in rows], pa.int32()),
        "added": pa.array([r[4] for r in rows], pa.float64()), "trust_q": pa.array([r[5] for r in rows], pa.int32()),
        "hits": pa.array([r[6] for r in rows], pa.int32()),
    })
    for name, table in (("strings.arrow", st), ("triples.arrow", tt)):
        with pa.OSFile(str(g / name), "wb") as sink, ipc.new_file(sink, table.schema) as w:
            w.write_table(table)
    (g / "outline.md").write_text(outline, encoding="utf-8")
    manifest = {"version": 1, "strings": len(strings), "triples": len(rows),
                "sha256": {n: hashlib.sha256((g / n).read_bytes()).hexdigest() for n in ("strings.arrow", "triples.arrow", "outline.md")}}
    (g / "MANIFEST.json").write_text(json.dumps(manifest), encoding="utf-8")
    if current:
        (directory / "CURRENT").write_text(g.name, encoding="utf-8")
    return g


@pytest.fixture
def sample_triples():
    return [
        ("coffee", "improve", "memory", "journal.example.org/coffee", 1.75e9, 230, 3),
        ("coffee", "not improve", "memory", "news.example.com/myth", 1.70e9, 128, 0),
        ("caffeine", "enhance", "alertness", "health.example.gov/caffeine", 1.74e9, 217, 1),
    ]
