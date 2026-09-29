# Retrieva — ephemeral agent, persistent context

A process that holds no state between questions. Everything it knows lives in two capped files:

| file | cap | contents |
|---|---|---|
| `triples.bin` | 8 MB | bare subject–predicate–object triples (no HTML, URLs, punctuation), each tagged with source, source-added time and source trust |
| `outline.md` | 2 MB | outliner-style routes: for each answered claim, the topics and source locators that led to it (plus topic frequency) |

```
python -m retrieva "caffeine enhances alertness" --corpus examples/corpus.json
python -m retrieva "coffee improves memory"      --corpus examples/corpus.json   # contested -> unresolved
python -m retrieva "..." --live                                                  # + Wikipedia
python -m unittest discover -s tests
```
Stdlib only, Python 3.11+.

## Modes and budgets
| input | mode | time cap | crawlers |
|---|---|---|---|
| one claim / question | `ask` | **3 s** | ≤ 32 concurrent |
| several claims spanning topics | `ask_long` | **7–15 s** (2 areas 7 s, 3 → 11 s, 4 → 15 s) | ≤ **128** = 4 areas × 32, areas concurrent, claims inside an area consecutive |

The CLI picks the mode automatically. A swarm cut off by the deadline returns `unresolved` instead of overrunning.

## Pipeline
1. **Memory pass** — score the claim against stored triples. A saved route plus a resolved score answers with zero fetches.
2. **Swarm**, one layer per round, each layer stops early if a direction reaches 60%:
   **32** crawlers (seed queries split evenly across for/against/neutral) → **16** deep dives into entities the evidence surfaced → **8** follow-ups → **1–4** consolidators (more when the picture is murkier, aimed at the thinnest side). Every crawler is one (topic, source) job.
3. **Verdict** — buckets for / against / neutral start with equal prior mass; each triple adds `source_trust × corroboration × recency × relevance`. Stop when one bucket ≥ 60% with ≥ 2 independent hosts, otherwise `unresolved` (never forced).
   - *recency*: half-life 365 days from when the source was added.
   - *corroboration* = synonymous vs antonymous statements about the same topic from **other** hosts. A host gets one voice per topic.
4. **The answer is only the prime voices.** Crawlers are numbered 1..128 (area *k* owns 32(*k*−1)+1 … 32*k*). The entire answer is the best retrieval point of crawlers **1 and the primes ≤ 127**, spoken from the highest number down to 1, composed from triples (never source text). A prime crawler with no result is skipped; a fact already spoken is not repeated. Verdict and shares are in structured fields, not prose.
5. **Persist** — triples and routes written back (evicting lowest trust×recency×usage; oldest/least-used routes). Layer-1 positions are saved so replayed routes speak identically.

## Parse shapes (`retrieva/parsers.py`)
Sentences are read by nine parsers and an ensemble votes (`python -m retrieva.parsers "sentence"` shows each):
`ll` left-to-right leftmost · `llr` LL with right-context lookahead (noun-vs-verb) · `lr` shift-reduce (coordinated VPs, relative clauses) · `rl` backwards, right-to-left with subject inheritance · `qar` question→claim and question+answer pairs · `passive` · `pattern` phrases ("leads to", "is good for") · `center` · `head`.
Readings are clustered and vote-counted; a reading is dropped when a better-voted parser contradicts its polarity. "LLR" and "QAR" are my interpretation of those names (see the module docstring); rename or re-scope as you intended.

## Injection defence (the restricted ingestion line)
allowlist gate (unknown hosts rejected) → size caps → strip HTML/scripts/URLs/markdown/invisible+bidi chars → sentence-level directive/injection filter → triple extraction → strict charset validation (`[a-z0-9' -]`, no punctuation, so payloads can't survive) → store. Files read back from disk are re-validated when first touched. Only triples cross the boundary.
