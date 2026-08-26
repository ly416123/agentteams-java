package io.agentteams.worker;

import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.AgentScopeRolloutPolicy;
import io.agentteams.runtime.CompletionStatus;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeStatus;
import io.agentteams.runtime.RuntimeSubmission;
import io.agentteams.runtime.RuntimeTask;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Selects a worker runtime once per task and preserves that owner until termination. */
public final class WorkerRuntimeRouter implements AgentRuntime {
    private static final String RUNTIME_UNAVAILABLE = "RUNTIME_UNAVAILABLE";
    private static final String[] REQUIRED_SCOPE = {"tenantId", "teamId", "agentId"};

    private final AgentRuntime qwenPaw;
    private final AgentRuntime agentScope;
    private final ConcurrentMap<UUID, AgentRuntime> owners = new ConcurrentHashMap<>();
    private volatile AgentScopeRolloutPolicy rollout;

    public WorkerRuntimeRouter(AgentRuntime qwenPaw, AgentRuntime agentScope,
            AgentScopeRolloutPolicy rollout) {
        this.qwenPaw = Objects.requireNonNull(qwenPaw, "qwenPaw");
        this.agentScope = agentScope;
        this.rollout = Objects.requireNonNull(rollout, "rollout");
    }

    public void updatePolicy(AgentScopeRolloutPolicy rollout) {
        this.rollout = Objects.requireNonNull(rollout, "rollout");
    }

    @Override
    public void start(AgentRuntimeContext context) {
        qwenPaw.start(withRuntimeName(context, "qwenpaw"));
        if (agentScope == null) {
            return;
        }
        try {
            agentScope.start(withRuntimeName(context, "agentscope"));
        } catch (RuntimeException error) {
            qwenPaw.stop();
            throw error;
        }
    }

    @Override
    public RuntimeSubmission submit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        AgentRuntime existing = owners.get(task.id());
        if (existing != null) {
            return existing.submit(task);
        }
        AgentRuntime selected = select(task);
        if (selected == null) {
            return RuntimeSubmission.rejected(RUNTIME_UNAVAILABLE);
        }
        RuntimeSubmission submission = selected.submit(task);
        if (submission.accepted()) {
            AgentRuntime previous = owners.putIfAbsent(task.id(), selected);
            if (previous != null && previous != selected) {
                selected.cancel(task.id());
                return RuntimeSubmission.rejected("task owner changed during submission");
            }
        }
        return submission;
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        AgentRuntime owner = owners.get(result.taskId());
        if (owner == null) {
            return CompletionStatus.UNKNOWN_TASK;
        }
        CompletionStatus status = owner.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            owners.remove(result.taskId(), owner);
        }
        return status;
    }

    @Override
    public boolean cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        AgentRuntime owner = owners.get(taskId);
        if (owner == null) {
            return false;
        }
        boolean cancelled = owner.cancel(taskId);
        if (cancelled) {
            owners.remove(taskId, owner);
        }
        return cancelled;
    }

    @Override
    public Optional<RuntimeStatus> status(UUID taskId) {
        AgentRuntime owner = owners.get(taskId);
        return owner == null ? Optional.empty() : owner.status(taskId);
    }

    @Override
    public RuntimeSnapshot snapshot() {
        RuntimeSnapshot qwen = qwenPaw.snapshot();
        RuntimeSnapshot scope = agentScope == null ? new RuntimeSnapshot(0, 0) : agentScope.snapshot();
        return new RuntimeSnapshot(qwen.running() + scope.running(), qwen.total() + scope.total());
    }

    @Override
    public void applyConfig(RuntimeConfigSnapshot snapshot) {
        qwenPaw.applyConfig(snapshot);
        if (agentScope != null) {
            agentScope.applyConfig(snapshot);
        }
    }

    @Override
    public void stop() {
        try {
            qwenPaw.stop();
        } finally {
            try {
                if (agentScope != null) {
                    agentScope.stop();
                }
            } finally {
                owners.clear();
            }
        }
    }

    private AgentRuntime select(RuntimeTask task) {
        if (!hasStableScope(task.metadata())) {
            return qwenPaw;
        }
        String selected = rollout.select(task.metadata());
        if (AgentScopeRolloutPolicy.AGENTSCOPE.equals(selected)) {
            return agentScope;
        }
        if (AgentScopeRolloutPolicy.QWENPAW.equals(selected)) {
            return qwenPaw;
        }
        return null;
    }

    private static boolean hasStableScope(Map<String, String> metadata) {
        for (String field : REQUIRED_SCOPE) {
            String value = metadata.get(field);
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static AgentRuntimeContext withRuntimeName(AgentRuntimeContext context, String runtimeName) {
        return new AgentRuntimeContext(runtimeName, context.maxConcurrency(), context.clock(),
                context.resultSink(), context.configuration());
    }
}
