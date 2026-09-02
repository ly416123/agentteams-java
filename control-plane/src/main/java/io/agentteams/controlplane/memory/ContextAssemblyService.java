package io.agentteams.controlplane.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Produces a minimal, summary-only context for an Agent execution. */
@Service
public final class ContextAssemblyService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MemoryRepository repository;
    private final MemoryPolicyService policies;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ContextAssemblyService(MemoryRepository repository, MemoryPolicyService policies) {
        this(repository, policies, Clock.systemUTC());
    }

    ContextAssemblyService(MemoryRepository repository, MemoryPolicyService policies, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AssembledContext assemble(ExecutionContext context, int tokenBudget) {
        return assemble(context, null, tokenBudget);
    }

    /** Assembles context for one task; task-scoped memory is unavailable without this explicit identity. */
    public AssembledContext assemble(ExecutionContext context, UUID taskId, int tokenBudget) {
        Objects.requireNonNull(context, "context");
        if (tokenBudget < 1 || tokenBudget > 100_000) {
            throw new IllegalArgumentException("tokenBudget must be between 1 and 100000");
        }
        Instant now = clock.instant();
        int used = 0;
        java.util.ArrayList<MemorySnippet> snippets = new java.util.ArrayList<>();
        for (MemoryRecord memory : repository.find(context.organizationId(), context.tenantId()).stream()
                .sorted(java.util.Comparator.comparing(MemoryRecord::updatedAt).reversed())
                .toList()) {
            if (memory.governanceStatus() != MemoryRecord.GovernanceStatus.ACTIVE) continue;
            if (memory.expiresAt() != null && !memory.expiresAt().isAfter(now)) continue;
            try {
                policies.requireReadable(memory.policy(), context, taskId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (containsSensitiveMarker(memory.summary())) continue;
            int cost = estimateTokens(memory.summary());
            if (cost == 0 || used + cost > tokenBudget) continue;
            snippets.add(new MemorySnippet(memory.id(), memory.policy().scope(), memory.summary(), memory.source()));
            used += cost;
        }
        return new AssembledContext(snippets, used);
    }

    private static int estimateTokens(String value) {
        return Math.max(1, (value.length() + 3) / 4);
    }

    private static boolean containsSensitiveMarker(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("token") || normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("authorization") || normalized.contains("system prompt")
                || normalized.contains("chain of thought");
    }

    public record MemorySnippet(UUID memoryId, MemoryPolicy.Scope scope, String summary, String source) { }

    public record AssembledContext(List<MemorySnippet> snippets, int estimatedTokens) {
        public AssembledContext {
            snippets = List.copyOf(Objects.requireNonNull(snippets, "snippets"));
            if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
        }

        /** Credential-free, summary-only representation for the Agent channel. */
        public String toJson() {
            ArrayNode values = JSON.createArrayNode();
            for (MemorySnippet snippet : snippets) {
                ObjectNode value = values.addObject();
                value.put("memoryId", snippet.memoryId().toString());
                value.put("scope", snippet.scope().name());
                value.put("summary", snippet.summary());
                value.put("source", snippet.source());
            }
            try {
                return JSON.writeValueAsString(values);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("assembled memory context cannot be serialized", error);
            }
        }
    }
}
