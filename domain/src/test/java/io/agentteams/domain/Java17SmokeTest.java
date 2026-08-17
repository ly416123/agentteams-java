package io.agentteams.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Java17SmokeTest {

    @Test
    void domainCompilesAndExecutesWithJava17LanguageFeatures() {
        assertTrue(Runtime.version().feature() >= 17, "the domain tests require Java 17 or newer");

        Object marker = new DomainMarker("domain");

        if (marker instanceof DomainMarker domainMarker) {
            assertEquals("domain", domainMarker.value());
        }
    }

    private record DomainMarker(String value) {
    }
}
