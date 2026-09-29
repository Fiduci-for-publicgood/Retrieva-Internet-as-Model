package io.retrieva.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiTest {
    private static void ok(List<String> failures) {
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test void routingAndAuth() throws Exception { ok(ApiChecks.routingAndAuth()); }
    @Test void inputValidation() throws Exception { ok(ApiChecks.inputValidation()); }
    @Test void asking() throws Exception { ok(ApiChecks.asking()); }
    @Test void admissionControl() throws Exception { ok(ApiChecks.admissionControl()); }
    @Test void configuration() { ok(ApiChecks.configuration()); }
}
