package io.retrieva.core;

import java.util.List;

/** A place documents come from. Everything returned is untrusted until it passes the ingestion gate. */
public interface Source {
    String name();

    /** Search for documents about {@code query}. Must honour thread interruption (the swarm cancels on deadline). */
    List<Doc> search(String query, int limit) throws Exception;

    /** locator = host/path with no scheme or query; text = raw, possibly hostile; added = epoch seconds. */
    record Doc(String locator, String text, double added) {}
}
