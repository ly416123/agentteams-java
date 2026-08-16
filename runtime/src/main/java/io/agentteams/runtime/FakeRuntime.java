package io.agentteams.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deterministic runtime used by tests and local protocol simulations. */
public final class FakeRuntime implements AgentRuntime {
    private final Map<UUID, RuntimeStatus> tasks = new LinkedHashMap<>();
    private AgentRuntimeContext context;
    private int running;

    @Override
    public synchronized void start(AgentRuntimeContext context) {
        if (this.context != null) {
            throw new IllegalStateException("runtime is already started");
        }
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public synchronized RuntimeSubmission submit(RuntimeTask task) {
        requireStarted();
        Objects.requireNonNull(task, "task");
        RuntimeStatus existing = tasks.get(task.id());
        if (existing != null) {
            return RuntimeSubmission.rejected("task already exists");
        }
        if (running >= context.maxConcurrency()) {
            return RuntimeSubmission.rejected("maximum concurrency reached");
        }
        tasks.put(task.id(), new RuntimeStatus(task, RuntimeTaskState.RUNNING, null));
        running++;
        return RuntimeSubmission.acceptedSubmission();
    }

    @Override
    public synchronized CompletionStatus complete(RuntimeResult result) {
        requireStarted();
        Objects.requireNonNull(result, "result");
        RuntimeStatus current = tasks.get(result.taskId());
        if (current == null) {
            return CompletionStatus.UNKNOWN_TASK;
        }
        if (current.state() != RuntimeTaskState.RUNNING) {
            return CompletionStatus.DUPLICATE;
        }
        RuntimeTaskState state = result.success() ? RuntimeTaskState.SUCCEEDED : RuntimeTaskState.FAILED;
        tasks.put(result.taskId(), new RuntimeStatus(current.task(), state, result));
        running--;
        context.resultSink().accept(result);
        return CompletionStatus.COMPLETED;
    }

    @Override
    public synchronized boolean cancel(UUID taskId) {
        requireStarted();
        RuntimeStatus current = tasks.get(taskId);
        if (current == null || current.state() != RuntimeTaskState.RUNNING) {
            return false;
        }
        tasks.put(taskId, new RuntimeStatus(current.task(), RuntimeTaskState.CANCELLED, null));
        running--;
        return true;
    }

    @Override
    public synchronized Optional<RuntimeStatus> status(UUID taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized RuntimeSnapshot snapshot() {
        return new RuntimeSnapshot(running, tasks.size());
    }

    @Override
    public synchronized void stop() {
        if (context == null) {
            return;
        }
        context = null;
        running = 0;
    }

    private void requireStarted() {
        if (context == null) {
            throw new IllegalStateException("runtime is not started");
        }
    }
}
