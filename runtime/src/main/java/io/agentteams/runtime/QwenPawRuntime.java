package io.agentteams.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/** Runtime adapter boundary for an external QwenPaw process or sidecar. */
public final class QwenPawRuntime implements AgentRuntime {
    private final QwenPawProcessPort process;
    private final RuntimeModelCallAdmission admission;
    private final Map<UUID, RuntimeModelCallLease> admittedCalls = new ConcurrentHashMap<>();
    private final FakeRuntime state = new FakeRuntime();
    private AgentRuntimeContext context;
    private volatile RuntimeConfigSnapshot activeConfiguration;

    public QwenPawRuntime(QwenPawProcessPort process) {
        this(process, RuntimeModelCallAdmission.noop());
    }

    public QwenPawRuntime(QwenPawProcessPort process, RuntimeModelCallAdmission admission) {
        this.process = Objects.requireNonNull(process, "process");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    @Override
    public void start(AgentRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
        // Process completions are delivered to the outer Agent client. The
        // state tracker must not invoke that callback a second time when the
        // GatewayRuntimeAdapter calls complete()/fail().
        state.start(new AgentRuntimeContext(context.runtimeName(), context.maxConcurrency(), context.clock(),
                ignored -> { }, context.configuration()));
        try {
            process.start(context, result -> {
                releaseAdmission(result.taskId());
                context.resultSink().accept(result);
            });
        } catch (RuntimeException error) {
            state.stop();
            this.context = null;
            throw error;
        }
    }

    @Override
    public RuntimeSubmission submit(RuntimeTask task) {
        RuntimeSubmission submission = state.submit(task);
        if (submission.accepted()) {
            try {
                RuntimeModelCallLease lease = admission.acquire(admissionRequest(task));
                if (lease == null) {
                    throw new IllegalStateException("runtime model call admission returned null lease");
                }
                RuntimeModelCallLease previous = admittedCalls.putIfAbsent(task.id(), lease);
                if (previous != null) {
                    lease.close();
                    throw new IllegalStateException("task already has an admitted model call: " + task.id());
                }
                process.submit(task);
            } catch (RuntimeModelCallAdmissionRejectedException rejected) {
                admittedCalls.remove(task.id());
                state.cancel(task.id());
                return RuntimeSubmission.rejected(rejected.getMessage());
            } catch (RuntimeException error) {
                releaseAdmission(task.id());
                context.resultSink().accept(RuntimeResult.failure(task.id(), "runtime submission failed",
                        java.time.Instant.now()));
                throw error;
            }
        }
        return submission;
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        CompletionStatus status = state.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            releaseAdmission(result.taskId());
        }
        return status;
    }

    @Override
    public boolean cancel(UUID taskId) {
        boolean cancelled = state.cancel(taskId);
        if (cancelled) {
            process.cancel(taskId);
            releaseAdmission(taskId);
        }
        return cancelled;
    }

    @Override
    public Optional<RuntimeStatus> status(UUID taskId) {
        return state.status(taskId);
    }

    @Override
    public RuntimeSnapshot snapshot() {
        return state.snapshot();
    }

    @Override
    public void applyConfig(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            process.applyConfig(snapshot);
        } catch (RuntimeException error) {
            throw new RuntimeConfigApplyException("QwenPaw configuration activation failed", error);
        }
        activeConfiguration = snapshot;
        state.applyConfig(snapshot);
    }

    @Override
    public void stop() {
        try {
            process.stop();
        } finally {
            admittedCalls.values().forEach(RuntimeModelCallLease::close);
            admittedCalls.clear();
            state.stop();
            context = null;
            activeConfiguration = null;
        }
    }

    private RuntimeModelCallAdmissionRequest admissionRequest(RuntimeTask task) {
        Map<String, String> values = activeConfiguration == null
                ? context.configuration() : activeConfiguration.values();
        String provider = firstNonBlank(task.metadata().get("provider"), task.metadata().get("provider_id"),
                values.get("provider_id"), values.get("modelProvider"), "qwenpaw");
        String model = firstNonBlank(task.metadata().get("model"), values.get("model"), "unknown");
        int maxTokens = positiveInt(firstNonBlank(task.metadata().get("maxTokens"),
                values.get("modelMaxTokens"), "1024"), "maxTokens");
        Map<String, String> baseValues = context.configuration();
        String tenantId = nullable(firstNonBlank(task.metadata().get("tenantId"), task.metadata().get("tenant_id"),
                values.get("tenant_id"), values.get("tenantId"), baseValues.get("tenant_id"),
                baseValues.get("tenantId")));
        String projectId = nullable(firstNonBlank(task.metadata().get("projectId"), task.metadata().get("project_id"),
                values.get("project_id"), values.get("projectId"), baseValues.get("project_id"),
                baseValues.get("projectId")));
        String workerId = nullable(firstNonBlank(values.get("worker_id"), values.get("workerId"),
                baseValues.get("worker_id"), baseValues.get("workerId"), values.get("agent_id"),
                values.get("agentId"), baseValues.get("agent_id"), baseValues.get("agentId"),
                context.runtimeName()));
        String teamId = nullable(firstNonBlank(task.metadata().get("teamId"), task.metadata().get("team_id"),
                values.get("team_id"), values.get("teamId"), baseValues.get("team_id"),
                baseValues.get("teamId")));
        String toolId = nullable(firstNonBlank(task.metadata().get("toolId"), task.metadata().get("tool_id"),
                values.get("tool_id"), values.get("toolId"), baseValues.get("tool_id"),
                baseValues.get("toolId")));
        String quotaId = nullable(firstNonBlank(task.metadata().get("quotaId"), task.metadata().get("quota_id"),
                values.get("quota_id"), values.get("quotaId"), baseValues.get("quota_id"),
                baseValues.get("quotaId")));
        String quotaDimension = nullable(firstNonBlank(task.metadata().get("quotaDimension"),
                task.metadata().get("quota_dimension"), values.get("quota_dimension"),
                values.get("quotaDimension"), baseValues.get("quota_dimension"),
                baseValues.get("quotaDimension"), tenantId == null ? null : "project"));
        RuntimeModelCallDimensions dimensions = new RuntimeModelCallDimensions(workerId, task.id().toString(),
                teamId, toolId, quotaId, quotaDimension);
        return new RuntimeModelCallAdmissionRequest(provider, model, maxTokens, tenantId, projectId, dimensions);
    }

    private void releaseAdmission(UUID taskId) {
        RuntimeModelCallLease lease = admittedCalls.remove(taskId);
        if (lease != null) {
            lease.close();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static int positiveInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be positive", error);
        }
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
