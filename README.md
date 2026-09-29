# Retrieva — ephemeral agent, persistent context

A process that holds no state between questions. Everything it knows lives in two capped files:

| file | cap | contents |
|---|---|---|
| `triples.bin` | 8 MB | bare subject–predicate–object triples (no HTML, URLs, punctuation), each tagged with source, source-added time and source trust |
| `outline.md` | 2 MB | outliner-style routes: for each answered claim, the topics and source locators that led to it (plus topic frequency) |

```
python -m retrieva "caffeine enhances alertness" --corpus examples/corpus.json
python -m retrieva "coffee improves memory"      --corpus examples/corpus.json   # contested -> unresolved
python -m retrieva "..." --live                                                  # + Wikipedia (see limits)
python -m unittest discover -s tests
```
Stdlib only, Python 3.11+.

## Pipeline (`ask`)
1. **Memory pass** — score the claim against stored triples. A saved route plus a resolved score answers with zero fetches.
2. **Swarm**, one layer per round, each layer stops early if a direction reaches 60%:
   **32** crawlers (seed queries split evenly across for/against/neutral) → **16** deep dives into entities the evidence surfaced → **8** follow-ups → **1–4** consolidators (more when the picture is murkier, aimed at the thinnest side). Every crawler is one (topic, source) job; all in a layer run concurrently under a hard deadline (default 1 s).
3. **Verdict** — buckets for / against / neutral start with equal prior mass; each triple adds `source_trust × corroboration × recency × relevance`. Stop when one bucket ≥ 60% with ≥ 2 independent hosts, otherwise report `unresolved` (never a forced answer).
   - *recency*: half-life 365 days from when the source was added.
   - *corroboration* = synonymous vs antonymous statements about the same topic from **other** hosts. A host gets one voice per topic.
4. **Prose** — templated from triples, never from source text. It speaks from the best retrieval point of layer-1 crawlers at prime positions 1, 2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, **in reverse** (31 → 1); a prime crawler with no result is skipped, and a fact already spoken isn't repeated. Positions are fixed and saved in the outline, so a replayed route speaks the same way.
5. **Persist** — triples and routes written back (evicting lowest trust×recency×usage; oldest/least-used routes).

## Injection defence (the restricted ingestion line)
allowlist gate (unknown hosts rejected) → size caps → strip HTML/scripts/URLs/markdown/invisible+bidi chars → sentence-level directive/injection filter → triple extraction → strict charset validation (`[a-z0-9' -]`, no punctuation, so payloads can't survive) → store. Files read back from disk are re-validated when first touched. Only triples cross the boundary.

## Honest limits
- **"Whole internet in microseconds" is not physically possible.** What is fast: memory lookups (~50 ms over a full 8 MB store ≈ 200k triples, cold load ≈ 0.35 s). Live fetches are bounded by network latency and the deadline; an unfinished swarm returns `unresolved` rather than overrunning. Wikipedia is blocked by this sandbox's proxy, so `WikipediaSource` is **untested live**; the swarm is tested against the offline corpus and a slow-source deadline test.
- "Synonym/antonym" understanding is a small hand-written lexicon (`lexicon.py`) and rule-based extraction, not a language model. It handles negation ("does not improve", "no evidence that…") but misses paraphrase and most nuance. Extend the lexicon, or swap `extract.py` for an NLP/LLM extractor behind the same interface.
- Sentence filtering cannot stop a malicious page on an allowlisted host from stating *false facts*; trust weights and cross-host corroboration are the mitigation.
- Sources are pluggable (`search(query, limit) -> [Doc]`); add real crawlers there.
