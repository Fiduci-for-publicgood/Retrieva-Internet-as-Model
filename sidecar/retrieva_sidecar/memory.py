"""Load a Retrieva memory directory into pandas.

Layout (contract with retrieva-arrow's ArrowMemoryRepository, see docs/ARCHITECTURE.md):

    DIR/CURRENT                    name of the live generation
    DIR/gen-NNNNNN/strings.arrow   value: utf8 (row index = string id)
    DIR/gen-NNNNNN/triples.arrow   s,p,o,src: int32 ids; added: float64; trust_q: int32; hits: int32
    DIR/gen-NNNNNN/outline.md      route outline
    DIR/gen-NNNNNN/MANIFEST.json   {"version","strings","triples","sha256":{file: hex}}

Files are verified against the manifest before use; an unusable newest generation falls back to the previous one,
exactly like the Java loader.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.ipc as ipc

FILES = ("strings.arrow", "triples.arrow", "outline.md")
_GEN = re.compile(r"^gen-\d{6}$")


class MemoryError_(RuntimeError):
    """No usable generation."""


@dataclass
class Memory:
    triples: pd.DataFrame            # subject, predicate, object, source, host, added(datetime64), trust(0..1), hits
    routes: pd.DataFrame             # key, verdict, for_share, against_share, neutral_share, t(datetime64), hits, n_steps
    topics: pd.DataFrame             # topic, count
    generation: str = ""
    warnings: list = field(default_factory=list)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_table(path: Path) -> pa.Table:
    # Read into memory rather than memory-mapping: the files are small (<= a few MB) and this leaves no dangling mapping.
    return ipc.open_file(pa.BufferReader(pa.py_buffer(path.read_bytes()))).read_all()


def _load_generation(g: Path) -> Memory:
    manifest = json.loads((g / "MANIFEST.json").read_text(encoding="utf-8"))
    if manifest.get("version") != 1:
        raise MemoryError_(f"unsupported memory version {manifest.get('version')}")
    for name in FILES:
        if _sha256(g / name) != manifest["sha256"][name]:
            raise MemoryError_(f"{name} does not match manifest")
    strings = _read_table(g / "strings.arrow").column("value").to_pandas().astype(str)
    t = _read_table(g / "triples.arrow").to_pandas()
    n = len(strings)
    for col in ("s", "p", "o", "src"):
        if len(t) and (t[col].min() < 0 or t[col].max() >= n):
            raise MemoryError_(f"column {col} has out-of-range string ids")
    df = pd.DataFrame({
        "subject": strings.iloc[t["s"]].to_numpy(),
        "predicate": strings.iloc[t["p"]].to_numpy(),
        "object": strings.iloc[t["o"]].to_numpy(),
        "source": strings.iloc[t["src"]].to_numpy(),
    })
    df["host"] = df["source"].str.split("/", n=1).str[0]
    df["added"] = pd.to_datetime(t["added"], unit="s", utc=True)
    df["trust"] = t["trust_q"] / 255.0
    df["hits"] = t["hits"]
    from .outline import parse_outline
    routes, topics = parse_outline((g / "outline.md").read_text(encoding="utf-8"))
    return Memory(df, routes, topics, generation=g.name)


def load(directory: str | Path) -> Memory:
    d = Path(directory)
    gens = sorted((p.name for p in d.iterdir() if p.is_dir() and _GEN.match(p.name)), reverse=True) if d.is_dir() else []
    cur = d / "CURRENT"
    if cur.exists():
        name = cur.read_text(encoding="utf-8").strip()
        if name in gens:
            gens.remove(name)
            gens.insert(0, name)
    warnings = []
    for g in gens:
        try:
            m = _load_generation(d / g)
            m.warnings = warnings
            return m
        except Exception as e:  # noqa: BLE001 - any damage means "try the previous generation"
            warnings.append(f"{g}: {e}")
    if gens:
        raise MemoryError_("no usable generation: " + "; ".join(warnings))
    raise MemoryError_(f"no memory found in {d}")
