package io.retrieva.core;

import io.retrieva.core.Ingest.Gate;
import io.retrieva.core.Ingest.Stats;
import io.retrieva.core.Nlp.Tri;
import io.retrieva.core.Outline.Route;
import io.retrieva.core.Outline.Step;
import io.retrieva.core.Source.Doc;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * The agent. Stateless per request; all knowledge lives in {@link Memory} (8 MB triples + 2 MB routes).
 *
 * <ol>
 *   <li>memory pass: score the claim against stored triples (no I/O)</li>
 *   <li>swarm: 32 crawlers, then 16 deep dives, 8 follow-ups, 1-4 consolidators under a hard deadline;
 *       seeds split evenly across for/against/neutral, later layers follow entities the evidence surfaced</li>
 *   <li>stop as soon as one direction holds >= 60% of the weighted evidence from >= 2 independent hosts</li>
 *   <li>the entire answer is the retrieval points of crawlers 1 and the primes up to 128, highest number first</li>
 * </ol>
 */
public final class Agent implements AutoCloseable {
    public static final double ALPHA = 0.15;             // equal prior mass in each of for / against / neutral
    public static final double PARTIAL_RELEVANCE = 0.25;
    public static final int MAX_AREAS = 4;               // 4 areas x 32 layer-1 crawlers = 128 crawlers at most
    public static final double QUICK_BUDGET = 3.0;       // seconds, hard cap for a single-claim query
    public static final double LONG_BUDGET_LO = 7.0;     // seconds with 2 areas
    public static final double LONG_BUDGET_HI = 15.0;    // seconds with 4 areas
    public static final int[] SWARM_LAYERS = {32, 16, 8, 4};
    public static final List<Integer> PRIME_VOICES = primeVoices();
    public static final List<String> STANCES = List.of("for", "against", "neutral");

    private static final Map<String, List<String>> STANCE_WORDS = new LinkedHashMap<>();
    static {
        STANCE_WORDS.put("for", List.of("benefits", "supports", "effective", "confirmed", "improves", "trial results", "advantages", "proven", "positive effect", "mechanism"));
        STANCE_WORDS.put("against", List.of("risks", "criticism", "no evidence", "myth", "harms", "debunked", "side effects", "limitations", "negative effect", "contradicts"));
        STANCE_WORDS.put("neutral", List.of("overview", "study", "review", "definition", "history", "effect", "meta-analysis", "context", "research", "explained"));
    }

    private static List<Integer> primeVoices() {
        List<Integer> out = new ArrayList<>();
        out.add(1);
        for (int i = 2; i <= 128; i++) {
            boolean p = true;
            for (int d = 2; d * d <= i; d++) if (i % d == 0) { p = false; break; }
            if (p) out.add(i);
        }
        return List.copyOf(out);
    }

    /**
     * Tunables. {@code deliberate}: after the layered swarm resolves (or not), keep cycling through reflection rounds while they
     * still produce new evidence, until convergence, {@code maxCycles}, or the deadline. Off by default so the engine reproduces
     * the Python reference exactly; the server turns it on.
     */
    public record Config(double budget, double threshold, int minSources, int[] layers, int docsPerTopic, int maxConcurrentCrawlers,
                         DoubleSupplier clock, boolean deliberate, int maxCycles) {
        public static Config defaults() {
            return new Config(QUICK_BUDGET, 0.60, 2, SWARM_LAYERS, 3, 128, () -> System.currentTimeMillis() / 1000.0, false, 12);
        }

        public Config withBudget(double b) {
            return new Config(b, threshold, minSources, layers, docsPerTopic, maxConcurrentCrawlers, clock, deliberate, maxCycles);
        }

        public Config withMaxCrawlers(int n) {
            return new Config(budget, threshold, minSources, layers, docsPerTopic, n, clock, deliberate, maxCycles);
        }

        public Config withClock(DoubleSupplier c) {
            return new Config(budget, threshold, minSources, layers, docsPerTopic, maxConcurrentCrawlers, c, deliberate, maxCycles);
        }

        public Config withDeliberation(boolean on, int cycles) {
            return new Config(budget, threshold, minSources, layers, docsPerTopic, maxConcurrentCrawlers, clock, on, cycles);
        }
    }

    // -- value types -----------------------------------------------------------------------------------
    public static final class ClaimException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ClaimException(String m) {
            super(m);
        }
    }

    public record Claim(String text, String subject, String predicate, String obj, Set<String> s, Set<String> o, int pol) {
        public String key() {
            return Outline.routeKey(subject + " " + obj);
        }
    }

    public record Evidence(TripleStore.Triple triple, int row, String stance, double weight, double corroboration) {}

    public record Tally(Map<String, Double> shares, List<Evidence> evidence, int nFull, int sources) {
        public String leader() {
            String best = "for";
            for (String k : STANCES) if (shares.get(k) > shares.get(best)) best = k;
            return best;
        }

        public double leaderShare() {
            return shares.get(leader());
        }
    }

    /** One speaking crawler's retrieval point: the display text (audit form) plus the triple fields the prose realiser needs. */
    public record Voice(int pos, String stance, String text, double weight, String subject, String predicate, String object, String host,
                        String month, double trust) {}

    public record Answer(String claim, String verdict, boolean resolved, Map<String, Double> shares, String prose, String numbered,
                         List<Step> route, double elapsedMs, boolean fromMemory, int rounds, Stats stats, List<Voice> voices, int cycles,
                         boolean converged) {}

    public record LongAnswer(List<List<Answer>> areas, String prose, String numbered, List<Voice> voices, double elapsedMs, double budget) {}

    // -- claims ----------------------------------------------------------------------------------------
    private static Claim claimOf(String text, Tri t) {
        return new Claim(text.strip(), t.s(), t.p(), t.o(), Nlp.content(t.s()), Nlp.content(t.o()), Nlp.polarity(t.p(), t.o()));
    }

    public static Claim parseClaim(String text) {
        for (String sent : Text.sentences(text).sentences()) {
            List<Tri> ts = Parsers.extractAll(sent);
            if (!ts.isEmpty()) return claimOf(text, ts.get(0));
        }
        throw new ClaimException("could not parse a 'subject predicate object' claim, e.g. 'coffee improves memory'");
    }

    /** One claim per sentence, using the best-voted reading. User input is untrusted: same filter as crawled text. */
    public static List<Claim> parseClaims(String text) {
        List<Claim> out = new ArrayList<>();
        for (String sent : Text.sentences(text).sentences()) {
            List<Tri> ts = Parsers.extractAll(sent);
            if (!ts.isEmpty()) out.add(claimOf(sent, ts.get(0)));
        }
        if (out.isEmpty()) throw new ClaimException("no 'subject predicate object' claims found in the input");
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return u;
    }

    /** Cluster claims sharing topic stems; merge the smallest into the most-overlapping area beyond {@code maxAreas}. */
    public static List<List<Claim>> groupAreas(List<Claim> claims, int maxAreas) {
        List<List<Claim>> groups = new ArrayList<>();
        outer:
        for (Claim c : claims) {
            for (List<Claim> g : groups) {
                for (Claim x : g) {
                    if (Parsers.intersects(c.s(), union(x.s(), x.o())) || Parsers.intersects(x.s(), union(c.s(), c.o()))) {
                        g.add(c);
                        continue outer;
                    }
                }
            }
            groups.add(new ArrayList<>(List.of(c)));
        }
        while (groups.size() > maxAreas) {
            groups.sort((a, b) -> Integer.compare(a.size(), b.size()));
            List<Claim> small = groups.remove(0);
            Set<String> stems = new HashSet<>();
            for (Claim c : small) stems.addAll(union(c.s(), c.o()));
            List<Claim> best = null;
            int bestN = -1;
            for (List<Claim> g : groups) {
                Set<String> gs = new HashSet<>();
                for (Claim c : g) gs.addAll(union(c.s(), c.o()));
                gs.retainAll(stems);
                if (gs.size() > bestN) {
                    bestN = gs.size();
                    best = g;
                }
            }
            best.addAll(small);
        }
        return groups;
    }

    public static double longBudget(int nAreas) {
        return LONG_BUDGET_LO + (LONG_BUDGET_HI - LONG_BUDGET_LO) * (Math.max(2, nAreas) - 2) / (MAX_AREAS - 2);
    }

    // -- the agent -------------------------------------------------------------------------------------
    private final List<Source> sources;
    private final Gate gate;
    private final Config cfg;
    private final ExecutorService crawlers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    public Agent(List<Source> sources, Gate gate, Config cfg) {
        this.sources = List.copyOf(sources);
        this.gate = gate;
        this.cfg = cfg;
        this.permits = new Semaphore(cfg.maxConcurrentCrawlers());
    }

    @Override
    public void close() {
        crawlers.shutdownNow();
    }

    // -- scoring ---------------------------------------------------------------------------------------
    private record GroupKey(Set<String> ss, Set<String> os) {}

    private record BestKey(String host, Set<String> ss, Set<String> os, String stance) {}

    private record HostPol(String host, int pol) {}

    private record Cand(int row, TripleStore.Triple t, Set<String> ss, Set<String> os, int pol, boolean full) {}

    public Tally evaluate(Claim claim, TripleStore store) {
        double now = cfg.clock().getAsDouble();
        List<Cand> rows = new ArrayList<>();
        for (int i : store.candidates(claim.s())) {
            Set<String> ss = store.subjectStems(i), os = store.objectStems(i);
            if (!Parsers.intersects(claim.s(), ss)) continue;
            TripleStore.Triple t = store.get(i);
            boolean full = claim.o().isEmpty() || Parsers.intersects(claim.o(), os) || Parsers.intersects(claim.o(), ss);
            rows.add(new Cand(i, t, ss, os, Nlp.polarity(t.p(), t.o()), full));
        }
        // confidence = synonymous vs antonymous statements about the same topic from *other* hosts
        Map<GroupKey, List<HostPol>> groups = new HashMap<>();
        for (Cand c : rows) groups.computeIfAbsent(new GroupKey(c.ss, c.os), k -> new ArrayList<>()).add(new HostPol(c.t.host(), c.pol));
        Map<BestKey, Evidence> best = new LinkedHashMap<>();
        for (Cand c : rows) {
            String host = c.t.host();
            Set<String> synHosts = new HashSet<>(), antHosts = new HashSet<>();
            for (HostPol hp : groups.get(new GroupKey(c.ss, c.os))) {
                if (hp.host.equals(host)) continue;
                if (hp.pol == c.pol) synHosts.add(hp.host);
                if (hp.pol == -c.pol && c.pol != 0) antHosts.add(hp.host);
            }
            int syn = synHosts.size(), ant = antHosts.size();
            // no independent voice = the source's own trust; contradiction divides, corroboration adds
            double corr = (double) (syn + 1) / (syn + ant + 1) * (1 + 0.25 * Math.min(syn, 3));
            String stance = !c.full || c.pol == 0 || claim.pol() == 0 ? "neutral" : (c.pol == claim.pol() ? "for" : "against");
            double w = c.t.trust() * corr * TripleStore.recency(c.t.t(), now) * (c.full ? 1.0 : PARTIAL_RELEVANCE);
            Set<String> osKey = c.full && !claim.o().isEmpty() ? intersection(c.os, claim.o()) : c.os;
            BestKey k = new BestKey(host, c.ss, osKey, stance);     // one voice per host per topic
            Evidence cur = best.get(k);
            if (cur == null || w > cur.weight()) best.put(k, new Evidence(c.t, c.row, stance, w, corr));
        }
        Map<String, Double> buckets = new LinkedHashMap<>();
        for (String s : STANCES) buckets.put(s, ALPHA);
        for (Evidence e : best.values()) buckets.merge(e.stance(), e.weight(), Double::sum);
        double total = 0;
        for (double v : buckets.values()) total += v;
        Map<String, Double> shares = new LinkedHashMap<>();
        for (String s : STANCES) shares.put(s, buckets.get(s) / total);
        List<Evidence> ev = new ArrayList<>(best.values());
        ev.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        int nFull = 0;
        Set<String> hosts = new HashSet<>();
        for (Evidence e : ev) {
            if (!e.stance().equals("neutral")) nFull++;
            hosts.add(e.triple().host());
        }
        return new Tally(shares, ev, nFull, hosts.size());
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> r = new HashSet<>(a);
        r.retainAll(b);
        return r;
    }

    private boolean resolved(Tally t) {
        return t.leaderShare() >= cfg.threshold() && t.sources() >= cfg.minSources() && t.nFull() >= cfg.minSources();
    }

    // -- entry points ----------------------------------------------------------------------------------
    /** Quick query: one claim, one 32-16-8-1..4 resolve, hard cap {@code cfg.budget()} (default 3 s). */
    public Answer ask(String text, Memory mem) {
        long deadline = System.nanoTime() + (long) (cfg.budget() * 1e9);
        return resolve(parseClaim(text), mem, deadline, 0);
    }

    /**
     * Long-form input spanning topics: at most 4 areas resolve concurrently (at most 128 crawlers); claims within an area
     * resolve consecutively. Budget 7 s (2 areas) to 15 s (4 areas).
     */
    public LongAnswer askLong(String text, Memory mem) {
        long t0 = System.nanoTime();
        List<List<Claim>> areas = groupAreas(parseClaims(text), MAX_AREAS);
        double budget = longBudget(areas.size());
        long deadline = t0 + (long) (budget * 1e9);
        List<Future<List<Answer>>> futs = new ArrayList<>();
        try (ExecutorService areaPool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int idx = 0; idx < areas.size(); idx++) {
                final int a = idx;
                futs.add(areaPool.submit(() -> {
                    List<Answer> out = new ArrayList<>();
                    for (Claim c : areas.get(a)) out.add(resolve(c, mem, deadline, a * cfg.layers()[0]));   // consecutive resolves
                    return out;
                }));
            }
        }
        List<List<Answer>> answers = new ArrayList<>();
        for (Future<List<Answer>> f : futs) {
            try {
                answers.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        Map<Integer, Voice> best = new LinkedHashMap<>();    // crawler number -> strongest voice
        for (List<Answer> area : answers) for (Answer a : area) for (Voice v : a.voices()) {
            Voice cur = best.get(v.pos());
            if (cur == null || v.weight() > cur.weight()) best.put(v.pos(), v);
        }
        List<Voice> voices = new ArrayList<>(best.values());
        return new LongAnswer(answers, Prose.realize(voices), speak(voices), voices, (System.nanoTime() - t0) / 1e6, budget);
    }

    private Answer resolve(Claim claim, Memory mem, long deadline, int offset) {
        long t0 = System.nanoTime();
        Tally tally;
        Route saved;
        mem.lock.lock();
        try {
            tally = evaluate(claim, mem.store);
            saved = mem.outline.lookup(claim.key());
        } finally {
            mem.lock.unlock();
        }
        Stats stats = new Stats();
        List<Step> route;
        int rounds = 0, cycles = 0;
        boolean fromMemory = false, converged = true;
        if (saved != null && resolved(tally)) {
            fromMemory = true;                    // a replayed route is already deliberated: answer instantly
            route = saved.steps;
        } else {
            Dive d = dive(claim, mem, saved, deadline, stats);
            route = d.route;
            rounds = d.rounds;
            if (cfg.deliberate()) {
                Reflection r = reflect(claim, mem, d.searched, deadline, stats, route);
                cycles = r.cycles;
                converged = r.converged;
            }
        }
        mem.lock.lock();
        try {
            tally = evaluate(claim, mem.store);
            String verdict = tally.leader();
            boolean resolved = resolved(tally);
            List<Integer> rowsUsed = new ArrayList<>();
            for (Evidence e : tally.evidence().subList(0, Math.min(12, tally.evidence().size()))) rowsUsed.add(e.row());
            mem.store.touch(rowsUsed);
            boolean routeSaved = resolved && !fromMemory;
            if (routeSaved) {
                mem.outline.saveRoute(new Route(claim.key(), verdict, new double[] {tally.shares().get("for"), tally.shares().get("against"),
                        tally.shares().get("neutral")}, (long) cfg.clock().getAsDouble(), 0, new ArrayList<>(route)));
            } else if (resolved && saved != null) {
                mem.outline.markHit(saved);
            }
            List<Voice> voices = voices(tally, route.subList(0, Math.min(cfg.layers()[0], route.size())), offset);
            return new Answer(claim.text(), resolved ? verdict : "unresolved", resolved, tally.shares(), Prose.realize(voices), speak(voices),
                    route, (System.nanoTime() - t0) / 1e6, fromMemory, rounds, stats, voices, cycles, converged);
        } finally {
            mem.lock.unlock();
        }
    }

    private record Dive(List<Step> route, int rounds, Set<String> searched) {}

    /** 32 crawlers -> 16 deep dives -> 8 follow-ups -> 1-4 consolidators, one layer per round. */
    private Dive dive(Claim claim, Memory mem, Route saved, long deadline, Stats stats) {
        Set<String> searched = new LinkedHashSet<>();
        List<Step> route = new ArrayList<>();
        int rounds = 0;
        Tally tally = lockedEval(claim, mem);
        int[] layers = cfg.layers();
        for (int layer = 0; layer < layers.length; layer++) {
            if (System.nanoTime() >= deadline) break;
            int size = layers[layer];
            List<String> topics;
            if (layer == 0) topics = seedTopics(claim);
            else if (layer < layers.length - 1) topics = entityTopics(claim, tally, searched, size);
            else topics = consolidationTopics(claim, tally);
            List<String> uniq = new ArrayList<>();
            for (String t : new LinkedHashSet<>(topics)) if (!searched.contains(t) && uniq.size() < size) uniq.add(t);
            if (uniq.isEmpty()) continue;
            rounds++;
            searched.addAll(uniq);
            List<Step> steps = swarm(uniq, mem, deadline, stats, layer == 0);
            if (layer == 0) while (steps.size() < size) steps.add(new Step("-", new ArrayList<>()));   // positions 1..32 are fixed
            route.addAll(steps);
            tally = lockedEval(claim, mem);
            if (resolved(tally)) break;
        }
        return new Dive(route, rounds, searched);
    }

    private static final long MIN_CYCLE_NANOS = 50_000_000L;

    private record Reflection(int cycles, boolean converged) {}

    /**
     * The deliberation loop. Each cycle: weigh what is known, find where the case is weakest (uncorroborated evidence, the
     * opposite side, missing context, unexplored entities), send crawlers at exactly those points, and weigh again. It ends at
     * convergence (two consecutive cycles that add nothing and move no share by more than 0.001), when no new probe can be
     * formed, at {@code maxCycles}, or when the deadline is too close for another cycle.
     */
    private Reflection reflect(Claim claim, Memory mem, Set<String> searched, long deadline, Stats stats, List<Step> route) {
        int cycles = 0, calm = 0;
        boolean converged = false;
        Tally tally = lockedEval(claim, mem);
        while (cycles < cfg.maxCycles() && deadline - System.nanoTime() > MIN_CYCLE_NANOS) {
            List<String> topics = reflectionTopics(claim, tally, searched, cycles);
            if (topics.isEmpty()) {
                converged = true;
                break;
            }
            searched.addAll(topics);
            int learnedBefore = stats.newTriples;
            route.addAll(swarm(topics, mem, deadline, stats, false));
            Tally next = lockedEval(claim, mem);
            cycles++;
            double moved = 0;
            for (String k : STANCES) moved = Math.max(moved, Math.abs(next.shares().get(k) - tally.shares().get(k)));
            calm = stats.newTriples == learnedBefore && moved < 1e-3 ? calm + 1 : 0;
            tally = next;
            if (calm >= 2) {
                converged = true;
                break;
            }
        }
        return new Reflection(cycles, converged);
    }

    /** At most 8 probes: corroborate the shakiest evidence, seek the opposite side, add context, follow new entities. */
    private List<String> reflectionTopics(Claim claim, Tally tally, Set<String> searched, int cycle) {
        String base = (claim.subject() + " " + claim.obj()).strip();
        List<String> out = new ArrayList<>();
        int probes = 0;
        for (Evidence e : tally.evidence()) {                       // 1. uncorroborated (single-voice) evidence, strongest first
            if (probes == 2) break;
            if (!e.stance().equals("neutral") && e.corroboration() <= 1.0) {
                String p = e.triple().p().startsWith("not ") ? e.triple().p().substring(4) : e.triple().p();
                out.add(e.triple().s() + " " + p + " " + e.triple().o());
                probes++;
            }
        }
        List<String> opposite = STANCE_WORDS.get(tally.leader().equals("for") ? "against" : "for");   // 2. the side most likely to be missing
        out.add(base + " " + opposite.get((cycle * 2) % opposite.size()));
        out.add(base + " " + opposite.get((cycle * 2 + 1) % opposite.size()));
        List<String> neutral = STANCE_WORDS.get("neutral");         // 3. context
        out.add(base + " " + neutral.get(cycle % neutral.size()));
        out.addAll(entityTopics(claim, tally, searched, 3));         // 4. entities the evidence keeps mentioning
        List<String> uniq = new ArrayList<>();
        for (String t : new LinkedHashSet<>(out)) if (!searched.contains(t) && uniq.size() < 8) uniq.add(t);
        return uniq;
    }

    private Tally lockedEval(Claim claim, Memory mem) {
        mem.lock.lock();
        try {
            return evaluate(claim, mem.store);
        } finally {
            mem.lock.unlock();
        }
    }

    private record Fetched(String topic, List<Doc> docs) {}

    /** One crawler per (topic, source), all concurrent, cancelled at the deadline; results ingested through the gate. */
    private List<Step> swarm(List<String> topics, Memory mem, long deadline, Stats stats, boolean keepEmpty) {
        List<Callable<Fetched>> tasks = new ArrayList<>();
        for (String topic : topics) {
            int limit;
            mem.lock.lock();
            try {
                limit = cfg.docsPerTopic() + Math.min(mem.outline.frequency(topic), 5);
            } finally {
                mem.lock.unlock();
            }
            for (Source src : sources) {
                tasks.add(() -> {
                    permits.acquire();
                    try {
                        return new Fetched(topic, src.search(topic, limit));
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        return new Fetched(topic, List.of());     // a dead crawler must never sink the answer
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        Map<String, List<Doc>> byTopic = new HashMap<>();
        List<Future<Fetched>> futs;
        try {
            futs = crawlers.invokeAll(tasks, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futs = List.of();
        }
        for (Future<Fetched> f : futs) {
            if (!f.isDone() || f.isCancelled()) continue;
            try {
                Fetched r = f.get();
                byTopic.computeIfAbsent(r.topic, k -> new ArrayList<>()).addAll(r.docs);
            } catch (Exception ignored) {
                // cancelled or failed crawler: skip
            }
        }
        List<Step> steps = new ArrayList<>();
        mem.lock.lock();
        try {
            for (String t : topics) {
                mem.outline.recordTopic(t);
                Stats s = Ingest.ingest(byTopic.getOrDefault(t, List.of()), gate, mem.store);
                stats.add(s);
                if (!s.locators.isEmpty() || keepEmpty) steps.add(new Step(t, new ArrayList<>(s.locators)));
            }
        } finally {
            mem.lock.unlock();
        }
        return steps;
    }

    /** Layer 1: base + facets, then query variants split evenly across for / against / neutral. */
    static List<String> seedTopics(Claim claim) {
        String base = (claim.subject() + " " + claim.obj()).strip();
        List<String> seeds = new ArrayList<>(List.of(base, claim.subject(), claim.obj()));
        List<List<String>> lanes = new ArrayList<>();
        for (List<String> words : STANCE_WORDS.values()) {
            List<String> l = new ArrayList<>();
            for (String w : words) l.add(base + " " + w);
            lanes.add(l);
        }
        int max = 0;
        for (List<String> l : lanes) max = Math.max(max, l.size());
        for (int i = 0; i < max; i++) for (List<String> l : lanes) if (i < l.size()) seeds.add(l.get(i));   // equal parts per direction
        return seeds;
    }

    /** Layers 2-3: follow entities the evidence surfaced, weighted by how much evidence mentions them. */
    private List<String> entityTopics(Claim claim, Tally tally, Set<String> searched, int size) {
        Set<String> seen = union(claim.s(), claim.o());
        for (String q : searched) seen.addAll(Nlp.content(q));
        Map<String, Double> heat = new LinkedHashMap<>();
        for (Evidence e : tally.evidence()) {
            for (String w : new java.util.TreeSet<>(union(Nlp.content(e.triple().s()), Nlp.content(e.triple().o())))) {   // sorted: deterministic ties
                if (!seen.contains(w)) heat.merge(w, e.weight(), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> l = new ArrayList<>(heat.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : l.subList(0, Math.min(size, l.size()))) out.add(claim.subject() + " " + e.getKey());
        return out;
    }

    /** 1-4 consolidators, more when the picture is murkier; each targets a thinnest side. */
    private List<String> consolidationTopics(Claim claim, Tally tally) {
        double share = tally.leaderShare();
        int n = share >= 0.5 ? 1 : share >= 0.4 ? 2 : share >= 0.35 ? 3 : 4;
        List<String> order = new ArrayList<>(STANCES);
        order.sort((a, b) -> Double.compare(tally.shares().get(a), tally.shares().get(b)));   // thinnest first
        String base = (claim.subject() + " " + claim.obj()).strip();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> w = STANCE_WORDS.get(order.get(i % 3));
            out.add(base + " " + w.get(w.size() - 1 - i / 3));
        }
        return out;
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    /** Best retrieval point of each speaking (1 / prime) crawler in this claim's layer 1. */
    static List<Voice> voices(Tally tally, List<Step> layer1, int offset) {
        Map<String, List<Evidence>> bySrc = new HashMap<>();
        for (Evidence e : tally.evidence()) bySrc.computeIfAbsent(e.triple().src(), k -> new ArrayList<>()).add(e);
        List<Voice> out = new ArrayList<>();
        Set<String> spoken = new HashSet<>();
        for (int i = PRIME_VOICES.size() - 1; i >= 0; i--) {
            int pos = PRIME_VOICES.get(i), n = pos - offset;
            if (n < 1 || n > layer1.size()) continue;
            Evidence best = null;
            for (String lk : layer1.get(n - 1).links()) {
                for (Evidence e : bySrc.getOrDefault(lk, List.of())) {
                    TripleStore.Triple t = e.triple();
                    if (spoken.contains(t.s() + "\u0000" + t.p() + "\u0000" + t.o())) continue;
                    if (best == null || e.weight() > best.weight()) best = e;
                }
            }
            if (best == null) continue;
            TripleStore.Triple t = best.triple();
            spoken.add(t.s() + "\u0000" + t.p() + "\u0000" + t.o());
            String pred = t.p().startsWith("not ") ? "does not " + t.p().substring(4) : (t.p().equals("is") || t.p().equals("has") ? t.p() : t.p() + "s");
            String day = MONTH.format(Instant.ofEpochSecond((long) Math.floor(t.t())));
            out.add(new Voice(pos, best.stance(), t.s() + " " + pred + " " + t.o() + " (" + t.host() + ", " + day + ")", best.weight(),
                    t.s(), t.p(), t.o(), t.host(), day, t.trust()));
        }
        return out;
    }

    /** The entire answer: prime-numbered crawlers' points, highest number first. */
    public static String speak(List<Voice> voices) {
        if (voices.isEmpty()) return "No prime-numbered crawler retrieved evidence.";
        List<Voice> v = new ArrayList<>(voices);
        v.sort((a, b) -> Integer.compare(b.pos(), a.pos()));
        StringBuilder sb = new StringBuilder();
        for (Voice x : v) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x.pos()).append(". [").append(x.stance()).append("] ").append(x.text());
        }
        return sb.toString();
    }
}
