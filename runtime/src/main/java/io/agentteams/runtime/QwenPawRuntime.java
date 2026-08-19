package io.agentteams.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Runtime adapter boundary for an external QwenPaw process or sidecar. */
public final class QwenPawRuntime implements AgentRuntime {
    private final QwenPawProcessPort process;
    private final FakeRuntime state = new FakeRuntime();
    private AgentRuntimeContext context;

    public QwenPawRuntime(QwenPawProcessPort process) {
        this.process = Objects.requireNonNull(process, "process");
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
            process.start(context, context.resultSink());
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
                process.submit(task);
            } catch (RuntimeException error) {
                context.resultSink().accept(RuntimeResult.failure(task.id(), "runtime submission failed",
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
            context = null;
        }
    }
}
