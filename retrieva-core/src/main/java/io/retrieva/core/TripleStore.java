package io.retrieva.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * In-memory working set of triples: interned strings + fixed-width rows, hard-capped in logical bytes
 * (12 header + UTF-8 string bytes + 4 per string + 29 per row), scored eviction, subject-stem index.
 * Only bare subject/predicate/object text lives here. Not thread-safe: callers hold {@link Memory#lock}.
 */
public final class TripleStore {
    public static final int MB = 1024 * 1024;
    public static final int HDR = 12;
    public static final int REC = 29;
    public static final double HALF_LIFE_DAYS = 365.0;

    private static final Pattern FIELD = Pattern.compile("[a-z0-9' -]{1,80}");
    private static final Pattern LOCATOR = Pattern.compile("[a-z0-9.-]{1,120}(?:/[A-Za-z0-9._~%()-]{0,120}){0,4}");

    public record Triple(String s, String p, String o, String src, double t, double trust, int hits) {
        public String host() {
            int i = src.indexOf('/');
            return i < 0 ? src : src.substring(0, i);
        }
    }

    /** Flat column view used by persistence layers (Arrow). */
    public record Snapshot(List<String> strings, int[] s, int[] p, int[] o, int[] src, double[] t, int[] trust, int[] hits) {}

    private static final class Row {
        final int s, p, o, src;
        double t;
        int q, hits;

        Row(int s, int p, int o, int src, double t, int q, int hits) {
            this.s = s;
            this.p = p;
            this.o = o;
            this.src = src;
            this.t = t;
            this.q = q;
            this.hits = hits;
        }
    }

    private record Key(int s, int p, int o, int src) {}

    private final int cap;
    private final DoubleSupplier clock;
    private final Map<String, Set<String>> stemCache = new HashMap<>();
    private List<String> strings;
    private Map<String, Integer> sid;
    private List<Row> rows;
    private Map<Key, Integer> keys;
    private Map<String, List<Integer>> subjectIndex;
    private int bytes;
    private boolean dirty;

    public TripleStore(int capBytes, DoubleSupplier clockSeconds) {
        this.cap = capBytes;
        this.clock = clockSeconds;
        clear();
    }

    private void clear() {
        strings = new ArrayList<>();
        sid = new HashMap<>();
        rows = new ArrayList<>();
        keys = new HashMap<>();
        subjectIndex = new HashMap<>();
        bytes = HDR;
    }

    public int size() {
        return rows.size();
    }

    public int sizeBytes() {
        return bytes;
    }

    public int capBytes() {
        return cap;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public static boolean valid(String s, String p, String o, String src) {
        if (!FIELD.matcher(s).matches() || !FIELD.matcher(p).matches() || !FIELD.matcher(o).matches() || !LOCATOR.matcher(src).matches()) return false;
        return !Text.isInjection(s + " " + p + " " + o);
    }

    public static double recency(double t, double now) {
        double ageDays = Math.max(0.0, (now - t) / 86400.0);
        return Math.max(0.05, Math.pow(0.5, ageDays / HALF_LIFE_DAYS));
    }

    private int intern(String x) {
        Integer i = sid.get(x);
        if (i == null) {
            i = strings.size();
            sid.put(x, i);
            strings.add(x);
            bytes += x.getBytes(StandardCharsets.UTF_8).length + 4;
        }
        return i;
    }

    private Set<String> stemOf(int id) {
        return stemCache.computeIfAbsent(strings.get(id), Nlp::content);
    }

    private void insert(Key k, double t, int q, int hits) {
        int i = rows.size();
        rows.add(new Row(k.s, k.p, k.o, k.src, t, q, hits));
        keys.put(k, i);
        for (String w : stemOf(k.s)) subjectIndex.computeIfAbsent(w, x -> new ArrayList<>()).add(i);
        bytes += REC;
    }

    /** Insert a validated triple. Returns true if new. Enforces the byte cap. */
    public boolean add(String s, String p, String o, String src, double t, double trust) {
        if (!valid(s, p, o, src)) return false;
        Key k = new Key(intern(s), intern(p), intern(o), intern(src));
        int q = (int) Math.max(0, Math.min(255, Math.rint(trust * 255)));
        dirty = true;
        Integer at = keys.get(k);
        if (at != null) {
            Row r = rows.get(at);
            r.t = Math.max(r.t, t);
            r.q = Math.max(r.q, q);
            return false;
        }
        insert(k, t, q, 0);
        if (bytes > cap) evict();
        return true;
    }

    /** Row ids (ascending) whose subject shares a topic stem with {@code stems}. */
    public int[] candidates(Set<String> stems) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String w : stems) {
            List<Integer> l = subjectIndex.get(w);
            if (l != null) out.addAll(l);
        }
        int[] a = new int[out.size()];
        int n = 0;
        for (int i : out) a[n++] = i;
        return a;
    }

    public Triple get(int i) {
        Row r = rows.get(i);
        return new Triple(strings.get(r.s), strings.get(r.p), strings.get(r.o), strings.get(r.src), r.t, r.q / 255.0, r.hits);
    }

    public Set<String> subjectStems(int i) {
        return stemOf(rows.get(i).s);
    }

    public Set<String> objectStems(int i) {
        return stemOf(rows.get(i).o);
    }

    public void touch(Iterable<Integer> ids) {
        for (int i : ids) rows.get(i).hits++;
    }

    /** Drop the lowest-value triples (trust x recency x usage) down to 90% of the cap. */
    private void evict() {
        double now = clock.getAsDouble();
        int n = rows.size();
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            score[i] = (r.q / 255.0) * recency(r.t, now) * (1 + r.hits);
        }
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Double.compare(score[b], score[a]));
        List<String> oldStrings = strings;
        List<Row> oldRows = rows;
        clear();
        stemCache.clear();
        dirty = true;
        for (int i : order) {
            Row r = oldRows.get(i);
            if (bytes + REC + 4 * oldStrings.get(r.s).length() > cap * 0.9) break;
            insert(new Key(intern(oldStrings.get(r.s)), intern(oldStrings.get(r.p)), intern(oldStrings.get(r.o)), intern(oldStrings.get(r.src))),
                    r.t, r.q, r.hits);
        }
    }

    /** Column snapshot with a compact string table (only strings still referenced). */
    public Snapshot snapshot() {
        Map<Integer, Integer> remap = new HashMap<>();
        List<String> table = new ArrayList<>();
        int n = rows.size();
        int[] s = new int[n], p = new int[n], o = new int[n], src = new int[n], q = new int[n], h = new int[n];
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            s[i] = remap.computeIfAbsent(r.s, k -> { table.add(strings.get(k)); return table.size() - 1; });
            p[i] = remap.computeIfAbsent(r.p, k -> { table.add(strings.get(k)); return table.size() - 1; });
            o[i] = remap.computeIfAbsent(r.o, k -> { table.add(strings.get(k)); return table.size() - 1; });
            src[i] = remap.computeIfAbsent(r.src, k -> { table.add(strings.get(k)); return table.size() - 1; });
            t[i] = r.t;
            q[i] = r.q;
            h[i] = r.hits;
        }
        return new Snapshot(table, s, p, o, src, t, q, h);
    }

    /**
     * Rebuild from a snapshot read off disk. Every row is re-validated (the disk is untrusted): rows with bad
     * indices, bad characters or injection-looking text are dropped. Runtime rationalisation, not blind load.
     */
    public static TripleStore restore(Snapshot sn, int capBytes, DoubleSupplier clock) {
        TripleStore st = new TripleStore(capBytes, clock);
        int n = sn.s().length;
        int ns = sn.strings().size();
        for (int i = 0; i < n; i++) {
            int a = sn.s()[i], b = sn.p()[i], c = sn.o()[i], d = sn.src()[i];
            if (a < 0 || b < 0 || c < 0 || d < 0 || a >= ns || b >= ns || c >= ns || d >= ns) continue;
            String s = sn.strings().get(a), p = sn.strings().get(b), o = sn.strings().get(c), src = sn.strings().get(d);
            if (!valid(s, p, o, src)) continue;
            Key k = new Key(st.intern(s), st.intern(p), st.intern(o), st.intern(src));
            if (st.keys.containsKey(k)) continue;
            st.insert(k, sn.t()[i], Math.max(0, Math.min(255, sn.trust()[i])), Math.max(0, sn.hits()[i]));
        }
        st.dirty = false;
        if (st.bytes > st.cap) st.evict();
        return st;
    }
}
