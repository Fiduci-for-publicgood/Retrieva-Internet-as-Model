# Retrieva — ephemeral agent, persistent context

A stateless agent whose "brain" is the internet: it crawls a restricted, allowlisted slice of it per question, condenses what it
finds into **subject–predicate–object triples (≤ 8 MB)**, remembers the **route** that led to each answer in an **outline (≤ 2 MB)**,
rates evidence for / against / neutral until one direction holds **60%**, and speaks the answer from the retrieval points of a
swarm of up to **128 crawlers** — crawler 1 and the primes, highest first.

* **Runtime:** Maven · Apache Arrow · Tomcat 10.1 · pandas (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md))
* **Cognition:** after the swarm it keeps cycling — probe the weakest point, re-weigh — until it converges or the 3 s cap, then writes the answer as prose
* **Speed:** quick queries ≤ **3 s**; long-form (several topics) **7–15 s** across ≤ 4 areas of 32 crawlers
* **Parsing:** nine shapes (LL, LLR, LR shift-reduce, backwards, question/answer, passive, phrase, centre-out, head) vote
* **Safety:** allowlist + SSRF-safe fetcher, sanitizer, injection filter, triples-only boundary, fail-closed config

```
mvn -B verify                                   # build + all tests (needs Maven Central)
docker compose up --build                       # RETRIEVA_API_TOKEN required
curl -s -H "Authorization: Bearer $RETRIEVA_API_TOKEN" -d '{"text":"Does caffeine enhance alertness?"}' localhost:8080/api/ask
pip install -e sidecar && retrieva-sidecar /var/lib/retrieva     # pandas analytics over the Arrow memory
```

| path | |
|---|---|
| `retrieva-core/` | the engine, dependency-free Java 21 |
| `retrieva-arrow/` | Arrow persistence with crash-safe generations |
| `retrieva-server/` | Tomcat WAR and HTTP API |
| `sidecar/` | pandas analytics |
| `reference-python/` | original Python implementation; golden-vector oracle for the Java core |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design, the Arrow contract, the API, configuration and exactly what has and has not been verified.
