"""The ephemeral agent: no process state survives a question, only the two memory files do.

ask(claim):
  1. memory pass    - score the claim against stored triples (microseconds, no I/O)
  2. swarm          - 32 crawlers, then 16 deep dives, 8 follow-ups, 1-4 consolidators, under a hard
                      deadline; seeds split evenly across for/against/neutral, later layers follow
                      entities the evidence surfaced (intelligent deviation)
  3. stop           - as soon as one direction holds >= 60% of the weighted evidence
  4. prose          - composed from triples with templates; source text is never re-read
  5. persist        - triples (<= 8 MB) and the route outline (<= 2 MB) are written back
"""
from __future__ import annotations

import os
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, wait
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .extract import content, extract, polarity, tokens
from .ingest import Gate, IngestStats, ingest
from .outline import Outline, Route, route_key
from .sanitize import clean
from .sources import Source
from .store import MB, TripleStore, recency

ALPHA = 0.15          # equal prior mass in each of for / against / neutral
PARTIAL_RELEVANCE = 0.25
STANCE_WORDS = {
    "for": ["benefits", "supports", "effective", "confirmed", "improves", "trial results", "advantages", "proven", "positive effect", "mechanism"],
    "against": ["risks", "criticism", "no evidence", "myth", "harms", "debunked", "side effects", "limitations", "negative effect", "contradicts"],
    "neutral": ["overview", "study", "review", "definition", "history", "effect", "meta-analysis", "context", "research", "explained"],
}
SWARM_LAYERS = (32, 16, 8, 4)   # crawlers per layer; the last layer runs 1-4 consolidators


class ClaimError(ValueError):
    pass


@dataclass
class Claim:
    text: str
    subject: str
    predicate: str
    obj: str
    s: frozenset
    o: frozenset
    pol: int

    @property
    def key(self) -> str:
        return route_key(f"{self.subject} {self.obj}")


def parse_claim(text: str) -> Claim:
    for sent in clean(text).split("\n"):
        for s, p, o in extract(sent):
            return Claim(text.strip(), s, p, o, content(s), content(o), polarity(p, o))
    raise ClaimError("could not parse a 'subject predicate object' claim, e.g. 'coffee improves memory'")


@dataclass
class Evidence:
    triple: object
    row: int
    stance: str      # for | against | neutral
    weight: float
    corroboration: float


@dataclass
class Tally:
    shares: dict
    evidence: list
    n_full: int
    sources: int

    @property
    def leader(self) -> tuple[str, float]:
        k = max(self.shares, key=self.shares.get)
        return k, self.shares[k]


@dataclass
class Answer:
    claim: str
    verdict: str            # for | against | neutral | unresolved
    resolved: bool
    shares: dict
    prose: str
    route: list
    elapsed_ms: float
    from_memory: bool
    rounds: int
    stats: IngestStats = field(default_factory=IngestStats)


class Agent:
    def __init__(self, sources: list[Source], gate: Gate, memory_dir: str | None = None, *,
                 budget: float = 1.0, threshold: float = 0.60, min_sources: int = 2,
                 layers: tuple = SWARM_LAYERS, docs_per_topic: int = 3,
                 store_cap: int = 8 * MB, outline_cap: int = 2 * MB, clock=time.time):
        self.sources, self.gate, self.dir = sources, gate, memory_dir
        self.budget, self.threshold, self.min_sources = budget, threshold, min_sources
        self.layers, self.docs_per_topic = layers, docs_per_topic
        self.store_cap, self.outline_cap, self.clock = store_cap, outline_cap, clock

    # -- memory -----------------------------------------------------------------------------
    def _open(self) -> tuple[TripleStore, Outline]:
        if not self.dir:
            return TripleStore(self.store_cap), Outline(self.outline_cap)
        os.makedirs(self.dir, exist_ok=True)
        return (TripleStore.load(os.path.join(self.dir, "triples.bin"), self.store_cap),
                Outline.load(os.path.join(self.dir, "outline.md"), self.outline_cap))

    def _persist(self, store: TripleStore, outline: Outline) -> None:
        if self.dir:
            store.save(os.path.join(self.dir, "triples.bin"))
            outline.save(os.path.join(self.dir, "outline.md"))

    # -- scoring ----------------------------------------------------------------------------
    def evaluate(self, claim: Claim, store: TripleStore) -> Tally:
        now = self.clock()
        rows = []
        for i in store.candidates(claim.s | claim.o):
            ss, os_ = store.stems(i)
            if not claim.s & ss:
                continue
            t = store.get(i)
            full = not claim.o or bool(claim.o & (os_ | ss))
            rows.append((i, t, ss, os_, polarity(t.p, t.o), full))
        # confidence = synonymous vs antonymous statements about the same topic from *other* hosts
        groups: dict[tuple, list] = defaultdict(list)
        for i, t, ss, os_, pol, _ in rows:
            groups[(ss, os_)].append((t.src.split("/")[0], pol))
        best: dict[tuple, Evidence] = {}
        for i, t, ss, os_, pol, full in rows:
            host = t.src.split("/")[0]
            others = {(h, p) for h, p in groups[(ss, os_)] if h != host}
            syn = len({h for h, p in others if p == pol})
            ant = len({h for h, p in others if p == -pol and pol != 0})
            # no independent voice = the source's own trust; contradiction divides, corroboration adds
            corr = (syn + 1) / (syn + ant + 1) * (1 + 0.25 * min(syn, 3))
            stance = "neutral" if not full or pol == 0 or claim.pol == 0 else ("for" if pol == claim.pol else "against")
            w = t.trust * corr * recency(t.t, now) * (1.0 if full else PARTIAL_RELEVANCE)
            k = (host, ss, (os_ & claim.o) if full and claim.o else os_, stance)  # one voice per host per topic
            if k not in best or w > best[k].weight:
                best[k] = Evidence(t, i, stance, w, corr)
        buckets = {"for": ALPHA, "against": ALPHA, "neutral": ALPHA}
        for ev in best.values():
            buckets[ev.stance] += ev.weight
        total = sum(buckets.values())
        ev_list = sorted(best.values(), key=lambda e: -e.weight)
        return Tally({k: v / total for k, v in buckets.items()}, ev_list,
                     sum(1 for e in ev_list if e.stance != "neutral"),
                     len({e.triple.src.split("/")[0] for e in ev_list}))

    def _resolved(self, tally: Tally) -> bool:
        return tally.leader[1] >= self.threshold and tally.sources >= self.min_sources and tally.n_full >= self.min_sources

    # -- main loop --------------------------------------------------------------------------
    def ask(self, text: str) -> Answer:
        t0 = time.perf_counter()
        deadline = t0 + self.budget
        claim = parse_claim(text)
        store, outline = self._open()
        tally = self.evaluate(claim, store)
        saved = outline.lookup(claim.key)
        stats, route, rounds, from_memory = IngestStats(), [], 0, False

        if saved and self._resolved(tally):
            from_memory, route = True, saved.steps
        else:
            route, rounds = self._dive(claim, store, outline, saved, deadline, stats)
            tally = self.evaluate(claim, store)

        verdict, share = tally.leader
        resolved = self._resolved(tally)
        store.touch(e.row for e in tally.evidence[:12])
        route_saved = resolved and not from_memory
        if route_saved:
            outline.save_route(Route(claim.key, verdict, (tally.shares["for"], tally.shares["against"],
                                                           tally.shares["neutral"]), int(self.clock()), 0, route))
        elif resolved and saved:
            saved.hits += 1
        prose = self._prose(claim, tally, resolved, self.clock())
        elapsed = (time.perf_counter() - t0) * 1000   # answer latency; persisting happens after the clock stops
        if store.dirty or route_saved or not from_memory:
            self._persist(store, outline)
        return Answer(text, verdict if resolved else "unresolved", resolved, tally.shares, prose, route,
                      elapsed, from_memory, rounds, stats)

    def _dive(self, claim, store, outline, saved, deadline, stats):
        """Swarm: 32 crawlers -> 16 deep dives -> 8 follow-ups -> 1-4 consolidators, one layer per round.
        Each layer ends early if a direction already holds the threshold; the deadline cuts any layer short."""
        searched, route, rounds = set(), [], 0
        pool = ThreadPoolExecutor(max_workers=max(self.layers))
        try:
            tally = self.evaluate(claim, store)
            for layer, size in enumerate(self.layers):
                if time.perf_counter() >= deadline:
                    break
                if layer == 0:
                    topics = self._seed_topics(claim, saved, size)
                elif layer < len(self.layers) - 1:
                    topics = self._entity_topics(claim, tally, searched, size)
                else:
                    topics = self._consolidation_topics(claim, tally, searched)
                topics = [t for t in dict.fromkeys(topics) if t not in searched][:size]
                if not topics:
                    continue
                rounds += 1
                searched.update(topics)
                route += self._swarm(topics, store, outline, deadline, stats, pool)
                tally = self.evaluate(claim, store)
                if self._resolved(tally):
                    break
        finally:
            pool.shutdown(wait=False, cancel_futures=True)
        return route, rounds

    def _swarm(self, topics, store, outline, deadline, stats, pool):
        """One crawler per (topic, source), all concurrent; results ingested through the gate."""
        futs = {pool.submit(src.search, t, self.docs_per_topic + min(outline.frequency(t), 5)): t
                for t in topics for src in self.sources}
        done, _ = wait(futs, timeout=max(0.0, deadline - time.perf_counter()))
        by_topic: dict[str, list] = defaultdict(list)
        for f in done:
            try:
                by_topic[futs[f]] += f.result()
            except Exception:
                pass  # a dead crawler must never sink the answer
        steps = []
        for t in topics:
            outline.record_topic(t)
            s = IngestStats()
            ingest(by_topic[t], self.gate, store, s)
            for k in ("docs", "rejected_docs", "dropped_injection", "triples", "new_triples"):
                setattr(stats, k, getattr(stats, k) + getattr(s, k))
            if s.locators:
                steps.append((t, s.locators))
        return steps

    @staticmethod
    def _seed_topics(claim, saved, size):
        """Layer 1: base + facets, then query variants split evenly across for / against / neutral."""
        base = f"{claim.subject} {claim.obj}".strip()
        seeds = [t for t, _ in saved.steps] if saved else []
        seeds += [base, claim.subject, claim.obj]
        lanes = [[f"{base} {w}" for w in words] for words in STANCE_WORDS.values()]
        for i in range(max(map(len, lanes))):          # round-robin: equal parts per direction
            seeds += [ln[i] for ln in lanes if i < len(ln)]
        return seeds

    def _entity_topics(self, claim, tally, searched, size):
        """Layers 2-3: follow entities the evidence surfaced, weighted by how much evidence mentions them."""
        seen = set(claim.s | claim.o)
        for q in searched:
            seen |= set(content(q))
        heat: Counter = Counter()
        for e in tally.evidence:
            for w in content(e.triple.s) | content(e.triple.o):
                if w not in seen:
                    heat[w] += e.weight
        return [f"{claim.subject} {w}" for w, _ in heat.most_common(size)]

    def _consolidation_topics(self, claim, tally, searched):
        """1-4 consolidators, more when the picture is murkier; each targets a thinnest side."""
        share = tally.leader[1]
        n = 1 if share >= 0.5 else 2 if share >= 0.4 else 3 if share >= 0.35 else 4
        order = sorted(tally.shares, key=tally.shares.get)          # thinnest first
        base = f"{claim.subject} {claim.obj}".strip()
        out = [f"{base} {STANCE_WORDS[order[i % 3]][-1 - i // 3]}" for i in range(n)]
        return out

    # -- prose ------------------------------------------------------------------------------
    @staticmethod
    def _prose(claim: Claim, tally: Tally, resolved: bool, now: float) -> str:
        f, a, n = (tally.shares[k] for k in ("for", "against", "neutral"))
        lead, share = tally.leader
        head = (f"{lead.capitalize()}: {share:.0%} of weighted evidence." if resolved else
                f"Unresolved: leading direction '{lead}' at {share:.0%}, below the threshold.")
        lines = [f"{head} Claim: \"{claim.text}\". for {f:.0%} / against {a:.0%} / neutral {n:.0%} "
                 f"({tally.sources} sources)."]
        for stance in ("for", "against", "neutral"):
            for e in [x for x in tally.evidence if x.stance == stance][:2]:
                t = e.triple
                day = datetime.fromtimestamp(t.t, timezone.utc).strftime("%Y-%m")
                pred = t.p.replace("not ", "does not ") if t.p.startswith("not ") else t.p + "s" if t.p not in ("is", "has") else t.p
                lines.append(f"- [{stance}] {t.s} {pred} {t.o} ({t.src.split('/')[0]}, {day}, weight {e.weight:.2f})")
        return "\n".join(lines)
