package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class ManagementRoleBootstrapRunnerTest {
    private final ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void blankConfigurationDoesNothing() {
        new ManagementRoleBootstrapRunner(roles, audit, clock, " ").run(null);
        verify(roles, never()).bootstrapPlatformAdminIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bootstrapOnlyCreatesAndAuditsFirstPlatformAdmin() {
        when(roles.bootstrapPlatformAdminIfAbsent("root-subject", "bootstrap", Instant.EPOCH)).thenReturn(true);
        new ManagementRoleBootstrapRunner(roles, audit, clock, " root-subject ").run(null);
        verify(roles).bootstrapPlatformAdminIfAbsent("root-subject", "bootstrap", Instant.EPOCH);
        verify(audit).record(ArgumentMatchers.argThat((AuditEvent event) ->
                event.action().equals("PLATFORM_ADMIN_BOOTSTRAPPED")));
    }

    @Test
    void existingPlatformAdministratorPreventsBootstrap() {
        when(roles.bootstrapPlatformAdminIfAbsent("second-root", "bootstrap", Instant.EPOCH)).thenReturn(false);
        new ManagementRoleBootstrapRunner(roles, audit, clock, "second-root").run(null);
        verify(roles).bootstrapPlatformAdminIfAbsent("second-root", "bootstrap", Instant.EPOCH);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }
}
