package io.retrieva.core;

import io.retrieva.core.Nlp.Tri;
import io.retrieva.core.Source.Doc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The restricted ingestion line: allowlist gate -> sanitize -> injection filter -> triples only. */
public final class Ingest {
    private Ingest() {}

    public static final int MAX_DOC_BYTES = 256 * 1024;
    public static final int MAX_TRIPLES_PER_DOC = 200;

    public static final class Stats {
        public int docs, rejectedDocs, droppedInjection, triples, newTriples;
        public final List<String> locators = new ArrayList<>();

        public void add(Stats o) {
            docs += o.docs;
            rejectedDocs += o.rejectedDocs;
            droppedInjection += o.droppedInjection;
            triples += o.triples;
            newTriples += o.newTriples;
        }
    }

    /** Only hosts on the allowlist are ingested; the value is the prior trust in that host (0..1). */
    public static final class Gate {
        private final Map<String, Double> allow = new TreeMap<>();
        private final double dflt;

        public Gate(Map<String, Double> allow, double defaultTrust) {
            allow.forEach((h, t) -> this.allow.put(h.toLowerCase(Locale.ROOT), t));
            this.dflt = defaultTrust;
        }

        public Gate(Map<String, Double> allow) {
            this(allow, 0.0);
        }

        public double trust(String locator) {
            int i = locator.indexOf('/');
            String host = (i < 0 ? locator : locator.substring(0, i)).toLowerCase(Locale.ROOT);
            String[] parts = host.split("\\.");
            for (int k = 0; k < parts.length; k++) {   // a.b.c matches entries a.b.c, b.c, c
                Double t = allow.get(String.join(".", java.util.Arrays.copyOfRange(parts, k, parts.length)));
                if (t != null) return t;
            }
            return dflt;
        }

        public boolean allows(String host) {
            return trust(host) > 0;
        }
    }

    public static Stats ingest(List<Doc> docs, Gate gate, TripleStore store) {
        Stats st = new Stats();
        for (Doc doc : docs) {
            double trust = gate.trust(doc.locator());
            if (trust <= 0 || doc.text().getBytes(StandardCharsets.UTF_8).length > MAX_DOC_BYTES) {
                st.rejectedDocs++;
                continue;
            }
            Text.Sentences ss = Text.sentences(doc.text());
            st.droppedInjection += ss.dropped();
            st.docs++;
            st.locators.add(doc.locator());
            List<Tri> found = new ArrayList<>();
            for (String sent : ss.sentences()) found.addAll(Parsers.extractAll(sent));   // every parse shape, voted
            found.addAll(Parsers.qaTriples(Text.clean(doc.text())));                     // "Does X improve Y? No."
            for (Tri t : found.subList(0, Math.min(found.size(), MAX_TRIPLES_PER_DOC))) {
                st.triples++;
                if (store.add(t.s(), t.p(), t.o(), doc.locator(), doc.added(), trust)) st.newTriples++;
            }
        }
        return st;
    }
}
