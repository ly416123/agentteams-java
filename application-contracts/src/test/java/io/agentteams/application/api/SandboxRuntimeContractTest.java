package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxRuntimeContractTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    @Test
    void defaultsToNoSandboxWithoutChangingExistingTaskPath() {
        SandboxRequest request = SandboxRequest.defaults(TASK_ID, ATTEMPT_ID, NOW);

        assertEquals(SandboxProfile.NONE, request.profile());
        assertEquals(Duration.ofMinutes(30), request.ttl());
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> SandboxRequest.of(TASK_ID, ATTEMPT_ID, SandboxProfile.NONE,
                        Duration.ZERO, "default", NOW));
    }

    @Test
    void rejectsMissingAttemptIdentity() {
        assertThrows(NullPointerException.class,
                () -> SandboxRequest.of(TASK_ID, null, SandboxProfile.ISOLATED,
                        Duration.ofMinutes(5), "python", NOW));
    }
}
