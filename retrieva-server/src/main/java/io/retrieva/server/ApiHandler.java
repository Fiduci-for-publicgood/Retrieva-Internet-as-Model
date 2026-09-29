package io.retrieva.server;

import io.retrieva.core.Agent;
import io.retrieva.core.Agent.Answer;
import io.retrieva.core.Agent.LongAnswer;
import io.retrieva.core.Agent.Voice;
import io.retrieva.core.Json;
import io.retrieva.core.Memory;
import io.retrieva.core.Outline.Step;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The whole HTTP API, independent of any servlet container so it can be unit-tested directly.
 *
 * <pre>
 * GET  /api/health        no auth   liveness + memory sizes
 * GET  /api/metrics       auth      Prometheus text
 * POST /api/ask           auth      {"text": "..."}  one claim -> quick (3 s), several claims -> long (7-15 s)
 * POST /api/ask/quick     auth      force quick mode
 * POST /api/ask/long      auth      force long-form mode
 * </pre>
 * Add {@code "route": true} to include the crawl route in the response.
 */
public final class ApiHandler {
    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());

    public record Request(String method, String path, String authorization, byte[] body) {}

    public record Response(int status, Map<String, String> headers, String body) {}

    private final Agent agent;
    private final Memory memory;
    private final AppConfig cfg;
    private final Metrics metrics;
    private final Semaphore quickSlots, longSlots;
    private final byte[] tokenHash;

    public ApiHandler(Agent agent, Memory memory, AppConfig cfg, Metrics metrics) {
        this.agent = agent;
        this.memory = memory;
        this.cfg = cfg;
        this.metrics = metrics;
        this.quickSlots = new Semaphore(cfg.quickConcurrency());
        this.longSlots = new Semaphore(cfg.longConcurrency());
        this.tokenHash = cfg.apiToken().isEmpty() ? null : sha256(cfg.apiToken());
    }

    public Response handle(Request req) {
        try {
            String path = req.path().endsWith("/") && req.path().length() > 1 ? req.path().substring(0, req.path().length() - 1) : req.path();
            switch (path) {
                case "/api/health":
                    return method(req, "GET") ? health() : notAllowed("GET");
                case "/api/metrics":
                    if (!method(req, "GET")) return notAllowed("GET");
                    return authorized(req) ? text(200, metricsText()) : unauthorized();
                case "/api/ask":
                case "/api/ask/quick":
                case "/api/ask/long":
                    if (!method(req, "POST")) return notAllowed("POST");
                    if (!authorized(req)) return unauthorized();
                    return ask(req, path.equals("/api/ask") ? "auto" : path.substring("/api/ask/".length()));
                default:
                    return error(404, "not_found", "no such endpoint");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "unhandled error", e);
            metrics.inc("retrieva_errors_total");
            return error(500, "internal", "internal error");
        }
    }

    private static boolean method(Request r, String m) {
        return r.method().equalsIgnoreCase(m);
    }

    private boolean authorized(Request req) {
        if (tokenHash == null) return true;       // only reachable with RETRIEVA_ALLOW_ANONYMOUS=true
        String h = req.authorization();
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(tokenHash, sha256(h.substring(7).strip()));   // constant-time over fixed-length digests
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // -- endpoints -------------------------------------------------------------------------------------
    private Response health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        memory.lock.lock();
        try {
            m.put("triples", memory.store.size());
            m.put("triple_bytes", memory.store.sizeBytes());
            m.put("triple_cap_bytes", memory.store.capBytes());
            m.put("routes", memory.outline.routeCount());
            m.put("unsaved_changes", memory.dirty());
        } finally {
            memory.lock.unlock();
        }
        return json(200, m);
    }

    private String metricsText() {
        memory.lock.lock();
        try {
            return metrics.render(memory.store.size(), memory.store.sizeBytes(), memory.outline.routeCount(), memory.dirty());
        } finally {
            memory.lock.unlock();
        }
    }

    private Response ask(Request req, String mode) {
        boolean quickOnly = mode.equals("quick");
        int limit = quickOnly ? cfg.maxQuickBody() : cfg.maxLongBody();
        if (req.body().length > limit) return error(413, "too_large", "body exceeds " + limit + " bytes");
        String text;
        boolean withRoute;
        try {
            Map<String, Object> body = Json.obj(Json.parse(new String(req.body(), StandardCharsets.UTF_8)));
            text = Json.str(body.get("text"));
            withRoute = Boolean.TRUE.equals(body.get("route"));
        } catch (Json.JsonException | ClassCastException e) {
            return error(400, "bad_request", "body must be JSON like {\"text\": \"...\"}");
        }
        if (text.isBlank()) return error(400, "bad_request", "text is empty");

        if (mode.equals("auto")) {
            try {
                mode = Agent.parseClaims(text).size() > 1 ? "long" : "quick";
            } catch (Agent.ClaimException e) {
                return error(422, "unparseable_claim", e.getMessage());
            }
        }
        Semaphore slots = mode.equals("long") ? longSlots : quickSlots;
        if (!slots.tryAcquire()) {
            metrics.inc("retrieva_rejected_total{reason=\"busy\"}");
            Response r = error(429, "busy", "too many concurrent " + mode + " requests");
            r.headers().put("Retry-After", "1");
            return r;
        }
        long t0 = System.nanoTime();
        try {
            Map<String, Object> out = mode.equals("long") ? longAnswer(agent.askLong(text, memory), withRoute) : quickAnswer(agent.ask(text, memory), withRoute);
            out.put("mode", mode);
            metrics.inc("retrieva_requests_total{mode=\"" + mode + "\"}");
            metrics.add("retrieva_request_millis_total{mode=\"" + mode + "\"}", (System.nanoTime() - t0) / 1_000_000);
            return json(200, out);
        } catch (Agent.ClaimException e) {
            return error(422, "unparseable_claim", e.getMessage());
        } finally {
            slots.release();
        }
    }

    // -- JSON shapes -----------------------------------------------------------------------------------
    private static List<Object> voices(List<Voice> vs) {
        List<Object> out = new ArrayList<>();
        List<Voice> sorted = new ArrayList<>(vs);
        sorted.sort((a, b) -> Integer.compare(b.pos(), a.pos()));
        for (Voice v : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("crawler", v.pos());
            m.put("stance", v.stance());
            m.put("text", v.text());
            m.put("weight", v.weight());
            out.add(m);
        }
        return out;
    }

    private static List<Object> route(List<Step> steps) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("crawler", i + 1);
            m.put("topic", steps.get(i).topic());
            m.put("sources", steps.get(i).links());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> quickAnswer(Answer a, boolean withRoute) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claim", a.claim());
        m.put("verdict", a.verdict());
        m.put("resolved", a.resolved());
        m.put("shares", a.shares());
        m.put("answer", a.prose());
        m.put("voices", voices(a.voices()));
        m.put("elapsed_ms", a.elapsedMs());
        m.put("from_memory", a.fromMemory());
        m.put("rounds", a.rounds());
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("docs", a.stats().docs);
        st.put("rejected_docs", a.stats().rejectedDocs);
        st.put("injection_dropped", a.stats().droppedInjection);
        m.put("ingest", st);
        if (!a.resolved()) metrics.inc("retrieva_unresolved_total");
        if (withRoute) m.put("route", route(a.route()));
        return m;
    }

    private Map<String, Object> longAnswer(LongAnswer a, boolean withRoute) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer", a.prose());
        m.put("voices", voices(a.voices()));
        m.put("elapsed_ms", a.elapsedMs());
        m.put("budget_s", a.budget());
        List<Object> areas = new ArrayList<>();
        for (List<Answer> area : a.areas()) {
            List<Object> claims = new ArrayList<>();
            for (Answer x : area) claims.add(quickAnswer(x, withRoute));
            areas.add(claims);
        }
        m.put("areas", areas);
        return m;
    }

    // -- response helpers ------------------------------------------------------------------------------
    private static Map<String, String> baseHeaders(String contentType) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", contentType);
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        return h;
    }

    private static Response json(int status, Object body) {
        return new Response(status, baseHeaders("application/json; charset=utf-8"), Json.write(body));
    }

    private static Response text(int status, String body) {
        return new Response(status, baseHeaders("text/plain; version=0.0.4; charset=utf-8"), body);
    }

    private static Response error(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return json(status, m);
    }

    private static Response unauthorized() {
        Response r = error(401, "unauthorized", "missing or invalid bearer token");
        r.headers().put("WWW-Authenticate", "Bearer");
        return r;
    }

    private static Response notAllowed(String allow) {
        Response r = error(405, "method_not_allowed", "use " + allow);
        r.headers().put("Allow", allow);
        return r;
    }
}
