package io.agentteams.worker.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
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
import java.time.format.DateTimeParseException;
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
    private final WorkspaceActiveGuard workspaceActiveGuard;
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private AgentRuntimeContext context;
    private long generation;

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory) {
        this(harnessFactory, ignored -> { }, WorkspaceActiveGuard.noop());
    }

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory,
            Consumer<AgentScopeExecutionEvent> eventSink) {
        this(harnessFactory, eventSink, WorkspaceActiveGuard.noop());
    }

    public AgentScopeRuntime(AgentScopeHarnessFactory harnessFactory,
            Consumer<AgentScopeExecutionEvent> eventSink, WorkspaceActiveGuard workspaceActiveGuard) {
        this.harnessFactory = Objects.requireNonNull(harnessFactory, "harnessFactory");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.workspaceActiveGuard = Objects.requireNonNull(workspaceActiveGuard, "workspaceActiveGuard");
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
            expireLeasesLocked();
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
        // Validate lease metadata before creating a provider Agent, so malformed
        // expiry metadata cannot leave an untracked Harness resource behind.
        parseLeaseExpiresAt(task.metadata().get("leaseExpiresAt"));
        AgentScopeEventTranslator translator = new AgentScopeEventTranslator(
                task.id().toString(), attemptId, leaseId, correlationId, currentContext.runtimeName());
        HarnessAgent agent = Objects.requireNonNull(harnessFactory.create(task, currentContext),
                "harnessFactory returned null");
        Execution execution = new Execution(task, agent, translator, attemptId, leaseId, currentGeneration);
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
            expireLeasesLocked();
            if (!isCurrentLocked(execution)) {
                return;
            }
            try {
                workspaceActiveGuard.assertActive(execution.task, context);
            } catch (RuntimeException error) {
                publishStaleAndFenceLocked(execution, originalEvent);
                return;
            }
            try {
                AgentEvent event = withExecutionMetadataIfMissing(originalEvent,
                        execution.attemptId, execution.leaseId);
                AgentScopeExecutionEvent translated = execution.translator.translate(event);
                if (!isCurrentLocked(execution)) {
                    return;
                }
                eventSink.accept(translated);
                if (!isCurrentLocked(execution) || translated.duplicate()) {
                    return;
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.AGENT_RESULT
                        && !translated.duplicate()) {
                    execution.resultCandidate = execution.translator.safeResultCandidate(event);
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.ERROR) {
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
            expireLeasesLocked();
            if (isCurrentLocked(execution)) {
                completeFailureLocked(execution, FAILURE_MESSAGE);
            }
        }
    }

    private void onStreamComplete(Execution execution) {
        synchronized (lifecycleLock) {
            expireLeasesLocked();
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
        return completeLocked(execution, result, true);
    }

    private CompletionStatus completeLocked(Execution execution, RuntimeResult result, boolean enforceWorkspaceGate) {
        if (!execution.terminalSubmitted.compareAndSet(false, true)) {
            return CompletionStatus.DUPLICATE;
        }
        CompletionStatus status;
        try {
            if (enforceWorkspaceGate) {
                workspaceActiveGuard.assertActive(execution.task, context);
            }
        } catch (RuntimeException error) {
            executions.remove(execution.task.id(), execution);
            execution.close();
            if (!enforceWorkspaceGate) throw error;
            return completeFencedLocked(execution);
        }
        try {
            status = state.complete(result);
            // FakeRuntime records the terminal state before resultSink runs.
            // Keep the CAS closed and clean every resource even when the sink
            // rejects the callback, otherwise a terminal task can be retried
            // with a live orphaned Harness session.
            if (status == CompletionStatus.COMPLETED) {
                executions.remove(execution.task.id(), execution);
                execution.close();
            } else {
                execution.terminalSubmitted.set(false);
            }
            return status;
        } catch (RuntimeException error) {
            executions.remove(execution.task.id(), execution);
            execution.close();
            throw error;
        }
    }

    private CompletionStatus completeFencedLocked(Execution execution) {
        try {
            CompletionStatus status = state.complete(RuntimeResult.failure(execution.task.id(),
                    "Sandbox workspace is stale", Instant.now(context.clock())));
            executions.remove(execution.task.id(), execution);
            execution.close();
            return status;
        } catch (RuntimeException ignored) {
            executions.remove(execution.task.id(), execution);
            execution.close();
            return CompletionStatus.COMPLETED;
        }
    }

    private void publishStaleAndFenceLocked(Execution execution, AgentEvent originalEvent) {
        AgentScopeExecutionEvent stale = new AgentScopeExecutionEvent(execution.task.id().toString(),
                execution.attemptId, execution.leaseId, originalEvent.getId(),
                execution.task.metadata().getOrDefault("correlationId", execution.task.id().toString()),
                context.runtimeName(), AgentScopeExecutionEvent.Kind.STALE,
                "sandbox workspace is stale", false, false, false);
        try {
            eventSink.accept(stale);
        } finally {
            completeLocked(execution, RuntimeResult.failure(execution.task.id(),
                    "Sandbox workspace is stale", Instant.now(context.clock())), false);
        }
    }

    @Override
    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        synchronized (lifecycleLock) {
            expireLeasesLocked();
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
            expireLeasesLocked();
            return state.status(taskId);
        }
    }

    @Override
    public RuntimeSnapshot snapshot() {
        synchronized (lifecycleLock) {
            expireLeasesLocked();
            return state.snapshot();
        }
    }

    /**
     * Closes active executions whose lease has expired according to the injected
     * runtime clock. Callers can invoke this from an existing lifecycle tick;
     * this runtime deliberately does not create a background scheduler.
     */
    public void expireLeases() {
        synchronized (lifecycleLock) {
            expireLeasesLocked();
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

    private static AgentEvent withExecutionMetadataIfMissing(AgentEvent event, String attemptId,
            String leaseId) {
        Objects.requireNonNull(event, "event");
        AgentEvent enriched = event;
        Map<String, Object> metadata = enriched.getMetadata();
        if (metadata == null || !metadata.containsKey("attemptId")) {
            enriched = enriched.withMetadataEntry("attemptId", attemptId);
        }
        metadata = enriched.getMetadata();
        if (metadata == null || !metadata.containsKey("leaseId")) {
            enriched = enriched.withMetadataEntry("leaseId", leaseId);
        }
        return enriched;
    }

    private void expireLeasesLocked() {
        if (context == null) {
            return;
        }
        Instant now = context.clock().instant();
        for (Execution execution : executions.values()) {
            Instant expiresAt = execution.leaseExpiresAt;
            if (expiresAt != null && !now.isBefore(expiresAt)
                    && executions.remove(execution.task.id(), execution)) {
                execution.close();
                state.cancel(execution.task.id());
            }
        }
    }

    private static String requiredMetadata(RuntimeTask task, String name) {
        String value = task.metadata().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task metadata must contain " + name);
        }
        return value;
    }

    private static Instant parseLeaseExpiresAt(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("task metadata leaseExpiresAt must be ISO-8601");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    "task metadata leaseExpiresAt must be ISO-8601", error);
        }
    }

    static final class Execution {
        private final RuntimeTask task;
        private final HarnessAgent agent;
        private final AgentScopeEventTranslator translator;
        private final String attemptId;
        private final String leaseId;
        private final Instant leaseExpiresAt;
        private final long generation;
        private final AtomicBoolean terminalSubmitted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private volatile String resultCandidate;

        Execution(RuntimeTask task, HarnessAgent agent, AgentScopeEventTranslator translator,
                String attemptId, String leaseId, long generation) {
            this.task = task;
            this.agent = agent;
            this.translator = translator;
            this.attemptId = attemptId;
            this.leaseId = leaseId;
            this.leaseExpiresAt = parseLeaseExpiresAt(task.metadata().get("leaseExpiresAt"));
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
