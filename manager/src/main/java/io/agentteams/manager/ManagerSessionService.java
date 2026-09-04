package io.agentteams.manager;

import io.agentteams.application.api.ModelPrice;
import io.agentteams.application.api.ModelPriceCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ManagerSessionService {
    private static final String AUDIT_CURRENCY = "USD";

    private final ModelProvider provider;
    private final StructuredOutputValidator validator;
    private final ManagerToolRegistry tools;
    private final ModelCallAuditor auditor;
    private final Clock clock;
    private final ModelCallAdmission admission;
    private final ModelCostCalculator costCalculator;

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools) {
        this(provider, mapper, tools, ModelCallAuditor.noop(), Clock.systemUTC(),
                ModelCallAdmission.noop(), null);
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock) {
        this(provider, mapper, tools, auditor, clock, ModelCallAdmission.noop(), null);
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelCallAdmission admission) {
        this(provider, mapper, tools, auditor, clock, admission, null);
    }

    /** Legacy-compatible overload that enables pricing without admission. */
    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelCostCalculator costCalculator) {
        this(provider, mapper, tools, auditor, clock, ModelCallAdmission.noop(), costCalculator);
    }

    /** Convenience overload that builds the calculator from a price catalog. */
    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelPriceCatalog priceCatalog) {
        this(provider, mapper, tools, auditor, clock, ModelCallAdmission.noop(),
                priceCatalog == null ? null : new ModelCostCalculator(priceCatalog));
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelCallAdmission admission,
            ModelCostCalculator costCalculator) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.validator = new StructuredOutputValidator(mapper);
        this.tools = Objects.requireNonNull(tools, "tools");
        this.auditor = Objects.requireNonNull(auditor, "auditor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.costCalculator = costCalculator;
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelCostCalculator costCalculator,
            ModelCallAdmission admission) {
        this(provider, mapper, tools, auditor, clock, admission, costCalculator);
    }

    public ManagerSessionService(ModelProvider provider, ObjectMapper mapper, ManagerToolRegistry tools,
            ModelCallAuditor auditor, Clock clock, ModelPriceCatalog priceCatalog,
            ModelCallAdmission admission) {
        this(provider, mapper, tools, auditor, clock, admission,
                priceCatalog == null ? null : new ModelCostCalculator(priceCatalog));
    }

    public Object handleCreateTask(String prompt, ManagerToolRegistry.ToolContext context) {
        ModelProvider.ModelRequest request = new ModelProvider.ModelRequest(prompt, 1024);
        String providerName = providerIdentity();
        String modelName = modelIdentity();
        ModelCallLease lease = admission.acquire(new ModelCallAdmissionRequest(
                providerName, modelName, request.maxTokens(),
                context.tenantId(), context.projectId(), admissionDimensions(context, "create_task")));
        if (lease == null) {
            throw new IllegalStateException("model call admission returned null lease");
        }
        Instant occurredAt = clock.instant();
        long startedNanos = System.nanoTime();
        try {
            ModelProvider.ModelResponse response;
            try {
                response = provider.complete(request);
            } catch (RuntimeException error) {
                recordFailure(request, context, occurredAt, elapsedSince(startedNanos), error,
                        providerName, modelName);
                throw error;
            }
            recordSuccess(request, response, context, occurredAt, elapsedSince(startedNanos),
                    providerName, modelName);
            CreateTaskIntent intent = validator.parseCreateTask(response.content());
            return tools.invoke("create_task", intent, context);
        } finally {
            // Release only after provider outcome, audit, and downstream handling are complete.
            lease.close();
        }
    }

    private void recordSuccess(ModelProvider.ModelRequest request, ModelProvider.ModelResponse response,
            ManagerToolRegistry.ToolContext context, Instant occurredAt, Duration latency,
            String providerName, String configuredModelName) {
        String modelName = ModelIdentity.normalize(response.model(), configuredModelName);
        ModelCallAudit.TokenUsage tokenUsage = new ModelCallAudit.TokenUsage(
                response.promptTokens(), response.completionTokens());
        ModelCostEstimate costEstimate = estimateCost(providerName, modelName, tokenUsage);
        record(new ModelCallAudit(providerName, modelName, latency, tokenUsage,
                ModelCallAuditHasher.hashRedacted(request.prompt()),
                ModelCallAuditHasher.hashRedacted(response.content()), ModelCallAudit.Outcome.SUCCESS, null, occurredAt,
                context.tenantId(), context.projectId(), costUsd(costEstimate), costStatus(costEstimate),
                dimensions(context, "create_task")));
    }

    private void recordFailure(ModelProvider.ModelRequest request, ManagerToolRegistry.ToolContext context,
            Instant occurredAt, Duration latency,
            RuntimeException error, String providerName, String modelName) {
        String category = error instanceof ModelProviderException providerError
                ? providerError.category().name() : "UNKNOWN";
        record(new ModelCallAudit(providerName, modelName, latency,
                new ModelCallAudit.TokenUsage(0, 0), ModelCallAuditHasher.hashRedacted(request.prompt()), null,
                ModelCallAudit.Outcome.FAILURE, category, occurredAt, context.tenantId(), context.projectId(), 0,
                ModelCallAudit.CostStatus.NOT_APPLICABLE, dimensions(context, "create_task")));
    }

    private static ModelCallAudit.Dimensions dimensions(ManagerToolRegistry.ToolContext context,
            String defaultTool) {
        String tool = context.toolId() == null || context.toolId().isBlank() ? defaultTool : context.toolId();
        return new ModelCallAudit.Dimensions(context.workerId(), context.taskId(), context.teamId(), tool,
                context.quotaId(), context.quotaDimension());
    }

    private static ModelCallDimensions admissionDimensions(ManagerToolRegistry.ToolContext context,
            String defaultTool) {
        String tool = context.toolId() == null || context.toolId().isBlank() ? defaultTool : context.toolId();
        return new ModelCallDimensions(context.workerId(), context.taskId(), context.teamId(), tool,
                context.quotaId(), context.quotaDimension());
    }

    private ModelCostEstimate estimateCost(String providerName, String modelName,
            ModelCallAudit.TokenUsage tokenUsage) {
        if (costCalculator == null) {
            return null;
        }
        return costCalculator.estimate(providerName, modelName, AUDIT_CURRENCY,
                new ModelTokenUsage(tokenUsage.promptTokens(), tokenUsage.completionTokens()));
    }

    private static double costUsd(ModelCostEstimate estimate) {
        return estimate == null || !estimate.isPriced() ? 0 : estimate.estimatedCost().doubleValue();
    }

    private static ModelCallAudit.CostStatus costStatus(ModelCostEstimate estimate) {
        return estimate != null && estimate.isPriced()
                ? ModelCallAudit.CostStatus.ESTIMATED : ModelCallAudit.CostStatus.UNPRICED;
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

    private String providerIdentity() {
        String fallback = ModelIdentity.normalize(provider.getClass().getSimpleName(), "unknown");
        return ModelIdentity.read(provider::providerName, fallback);
    }

    private String modelIdentity() {
        return ModelIdentity.read(provider::modelName, "unknown");
    }
}
