package io.retrieva.server;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Thin adapter: servlet request -> {@link ApiHandler.Request}, {@link ApiHandler.Response} -> servlet response. */
public final class ApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private final transient ApiHandler handler;
    private final int maxBody;

    public ApiServlet(ApiHandler handler, int maxBody) {
        this.handler = handler;
        this.maxBody = maxBody;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Read at most maxBody+1 bytes: enough for the handler to detect and reject oversized bodies without buffering them.
        byte[] body = "GET".equalsIgnoreCase(req.getMethod()) ? new byte[0] : req.getInputStream().readNBytes(maxBody + 1);
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        ApiHandler.Response r = handler.handle(new ApiHandler.Request(req.getMethod(), path, req.getHeader("Authorization"), body));
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        resp.setStatus(r.status());
        r.headers().forEach(resp::setHeader);
        resp.setContentLength(out.length);
        resp.getOutputStream().write(out);
    }
}
