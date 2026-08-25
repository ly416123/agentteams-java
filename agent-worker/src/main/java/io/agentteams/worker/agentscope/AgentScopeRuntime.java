package io.agentteams.worker.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.CompletionStatus;
import io.agentteams.runtime.FakeRuntime;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeStatus;
import io.agentteams.runtime.RuntimeSubmission;
import io.agentteams.runtime.RuntimeTask;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import reactor.core.Disposable;

/**
 * AgentRuntime implementation backed by AgentScope Harness.
 *
 * <p>The existing FakeRuntime remains the state and concurrency authority for
 * this local adapter. AgentScope is limited to the in-flight execution
 * session; terminal results still flow through the existing result sink.</p>
 */
public final class AgentScopeRuntime implements AgentRuntime {
    private static final String FAILURE_MESSAGE = "AgentScope execution failed";

    private volatile FakeRuntime state = new FakeRuntime();
    private final AgentScopeHarnessFactory harnessFactory;
    private final Consumer<AgentScopeExecutionEvent> eventSink;
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private volatile AgentRuntimeContext context;

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory) {
        this(harnessFactory, ignored -> { });
    }

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory,
            Consumer<AgentScopeExecutionEvent> eventSink) {
        this.harnessFactory = Objects.requireNonNull(harnessFactory, "harnessFactory");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    @Override
    public synchronized void start(AgentRuntimeContext context) {
        Objects.requireNonNull(context, "context");
        state.start(context);
        this.context = context;
    }

    @Override
    public RuntimeSubmission submit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        AgentRuntimeContext currentContext = requireContext();
        RuntimeSubmission submission = state.submit(task);
        if (!submission.accepted()) {
            return submission;
        }
        try {
            startExecution(task, currentContext);
        } catch (RuntimeException error) {
            completeFailure(task.id());
        }
        return submission;
    }

    private void startExecution(RuntimeTask task, AgentRuntimeContext currentContext) {
        String attemptId = requiredMetadata(task, "attemptId");
        String leaseId = requiredMetadata(task, "leaseId");
        String correlationId = task.metadata().getOrDefault("correlationId", task.id().toString());
        AgentScopeEventTranslator translator = new AgentScopeEventTranslator(
                task.id().toString(), attemptId, leaseId, correlationId, currentContext.runtimeName());
        HarnessAgent agent = Objects.requireNonNull(harnessFactory.create(task, currentContext),
                "harnessFactory returned null");
        Execution execution = new Execution(task, agent, translator, attemptId, currentContext);
        Execution previous = executions.putIfAbsent(task.id(), execution);
        if (previous != null) {
            close(execution);
            throw new IllegalStateException("task already has an AgentScope execution");
        }
        try {
            RuntimeContext agentContext = RuntimeContext.builder()
                    .sessionId(attemptId)
                    .userId(task.metadata().getOrDefault("agentId", "agent-worker"))
                    .put("taskId", task.id().toString())
                    .put("attemptId", attemptId)
                    .put("leaseId", leaseId)
                    .put("correlationId", correlationId)
                    .put("runtime", currentContext.runtimeName())
                    .build();
            Disposable disposable = agent.streamEvents(new UserMessage(task.inputJson()), agentContext)
                    .subscribe(event -> onEvent(execution, event),
                            error -> onStreamError(execution),
                            () -> onStreamComplete(execution));
            execution.disposable = disposable;
        } catch (RuntimeException error) {
            executions.remove(task.id(), execution);
            close(execution);
            throw error;
        }
    }

    private void onEvent(Execution execution, AgentEvent originalEvent) {
        if (execution.closed.get()) {
            return;
        }
        try {
            AgentEvent event = originalEvent.withMetadataEntry("attemptId", execution.attemptId);
            AgentScopeExecutionEvent translated = execution.translator.translate(event);
            eventSink.accept(translated);
            if (translated.duplicate()) {
                return;
            }
            if (originalEvent instanceof AgentResultEvent) {
                execution.resultCandidate = execution.translator.safeResultCandidate(event);
            }
            if (translated.kind() == AgentScopeExecutionEvent.Kind.STALE) {
                completeFailure(execution, FAILURE_MESSAGE);
            } else if (translated.kind() == AgentScopeExecutionEvent.Kind.ERROR) {
                completeFailure(execution, FAILURE_MESSAGE);
            } else if (translated.kind() == AgentScopeExecutionEvent.Kind.AGENT_ENDED) {
                completeSuccess(execution);
            }
        } catch (RuntimeException error) {
            completeFailure(execution, FAILURE_MESSAGE);
        }
    }

    private void onStreamError(Execution execution) {
        completeFailure(execution, FAILURE_MESSAGE);
    }

    private void onStreamComplete(Execution execution) {
        if (!execution.terminalSubmitted.get() && !execution.closed.get()) {
            completeFailure(execution, FAILURE_MESSAGE);
        }
    }

    private void completeSuccess(Execution execution) {
        complete(execution, RuntimeResult.success(execution.task.id(),
                execution.resultCandidate == null ? "" : execution.resultCandidate,
                Instant.now(context().clock())));
    }

    private void completeFailure(UUID taskId) {
        Execution execution = executions.get(taskId);
        if (execution == null) {
            state.complete(RuntimeResult.failure(taskId, FAILURE_MESSAGE, Instant.now(context().clock())));
            return;
        }
        completeFailure(execution, FAILURE_MESSAGE);
    }

    private void completeFailure(Execution execution, String message) {
        complete(execution, RuntimeResult.failure(execution.task.id(), message,
                Instant.now(context().clock())));
    }

    private void complete(Execution execution, RuntimeResult result) {
        if (!execution.terminalSubmitted.compareAndSet(false, true)) {
            return;
        }
        try {
            state.complete(result);
        } finally {
            executions.remove(execution.task.id(), execution);
            close(execution);
        }
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        CompletionStatus status = state.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            Execution execution = executions.remove(result.taskId());
            if (execution != null) {
                close(execution);
            }
        }
        return status;
    }

    @Override
    public boolean cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        Execution execution = executions.remove(taskId);
        if (execution != null) {
            execution.cancel();
        }
        return state.cancel(taskId);
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
        state.applyConfig(snapshot);
    }

    @Override
    public synchronized void stop() {
        for (Execution execution : executions.values()) {
            execution.cancel();
        }
        executions.clear();
        if (context != null) {
            state.stop();
            state = new FakeRuntime();
            context = null;
        }
    }

    private AgentRuntimeContext requireContext() {
        AgentRuntimeContext current = context;
        if (current == null) {
            throw new IllegalStateException("runtime is not started");
        }
        return current;
    }

    private AgentRuntimeContext context() {
        return requireContext();
    }

    private static String requiredMetadata(RuntimeTask task, String name) {
        String value = task.metadata().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task metadata must contain " + name);
        }
        return value;
    }

    private static void close(Execution execution) {
        if (!execution.closed.compareAndSet(false, true)) {
            return;
        }
        Disposable disposable = execution.disposable;
        if (disposable != null) {
            disposable.dispose();
        }
        execution.translator.clear();
        try {
            execution.agent.interrupt();
        } finally {
            execution.agent.close();
        }
    }

    private static final class Execution {
        private final RuntimeTask task;
        private final HarnessAgent agent;
        private final AgentScopeEventTranslator translator;
        private final String attemptId;
        @SuppressWarnings("unused")
        private final AgentRuntimeContext context;
        private final AtomicBoolean terminalSubmitted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Disposable disposable;
        private volatile String resultCandidate;

        private Execution(RuntimeTask task, HarnessAgent agent, AgentScopeEventTranslator translator,
                String attemptId, AgentRuntimeContext context) {
            this.task = task;
            this.agent = agent;
            this.translator = translator;
            this.attemptId = attemptId;
            this.context = context;
        }

        private void cancel() {
            close(this);
        }
    }
}
