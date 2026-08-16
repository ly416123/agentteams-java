package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ManagerSessionService {
    private final ModelProvider provider;
    private final StructuredOutputValidator validator;
    private final ManagerToolRegistry tools;
    private final ModelCallAuditor auditor;
    private final Clock clock;

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools) {
        this(provider, mapper, tools, ModelCallAuditor.noop(), Clock.systemUTC());
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.validator = new StructuredOutputValidator(mapper);
        this.tools = Objects.requireNonNull(tools, "tools");
        this.auditor = Objects.requireNonNull(auditor, "auditor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Object handleCreateTask(String prompt, ManagerToolRegistry.ToolContext context) {
        ModelProvider.ModelRequest request = new ModelProvider.ModelRequest(prompt, 1024);
        Instant occurredAt = clock.instant();
        long startedNanos = System.nanoTime();
        ModelProvider.ModelResponse response;
        try {
            response = provider.complete(request);
        } catch (RuntimeException error) {
            recordFailure(request, occurredAt, elapsedSince(startedNanos), error);
            throw error;
        }
        recordSuccess(request, response, occurredAt, elapsedSince(startedNanos));
        CreateTaskIntent intent = validator.parseCreateTask(response.content());
        return tools.invoke("create_task", intent, context);
    }

    private void recordSuccess(ModelProvider.ModelRequest request, ModelProvider.ModelResponse response,
            Instant occurredAt, Duration latency) {
        record(new ModelCallAudit(nonBlankOr(provider.providerName(), provider.getClass().getSimpleName()), response.model(), latency,
                new ModelCallAudit.TokenUsage(response.promptTokens(), response.completionTokens()),
                ModelCallAuditHasher.hashRedacted(request.prompt()),
                ModelCallAuditHasher.hashRedacted(response.content()), ModelCallAudit.Outcome.SUCCESS, null, occurredAt));
    }

    private void recordFailure(ModelProvider.ModelRequest request, Instant occurredAt, Duration latency,
            RuntimeException error) {
        String category = error instanceof ModelProviderException providerError
                ? providerError.category().name() : "UNKNOWN";
        record(new ModelCallAudit(nonBlankOr(provider.providerName(), provider.getClass().getSimpleName()),
                nonBlankOr(provider.modelName(), "unknown"), latency,
                new ModelCallAudit.TokenUsage(0, 0), ModelCallAuditHasher.hashRedacted(request.prompt()), null,
                ModelCallAudit.Outcome.FAILURE, category, occurredAt));
    }

    private void record(ModelCallAudit audit) {
        try {
            auditor.record(audit);
        } catch (RuntimeException ignored) {
            // Auditing must never turn a model response or provider failure into a different outcome.
        }
    }

    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
