package io.retrieva.core.sources;

import io.retrieva.core.Source;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** arXiv Atom API (export.arxiv.org). arXiv asks for at most one request per 3 seconds: configure {@link Politeness} accordingly. */
public final class ArxivSource implements Source {
    public static final String HOST = "export.arxiv.org";
    private final SafeHttp http;
    private final Politeness politeness;
    private final Duration timeout;

    public ArxivSource(SafeHttp http, Politeness politeness, Duration timeout) {
        this.http = http;
        this.politeness = politeness;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "arxiv";
    }

    public URI url(String query, int limit) {
        return URI.create("https://" + HOST + "/api/query?search_query=all:" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=0&max_results=" + Math.max(1, Math.min(limit, 20)) + "&sortBy=relevance");
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

    /** XXE-hardened Atom parse: entry/id, entry/title, entry/summary, entry/published. */
    public List<Doc> parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(true);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());   // throw, never print to stderr
        NodeList entries = b.parse(new InputSource(new StringReader(xml))).getElementsByTagNameNS("*", "entry");
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = text(e, "id"), title = text(e, "title"), summary = text(e, "summary");
            int at = id.indexOf("arxiv.org/abs/");
            if (at < 0 || summary.isBlank()) continue;
            String slug = id.substring(at + "arxiv.org/abs/".length()).replaceAll("[^A-Za-z0-9._~%()-]", "_");
            double t;
            try {
                t = Instant.parse(text(e, "published")).getEpochSecond();
            } catch (java.time.format.DateTimeParseException ex) {
                t = 0;
            }
            docs.add(new Doc("arxiv.org/abs/" + slug, title + ". " + summary, t));
        }
        return docs;
    }

    private static String text(Element e, String tag) {
        NodeList l = e.getElementsByTagNameNS("*", tag);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent().replaceAll("\\s+", " ").strip();
    }
}
