package io.agentteams.worker.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.CompletionStatus;
import io.agentteams.runtime.FakeRuntime;
import io.agentteams.runtime.RuntimeCallUsage;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.agentteams.runtime.RuntimeEvent;
import io.agentteams.runtime.RuntimeEventSink;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeStatus;
import io.agentteams.runtime.RuntimeSubmission;
import io.agentteams.runtime.RuntimeTask;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String REQUIRED_TOOL_FAILURE_PREFIX = "REQUIRED_TOOL_NOT_CALLED: ";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(AgentScopeRuntime.class.getName());

    private final Object lifecycleLock = new Object();
    private volatile FakeRuntime state = new FakeRuntime();
    private final AgentScopeHarnessFactory harnessFactory;
    private final Consumer<AgentScopeExecutionEvent> eventSink;
    private final WorkspaceActiveGuard workspaceActiveGuard;
    private volatile RuntimeEventSink runtimeEventSink = event -> { };
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeResult> pendingTerminalResults = new ConcurrentHashMap<>();
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
    public void setEventSink(RuntimeEventSink eventSink) {
        this.runtimeEventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /** Publishes AgentScope tool lifecycle events through the common worker event sink. */
    void reportRuntimeEvent(AgentScopeExecutionEvent event, int sequence) {
        Objects.requireNonNull(event, "event");
        String eventType;
        if (event.kind() == AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED) {
            eventType = "tool.called";
        } else if (event.kind() == AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED) {
            eventType = "tool.finished";
        } else {
            return;
        }
        String tool = toolName(event.safeMessage());
        String callId = event.safeToolCallId() == null
                ? ""
                : ",\"callId\":\"" + jsonEscape(event.safeToolCallId()) + "\"";
        String payload;
        if (eventType.equals("tool.called")) {
            payload = "{\"tool\":\"" + jsonEscape(tool) + "\"" + callId
                    + ",\"sequence\":" + sequence + "}";
        } else if (event.success()) {
            payload = "{\"tool\":\"" + jsonEscape(tool) + "\"" + callId
                    + ",\"ok\":true,\"sequence\":"
                    + sequence + "}";
        } else {
            payload = "{\"tool\":\"" + jsonEscape(tool) + "\"" + callId
                    + ",\"ok\":false,\"errorCategory\":\""
                    + jsonEscape(toolErrorCategory(event.safeMessage())) + "\",\"sequence\":" + sequence + "}";
        }
        try {
            runtimeEventSink.accept(new RuntimeEvent(UUID.fromString(event.taskId()), eventType,
                    payload, Instant.now(requireContextLocked().clock())));
        } catch (RuntimeException error) {
            // Middle-of-execution reporting is best-effort and must not fail the task,
            // but the boundary failure must remain diagnosable in production.
            LOG.log(System.Logger.Level.WARNING,
                    "AgentScope runtime event delivery failed task=" + event.taskId()
                            + " type=" + eventType + " tool=" + tool + " reason=" + rootMessage(error));
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
                state.cancel(task.id());
                releaseBinding(task.id());
                LOG.log(System.Logger.Level.WARNING,
                        "AgentScope task rejected before acceptance task=" + task.id()
                                + " reason=" + rootMessage(error));
                return RuntimeSubmission.rejected("RUNTIME_UNAVAILABLE");
            }
            return submission;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void startExecutionLocked(RuntimeTask task, AgentRuntimeContext currentContext,
            long currentGeneration) {
        String attemptId = requiredMetadata(task, "attemptId");
        String leaseId = requiredMetadata(task, "leaseId");
        String correlationId = task.metadata().getOrDefault("correlationId", task.id().toString());
        Set<String> requiredMcpTools = requiredMcpTools(task.inputJson());
        // Validate lease metadata before creating a provider Agent, so malformed
        // expiry metadata cannot leave an untracked Harness resource behind.
        parseLeaseExpiresAt(task.metadata().get("leaseExpiresAt"));
        AgentScopeEventTranslator translator = new AgentScopeEventTranslator(
                task.id().toString(), attemptId, leaseId, correlationId, currentContext.runtimeName());
        HarnessAgent agent = Objects.requireNonNull(harnessFactory.create(task, currentContext),
                "harnessFactory returned null");
        Execution execution = new Execution(task, agent, translator, attemptId, leaseId, currentGeneration,
                requiredMcpTools);
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
            Disposable disposable = agent.streamEvents(new UserMessage(agentInput(task.inputJson(), requiredMcpTools)), agentContext)
                    .subscribe(event -> onEvent(execution, event),
                    error -> onStreamError(execution, error),
                            () -> onStreamComplete(execution));
            execution.installDisposable(disposable);
        } catch (RuntimeException error) {
            executions.remove(task.id(), execution);
            execution.close();
            releaseBinding(task.id());
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
                execution.recordUsage(modelCallUsage(event, context));
                eventSink.accept(translated);
                if (!isCurrentLocked(execution) || translated.duplicate()) {
                    return;
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED
                        || translated.kind() == AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED) {
                    reportRuntimeEvent(translated, execution.toolEventSequence.incrementAndGet());
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED
                        && translated.success()) {
                    String tool = toolName(translated.safeMessage());
                    if (!"unknown".equals(tool)) {
                        execution.completedMcpTools.add(tool);
                    }
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.AGENT_RESULT
                        && !translated.duplicate()) {
                    execution.resultCandidate = execution.translator.safeResultCandidate(event);
                }
                if (translated.kind() == AgentScopeExecutionEvent.Kind.ERROR) {
                    LOG.log(System.Logger.Level.WARNING,
                            "AgentScope emitted an error event task=" + execution.task.id()
                                    + " type=" + originalEvent.getClass().getSimpleName());
                    completeFailureLocked(execution, FAILURE_MESSAGE);
                } else if (translated.kind() == AgentScopeExecutionEvent.Kind.AGENT_ENDED) {
                    completeRequiredToolsOrSuccessLocked(execution);
                }
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING,
                        "AgentScope event processing failed task=" + execution.task.id()
                                + " reason=" + rootMessage(error));
                if (isCurrentLocked(execution)) {
                    completeFailureLocked(execution, FAILURE_MESSAGE);
                }
            }
        }
    }

    private void onStreamError(Execution execution, Throwable error) {
        synchronized (lifecycleLock) {
            expireLeasesLocked();
            if (isCurrentLocked(execution)) {
                LOG.log(System.Logger.Level.WARNING,
                        "AgentScope stream failed task=" + execution.task.id()
                                + " reason=" + rootMessage(error));
                completeFailureLocked(execution, FAILURE_MESSAGE);
            }
        }
    }

    private void onStreamComplete(Execution execution) {
        synchronized (lifecycleLock) {
            expireLeasesLocked();
            if (isCurrentLocked(execution)) {
                LOG.log(System.Logger.Level.WARNING,
                        "AgentScope stream completed without a terminal result task=" + execution.task.id());
                completeFailureLocked(execution, FAILURE_MESSAGE);
            }
        }
    }

    private void completeSuccessLocked(Execution execution) {
        AgentRuntimeContext currentContext = requireContextLocked();
        completeLocked(execution, RuntimeResult.success(execution.task.id(),
                execution.resultCandidate == null ? "" : execution.resultCandidate,
                Instant.now(currentContext.clock()), execution.callUsage));
    }

    private void completeRequiredToolsOrSuccessLocked(Execution execution) {
        if (!execution.requiredMcpTools.isEmpty()) {
            List<String> missing = execution.requiredMcpTools.stream()
                    .filter(tool -> !execution.completedMcpTools.contains(tool))
                    .toList();
            if (!missing.isEmpty()) {
                completeFailureLocked(execution, REQUIRED_TOOL_FAILURE_PREFIX + String.join(",", missing));
                return;
            }
        }
        completeSuccessLocked(execution);
    }

    private void completeFailureLocked(UUID taskId) {
        AgentRuntimeContext currentContext = requireContextLocked();
        state.complete(RuntimeResult.failure(taskId, FAILURE_MESSAGE, Instant.now(currentContext.clock())));
    }

    private void completeFailureLocked(Execution execution, String message) {
        AgentRuntimeContext currentContext = requireContextLocked();
        completeLocked(execution, RuntimeResult.failure(execution.task.id(), message,
                Instant.now(currentContext.clock()), execution.callUsage));
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
            releaseBinding(execution.task.id());
            if (!enforceWorkspaceGate) throw error;
            return completeFencedLocked(execution);
        }
        try {
            pendingTerminalResults.put(execution.task.id(), result);
            status = state.complete(result);
            // FakeRuntime records the terminal state before resultSink runs.
            // Keep the CAS closed and clean every resource even when the sink
            // rejects the callback, otherwise a terminal task can be retried
            // with a live orphaned Harness session.
            if (status == CompletionStatus.COMPLETED) {
                executions.remove(execution.task.id(), execution);
                execution.close();
                releaseBinding(execution.task.id());
                pendingTerminalResults.remove(execution.task.id(), result);
            } else {
                execution.terminalSubmitted.set(false);
                pendingTerminalResults.remove(execution.task.id(), result);
            }
            return status;
        } catch (RuntimeException error) {
            executions.remove(execution.task.id(), execution);
            execution.close();
            releaseBinding(execution.task.id());
            pendingTerminalResults.remove(execution.task.id(), result);
            throw error;
        }
    }

    private CompletionStatus completeFencedLocked(Execution execution) {
        try {
            CompletionStatus status = state.complete(RuntimeResult.failure(execution.task.id(),
                    "Sandbox workspace is stale", Instant.now(context.clock())));
            executions.remove(execution.task.id(), execution);
            execution.close();
            releaseBinding(execution.task.id());
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
            RuntimeResult pending = pendingTerminalResults.get(result.taskId());
            if (pending != null && pending.equals(result)) {
                pendingTerminalResults.remove(result.taskId(), pending);
                return CompletionStatus.COMPLETED;
            }
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
            pendingTerminalResults.remove(taskId);
            if (execution != null) {
                execution.close();
            }
            try {
                return state.cancel(taskId);
            } finally {
                releaseBinding(taskId);
            }
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
            harnessFactory.applyConfig(Objects.requireNonNull(snapshot, "snapshot"));
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
            pendingTerminalResults.clear();
            harnessFactory.releaseAll();
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

    private void releaseBinding(UUID taskId) {
        try {
            harnessFactory.release(taskId);
        } catch (RuntimeException ignored) {
            // Binding cleanup must not mask the terminal runtime outcome.
        }
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

    private static String toolName(String message) {
        if (message == null || !message.startsWith("tool ")) {
            return "unknown";
        }
        String candidate = message.substring("tool ".length());
        int suffix = candidate.indexOf(" started");
        if (suffix < 0) suffix = candidate.indexOf(" completed");
        if (suffix < 0) suffix = candidate.indexOf(" failed:");
        if (suffix >= 0) candidate = candidate.substring(0, suffix);
        return candidate.isBlank() ? "unknown" : candidate;
    }

    private static String toolErrorCategory(String message) {
        if (message == null) return "TOOL_EXECUTION_FAILED";
        int categoryStart = message.lastIndexOf(" failed: ");
        if (categoryStart < 0) return "TOOL_EXECUTION_FAILED";
        String category = message.substring(categoryStart + " failed: ".length());
        return switch (category) {
            case "TOOL_DENIED", "TOOL_INTERRUPTED", "TOOL_EXECUTION_FAILED" -> category;
            default -> "TOOL_EXECUTION_FAILED";
        };
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Reads the optional task contract without changing the existing plain-text task format. */
    private static Set<String> requiredMcpTools(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = JSON.readTree(inputJson);
            JsonNode required = root == null ? null : root.get("requiredMcpTools");
            if (required == null || required.isNull()) {
                return Set.of();
            }
            if (!required.isArray()) {
                throw new IllegalArgumentException("task input requiredMcpTools must be an array");
            }
            Set<String> tools = new LinkedHashSet<>();
            for (JsonNode item : required) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("task input requiredMcpTools must contain non-empty strings");
                }
                tools.add(item.asText().trim());
            }
            return Set.copyOf(tools);
        } catch (java.io.IOException error) {
            // Existing tasks may carry arbitrary text in inputJson; only valid JSON objects
            // participate in the optional required-tool contract.
            return Set.of();
        }
    }

    private static String agentInput(String inputJson, Set<String> requiredMcpTools) {
        StringBuilder prefix = new StringBuilder();
        if (!requiredMcpTools.isEmpty()) {
            prefix.append("Before answering, you MUST call each of these MCP tools exactly as required by the task: ")
                    .append(String.join(", ", requiredMcpTools))
                    .append(". Do not claim that a tool was called unless the tool call actually succeeds.\n\n");
        }
        try {
            JsonNode root = JSON.readTree(inputJson);
            String phase = root == null ? "" : root.path("orchestrationPhase").asText("");
            if ("PLAN".equalsIgnoreCase(phase)) {
                prefix.append("You are the Team Leader in the planning phase. Analyze the user request and do not execute the subtasks. "
                        + "Return ONLY a JSON object with a plan array containing exactly two independent subtasks. "
                        + "Each item must have sequence (1 or 2), title, requiredCapabilities (array), and dependsOn (array of sequence numbers). "
                        + "Use dependsOn=[] for independent work.\n\n");
            } else if ("AGGREGATE".equalsIgnoreCase(phase)) {
                prefix.append("You are the Team Leader in the aggregation phase. Use the childResults in the input, synthesize their outputs, "
                        + "and return ONLY a JSON object with an artifacts array. Each artifact must have name and content. "
                        + "Include a concise final answer in output.md.\n\n");
            }
        } catch (java.io.IOException ignored) {
            // Existing plain-text tasks keep their original input contract.
        }
        String original = inputJson == null ? "" : inputJson;
        if (prefix.isEmpty()) {
            return original;
        }
        return original + "\n\n" + prefix;
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

    private static RuntimeCallUsage modelCallUsage(AgentEvent event, AgentRuntimeContext context) {
        if (!(event instanceof ModelCallEndEvent modelCallEnd) || modelCallEnd.getUsage() == null) {
            return null;
        }
        ChatUsage usage = modelCallEnd.getUsage();
        return new RuntimeCallUsage(
                context.configuration().getOrDefault("provider_id", "agentscope"),
                context.configuration().getOrDefault("model", "unknown"),
                0,
                Math.max(0, usage.getInputTokens()),
                Math.max(0, usage.getOutputTokens()));
    }

    static final class Execution {
        private final RuntimeTask task;
        private final HarnessAgent agent;
        private final AgentScopeEventTranslator translator;
        private final String attemptId;
        private final String leaseId;
        private final Instant leaseExpiresAt;
        private final long generation;
        private final Set<String> requiredMcpTools;
        private final Set<String> completedMcpTools = ConcurrentHashMap.newKeySet();
        private final AtomicInteger toolEventSequence = new AtomicInteger();
        private final AtomicBoolean terminalSubmitted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private volatile String resultCandidate;
        private volatile RuntimeCallUsage callUsage;

        Execution(RuntimeTask task, HarnessAgent agent, AgentScopeEventTranslator translator,
                String attemptId, String leaseId, long generation, Set<String> requiredMcpTools) {
            this.task = task;
            this.agent = agent;
            this.translator = translator;
            this.attemptId = attemptId;
            this.leaseId = leaseId;
            this.leaseExpiresAt = parseLeaseExpiresAt(task.metadata().get("leaseExpiresAt"));
            this.generation = generation;
            this.requiredMcpTools = Set.copyOf(requiredMcpTools);
        }

        Execution(RuntimeTask task, HarnessAgent agent, AgentScopeEventTranslator translator,
                String attemptId, String leaseId, long generation) {
            this(task, agent, translator, attemptId, leaseId, generation, Set.of());
        }

        void recordUsage(RuntimeCallUsage observed) {
            if (observed == null) {
                return;
            }
            RuntimeCallUsage previous = callUsage;
            if (previous == null) {
                callUsage = observed;
                return;
            }
            callUsage = new RuntimeCallUsage(previous.provider(), previous.model(),
                    Math.addExact(previous.latencyMillis(), observed.latencyMillis()),
                    Math.addExact(previous.promptTokens(), observed.promptTokens()),
                    Math.addExact(previous.completionTokens(), observed.completionTokens()));
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
