package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryPolicyTest {
    @Test
    void privateMemoryRequiresSubjectAndSharedMemoryRequiresResourceScope() {
        assertDoesNotThrow(() -> new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE, "org", "tenant", null, null,
                "user", MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofDays(30)));
        assertDoesNotThrow(() -> new MemoryPolicy(MemoryPolicy.Scope.PROJECT_SHARED, "org", "tenant", "project",
                null, null, MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE,
                "org", "tenant", null, null, null, MemoryPolicy.Sensitivity.NORMAL,
                MemoryPolicy.Consent.CONFIRMED, Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPolicy(MemoryPolicy.Scope.TEAM_SHARED,
                "org", "tenant", null, null, null, MemoryPolicy.Sensitivity.NORMAL,
                MemoryPolicy.Consent.CONFIRMED, Duration.ofDays(30)));
    }

    @Test
    void onlyConfirmedMemoryCanEnterModelContext() {
        MemoryPolicy candidate = new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE, "org", "tenant", null, null,
                "user", MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CANDIDATE, Duration.ofDays(30));
        assertThrows(IllegalArgumentException.class, candidate::requireUsableInModelContext);
    }
}
