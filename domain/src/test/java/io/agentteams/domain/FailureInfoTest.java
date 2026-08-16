package io.agentteams.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.domain.task.FailureInfo;
import org.junit.jupiter.api.Test;

class FailureInfoTest {

    @Test
    void redactsCommonSecretKeysInPlainJsonAndUrlForms() {
        String raw = "Password=one password:two TOKEN=three token:four "
                + "secret=five API-Key=six api_key=seven "
                + "{\"password\":\"eight\",\"token\":\"nine\"} "
                + "https://example.test/run?token=ten&password=eleven&keep=visible";

        String redacted = FailureInfo.fromRaw("RUNTIME_ERROR", raw).redactedMessage();

        assertFalse(redacted.contains("one"));
        assertFalse(redacted.contains("two"));
        assertFalse(redacted.contains("three"));
        assertFalse(redacted.contains("four"));
        assertFalse(redacted.contains("five"));
        assertFalse(redacted.contains("six"));
        assertFalse(redacted.contains("seven"));
        assertFalse(redacted.contains("eight"));
        assertFalse(redacted.contains("nine"));
        assertFalse(redacted.contains("ten"));
        assertFalse(redacted.contains("eleven"));
        assertTrue(redacted.contains("keep=visible"));
        assertTrue(redacted.contains("[REDACTED]"));
    }
}
