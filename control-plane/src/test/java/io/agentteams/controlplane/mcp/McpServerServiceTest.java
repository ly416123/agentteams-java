package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpServerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private McpServerRepository repository;

    private McpServerService service;

    @BeforeEach
    void setUp() {
        service = new McpServerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsServerWithNormalizedTransportAndDefaultHealth() {
        when(repository.insertIdempotency(eq("create-key"), any(), eq(NOW))).thenReturn(1);

        McpServerRecord created = service.create(" create-key ", new McpServerService.CreateInput(
                " weather ", "streamable-http", "https://mcp.example.test/mcp", "secret/mcp", null,
                null, null));

        assertThat(created.name()).isEqualTo("weather");
        assertThat(created.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(created.healthStatus()).isEqualTo(McpHealthStatus.UNKNOWN);
        assertThat(created.enabled()).isTrue();
        assertThat(created.credentialRef()).isEqualTo("secret/mcp");
        assertThat(created.createdAt()).isEqualTo(NOW);
        verify(repository).insert(created);
        verify(repository).bindIdempotency(eq("create-key"), eq(created.id()));
    }

    @Test
    void repeatedCreateReturnsOriginalResourceForTheSamePayload() {
        UUID id = UUID.randomUUID();
        McpServerRecord original = record(id, "weather", McpHealthStatus.UNKNOWN, 0);
        when(repository.findIdempotency("same-key"))
                .thenReturn(Optional.empty(), Optional.of(new McpServerRepository.McpIdempotencyRecord(
                        "same-key", hashFor("weather", "SSE", "https://mcp.example.test/sse", null, true,
                                "UNKNOWN", null), id, NOW)));
        when(repository.insertIdempotency(eq("same-key"), any(), eq(NOW))).thenReturn(0);
        when(repository.findById(id)).thenReturn(Optional.of(original));

        McpServerRecord returned = service.create("same-key", new McpServerService.CreateInput(
                "weather", "SSE", "https://mcp.example.test/sse", null, true, null, null));

        assertThat(returned).isEqualTo(original);
        verify(repository, never()).insert(any(McpServerRecord.class));
    }

    @Test
    void rejectsInvalidTransportAndEndpointBeforePersistence() {
        assertThatThrownBy(() -> service.create("key", new McpServerService.CreateInput(
                "weather", "stdio", "localhost:8080", null, true, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transport must be SSE or STREAMABLE_HTTP");
        verify(repository, never()).insertIdempotency(any(), any(), any());
    }

    @Test
    void updatesServerWithOptimisticVersionAndHealth() {
        UUID id = UUID.randomUUID();
        McpServerRecord current = record(id, "weather", McpHealthStatus.UNKNOWN, 3);
        when(repository.findById(id)).thenReturn(Optional.of(current));
        when(repository.update(any(McpServerRecord.class), eq(3L))).thenReturn(1);

        McpServerRecord updated = service.update(id, new McpServerService.UpdateInput(
                "weather-v2", "SSE", "https://mcp.example.test/sse", null, false, "HEALTHY", NOW));

        assertThat(updated.name()).isEqualTo("weather-v2");
        assertThat(updated.healthStatus()).isEqualTo(McpHealthStatus.HEALTHY);
        assertThat(updated.version()).isEqualTo(4);
        verify(repository).update(updated, 3);
    }

    @Test
    void healthUpdateDefaultsCheckedAtToTheCurrentClock() {
        UUID id = UUID.randomUUID();
        McpServerRecord current = record(id, "weather", McpHealthStatus.UNKNOWN, 1);
        when(repository.findById(id)).thenReturn(Optional.of(current));
        when(repository.updateHealth(id, McpHealthStatus.HEALTHY, NOW, NOW, 1)).thenReturn(1);

        McpServerRecord updated = service.updateHealth(id, new McpServerService.HealthInput("healthy", null));

        assertThat(updated.healthStatus()).isEqualTo(McpHealthStatus.HEALTHY);
        assertThat(updated.lastCheckedAt()).isEqualTo(NOW);
        assertThat(updated.version()).isEqualTo(2);
    }

    private static McpServerRecord record(UUID id, String name, McpHealthStatus status, long version) {
        return new McpServerRecord(id, name, McpTransport.SSE, "https://mcp.example.test/sse", null, true, status,
                null, NOW, NOW, version);
    }

    private static String hashFor(String name, String transport, String endpoint, String credentialRef,
            boolean enabled, String healthStatus, Instant lastCheckedAt) {
        String payload = String.join("\u0000", name, transport, endpoint, credentialRef == null ? "" : credentialRef,
                Boolean.toString(enabled), healthStatus, lastCheckedAt == null ? "" : lastCheckedAt.toString());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
