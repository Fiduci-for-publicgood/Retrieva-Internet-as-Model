package io.retrieva.server;

import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Tiny Prometheus-text counter set; no dependencies. */
public final class Metrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void inc(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void add(String name, long v) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(v);
    }

    public String render(long triples, long tripleBytes, long routes, boolean dirty) {
        StringBuilder sb = new StringBuilder();
        for (var e : new TreeMap<>(counters).entrySet()) {
            String n = e.getKey();
            String base = n.contains("{") ? n.substring(0, n.indexOf('{')) : n;
            sb.append("# TYPE ").append(base).append(" counter\n").append(n).append(' ').append(e.getValue().sum()).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "# TYPE retrieva_triples gauge%nretrieva_triples %d%n# TYPE retrieva_triple_bytes gauge%nretrieva_triple_bytes %d%n"
                + "# TYPE retrieva_routes gauge%nretrieva_routes %d%n# TYPE retrieva_memory_dirty gauge%nretrieva_memory_dirty %d%n",
                triples, tripleBytes, routes, dirty ? 1 : 0));
        return sb.toString();
    }
}
