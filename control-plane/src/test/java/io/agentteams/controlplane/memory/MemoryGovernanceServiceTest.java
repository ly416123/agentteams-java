package io.agentteams.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemoryGovernanceServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1",
            "user-1");
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Mock
    private MemoryGovernanceRepository repository;

    private MemoryGovernanceService service;
    private MemoryRecord memory;

    @BeforeEach
    void setUp() {
        service = new MemoryGovernanceService(repository, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        memory = new MemoryRecord(UUID.randomUUID(), new MemoryPolicy(MemoryPolicy.Scope.USER_PRIVATE, "org-1",
                "tenant-1", null, null, "user-1", MemoryPolicy.Sensitivity.NORMAL,
                MemoryPolicy.Consent.CANDIDATE, Duration.ofHours(1)), "secret://memory/1", "private summary",
                "conversation", NOW.plusSeconds(3600), NOW, NOW, 0);
        lenient().when(repository.findById(memory.id(), "org-1", "tenant-1")).thenReturn(Optional.of(memory));
        lenient().when(repository.findOperation(any())).thenReturn(Optional.empty());
        lenient().when(repository.recordOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void ownerCanConfirmPrivateMemoryAndAdminCanFreezeIt() {
        MemoryRecord confirmed = service.confirm(CONTEXT, memory.id(), new MemoryGovernanceActor("user-1", false),
                "user confirmed", "key-1");

        assertThat(confirmed.policy().consent()).isEqualTo(MemoryPolicy.Consent.CONFIRMED);
        assertThat(confirmed.governanceStatus()).isEqualTo(MemoryRecord.GovernanceStatus.ACTIVE);

        when(repository.findById(memory.id(), "org-1", "tenant-1")).thenReturn(Optional.of(confirmed));
        when(repository.findOperation("key-2")).thenReturn(Optional.empty());
        MemoryRecord frozen = service.freeze(CONTEXT, memory.id(), new MemoryGovernanceActor("admin-1", true),
                "incident response", "key-2");

        assertThat(frozen.governanceStatus()).isEqualTo(MemoryRecord.GovernanceStatus.FROZEN);
    }

    @Test
    void administratorCanGovernPrivateMemoryButCannotExportItsContent() {
        MemoryGovernanceExport export = service.exportMetadata(CONTEXT, memory.id(),
                new MemoryGovernanceActor("admin-1", true), "compliance export", "key-export");

        assertThat(export.memoryId()).isEqualTo(memory.id());
        assertThat(export.toString()).doesNotContain("private summary", "secret://memory/1");
        verify(repository).recordOperation(any());
    }

    @Test
    void crossTenantGovernanceIsRejectedBeforeMutation() {
        ExecutionContext otherTenant = new ExecutionContext("org-1", "tenant-2", "project-1", "team-1", "user-1");

        assertThatThrownBy(() -> service.delete(otherTenant, memory.id(),
                new MemoryGovernanceActor("admin-1", true), "retention request", "key-delete"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memory is outside the execution context");

        verify(repository, never()).save(any());
        verify(repository, never()).recordOperation(any());
    }

    @Test
    void nonOwnerCannotGovernPrivateMemory() {
        assertThatThrownBy(() -> service.revoke(CONTEXT, memory.id(),
                new MemoryGovernanceActor("user-2", false), "revoke", "key-revoke"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memory governance is not permitted for this actor");

        verify(repository, never()).save(any());
    }
}
