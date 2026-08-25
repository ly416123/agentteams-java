package io.agentteams.contracts.v1;

import com.google.protobuf.Timestamp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SandboxAssignmentContractReviewTest {
    @Test
    void taskAssignedCarriesAnOptionalSandboxAssignmentWithoutChangingLegacyShape() {
        Timestamp expiresAt = Timestamp.newBuilder().setSeconds(1_800_000_000L).build();
        TaskAssigned assigned = TaskAssigned.newBuilder()
                .setSandbox(SandboxAssignment.newBuilder()
                        .setSandboxId("sandbox-1")
                        .setProviderSandboxId("provider-sandbox-1")
                        .setProfile("ISOLATED")
                        .setStatus("READY")
                        .setEndpointRef("sandbox://provider/sandbox-1")
                        .setExpiresAt(expiresAt)
                        .setOwnerTaskId("task-1")
                        .setOwnerAttemptId("attempt-1"))
                .build();

        assertTrue(assigned.hasSandbox());
        assertEquals("sandbox-1", assigned.getSandbox().getSandboxId());
        assertEquals("provider-sandbox-1", assigned.getSandbox().getProviderSandboxId());
        assertEquals("ISOLATED", assigned.getSandbox().getProfile());
        assertEquals("READY", assigned.getSandbox().getStatus());
        assertEquals("sandbox://provider/sandbox-1", assigned.getSandbox().getEndpointRef());
        assertEquals(expiresAt, assigned.getSandbox().getExpiresAt());
        assertEquals("task-1", assigned.getSandbox().getOwnerTaskId());
        assertEquals("attempt-1", assigned.getSandbox().getOwnerAttemptId());
        assertFalse(TaskAssigned.getDefaultInstance().hasSandbox());
    }
}
