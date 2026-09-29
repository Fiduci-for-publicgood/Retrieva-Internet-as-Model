package io.retrieva.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Deployment configuration, all from environment variables (or servlet context parameters of the same name).
 * Fails closed: no API token and no explicit opt-out, or no source, means the service refuses to start.
 */
public record AppConfig(Path memoryDir, List<String> sources, Path corpusFile, Map<String, Double> trust, String userAgent,
                        String apiToken, boolean allowAnonymous, double quickBudgetSeconds, int maxCrawlers, int quickConcurrency,
                        int longConcurrency, int persistSeconds, int maxQuickBody, int maxLongBody, boolean deliberate, int maxCycles) {

    public static final class ConfigException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ConfigException(String m) {
            super(m);
        }
    }

    public static AppConfig from(Function<String, String> env) {
        String srcList = or(env, "RETRIEVA_SOURCES", "wikipedia,arxiv");
        List<String> sources = new ArrayList<>();
        for (String s : srcList.split(",")) {
            String n = s.strip().toLowerCase(Locale.ROOT);
            if (n.isEmpty() || n.equals("none")) continue;      // "none": offline / corpus-only deployment
            if (!n.equals("wikipedia") && !n.equals("arxiv")) throw new ConfigException("unknown source '" + n + "' (wikipedia, arxiv, none)");
            sources.add(n);
        }
        String corpus = or(env, "RETRIEVA_CORPUS_FILE", "");
        if (sources.isEmpty() && corpus.isEmpty()) throw new ConfigException("no sources: set RETRIEVA_SOURCES and/or RETRIEVA_CORPUS_FILE");

        Map<String, Double> trust = new LinkedHashMap<>();
        if (sources.contains("wikipedia")) trust.put("wikipedia.org", 0.7);
        if (sources.contains("arxiv")) trust.put("arxiv.org", 0.75);
        for (String pair : or(env, "RETRIEVA_TRUST", "").split(",")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq < 1) throw new ConfigException("RETRIEVA_TRUST entries look like host=0.8, got '" + pair + "'");
            double v;
            try {
                v = Double.parseDouble(pair.substring(eq + 1).strip());
            } catch (NumberFormatException e) {
                throw new ConfigException("bad trust value in '" + pair + "'");
            }
            if (!(v > 0 && v <= 1)) throw new ConfigException("trust must be in (0,1]: '" + pair + "'");
            trust.put(pair.substring(0, eq).strip().toLowerCase(Locale.ROOT), v);
        }

        String token = or(env, "RETRIEVA_API_TOKEN", "");
        boolean anon = or(env, "RETRIEVA_ALLOW_ANONYMOUS", "false").equalsIgnoreCase("true");
        if (token.isEmpty() && !anon) throw new ConfigException("RETRIEVA_API_TOKEN is not set (set RETRIEVA_ALLOW_ANONYMOUS=true to run without auth)");
        if (!token.isEmpty() && token.length() < 16) throw new ConfigException("RETRIEVA_API_TOKEN must be at least 16 characters");

        return new AppConfig(Path.of(or(env, "RETRIEVA_MEMORY_DIR", "/var/lib/retrieva")), List.copyOf(sources),
                corpus.isEmpty() ? null : Path.of(corpus), Map.copyOf(trust), or(env, "RETRIEVA_USER_AGENT", "retrieva/1.0"), token, anon,
                num(env, "RETRIEVA_QUICK_BUDGET_S", 3.0, 0.2, 3.0), (int) num(env, "RETRIEVA_MAX_CRAWLERS", 128, 1, 128),
                (int) num(env, "RETRIEVA_QUICK_CONCURRENCY", 16, 1, 1000), (int) num(env, "RETRIEVA_LONG_CONCURRENCY", 2, 1, 100),
                (int) num(env, "RETRIEVA_PERSIST_SECONDS", 10, 1, 3600), (int) num(env, "RETRIEVA_MAX_QUICK_BODY", 8 * 1024, 256, 1 << 20),
                (int) num(env, "RETRIEVA_MAX_LONG_BODY", 64 * 1024, 256, 1 << 22),
                !or(env, "RETRIEVA_DELIBERATE", "true").equalsIgnoreCase("false"), (int) num(env, "RETRIEVA_MAX_CYCLES", 12, 1, 100));
    }

    private static String or(Function<String, String> env, String key, String dflt) {
        String v = env.apply(key);
        return v == null || v.isBlank() ? dflt : v.strip();
    }

    private static double num(Function<String, String> env, String key, double dflt, double lo, double hi) {
        String v = or(env, key, "");
        if (v.isEmpty()) return dflt;
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " is not a number: " + v);
        }
        if (d < lo || d > hi) throw new ConfigException(key + " must be within [" + lo + ", " + hi + "]");
        return d;
    }
}
