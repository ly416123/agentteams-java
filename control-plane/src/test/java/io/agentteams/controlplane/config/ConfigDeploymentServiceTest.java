package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.AgentRepository;
import io.agentteams.controlplane.persistence.DomainEventRepository;
import io.agentteams.controlplane.persistence.OutboxEventRepository;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConfigDeploymentServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void includesOnlyCompletedFilesInConfigChangedPayload() throws Exception {
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        ConfigLifecycleRepository lifecycle = mock(ConfigLifecycleRepository.class);
        ConfigSnapshotRepository snapshots = mock(ConfigSnapshotRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        DomainEventRepository domainEvents = mock(DomainEventRepository.class);
        UUID agentId = UUID.randomUUID();
        ConfigSnapshot snapshot = new ConfigSnapshot(UUID.randomUUID(), "worker", 4,
                "{\"model\":\"deepseek\"}", "manifest-sha", "test", NOW);
        ConfigFileRecord file = new ConfigFileRecord(UUID.randomUUID(), snapshot.id(), "models/default.json",
                "configs/" + snapshot.id() + "/files/models/default.json", "file-sha", 42,
                "application/json");
        when(tx.configLifecycle()).thenReturn(lifecycle);
        when(tx.agents()).thenReturn(agents);
        when(tx.outboxEvents()).thenReturn(outbox);
        when(tx.domainEvents()).thenReturn(domainEvents);
        when(agents.findById(agentId)).thenReturn(Optional.of(AgentRecord.create(agentId, "worker",
                AgentPhase.PROVISIONING, "qwenpaw", "{}", NOW)));
        when(lifecycle.findBinding("worker", agentId)).thenReturn(Optional.empty());
        when(lifecycle.findFiles(snapshot.id())).thenReturn(List.of(file));
        when(outbox.findByEventId(any())).thenReturn(Optional.empty());
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            Function<FoundationTransaction, ?> work = invocation.getArgument(0);
            return work.apply(tx);
        });

        ConfigDeploymentService service = new ConfigDeploymentService(persistence, snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC), MAPPER);

        service.deploy(agentId, "worker", snapshot);

        var event = org.mockito.ArgumentCaptor.forClass(OutboxEventRecord.class);
        org.mockito.Mockito.verify(outbox).insert(event.capture());
        JsonNode payload = MAPPER.readTree(event.getValue().payloadJson());
        assertThat(payload.path("files")).hasSize(1);
        assertThat(payload.path("files").get(0).path("path").asText()).isEqualTo("models/default.json");
        assertThat(payload.path("files").get(0).path("uri").asText())
                .isEqualTo("urn:agentteams:config-file:" + snapshot.id() + ":models/default.json");
        assertThat(payload.path("files").get(0).path("sha256").asText()).isEqualTo("file-sha");
        assertThat(payload.path("files").get(0).path("sizeBytes").asLong()).isEqualTo(42);
        assertThat(payload.path("files").get(0).path("contentType").asText()).isEqualTo("application/json");
    }

    @Test
    void rollsBackToNewestPreviouslyAppliedSnapshot() throws Exception {
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        ConfigLifecycleRepository lifecycle = mock(ConfigLifecycleRepository.class);
        ConfigSnapshotRepository snapshots = mock(ConfigSnapshotRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        DomainEventRepository domainEvents = mock(DomainEventRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ConfigSnapshot current = new ConfigSnapshot(UUID.randomUUID(), "worker", 3,
                "{\"version\":3}", "current-sha", "test", NOW);
        ConfigSnapshot stable = new ConfigSnapshot(UUID.randomUUID(), "worker", 2,
                "{\"version\":2}", "stable-sha", "test", NOW.minusSeconds(1));
        ConfigBindingRecord binding = new ConfigBindingRecord(bindingId, "worker", agentId, current.id(), NOW);
        when(tx.configLifecycle()).thenReturn(lifecycle);
        when(tx.outboxEvents()).thenReturn(outbox);
        when(tx.domainEvents()).thenReturn(domainEvents);
        when(lifecycle.findBindingForUpdate(bindingId)).thenReturn(Optional.of(binding));
        when(snapshots.findById(current.id())).thenReturn(Optional.of(current));
        when(lifecycle.findLatestAppliedSnapshotForRollback(bindingId, current.id())).thenReturn(Optional.of(stable));
        when(lifecycle.findFiles(stable.id())).thenReturn(List.of());
        when(outbox.findByEventId(any())).thenReturn(Optional.empty());
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            Function<FoundationTransaction, ?> work = invocation.getArgument(0);
            return work.apply(tx);
        });

        ConfigDeploymentService service = new ConfigDeploymentService(persistence, snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC), MAPPER);

        ConfigDeploymentService.ConfigDeployment result = service.rollback(bindingId);

        assertThat(result.snapshot()).isEqualTo(stable);
        assertThat(result.binding().snapshotId()).isEqualTo(stable.id());
        org.mockito.Mockito.verify(lifecycle).upsertBinding(any(ConfigBindingRecord.class));
        org.mockito.Mockito.verify(lifecycle).markApplyPending(eq(bindingId), eq(agentId), eq(stable.id()),
                eq(result.eventId()), eq(NOW));
        var event = org.mockito.ArgumentCaptor.forClass(OutboxEventRecord.class);
        org.mockito.Mockito.verify(outbox).insert(event.capture());
        assertThat(MAPPER.readTree(event.getValue().payloadJson()).path("rollback").asBoolean()).isTrue();
    }
}
