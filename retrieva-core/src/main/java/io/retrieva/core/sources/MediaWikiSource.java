package io.retrieva.core.sources;

import io.retrieva.core.Json;
import io.retrieva.core.Source;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MediaWiki search + plain-text extracts (Wikipedia and compatible wikis). One HTTP request per search. */
public final class MediaWikiSource implements Source {
    private final SafeHttp http;
    private final Politeness politeness;
    private final String host;
    private final Duration timeout;

    public MediaWikiSource(SafeHttp http, Politeness politeness, String host, Duration timeout) {
        this.http = http;
        this.politeness = politeness;
        this.host = host;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "mediawiki:" + host;
    }

    public URI url(String query, int limit) {
        String qs = "action=query&generator=search&gsrsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&gsrlimit=" + Math.max(1, Math.min(limit, 20)) + "&prop=extracts%7Cinfo&exintro=1&explaintext=1&exlimit=max"
                + "&format=json&formatversion=2";
        return URI.create("https://" + host + "/w/api.php?" + qs);
    }

    @Override
    public List<Doc> search(String query, int limit) throws Exception {
        politeness.acquire();
        String body;
        try {
            body = http.get(url(query, limit), timeout);
            politeness.success();
        } catch (java.io.IOException e) {
            politeness.failure();
            throw e;
        }
        return parse(body);
    }

    /** Parse an API response: {"query":{"pages":[{"title","extract","touched"}...]}}. */
    public List<Doc> parse(String body) {
        List<Doc> docs = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(body));
        if (!(root.get("query") instanceof Map<?, ?>)) return docs;
        Object pages = Json.obj(root.get("query")).get("pages");
        if (!(pages instanceof List<?>)) return docs;
        for (Object o : Json.arr(pages)) {
            Map<String, Object> p = Json.obj(o);
            Object title = p.get("title"), extract = p.get("extract");
            if (!(title instanceof String t) || !(extract instanceof String x) || x.isBlank()) continue;
            String slug = t.replaceAll("[^A-Za-z0-9._~%()-]", "_");
            if (slug.length() > 120) slug = slug.substring(0, 120);
            if (slug.isEmpty()) continue;
            docs.add(new Doc(host + "/wiki/" + slug, x, epoch(p.get("touched"))));
        }
        return docs;
    }

    static double epoch(Object iso) {
        try {
            return iso instanceof String s ? Instant.parse(s).getEpochSecond() : 0;
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }
}
