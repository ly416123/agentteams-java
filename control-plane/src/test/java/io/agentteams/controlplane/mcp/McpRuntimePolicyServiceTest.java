package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.agentteams.controlplane.security.OutboundPolicy;

class McpRuntimePolicyServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void allowsOnlyEnabledHealthyAllowlistedCalls() {
        McpServerRecord server = new McpServerRecord(UUID.randomUUID(), "search", McpTransport.SSE,
                "https://api.example.test/mcp", null, true, McpHealthStatus.HEALTHY, NOW, NOW, NOW, 0,
                new OutboundPolicy(Set.of("https"), Set.of("api.example.test"), Duration.ofSeconds(5),
                        Set.of("search")));
        McpRuntimePolicyService service = new McpRuntimePolicyService();

        assertThat(service.authorize(server, "search", Duration.ofSeconds(2)))
                .isEqualTo(new McpRuntimePolicyService.Authorization(true, "ALLOWED"));
        assertThat(service.authorize(server, "delete", Duration.ofSeconds(2)).classification())
                .isEqualTo("OUTBOUND_POLICY_DENIED");
    }

    @Test
    void deniesDisabledOrUnhealthyServersBeforePolicyEvaluation() {
        McpServerRecord disabled = new McpServerRecord(UUID.randomUUID(), "search", McpTransport.SSE,
                "https://api.example.test/mcp", null, false, McpHealthStatus.HEALTHY, NOW, NOW, NOW, 0);
        McpServerRecord unhealthy = new McpServerRecord(UUID.randomUUID(), "search", McpTransport.SSE,
                "https://api.example.test/mcp", null, true, McpHealthStatus.UNHEALTHY, NOW, NOW, NOW, 0);
        McpRuntimePolicyService service = new McpRuntimePolicyService();

        assertThat(service.authorize(disabled, "search", Duration.ofSeconds(1)).classification())
                .isEqualTo("SERVER_DISABLED");
        assertThat(service.authorize(unhealthy, "search", Duration.ofSeconds(1)).classification())
                .isEqualTo("SERVER_UNHEALTHY");
    }
}
