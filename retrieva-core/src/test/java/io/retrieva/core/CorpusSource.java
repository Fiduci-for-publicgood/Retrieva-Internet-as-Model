package io.retrieva.core;

import io.retrieva.core.Source.Doc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Fixed-corpus source for tests: TF-IDF-ish ranking identical to the Python reference. */
final class CorpusSource implements Source {
    final List<Doc> docs = new ArrayList<>();
    final Map<String, Double> trust = new HashMap<>();
    private final List<Set<String>> stems = new ArrayList<>();
    private final Map<String, Double> idf = new HashMap<>();

    CorpusSource(Path json) throws IOException {
        this(load(json, new HashMap<>()));
        trust.putAll(lastTrust);
    }

    private static Map<String, Double> lastTrust = new HashMap<>();

    private static List<Doc> load(Path json, Map<String, Double> trustOut) throws IOException {
        Map<String, Object> root = Json.obj(Json.parse(Files.readString(json, StandardCharsets.UTF_8)));
        Json.obj(root.get("trust")).forEach((k, v) -> trustOut.put(k, Json.num(v)));
        lastTrust = trustOut;
        List<Doc> docs = new ArrayList<>();
        for (Object o : Json.arr(root.get("docs"))) {
            Map<String, Object> d = Json.obj(o);
            double added = LocalDate.parse(Json.str(d.get("added"))).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            docs.add(new Doc(Json.str(d.get("locator")), Json.str(d.get("text")), added));
        }
        return docs;
    }

    CorpusSource(List<Doc> docs) {
        this.docs.addAll(docs);
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            Set<String> st = new TreeSet<>(Nlp.content(Nlp.tokens(d.text())));
            st.addAll(Nlp.content(Nlp.tokens(d.locator().replace("/", " ").replace("-", " "))));
            stems.add(st);
            for (String w : st) df.merge(w, 1, Integer::sum);
        }
        df.forEach((w, c) -> idf.put(w, Math.log(1 + (double) docs.size() / c)));
    }

    @Override
    public String name() {
        return "corpus";
    }

    @Override
    public List<Doc> search(String query, int limit) {
        Set<String> q = new TreeSet<>();
        for (String w : Nlp.content(Nlp.tokens(query))) q.add(Nlp.stem(w));
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            double sc = 0;
            for (String w : q) if (stems.get(i).contains(w)) sc += idf.getOrDefault(w, 0.0);
            scored.add(new double[] {sc, i});
        }
        scored.sort((a, b) -> a[0] != b[0] ? Double.compare(b[0], a[0]) : Double.compare(b[1], a[1]));   // (score, index) descending
        List<Doc> out = new ArrayList<>();
        for (double[] s : scored.subList(0, Math.min(limit, scored.size()))) if (s[0] > 0) out.add(docs.get((int) s[1]));
        return out;
    }
}
