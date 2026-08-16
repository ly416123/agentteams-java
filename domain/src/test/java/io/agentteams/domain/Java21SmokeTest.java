package io.agentteams.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Java21SmokeTest {

    @Test
    void domainCompilesAndExecutesWithJava21LanguageFeatures() {
        assertTrue(Runtime.version().feature() >= 21, "the domain tests require Java 21 or newer");

        Object marker = new DomainMarker("domain");

        if (marker instanceof DomainMarker(String value)) {
            assertEquals("domain", value);
        }
    }

    private record DomainMarker(String value) {
    }
}
