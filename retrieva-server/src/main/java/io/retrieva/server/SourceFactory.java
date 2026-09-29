package io.retrieva.server;

import io.retrieva.core.Ingest.Gate;
import io.retrieva.core.Source;
import io.retrieva.core.sources.ArxivSource;
import io.retrieva.core.sources.CorpusSource;
import io.retrieva.core.sources.MediaWikiSource;
import io.retrieva.core.sources.Politeness;
import io.retrieva.core.sources.SafeHttp;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the configured crawlers and the ingestion allowlist that goes with them. */
public final class SourceFactory {
    private SourceFactory() {}

    public record Built(List<Source> sources, Gate gate) {}

    public static Built build(AppConfig cfg) throws IOException {
        List<Source> sources = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        Map<String, Double> trust = new LinkedHashMap<>(cfg.trust());
        Duration http = Duration.ofMillis((long) (cfg.quickBudgetSeconds() * 1000 * 0.8));   // one request must fit inside a quick query
        if (cfg.sources().contains("wikipedia")) hosts.add("en.wikipedia.org");
        if (cfg.sources().contains("arxiv")) hosts.add(ArxivSource.HOST);
        SafeHttp safe = new SafeHttp(hosts, 512 * 1024, cfg.userAgent(), Duration.ofSeconds(2));
        if (cfg.sources().contains("wikipedia")) sources.add(new MediaWikiSource(safe, new Politeness(50, 5, 30_000), "en.wikipedia.org", http));
        if (cfg.sources().contains("arxiv")) sources.add(new ArxivSource(safe, new Politeness(3_000, 3, 60_000), http));   // arXiv: 1 request / 3 s
        if (cfg.corpusFile() != null) {
            CorpusSource c = CorpusSource.load(cfg.corpusFile());
            sources.add(c);
            c.trust.forEach(trust::putIfAbsent);
        }
        return new Built(sources, new Gate(trust));
    }
}
