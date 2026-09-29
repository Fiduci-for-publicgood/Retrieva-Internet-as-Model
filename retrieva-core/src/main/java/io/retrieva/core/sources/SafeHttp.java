package io.retrieva.core.sources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * The only way the crawler touches the network. Policy, in order:
 * https only, no credentials in the URL, default port only, host on an exact allowlist, every resolved address public
 * (SSRF guard), redirects followed manually (max 3) with the same checks on each hop, response size capped, text-like
 * content types only.
 *
 * <p>Residual risk: {@link HttpClient} resolves the name again when it connects, so a hostile DNS server could
 * answer differently the second time (DNS rebinding). The allowlist is exact hostnames operated by parties you
 * chose to trust; if you allow hosts you do not control, front this with an egress proxy that pins addresses.
 */
public final class SafeHttp {
    public static final class BlockedException extends IOException {
        private static final long serialVersionUID = 1L;

        public BlockedException(String m) {
            super(m);
        }
    }

    private static final Set<String> TEXT_TYPES = Set.of("application/json", "application/xml", "application/atom+xml", "text/xml",
            "text/plain", "text/html", "application/ld+json");

    private final Set<String> allowedHosts;
    private final int maxBytes;
    private final String userAgent;
    private final HttpClient client;

    public SafeHttp(Set<String> allowedHosts, int maxBytes, String userAgent, Duration connectTimeout) {
        this.allowedHosts = Set.copyOf(allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList());
        this.maxBytes = maxBytes;
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build();
    }

    /** True if the address is routable on the public internet (not loopback, private, link-local, CGNAT, multicast, ULA...). */
    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;      // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 0 || b0 >= 240) return false;                     // "this network", reserved, broadcast
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false;   // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;      // benchmarking
            return true;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                    // fc00::/7 unique local
            boolean mapped = true;                                      // ::ffff:a.b.c.d -> judge the embedded IPv4
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Validate a URL against the static policy (no DNS). */
    public URI check(URI uri) throws BlockedException {
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) throw new BlockedException("https only");
        if (uri.getUserInfo() != null) throw new BlockedException("credentials in URL");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new BlockedException("non-default port");
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) throw new BlockedException("host not allowlisted: " + host);
        return uri;
    }

    private void checkAddresses(String host) throws IOException {
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (!isPublic(a)) throw new BlockedException("host resolves to non-public address");
        }
    }

    /** GET with the full policy; returns the body as UTF-8 text. */
    public String get(URI uri, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI cur = uri;
        for (int hop = 0; hop <= 3; hop++) {
            check(cur);
            checkAddresses(cur.getHost());
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new IOException("timeout");
            HttpRequest req = HttpRequest.newBuilder(cur).timeout(Duration.ofNanos(left)).header("User-Agent", userAgent)
                    .header("Accept", "application/json, application/atom+xml, application/xml, text/plain;q=0.5").GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                resp.body().close();
                String loc = resp.headers().firstValue("Location").orElseThrow(() -> new IOException("redirect without Location"));
                cur = cur.resolve(loc);
                continue;
            }
            try (InputStream in = resp.body()) {
                if (code != 200) throw new IOException("HTTP " + code);
                String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                int semi = ct.indexOf(';');
                if (semi >= 0) ct = ct.substring(0, semi).strip();
                if (!TEXT_TYPES.contains(ct)) throw new BlockedException("content type not allowed: " + ct);
                return new String(readCapped(in, maxBytes), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IOException("too many redirects");
    }

    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) throw new BlockedException("response exceeds " + max + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
