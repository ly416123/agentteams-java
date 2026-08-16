package io.agentteams.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Runtime adapter boundary for an external QwenPaw process or sidecar. */
public final class QwenPawRuntime implements AgentRuntime {
    private final QwenPawProcessPort process;
    private final FakeRuntime state = new FakeRuntime();

    public QwenPawRuntime(QwenPawProcessPort process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    @Override
    public void start(AgentRuntimeContext context) {
        state.start(context);
        try {
            process.start(context, state::complete);
        } catch (RuntimeException error) {
            state.stop();
            throw error;
        }
    }

    @Override
    public RuntimeSubmission submit(RuntimeTask task) {
        RuntimeSubmission submission = state.submit(task);
        if (submission.accepted()) {
            try {
                process.submit(task);
            } catch (RuntimeException error) {
                state.complete(RuntimeResult.failure(task.id(), "runtime submission failed",
                        java.time.Instant.now()));
                throw error;
            }
        }
        return submission;
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        return state.complete(result);
    }

    @Override
    public boolean cancel(UUID taskId) {
        boolean cancelled = state.cancel(taskId);
        if (cancelled) {
            process.cancel(taskId);
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
    public void stop() {
        try {
            process.stop();
        } finally {
            state.stop();
        }
    }
}
