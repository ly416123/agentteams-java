package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCallAuditTest {
    @Test
    void recordsStructuredUsageLatencyAndOnlyRedactedHashes() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("MockModelProvider");
        when(provider.complete(any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Audit\","
                        + "\"description\":\"Record audit\"}", "deepseek-chat", 7, 11));
        List<ModelCallAudit> audits = new ArrayList<>();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))), audits::add,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        assertThat(service.handleCreateTask("prompt apiKey=do-not-persist",
                new ManagerToolRegistry.ToolContext(Set.of("task:create"), false))).isEqualTo("accepted");

        ModelCallAudit audit = audits.get(0);
        assertThat(audit.provider()).isEqualTo("MockModelProvider");
        assertThat(audit.model()).isEqualTo("deepseek-chat");
        assertThat(audit.latency()).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
        assertThat(audit.tokenUsage()).isEqualTo(new ModelCallAudit.TokenUsage(7, 11));
        assertThat(audit.requestHash()).isNotBlank();
        assertThat(audit.responseHash()).isNotBlank();
        assertThat(audit.toString()).doesNotContain("do-not-persist");
    }

    @Test
    void hashesSecretsAfterRedactionSoSecretRotationDoesNotChangeAuditIdentity() {
        assertThat(ModelCallAuditHasher.hashRedacted("apiKey=first-secret prompt=hello"))
                .isEqualTo(ModelCallAuditHasher.hashRedacted("apiKey=second-secret prompt=hello"));
        assertThat(ModelCallAuditHasher.hashRedacted("prompt=hello"))
                .isNotEqualTo(ModelCallAuditHasher.hashRedacted("prompt=goodbye"));
    }

    @Test
    void recordsProviderFailureWithoutLeakingFailureText() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("MockModelProvider");
        when(provider.complete(any())).thenThrow(new ModelProviderException(
                "upstream secret=should-not-persist", ModelProviderException.Category.NETWORK));
        List<ModelCallAudit> audits = new ArrayList<>();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of()), audits::add,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleCreateTask("hello",
                new ManagerToolRegistry.ToolContext(Set.of(), false)))
                .isInstanceOf(ModelProviderException.class);

        ModelCallAudit audit = audits.get(0);
        assertThat(audit.outcome()).isEqualTo(ModelCallAudit.Outcome.FAILURE);
        assertThat(audit.errorCategory()).isEqualTo(ModelProviderException.Category.NETWORK.name());
        assertThat(audit.toString()).doesNotContain("should-not-persist");
    }
}
