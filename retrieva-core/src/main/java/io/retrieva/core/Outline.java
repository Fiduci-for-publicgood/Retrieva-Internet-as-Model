package io.retrieva.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outliner-style route index (default 2 MB): the <em>path</em> that led to each answer.
 * Triples are the condensed knowledge; routes are the map. Plain indented text, human-editable:
 *
 * <pre>
 * # retrieva outline v1
 * ## topics
 * - coffee memory :: 3
 * ## routes
 * - [1] coffee memory :: verdict=for for=0.710 against=0.120 neutral=0.170 t=1727600000 hits=2
 *   - 1. coffee memory
 *     - journal.example.org/coffee-and-memory
 * </pre>
 */
public final class Outline {
    public record Step(String topic, List<String> links) {}

    public static final class Route {
        public final String key;
        public final String verdict;
        public final double[] shares;
        public final long t;
        public int hits;
        public final List<Step> steps;

        public Route(String key, String verdict, double[] shares, long t, int hits, List<Step> steps) {
            this.key = key;
            this.verdict = verdict;
            this.shares = shares;
            this.t = t;
            this.hits = hits;
            this.steps = steps;
        }
    }

    private static final Pattern ROUTE = Pattern.compile("^- \\[\\d+\\] (.+?) :: verdict=(\\S+) for=([\\d.]+) against=([\\d.]+) neutral=([\\d.]+) t=(\\d+) hits=(\\d+)$");
    private static final Pattern STEP = Pattern.compile("^  - \\d+\\. (.+)$");
    private static final Pattern LINK = Pattern.compile("^    - (\\S+)$");
    private static final Pattern TOPIC = Pattern.compile("^- (.+?) :: (\\d+)$");

    private final int cap;
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Map<String, Integer> topicHits = new LinkedHashMap<>();
    private boolean dirty;

    public Outline(int capBytes) {
        this.cap = capBytes;
    }

    public static String routeKey(String text) {
        return String.join(" ", new java.util.TreeSet<>(Nlp.content(text)));
    }

    public Route lookup(String key) {
        return routes.get(key);
    }

    public int routeCount() {
        return routes.size();
    }

    public int frequency(String topic) {
        return topicHits.getOrDefault(topic, 0);
    }

    public void recordTopic(String topic) {
        topicHits.merge(topic, 1, Integer::sum);
        dirty = true;
    }

    public void saveRoute(Route r) {
        Route old = routes.get(r.key);
        if (old != null) r.hits = old.hits + 1;
        routes.put(r.key, r);
        dirty = true;
    }

    public void markHit(Route r) {
        r.hits++;
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public String render() {
        StringBuilder sb = new StringBuilder("# retrieva outline v1\n## topics\n");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(topicHits.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : top) sb.append("- ").append(e.getKey()).append(" :: ").append(e.getValue()).append('\n');
        sb.append("## routes\n");
        int i = 1;
        for (Route r : routes.values()) {
            sb.append(String.format(Locale.ROOT, "- [%d] %s :: verdict=%s for=%.3f against=%.3f neutral=%.3f t=%d hits=%d%n",
                    i++, r.key, r.verdict, r.shares[0], r.shares[1], r.shares[2], r.t, r.hits));
            int j = 1;
            for (Step s : r.steps) {
                sb.append("  - ").append(j++).append(". ").append(s.topic()).append('\n');
                for (String lk : s.links()) sb.append("    - ").append(lk).append('\n');
            }
        }
        return sb.toString();
    }

    /** Evict least-used, oldest routes (then rare topics) until the render fits the cap. */
    private void shrink() {
        while (render().getBytes(StandardCharsets.UTF_8).length > cap && (!routes.isEmpty() || !topicHits.isEmpty())) {
            if (!routes.isEmpty()) {
                List<Route> worst = new ArrayList<>(routes.values());
                worst.sort((a, b) -> a.hits != b.hits ? Integer.compare(a.hits, b.hits) : Long.compare(a.t, b.t));
                for (Route r : worst.subList(0, Math.max(1, routes.size() / 10))) routes.remove(r.key);
            } else {
                List<Map.Entry<String, Integer>> top = new ArrayList<>(topicHits.entrySet());
                top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Integer> e : top.subList(top.size() - Math.max(1, top.size() / 10), top.size())) topicHits.remove(e.getKey());
            }
        }
    }

    /** Shrinks to the cap, then returns the text to persist. */
    public String toText() {
        shrink();
        return render();
    }

    public static Outline parse(String text, int capBytes) {
        Outline ol = new Outline(capBytes);
        String section = "";
        Route cur = null;
        for (String ln : text.split("\n", -1)) {
            if (ln.startsWith("## ")) {
                section = ln.substring(3).strip();
                continue;
            }
            Matcher m;
            if (section.equals("topics") && (m = TOPIC.matcher(ln)).matches()) {
                ol.topicHits.put(m.group(1), Integer.parseInt(m.group(2)));
            } else if (section.equals("routes")) {
                if ((m = ROUTE.matcher(ln)).matches()) {
                    cur = new Route(m.group(1), m.group(2), new double[] {Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                            Double.parseDouble(m.group(5))}, Long.parseLong(m.group(6)), Integer.parseInt(m.group(7)), new ArrayList<>());
                    ol.routes.put(cur.key, cur);
                } else if (cur != null && (m = STEP.matcher(ln)).matches()) {
                    cur.steps.add(new Step(m.group(1), new ArrayList<>()));
                } else if (cur != null && !cur.steps.isEmpty() && (m = LINK.matcher(ln)).matches()) {
                    cur.steps.get(cur.steps.size() - 1).links().add(m.group(1));
                }
            }
        }
        return ol;
    }
}
