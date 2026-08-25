package io.agentteams.controlplane.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxLifecycleServiceTest {

    @Test
    void fakeProviderIsIdempotentAndTracksLifecycleCalls() {
        FakeSandboxRuntime runtime = new FakeSandboxRuntime();
        SandboxRequest request = SandboxRequest.of(UUID.randomUUID(), UUID.randomUUID(), SandboxProfile.ISOLATED,
                Duration.ofMinutes(5), "python", Instant.parse("2026-08-25T08:00:00Z"));

        var first = runtime.provision(request);
        var duplicate = runtime.provision(request);

        assertSame(first, duplicate);
        assertEquals(SandboxStatus.READY, runtime.inspect(first.providerSandboxId()));
        runtime.renew(first.providerSandboxId(), Instant.parse("2026-08-25T08:10:00Z"));
        runtime.terminate(first.providerSandboxId(), SandboxTerminationReason.TASK_COMPLETED);
        assertEquals(SandboxStatus.DESTROYED, runtime.inspect(first.providerSandboxId()));
        assertEquals(1, runtime.provisionCalls());
        assertEquals(1, runtime.renewCalls());
        assertEquals(1, runtime.terminateCalls());
    }
}
