package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxProviderContractTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void providerContractExposesAllIdempotentOperations() throws NoSuchMethodException {
        assertEquals(SandboxProvisionReceipt.class,
                SandboxRuntimePort.class.getMethod("ensureProvisioned", SandboxProvisionCommand.class)
                        .getReturnType());
        assertEquals(SandboxObservation.class,
                SandboxRuntimePort.class.getMethod("inspect", SandboxProviderRef.class).getReturnType());
        assertEquals(SandboxRenewReceipt.class,
                SandboxRuntimePort.class.getMethod("ensureExpiry", SandboxRenewCommand.class).getReturnType());
        assertEquals(SandboxTerminationReceipt.class,
                SandboxRuntimePort.class.getMethod("ensureTerminated", SandboxTerminationCommand.class)
                        .getReturnType());
    }

    @Test
    void valueObjectsRejectMissingIdentityInvalidGenerationAndInvalidTtl() {
        assertThrows(NullPointerException.class, () -> new SandboxProviderRef(null, "resource", "uid"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxProviderRef("fake", " ", "uid"));
        assertThrows(NullPointerException.class,
                () -> new SandboxProvisionReceipt(null, SandboxProviderPhase.READY, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxProvisionReceipt(new SandboxProviderRef("fake", "resource", "uid"),
                        SandboxProviderPhase.READY, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxProvisionCommand(TASK_ID, ATTEMPT_ID, SandboxProfile.ISOLATED,
                        Duration.ofSeconds(-1), "python", NOW, "sandbox:" + ATTEMPT_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxProvisionCommand(TASK_ID, ATTEMPT_ID, SandboxProfile.ISOLATED,
                        Duration.ofMinutes(5), " ", NOW, "sandbox:" + ATTEMPT_ID));
        assertThrows(NullPointerException.class,
                () -> new SandboxRenewCommand(null, NOW.plusSeconds(60)));
    }

    @Test
    void observationCarriesProvisioningPhaseAndWorkloadUid() {
        SandboxProviderRef providerRef = new SandboxProviderRef("kubernetes", "agentteams/task-sandbox", "cr-uid");

        SandboxObservation observation = new SandboxObservation(providerRef, SandboxProviderPhase.PROVISIONING,
                null, NOW.plusSeconds(300), 3, "job-uid", null);

        assertEquals(SandboxProviderPhase.PROVISIONING, observation.phase());
        assertEquals("job-uid", observation.workloadUid());
        assertEquals(3, observation.observedGeneration());
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxObservation(providerRef, SandboxProviderPhase.PROVISIONING,
                        " ", NOW.plusSeconds(300), 3, "job-uid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxObservation(providerRef, SandboxProviderPhase.PROVISIONING,
                        null, NOW.plusSeconds(300), -1, "job-uid", null));
    }

    @Test
    void provisionCommandCarriesTheResolvedSandboxPolicy() {
        SandboxRequest request = SandboxRequest.of(TASK_ID, ATTEMPT_ID, SandboxProfile.ISOLATED,
                Duration.ofMinutes(5), "python", NOW);

        SandboxProvisionCommand command = SandboxProvisionCommand.from(request);

        assertEquals(request.policy(), command.policy());
        assertEquals(request.profile(), command.policy().profile());
        assertEquals(request.ttl(), command.policy().ttl());
    }

    @Test
    void failureAndExceptionRedactSecretsAndBoundMessages() {
        String sensitive = "token=top-secret; " + "x".repeat(700);

        SandboxFailure failure = new SandboxFailure(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID, sensitive);
        SandboxProviderException exception = new SandboxProviderException(
                SandboxFailureCategory.PROVIDER_RESPONSE_INVALID, sensitive);

        assertTrue(failure.message().length() <= 512);
        assertTrue(exception.getMessage().length() <= 512);
        assertFalse(failure.message().contains("top-secret"));
        assertFalse(exception.getMessage().contains("top-secret"));
    }
}
