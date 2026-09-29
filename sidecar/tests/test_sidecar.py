import os
import sys
from pathlib import Path

import pandas as pd
import pytest

from conftest import write_generation
from retrieva_sidecar import MemoryError_, analytics, load
from retrieva_sidecar.outline import parse_outline

ROOT = Path(__file__).resolve().parents[2]

OUTLINE = """# retrieva outline v1
## topics
- coffee memory :: 3
- caffeine alertness :: 1
## routes
- [1] coffee memory :: verdict=for for=0.710 against=0.120 neutral=0.170 t=1780000000 hits=2
  - 1. coffee memory
    - journal.example.org/coffee
    - health.example.gov/caffeine
  - 2. -
"""


def test_polarity_tables_match_the_reference_implementation():
    sys.path.insert(0, str(ROOT / "reference-python"))
    from retrieva import extract, lexicon  # noqa: PLC0415
    assert {l: p for l, p in lexicon.VERBS.values() if p} == analytics.LEMMA_POLARITY
    assert lexicon.ADJ == analytics.ADJ_POLARITY
    for pred, obj in [("improve", "memory"), ("not improve", "memory"), ("is", "dangerous"), ("not is", "safe"), ("reduce", "harm"), ("cause", "cancer")]:
        assert analytics.polarity(pred, obj) == extract.polarity(pred, obj), (pred, obj)


def test_loads_a_generation_and_joins_strings(tmp_path, sample_triples):
    write_generation(tmp_path, 1, sample_triples, OUTLINE)
    m = load(tmp_path)
    assert m.generation == "gen-000001" and m.warnings == []
    assert list(m.triples["subject"]) == ["coffee", "coffee", "caffeine"]
    assert list(m.triples["host"]) == ["journal.example.org", "news.example.com", "health.example.gov"]
    assert m.triples["trust"].iloc[0] == pytest.approx(230 / 255)
    assert m.triples["added"].iloc[0] == pd.Timestamp(1.75e9, unit="s", tz="UTC")
    assert len(m.routes) == 1 and m.routes["n_steps"].iloc[0] == 2 and m.routes["n_links"].iloc[0] == 2
    assert m.topics.set_index("topic")["count"]["coffee memory"] == 3


def test_falls_back_to_the_previous_generation_when_the_newest_is_damaged(tmp_path, sample_triples):
    write_generation(tmp_path, 1, sample_triples)
    g2 = write_generation(tmp_path, 2, sample_triples + [("tea", "improve", "focus", "a.org/t", 1.7e9, 200, 0)])
    (g2 / "triples.arrow").write_bytes(b"garbage")
    m = load(tmp_path)
    assert m.generation == "gen-000001" and len(m.triples) == 3
    assert len(m.warnings) == 1 and "gen-000002" in m.warnings[0]


def test_no_usable_generation_raises(tmp_path, sample_triples):
    g = write_generation(tmp_path, 1, sample_triples)
    (g / "outline.md").write_text("tampered", encoding="utf-8")
    with pytest.raises(MemoryError_):
        load(tmp_path)
    with pytest.raises(MemoryError_):
        load(tmp_path / "does-not-exist")


def test_out_of_range_string_ids_are_rejected(tmp_path, sample_triples):
    import hashlib
    import json

    import pyarrow as pa
    import pyarrow.ipc as ipc
    g = write_generation(tmp_path, 1, sample_triples)
    t = ipc.open_file(str(g / "triples.arrow")).read_all().to_pandas()
    t.loc[0, "s"] = 9999
    tbl = pa.Table.from_pandas(t, preserve_index=False).cast(ipc.open_file(str(g / "triples.arrow")).schema)
    with pa.OSFile(str(g / "triples.arrow"), "wb") as sink, ipc.new_file(sink, tbl.schema) as w:
        w.write_table(tbl)
    mf = json.loads((g / "MANIFEST.json").read_text())
    mf["sha256"]["triples.arrow"] = hashlib.sha256((g / "triples.arrow").read_bytes()).hexdigest()
    (g / "MANIFEST.json").write_text(json.dumps(mf))
    with pytest.raises(MemoryError_, match="out-of-range"):
        load(tmp_path)


def test_outline_parser_handles_empty_input():
    routes, topics = parse_outline("# retrieva outline v1\n## topics\n## routes\n")
    assert routes.empty and topics.empty


def test_host_stats_and_contested(tmp_path, sample_triples):
    write_generation(tmp_path, 1, sample_triples)
    m = load(tmp_path)
    hs = analytics.host_stats(m.triples)
    assert set(hs.index) == {"journal.example.org", "news.example.com", "health.example.gov"}
    c = analytics.contested(m.triples)
    assert list(c.index) == [("coffee", "memory")] and c.iloc[0]["for_hosts"] == 1 and c.iloc[0]["against_hosts"] == 1
    bal = analytics.trust_weighted_balance(m.triples)
    assert bal.loc[("coffee", "memory"), "hosts"] == 2
    assert bal.loc[("coffee", "memory"), "balance"] == pytest.approx(230 / 255 - 128 / 255)


def test_one_host_cannot_make_a_topic_contested(tmp_path):
    write_generation(tmp_path, 1, [("coffee", "improve", "memory", "a.org/x", 1.7e9, 200, 0), ("coffee", "not improve", "memory", "a.org/y", 1.7e9, 200, 0)])
    assert analytics.contested(load(tmp_path).triples).empty


@pytest.mark.skipif(not os.environ.get("RETRIEVA_JAVA_FIXTURE"), reason="set RETRIEVA_JAVA_FIXTURE to the Java-written memory directory")
def test_reads_memory_written_by_the_java_repository():
    m = load(os.environ["RETRIEVA_JAVA_FIXTURE"])
    assert m.warnings == []
    assert set(zip(m.triples["subject"], m.triples["predicate"], m.triples["object"])) == {
        ("coffee", "improve", "memory"), ("coffee", "not improve", "memory"), ("caffeine", "enhance", "alertness")}
    assert set(m.triples["host"]) == {"journal.example.org", "news.example.com", "health.example.gov"}
    assert len(m.routes) == 1 and m.routes["key"].iloc[0] == "coffee memory" and m.routes["verdict"].iloc[0] == "for"
