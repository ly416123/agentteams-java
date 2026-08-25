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
import java.util.concurrent.atomic.AtomicReference;
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

    private final Object lifecycleLock = new Object();
    private volatile FakeRuntime state = new FakeRuntime();
    private final AgentScopeHarnessFactory harnessFactory;
    private final Consumer<AgentScopeExecutionEvent> eventSink;
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private AgentRuntimeContext context;
    private long generation;

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory) {
        this(harnessFactory, ignored -> { });
    }

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory,
            Consumer<AgentScopeExecutionEvent> eventSink) {
        this.harnessFactory = Objects.requireNonNull(harnessFactory, "harnessFactory");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    @Override
    public void start(AgentRuntimeContext context) {
        Objects.requireNonNull(context, "context");
        synchronized (lifecycleLock) {
            state.start(context);
            this.context = context;
            generation++;
        }
    }

    @Override
    public RuntimeSubmission submit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        synchronized (lifecycleLock) {
            AgentRuntimeContext currentContext = requireContextLocked();
            RuntimeSubmission submission = state.submit(task);
            if (!submission.accepted()) {
                return submission;
            }
            try {
                startExecutionLocked(task, currentContext, generation);
            } catch (RuntimeException error) {
                completeFailureLocked(task.id());
            }
            return submission;
        }
    }

    private void startExecutionLocked(RuntimeTask task, AgentRuntimeContext currentContext,
            long currentGeneration) {
        String attemptId = requiredMetadata(task, "attemptId");
        String leaseId = requiredMetadata(task, "leaseId");
        String correlationId = task.metadata().getOrDefault("correlationId", task.id().toString());
        AgentScopeEventTranslator translator = new AgentScopeEventTranslator(
                task.id().toString(), attemptId, leaseId, correlationId, currentContext.runtimeName());
        HarnessAgent agent = Objects.requireNonNull(harnessFactory.create(task, currentContext),
                "harnessFactory returned null");
        Execution execution = new Execution(task, agent, translator, attemptId, currentGeneration);
        Execution previous = executions.putIfAbsent(task.id(), execution);
        if (previous != null) {
            execution.close();
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
            execution.installDisposable(disposable);
        } catch (RuntimeException error) {
            executions.remove(task.id(), execution);
            execution.close();
            throw error;
        }
    }

    private void onEvent(Execution execution, AgentEvent originalEvent) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(execution)) {
                return;
            }
            try {
                AgentEvent event = withAttemptIfMissing(originalEvent, execution.attemptId);
                AgentScopeExecutionEvent translated = execution.translator.translate(event);
                if (!isCurrentLocked(execution)) {
                    return;
                }
                eventSink.accept(translated);
                if (!isCurrentLocked(execution) || translated.duplicate()) {
                    return;
                }
                if (originalEvent instanceof AgentResultEvent) {
                    execution.resultCandidate = execution.translator.safeResultCandidate(event);
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.STALE
                        || translated.kind() == AgentScopeExecutionEvent.Kind.ERROR) {
                    completeFailureLocked(execution, FAILURE_MESSAGE);
                } else if (translated.kind() == AgentScopeExecutionEvent.Kind.AGENT_ENDED) {
                    completeSuccessLocked(execution);
                }
            } catch (RuntimeException error) {
                if (isCurrentLocked(execution)) {
                    completeFailureLocked(execution, FAILURE_MESSAGE);
                }
            }
        }
    }

    private void onStreamError(Execution execution) {
        synchronized (lifecycleLock) {
            if (isCurrentLocked(execution)) {
                completeFailureLocked(execution, FAILURE_MESSAGE);
            }
        }
    }

    private void onStreamComplete(Execution execution) {
        synchronized (lifecycleLock) {
            if (isCurrentLocked(execution)) {
                completeFailureLocked(execution, FAILURE_MESSAGE);
            }
        }
    }

    private void completeSuccessLocked(Execution execution) {
        AgentRuntimeContext currentContext = requireContextLocked();
        completeLocked(execution, RuntimeResult.success(execution.task.id(),
                execution.resultCandidate == null ? "" : execution.resultCandidate,
                Instant.now(currentContext.clock())));
    }

    private void completeFailureLocked(UUID taskId) {
        AgentRuntimeContext currentContext = requireContextLocked();
        state.complete(RuntimeResult.failure(taskId, FAILURE_MESSAGE, Instant.now(currentContext.clock())));
    }

    private void completeFailureLocked(Execution execution, String message) {
        AgentRuntimeContext currentContext = requireContextLocked();
        completeLocked(execution, RuntimeResult.failure(execution.task.id(), message,
                Instant.now(currentContext.clock())));
    }

    private CompletionStatus completeLocked(Execution execution, RuntimeResult result) {
        if (!execution.terminalSubmitted.compareAndSet(false, true)) {
            return CompletionStatus.DUPLICATE;
        }
        CompletionStatus status;
        try {
            status = state.complete(result);
        } catch (RuntimeException error) {
            execution.terminalSubmitted.set(false);
            throw error;
        }
        if (status == CompletionStatus.COMPLETED) {
            executions.remove(execution.task.id(), execution);
            execution.close();
        } else {
            execution.terminalSubmitted.set(false);
        }
        return status;
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        synchronized (lifecycleLock) {
            Execution execution = executions.get(result.taskId());
            if (execution == null) {
                return state.complete(result);
            }
            return completeLocked(execution, result);
        }
    }

    @Override
    public boolean cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        synchronized (lifecycleLock) {
            Execution execution = executions.remove(taskId);
            if (execution != null) {
                execution.close();
            }
            return state.cancel(taskId);
        }
    }

    @Override
    public Optional<RuntimeStatus> status(UUID taskId) {
        synchronized (lifecycleLock) {
            return state.status(taskId);
        }
    }

    @Override
    public RuntimeSnapshot snapshot() {
        synchronized (lifecycleLock) {
            return state.snapshot();
        }
    }

    @Override
    public void applyConfig(RuntimeConfigSnapshot snapshot) {
        synchronized (lifecycleLock) {
            state.applyConfig(snapshot);
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            generation++;
            for (Execution execution : executions.values()) {
                execution.close();
            }
            executions.clear();
            if (context != null) {
                state.stop();
                state = new FakeRuntime();
                context = null;
            }
        }
    }

    private AgentRuntimeContext requireContextLocked() {
        if (context == null) {
            throw new IllegalStateException("runtime is not started");
        }
        return context;
    }

    private boolean isCurrentLocked(Execution execution) {
        return context != null
                && execution.generation == generation
                && !execution.closed.get()
                && !execution.terminalSubmitted.get()
                && executions.get(execution.task.id()) == execution;
    }

    private static AgentEvent withAttemptIfMissing(AgentEvent event, String attemptId) {
        Objects.requireNonNull(event, "event");
        Map<String, Object> metadata = event.getMetadata();
        if (metadata != null && metadata.containsKey("attemptId")) {
            return event;
        }
        return event.withMetadataEntry("attemptId", attemptId);
    }

    private static String requiredMetadata(RuntimeTask task, String name) {
        String value = task.metadata().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task metadata must contain " + name);
        }
        return value;
    }

    static final class Execution {
        private final RuntimeTask task;
        private final HarnessAgent agent;
        private final AgentScopeEventTranslator translator;
        private final String attemptId;
        private final long generation;
        private final AtomicBoolean terminalSubmitted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private volatile String resultCandidate;

        Execution(RuntimeTask task, HarnessAgent agent, AgentScopeEventTranslator translator,
                String attemptId, long generation) {
            this.task = task;
            this.agent = agent;
            this.translator = translator;
            this.attemptId = attemptId;
            this.generation = generation;
        }

        boolean installDisposable(Disposable candidate) {
            Objects.requireNonNull(candidate, "candidate");
            if (closed.get() || !disposable.compareAndSet(null, candidate)) {
                disposeQuietly(candidate);
                return false;
            }
            if (closed.get() && disposable.compareAndSet(candidate, null)) {
                disposeQuietly(candidate);
                return false;
            }
            return true;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Disposable current = disposable.getAndSet(null);
            disposeQuietly(current);
            runCleanup("translator", translator::clear);
            runCleanup("interrupt", agent::interrupt);
            runCleanup("agent", agent::close);
        }

        private static void disposeQuietly(Disposable disposable) {
            if (disposable != null) {
                runCleanup("stream", disposable::dispose);
            }
        }

        private static void runCleanup(String component, Runnable cleanup) {
            try {
                cleanup.run();
            } catch (RuntimeException ignored) {
                // Do not expose provider, prompt, credential, or filesystem details.
                System.err.println("AgentScope cleanup failed component=" + component);
            }
        }
    }
}
