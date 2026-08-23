package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSpecServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private AgentSpecRepository repository;

    private AgentSpecService service;

    @BeforeEach
    void setUp() {
        service = new AgentSpecService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsVersionedDraftAndNormalizesState() {
        when(repository.findIdempotency(any())).thenReturn(Optional.empty());
        when(repository.insertIdempotency(any())).thenReturn(true);
        AgentSpecRecord record = service.create("spec-key", new AgentSpecService.Input(
                " analyst ", "qwenpaw", "deepseek", "deepseek-chat", "research", "running",
                "{\"skillRefs\":[\"search-v1\"]}"));

        assertThat(record.name()).isEqualTo("analyst");
        assertThat(record.desiredState()).isEqualTo("RUNNING");
        assertThat(record.lifecycleStatus()).isEqualTo("DRAFT");
        assertThat(record.version()).isEqualTo(1);
        assertThat(record.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsUnsupportedDesiredState() {
        assertThatThrownBy(() -> service.create("spec-key", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "paused", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("desiredState must be RUNNING or STOPPED");
    }

    @Test
    void returnsIdempotentResourceForSameRequest() {
        UUID id = UUID.randomUUID();
        AgentSpecService.Input input = new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING", "{}");
        AgentSpecRecord existing = new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1);
        when(repository.findIdempotency("spec-key")).thenReturn(Optional.of(
                new AgentSpecRepository.IdempotencyRecord("spec-key", sha(input), id, NOW)));
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThat(service.create("spec-key", input)).isEqualTo(existing);
    }

    @Test
    void listsRepositoryRecords() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }

    private static String sha(AgentSpecService.Input input) {
        try {
            String value = String.join("\u0000", input.name(), input.runtime(), input.modelProvider(),
                    input.modelName(), "", input.desiredState(), input.specJson());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
