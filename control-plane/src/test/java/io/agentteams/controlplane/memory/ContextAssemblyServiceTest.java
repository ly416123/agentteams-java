package io.agentteams.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextAssemblyServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @Test
    void assemblesConfirmedAuthorizedSummariesWithinBudget() {
        MemoryRepository repository = new InMemoryMemoryRepository(List.of(
                memory("first summary", MemoryPolicy.Scope.USER_PRIVATE, "user-1", null, null),
                memory("second summary", MemoryPolicy.Scope.PROJECT_SHARED, null, "project-1", null),
                memory("other user", MemoryPolicy.Scope.USER_PRIVATE, "user-2", null, null)));
        ContextAssemblyService service = new ContextAssemblyService(repository, new MemoryPolicyService());

        ContextAssemblyService.AssembledContext result = service.assemble(CONTEXT, 4);

        assertThat(result.snippets()).extracting(ContextAssemblyService.MemorySnippet::summary)
                .containsExactly("first summary");
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(4);
    }

    private static MemoryRecord memory(String summary, MemoryPolicy.Scope scope, String subject,
            String project, String team) {
        MemoryPolicy policy = new MemoryPolicy(scope, "org-1", "tenant-1", project, team, subject,
                MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED, Duration.ofHours(1));
        return new MemoryRecord(UUID.randomUUID(), policy, "secret://content-not-returned", summary, "user",
                Instant.parse("2099-01-01T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"),
                Instant.parse("2026-08-31T00:00:00Z"), 0);
    }

    private static final class InMemoryMemoryRepository implements MemoryRepository {
        private final List<MemoryRecord> records;

        private InMemoryMemoryRepository(List<MemoryRecord> records) { this.records = records; }

        @Override
        public MemoryRecord save(MemoryRecord memory) { return memory; }

        @Override
        public List<MemoryRecord> find(String organizationId, String tenantId) { return records; }
    }
}
