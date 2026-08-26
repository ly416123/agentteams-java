package io.agentteams.controlplane.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.SandboxFailureCategory;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxProvisionReceipt;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderException;
import io.agentteams.application.api.SandboxProviderRef;
import io.agentteams.application.api.SandboxRenewCommand;
import io.agentteams.application.api.SandboxTerminationCommand;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxProviderContractTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void duplicateProvisionReturnsSameProviderReference() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProvisionCommand command = commandFor(ATTEMPT_ID);

        SandboxProvisionReceipt first = runtime.ensureProvisioned(command);
        SandboxProvisionReceipt second = runtime.ensureProvisioned(command);

        assertThat(second.providerRef()).isEqualTo(first.providerRef());
        assertThat(first.phase()).isEqualTo(SandboxProviderPhase.READY);
        assertThat(first.providerRef().resourceUid()).isNotBlank();
        assertThat(runtime.provisionCalls()).isEqualTo(1);
    }

    @Test
    void inspectionCarriesGenerationEndpointAndProviderFailure() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProvisionReceipt receipt = runtime.ensureProvisioned(commandFor(ATTEMPT_ID));

        SandboxObservation observation = runtime.inspect(receipt.providerRef());

        assertThat(observation.phase()).isEqualTo(SandboxProviderPhase.READY);
        assertThat(observation.observedGeneration()).isEqualTo(1L);
        assertThat(observation.endpointRef()).startsWith("sandbox://fake/");
        assertThat(observation.failure()).isNull();
    }

    @Test
    void exposesOnlyStableProviderFailureCategories() {
        assertThat(SandboxFailureCategory.values()).containsExactly(
                SandboxFailureCategory.RUNTIME_CLASS_NOT_FOUND,
                SandboxFailureCategory.POLICY_REJECTED,
                SandboxFailureCategory.RESOURCE_QUOTA_EXCEEDED,
                SandboxFailureCategory.KUBERNETES_UNAVAILABLE,
                SandboxFailureCategory.STATUS_TIMEOUT,
                SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                SandboxFailureCategory.IDEMPOTENCY_CONFLICT,
                SandboxFailureCategory.PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void rejectsAnExpiryThatWouldShortenTheExistingProviderExpiry() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProvisionReceipt receipt = runtime.ensureProvisioned(commandFor(ATTEMPT_ID));

        assertThatThrownBy(() -> runtime.ensureExpiry(
                new SandboxRenewCommand(receipt.providerRef(), NOW.plusSeconds(60))))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void repeatedExpiryEnsureReturnsTheSameReceiptWithoutASecondProviderCall() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProvisionReceipt provisioned = runtime.ensureProvisioned(commandFor(ATTEMPT_ID));
        Instant extendedExpiry = NOW.plusSeconds(600);

        var first = runtime.ensureExpiry(new SandboxRenewCommand(provisioned.providerRef(), extendedExpiry));
        var second = runtime.ensureExpiry(new SandboxRenewCommand(provisioned.providerRef(), extendedExpiry));

        assertThat(second).isEqualTo(first);
        assertThat(runtime.renewCalls()).isEqualTo(1);
    }

    @Test
    void repeatedTerminationIsIdempotent() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProvisionReceipt provisioned = runtime.ensureProvisioned(commandFor(ATTEMPT_ID));
        SandboxTerminationCommand command = new SandboxTerminationCommand(provisioned.providerRef(),
                SandboxTerminationReason.TASK_COMPLETED);

        var first = runtime.ensureTerminated(command);
        var second = runtime.ensureTerminated(command);

        assertThat(second).isEqualTo(first);
        assertThat(first.phase()).isEqualTo(SandboxProviderPhase.DESTROYED);
        assertThat(runtime.terminateCalls()).isEqualTo(1);
        assertThat(runtime.inspect(provisioned.providerRef()).phase()).isEqualTo(SandboxProviderPhase.DESTROYED);
    }

    @Test
    void rejectsSameAttemptWithDifferentIdempotencyKey() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        runtime.ensureProvisioned(commandFor(ATTEMPT_ID));

        assertThatThrownBy(() -> runtime.ensureProvisioned(commandFor(ATTEMPT_ID, "sandbox:other-key", "python")))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
        assertThat(runtime.provisionCalls()).isEqualTo(1);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentSpec() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        runtime.ensureProvisioned(commandFor(ATTEMPT_ID));

        assertThatThrownBy(() -> runtime.ensureProvisioned(commandFor(ATTEMPT_ID,
                "sandbox:" + ATTEMPT_ID, "node")))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
        assertThat(runtime.provisionCalls()).isEqualTo(1);
    }

    @Test
    void rejectsProviderUidMismatchAsAStableProviderFailure() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxProviderRef providerRef = runtime.ensureProvisioned(commandFor(ATTEMPT_ID)).providerRef();
        SandboxProviderRef mismatched = new SandboxProviderRef(providerRef.provider(), providerRef.resourceId(),
                "different-uid");

        assertThatThrownBy(() -> runtime.inspect(mismatched))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.PROVIDER_RESOURCE_LOST);
    }

    private static SandboxProvisionCommand commandFor(UUID attemptId) {
        return commandFor(attemptId, "sandbox:" + attemptId, "python");
    }

    private static SandboxProvisionCommand commandFor(UUID attemptId, String idempotencyKey, String template) {
        return new SandboxProvisionCommand(TASK_ID, attemptId, SandboxProfile.ISOLATED,
                Duration.ofMinutes(5), template, NOW, idempotencyKey);
    }
}
