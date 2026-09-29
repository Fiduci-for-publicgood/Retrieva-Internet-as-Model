package io.retrieva.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** JUnit entry points for {@link Checks}; each check returns its failure messages. */
class CoreTest {
    private static void ok(List<String> failures) {
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test void parityText() throws Exception { ok(Checks.parityText()); }
    @Test void parityParsers() throws Exception { ok(Checks.parityParsers()); }
    @Test void parityEngine() throws Exception { ok(Checks.parityEngine()); }
    @Test void memory() { ok(Checks.memory()); }
    @Test void ingestion() { ok(Checks.ingestion()); }
    @Test void json() { ok(Checks.json()); }
    @Test void sources() throws Exception { ok(Checks.sources()); }
    @Test void quickBudget() throws Exception { ok(Checks.quickBudget()); }
    @Test void longForm() throws Exception { ok(Checks.longForm()); }
    @Test void savedRouteReplay() throws Exception { ok(Checks.savedRouteReplay()); }
    @Test void deadlineCut() throws Exception { ok(Checks.deadlineCut()); }
}
