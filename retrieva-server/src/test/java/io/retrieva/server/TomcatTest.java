package io.retrieva.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.retrieva.core.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boots a real embedded Tomcat with the real listener, calls it over HTTP, restarts it and checks memory survived. */
class TomcatTest {
    private static final String TOKEN = "integration-token-0123456789";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static Tomcat start(Path base, Path memory) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", base.toString());
        ctx.addParameter("RETRIEVA_API_TOKEN", TOKEN);
        ctx.addParameter("RETRIEVA_SOURCES", "none");
        ctx.addParameter("RETRIEVA_CORPUS_FILE", Path.of(System.getProperty("basedir", "."), "src", "test", "resources", "corpus.json").toString());
        ctx.addParameter("RETRIEVA_MEMORY_DIR", memory.toString());
        ctx.addParameter("RETRIEVA_PERSIST_SECONDS", "1");
        ctx.addApplicationListener(AppListener.class.getName());
        tomcat.start();
        return tomcat;
    }

    private HttpResponse<String> send(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheApiAndMemorySurvivesARestart(@TempDir Path tmp) throws Exception {
        Path memory = tmp.resolve("memory");
        String ask = "{\"text\":\"Does caffeine enhance alertness?\"}";

        Tomcat first = start(tmp.resolve("tc1"), memory);
        try {
            int port = first.getConnector().getLocalPort();
            assertEquals(200, send(port, "GET", "/api/health", null, null).statusCode());
            assertEquals(401, send(port, "POST", "/api/ask", null, ask).statusCode());
            HttpResponse<String> r = send(port, "POST", "/api/ask", TOKEN, ask);
            assertEquals(200, r.statusCode(), r.body());
            Map<String, Object> m = Json.obj(Json.parse(r.body()));
            assertEquals("for", m.get("verdict"));
            assertEquals(false, m.get("from_memory"));
            assertEquals("application/json; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        } finally {
            first.stop();      // contextDestroyed persists memory
            first.destroy();
        }
        assertTrue(Files.exists(memory.resolve("CURRENT")), "memory was written at shutdown");

        Tomcat second = start(tmp.resolve("tc2"), memory);
        try {
            int port = second.getConnector().getLocalPort();
            HttpResponse<String> r = send(port, "POST", "/api/ask", TOKEN, ask);
            Map<String, Object> m = Json.obj(Json.parse(r.body()));
            assertEquals("for", m.get("verdict"));
            assertEquals(true, m.get("from_memory"), "route replayed from persisted Arrow memory, no crawl");
            assertTrue(Json.obj(Json.parse(send(port, "GET", "/api/health", null, null).body())).get("triples") instanceof Number n && n.intValue() > 0);
        } finally {
            second.stop();
            second.destroy();
        }
    }

    @Test
    void refusesToStartWithoutAToken(@TempDir Path tmp) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(tmp.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", tmp.toString());
        ctx.addParameter("RETRIEVA_SOURCES", "none");
        ctx.addParameter("RETRIEVA_CORPUS_FILE", Path.of(System.getProperty("basedir", "."), "src", "test", "resources", "corpus.json").toString());
        ctx.addParameter("RETRIEVA_MEMORY_DIR", tmp.resolve("m").toString());
        ctx.addApplicationListener(AppListener.class.getName());
        try {
            tomcat.start();      // the host reports the failed child by throwing
        } catch (org.apache.catalina.LifecycleException expected) {
            // ok: a deployment with unsafe configuration must not come up
        }
        try {
            assertTrue(!ctx.getState().isAvailable(), "context must not be available when configuration is unsafe");
        } finally {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (org.apache.catalina.LifecycleException ignored) {
                // already failed
            }
        }
    }
}
