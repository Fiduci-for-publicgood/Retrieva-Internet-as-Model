# Retrieva architecture

An **ephemeral agent with persistent context**: every request is stateless; everything the agent knows lives in two
capped structures that survive restarts.

```
                    ┌────────────────────────── Tomcat 10.1 (virtual threads) ─────────────────────────┐
 client ── HTTPS ─▶ │ ApiServlet ─▶ ApiHandler  (auth · admission · body caps · JSON · metrics)        │
                    │                    │                                                            │
                    │                    ▼                                                            │
                    │               Agent (retrieva-core, zero deps)                                   │
                    │   parse claim ─▶ memory pass ─▶ swarm 32→16→8→1-4 ─▶ prime-voice answer          │
                    │        │              ▲                 │                                       │
                    │        │        ┌─────┴──────┐   ┌──────▼───────────────────────┐               │
                    │        │        │  Memory    │   │ crawlers (virtual threads)   │               │
                    │        │        │ 8 MB triples│◀─│ SafeHttp: allowlist · SSRF · │── egress ─▶ Wikipedia / arXiv
                    │        │        │ 2 MB routes │   │ size caps · rate · breaker   │               │
                    │        │        └─────┬──────┘   └──────────────────────────────┘               │
                    │        ▼              │ snapshot                                                │
                    │   Ingest gate: allowlist → sanitize → injection filter → 9 parsers vote → triples │
                    └────────────────────────┬─────────────────────────────────────────────────────────┘
                                             ▼ generations, atomic CURRENT pointer
                                   Apache Arrow IPC files  ──▶  pandas sidecar (read-only analytics)
```

## Modules
| module | role | dependencies |
|---|---|---|
| `retrieva-core` | sanitizer, nine parsers + ensemble, triple store, outline, ingestion gate, swarm engine, safe HTTP, sources | none |
| `retrieva-arrow` | crash-safe Arrow persistence of `Memory` | Arrow 18, slf4j-jdk14 |
| `retrieva-server` | Tomcat WAR: `ApiHandler` (pure), thin servlet + listener | Jakarta Servlet 6 (provided) |
| `sidecar/` | pandas analytics over the Arrow files | pandas, pyarrow |
| `reference-python/` | the original implementation; **the oracle** for golden-vector parity tests | none |

## The request path
1. **Parse.** The input is sanitized and split into sentences (same filter as crawled text); each sentence becomes a claim via the best-voted parser reading.
2. **Mode.** One claim → quick (hard cap **3 s**, ≤ 32 concurrent crawlers). Several claims → long-form: ≤ **4 areas** resolve concurrently (≤ **128** crawlers), claims inside an area consecutively; budget **7 s** (2 areas) to **15 s** (4 areas).
3. **Memory pass.** Score the claim against stored triples. A saved route plus a resolved score answers with **zero fetches**.
4. **Swarm.** Layers of 32 → 16 → 8 → 1–4 crawlers (one crawler = one topic × one source). Layer 1 seeds are split evenly across for/against/neutral; later layers follow entities the evidence surfaced; the last layer consolidates the thinnest side (1–4 crawlers, more when murkier). All crawlers of a layer are cancelled at the deadline.
5. **Deliberation (server default).** After the layered swarm, the agent keeps *thinking* while it can still learn, up to the 3 s cap. Each cycle weighs the evidence, finds where the case is weakest — uncorroborated single-voice evidence, the opposite side, missing context, entities it keeps meeting — sends up to 8 crawlers at exactly those points, and weighs again. It stops at convergence (two consecutive cycles that add nothing and move no share by more than 0.001), when no new probe can be formed, at `RETRIEVA_MAX_CYCLES`, or when the deadline is too close for another cycle. A route replayed from memory skips this: it was already deliberated. `cycles` and `converged` are reported. It uses the full budget only while cycles keep producing evidence, not by spinning.
6. **Verdict.** For/against/neutral buckets start with equal prior mass. Each triple adds `source_trust × corroboration × recency × relevance`; one voice per host per topic; corroboration = synonymous vs antonymous statements from *other* hosts; recency has a 365-day half-life from the time the source was added. Stop at ≥ 60% with ≥ 2 independent hosts, otherwise `unresolved` — never forced.
7. **Answer.** Crawlers are numbered 1..128 (area *k* owns 32(*k*−1)+1 … 32*k*). The **entire answer** is the best retrieval point of crawlers **1 and the primes ≤ 127**, spoken from the highest number down to 1, composed from triples, never from source text. `Prose` realises those points as flowing English: subject–verb agreement, negation, attribution to the source with its month, hedging matched to source trust ("a lower-trust source … claims that …"), connectives that follow stance (Likewise / However / On the other hand / For context), paragraph breaks per area and after five sentences. The numbered audit form (`answer_numbered`) and each voice's crawler number, source and trust are returned alongside.

> **Consequence of the prime-only rule:** the prose shows only what the speaking crawlers retrieved. On a contested claim (verdict `unresolved`) it can show mostly one side. Always read `verdict` and `shares` next to `answer`.
> **Limit of template prose:** phrasing is only as good as the triples. They keep short word runs, so a phrase like "boosts alertness several hours" loses its preposition. The text is grammatical and well attributed; it is not free-form writing.

## Ingestion line (prompt-injection defence)
allowlist gate (exact host suffix; unknown hosts dropped) → size caps → strip HTML/scripts/URLs/markdown/invisible+bidi characters → sentence-level directive/injection filter → nine parse shapes vote → strict triple charset (`[a-z0-9' -]`, no punctuation, so payloads cannot survive) → store. Files read back from disk are re-validated row by row on load. Only triples cross the boundary; no source text is ever given to a model.

**Network policy** (`SafeHttp`): https only · no credentials in URL · default port only · exact-hostname allowlist · every resolved address must be public (loopback, RFC 1918, link-local, CGNAT, ULA, multicast, reserved and IPv4-mapped forms are refused) · manual redirects (≤ 3) re-checked per hop · response size cap · text content types only · per-source rate spacing and circuit breaker (arXiv: 1 request / 3 s).

## Memory
| structure | cap | contents |
|---|---|---|
| triples | **8 MB** logical (12 + Σ(string UTF-8 bytes + 4) + 29 per row) | interned strings + rows `(s,p,o,src,added,trust,hits)`; eviction by `trust × recency × (1+hits)` down to 90% |
| outline | **2 MB** rendered text | for each answered claim, the topics and source locators that led to it (plus topic frequency); least-used, oldest routes evicted |

### Arrow contract (`retrieva-arrow` writes, the sidecar reads)
```
DIR/CURRENT                     name of the live generation, replaced atomically
DIR/gen-NNNNNN/strings.arrow    value: utf8                                 (row index = string id)
DIR/gen-NNNNNN/triples.arrow    s,p,o,src: int32 (string ids) · added: float64 (epoch s) · trust_q: int32 (0..255) · hits: int32
DIR/gen-NNNNNN/outline.md       route outline (grammar in Outline.java)
DIR/gen-NNNNNN/MANIFEST.json    {"version":1,"strings":N,"triples":M,"sha256":{file:hex}}
```
A save writes a complete new generation, then flips `CURRENT`; a crash leaves the previous generation intact. Load verifies hashes, falls back to the previous generation on damage, and re-validates every row. The last 3 generations are kept.

## HTTP API
| endpoint | auth | |
|---|---|---|
| `GET /api/health` | none | liveness and memory sizes |
| `GET /api/metrics` | bearer | Prometheus text |
| `POST /api/ask` | bearer | `{"text": "..."}` → auto mode; add `"route": true` for the crawl route. Returns `answer` (prose), `answer_numbered`, `verdict`, `shares`, `voices` (with source and trust), `cycles`, `converged` |
| `POST /api/ask/quick`, `/api/ask/long` | bearer | force a mode |

Errors: 400 bad body · 401 · 405 · 413 body too large · 422 no parseable claim · 429 busy (`Retry-After: 1`) · 500 generic. Config fails **closed**: no token (without `RETRIEVA_ALLOW_ANONYMOUS=true`), no sources, or any invalid value stops the deployment.

## Configuration (environment variables, or servlet context parameters of the same name)
`RETRIEVA_API_TOKEN` (≥ 16 chars) · `RETRIEVA_SOURCES` (`wikipedia,arxiv`, or `none`) · `RETRIEVA_CORPUS_FILE` (offline JSON corpus) · `RETRIEVA_TRUST` (`host=0.8,...`) · `RETRIEVA_USER_AGENT` (identify yourself; include a contact) · `RETRIEVA_MEMORY_DIR` · `RETRIEVA_DELIBERATE` (default `true`) · `RETRIEVA_MAX_CYCLES` (default 12) · `RETRIEVA_QUICK_BUDGET_S` (≤ 3) · `RETRIEVA_MAX_CRAWLERS` (≤ 128) · `RETRIEVA_QUICK_CONCURRENCY` · `RETRIEVA_LONG_CONCURRENCY` · `RETRIEVA_PERSIST_SECONDS` · `RETRIEVA_MAX_QUICK_BODY` · `RETRIEVA_MAX_LONG_BODY`.

## How this is verified
CI run 2 (commit `da4ac1f`): all four jobs green.

| layer | how | status |
|---|---|---|
| core (parsers, sanitizer, store, outline, engine, source policy) | 11 checks incl. **golden-vector parity with the Python reference** (parser readings, ensemble, verdicts, shares, voices, weights, routes, ingestion stats) | passed locally and in CI (`mvn verify`) |
| `ApiHandler` / `AppConfig` | 5 checks: auth, validation, quick + long, 429 admission, config fails closed | passed locally and in CI |
| Arrow persistence | round trip, generation pruning, damaged-newest fallback, crashed-save leftovers, hostile rows | passed in CI |
| Tomcat wiring | embedded Tomcat: real listener + servlet, HTTP calls, refusal to start without a token, restart with the route replayed from persisted memory | passed in CI (after one test-assertion fix) |
| pandas sidecar | 9 pytest checks incl. **reading a memory directory written by the Java code** | passed in CI, 0 skipped |
| Docker image | `docker build` | builds in CI; the container has **not** been started or health-checked |
| live Wikipedia / arXiv crawling | response parsers tested on hand-written fixtures shaped per the public API docs | **the network path has never been run** in CI or the dev sandbox |
| answer quality on real questions | — | **not measured.** Verdicts come from a small hand-written lexicon and rule-based parsers; there is no benchmark against real queries |

## Operations
* `docker compose up` (see `docker-compose.yml`); the WAR is `ROOT.war`. Give the JVM `--add-opens=java.base/java.nio=ALL-UNNAMED` (Arrow) — the image sets it.
* Memory volume: `/var/lib/retrieva`. Back it up by copying the newest `gen-*` directory plus `CURRENT`.
* Set `RETRIEVA_USER_AGENT` with a real contact before crawling anything.
* Regenerate golden vectors after any change to the Python reference: `python reference-python/tools/gen_golden.py` (CI fails if they drift).
