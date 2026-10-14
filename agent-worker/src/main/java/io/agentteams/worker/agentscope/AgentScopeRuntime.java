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
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s<>\\\"'\\]]+", Pattern.CASE_INSENSITIVE);
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
            Disposable disposable = agent.streamEvents(
                    new UserMessage(agentInput(task.inputJson(), requiredMcpTools, task.id())), agentContext)
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
        String summary = execution.resultCandidate == null ? "" : execution.resultCandidate;
        summary = ensureAggregateSourceCitations(execution.task.inputJson(), summary);
        completeLocked(execution, RuntimeResult.success(execution.task.id(),
                summary, Instant.now(currentContext.clock()), execution.callUsage));
    }

    /** Keeps at least one child-result citation visible in the aggregate task result. */
    private static String ensureAggregateSourceCitations(String inputJson, String resultCandidate) {
        try {
            JsonNode input = JSON.readTree(inputJson);
            if (input == null || !"AGGREGATE".equalsIgnoreCase(input.path("orchestrationPhase").asText())) {
                return resultCandidate;
            }
            JsonNode candidate = null;
            if (resultCandidate == null || resultCandidate.isBlank()) {
                candidate = JSON.createObjectNode();
            } else {
                String candidateJson = resultCandidate.trim();
                if (candidateJson.startsWith("```") && candidateJson.endsWith("```")) {
                    int firstLine = candidateJson.indexOf('\n');
                    if (firstLine >= 0) {
                        candidateJson = candidateJson.substring(firstLine + 1,
                                candidateJson.length() - 3).trim();
                    }
                }
                try {
                    candidate = JSON.readTree(candidateJson);
                } catch (java.io.IOException ignored) {
                    // AgentResultEvent may be plain text instead of a JSON object.
                }
            }
            String summary = candidate != null && candidate.isObject()
                    ? candidate.path("summary").asText("") : resultCandidate == null ? "" : resultCandidate;

            LinkedHashSet<String> childUrls = new LinkedHashSet<>();
            JsonNode childResults = input.path("childResults");
            int childResultCount = 0;
            if (childResults.isArray()) {
                for (JsonNode child : childResults) {
                    childResultCount++;
                    collectHttpUrls(child.path("result"), childUrls, 0);
                    if (childUrls.size() >= 50) break;
                }
            }
            LinkedHashSet<String> verifiedUrls = new LinkedHashSet<>();
            JsonNode sourceEvidence = input.path("childSourceEvidence");
            if (sourceEvidence.isArray()) {
                for (JsonNode child : sourceEvidence) {
                    JsonNode sources = child.path("sources");
                    if (!sources.isArray()) continue;
                    for (JsonNode source : sources) {
                        String url = source.path("url").asText("");
                        String evidenceRef = source.path("evidenceRef").asText("");
                        if (source.path("verified").asBoolean(false) && HTTP_URL.matcher(url).matches()
                                && evidenceRef.startsWith("task-source:")) {
                            verifiedUrls.add(url);
                        }
                    }
                }
            }
            if (verifiedUrls.isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "Aggregate citation preservation found no verified child URLs childResults={0} summaryHadUrl={1}",
                        childResultCount, containsHttpUrl(summary));
                return resultCandidate;
            }
            String citedUrl = childUrls.stream().filter(verifiedUrls::contains).findFirst()
                    .orElse(verifiedUrls.iterator().next());
            String safeBody = HTTP_URL.matcher(summary).replaceAll("").trim();
            if (safeBody.length() > 1800) safeBody = safeBody.substring(0, 1799).stripTrailing() + "…";
            String updatedSummary = "Sources:\n" + citedUrl
                    + (safeBody.isBlank() ? "" : "\n\n" + safeBody);
            if (candidate != null && candidate.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) candidate).put("summary", updatedSummary);
                LOG.log(System.Logger.Level.INFO,
                        "Aggregate citation preservation pinned verified child source childResults={0}", childResultCount);
                return JSON.writeValueAsString(candidate);
            }
            LOG.log(System.Logger.Level.INFO,
                    "Aggregate citation preservation pinned verified child source childResults={0}", childResultCount);
            return updatedSummary;
        } catch (java.io.IOException ignored) {
            return resultCandidate;
        }
    }

    private static boolean containsHttpUrl(String value) {
        return value != null && HTTP_URL.matcher(value).find();
    }

    private static void collectHttpUrls(JsonNode node, Set<String> urls, int depth) {
        if (node == null || urls.size() >= 10) return;
        if (node.isTextual()) {
            String text = node.asText();
            Matcher matcher = HTTP_URL.matcher(text);
            while (matcher.find() && urls.size() < 10) {
                String value = matcher.group().replaceAll("[.,;:!?)}]+$", "");
                if (safeHttpUrl(value)) urls.add(value);
            }
            if (depth < 3) {
                try {
                    JsonNode nested = JSON.readTree(text);
                    if (nested != null && (nested.isObject() || nested.isArray())) {
                        collectHttpUrls(nested, urls, depth + 1);
                    }
                } catch (java.io.IOException ignored) {
                    // Child result text need not be JSON.
                }
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectHttpUrls(entry.getValue(), urls, depth));
        } else if (node.isArray()) {
            node.forEach(value -> collectHttpUrls(value, urls, depth));
        }
    }

    private static boolean safeHttpUrl(String value) {
        if (value.matches("(?i).*([?&](token|password|secret|api[-_]?key)=).*")) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException ignored) {
            return false;
        }
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

    private static String agentInput(String inputJson, Set<String> requiredMcpTools, UUID taskId) {
        StringBuilder prefix = new StringBuilder();
        try {
            JsonNode root = JSON.readTree(inputJson);
            if (root != null && root.path("dependencyResults").isArray()
                    && !root.path("dependencyResults").isEmpty()) {
                prefix.append("Use dependencyResults as source material for factual claims and relevant findings only. "
                        + "Synthesize them into the user's requested deliverable. "
                        + "Do not copy task IDs, file IDs, upstream artifact names, tool-call logs, dependency/workflow details, "
                        + "or other execution metadata into user-facing content unless explicitly requested. "
                        + "If information is missing, describe only the substantive evidence gap and its impact on the answer.\n\n");
            }
        } catch (java.io.IOException ignored) {
            // Existing plain-text task inputs do not carry dependency results.
        }
        if (!requiredMcpTools.isEmpty()) {
            prefix.append("Before answering, you MUST call each of these MCP tools exactly as required by the task: ")
                    .append(String.join(", ", requiredMcpTools))
                    .append(". Do not claim that a tool was called unless the tool call actually succeeds.\n\n");
        }
        if (requiredMcpTools.contains("create_task_pdf_artifact")) {
            prefix.append("When calling create_task_pdf_artifact, set task_id=\"")
                    .append(taskId)
                    .append("\" exactly. This is the current task ID; do not use parentTaskId or guess another ID.\n\n");
        }
        try {
            JsonNode root = JSON.readTree(inputJson);
            String phase = root == null ? "" : root.path("orchestrationPhase").asText("");
            if ("EXECUTE".equalsIgnoreCase(phase)
                    && requiredMcpTools.contains("create_task_pdf_artifact")) {
                prefix.append("For this PDF deliverable, first prepare the complete reader-facing report in Markdown, "
                        + "then call create_task_pdf_artifact exactly once with this task_id, the complete report in markdown, "
                        + "and the requested filename. Preserve each verified source URL verbatim in the report's Sources section. "
                        + "Before calling the tool, scan the Markdown body against the artifact validator's metadata guard: "
                        + "it must not contain the identifiers sourceTaskId, sourceFileId, taskId, task_id, fileId, file_id, "
                        + "runId, run_id, dependencyResults, create_task_pdf_artifact, search_news, web_search, "
                        + "plan_subtasks, list_subtasks, get_task_result, update_subtask_status, EXECUTOR or EXECUTE; "
                        + "it must also omit subtask/workflow notes, upstream-file references, execution details, and the requested PDF filename. "
                        + "Keep all these operational identifiers only in tool arguments or task instructions, never in the report body. "
                        + "Wait for the successful tool result before responding; do not answer with prose alone, "
                        + "claim success after a failed tool call, or retry the artifact call unless the task explicitly asks for it.\n\n");
            }
            if ("PLAN".equalsIgnoreCase(phase)) {
                prefix.append("You are the Team Leader in the planning phase. Analyze the user request and do not execute the subtasks. "
                        + "Return ONLY a JSON object with a plan array containing exactly two subtasks. "
                        + "When calling plan_subtasks, set task_id=\"").append(taskId).append("\", "
                        + "give each new subtask a fresh UUID and use the tool's dependencyIds field. "
                        + "Each tool item must have subtaskId, sequence (1 or 2), title, and dependencyIds. "
                        + "Do not add requiredCapabilities unless the task input explicitly supplies an executor capability registry; "
                        + "when no registry is supplied, omit requiredCapabilities (the platform will use an empty requirement). "
                        + "Set subtask 1 dependencyIds=[] and, when subtask 2 needs its result, set subtask 2 dependencyIds to [the subtask 1 UUID]. "
                        + "Do not use dependsOn in the plan_subtasks call.\n\n");
            } else if ("AGGREGATE".equalsIgnoreCase(phase)) {
                prefix.append("You are the Team Leader in the aggregation phase. Use the childResults in the input, synthesize their outputs, "
                        + "and cite source URLs only when they appear as verified=true entries in childSourceEvidence. "
                        + "A childSourceEvidence entry may be incomplete because the overall evidence was truncated; "
                        + "its individually verified source URLs remain valid to cite. ");
                if (requiredMcpTools.contains("get_task_result")) {
                    prefix.append("Before answering, ");
                    if (requiredMcpTools.contains("list_subtasks")) {
                        prefix.append("call list_subtasks with task_id=\"")
                                .append(taskId)
                                .append("\" (the current parent task ID), then call get_task_result for each successful subtask. ");
                    } else {
                        prefix.append("call get_task_result for each successful subtask. ");
                    }
                    if (requiredMcpTools.contains("create_task_pdf_artifact")) {
                        prefix.append(
                                "Use the child results and available taskFiles as source material for the final report. "
                                + "Create the one final PDF directly on this parent task with create_task_pdf_artifact; "
                                + "do not forward or select a child PDF as the parent deliverable. "
                                + "The PDF is uploaded directly to this parent task. Return ONLY a JSON object "
                                + "with a top-level summary string; the artifacts array must be empty.\n\n");
                    } else {
                        prefix.append(
                                "Inspect each returned taskFiles entry and include only files you explicitly select as final deliverables. "
                                + "For every selected file, include one metadata-only artifact object with its exact name, contentType, "
                                + "sizeBytes, sha256, sourceTaskId (the subtaskId), and sourceFileId (the fileId); never guess these values. "
                                + "Do not include unselected files or file bytes. Return ONLY a JSON object with a top-level summary string "
                                + "and an artifacts array containing the selected file references.\n\n");
                    }
                } else {
                    prefix.append("Return ONLY a JSON object with a top-level summary string and an artifacts array; leave artifacts empty. ");
                }
                prefix.append("Put the concise final answer and at least one exact source citation URL in that summary. "
                        + "Repeat the complete answer in output.md.\n\n");
                String aggregationPrompt = root == null ? "" : root.path("aggregatePrompt").asText("").trim();
                if (aggregationPrompt.isEmpty() && root != null) {
                    aggregationPrompt = root.path("prompt").asText("").trim();
                }
                if (!aggregationPrompt.isEmpty()) {
                    prefix.append("Follow the aggregation prompt exactly:\n")
                            .append(aggregationPrompt)
                            .append("\n\n");
                }
                if (requiredMcpTools.contains("create_task_pdf_artifact")) {
                    prefix.append("You must also call create_task_pdf_artifact exactly once with task_id=\"")
                            .append(taskId).append("\", the exact PDF filename requested in the aggregation prompt, "
                                    + "and the complete aggregated report in Markdown. "
                                    + "The tool uploads the PDF as a downloadable OUTPUT file directly to this parent task; "
                                    + "do not include PDF bytes or a PDF artifact in the JSON result.\n\n");
                }
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
