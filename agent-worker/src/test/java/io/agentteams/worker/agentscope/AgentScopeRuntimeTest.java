package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeEvent;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.runtime.RuntimeTaskState;
import io.agentteams.runtime.RuntimeSubmission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentScopeRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    private final List<HarnessAgent> agents = new ArrayList<>();
    private AgentScopeRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.stop();
        }
        agents.forEach(HarnessAgent::close);
    }

    @Test
    void placesTeamPhaseContractAfterOriginalPrompt() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);

        UUID taskId = UUID.fromString("167624c4-c261-48f7-8461-36bab1d343e2");
        String prompt = (String) method.invoke(null,
                "{\"prompt\":\"请直接给出结论\",\"orchestrationPhase\":\"PLAN\"}", Set.of(), taskId);

        assertThat(prompt).startsWith("{\"prompt\":\"请直接给出结论\"");
        assertThat(prompt).contains("exactly two subtasks", "dependencyIds", "fresh UUID",
                "Do not use dependsOn in the plan_subtasks call", "task_id=\"" + taskId + "\"",
                "Do not add requiredCapabilities unless the task input explicitly supplies an executor capability registry");
    }

    @Test
    void suppliesParentTaskIdForRequiredPdfArtifactToolDuringAggregation() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);
        UUID taskId = UUID.fromString("c2a4c900-1ba0-4b31-ae58-f13ea56d868b");

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"aggregatePrompt\":\"Create final report research-final.pdf\",\"childResults\":[]}",
                Set.of("create_task_pdf_artifact"), taskId);

        assertThat(prompt).contains("create_task_pdf_artifact", taskId.toString(), "research-final.pdf")
                .doesNotContain("quarterly-project-report.pdf");
    }

    @Test
    void suppliesCurrentSubtaskIdForRequiredPdfArtifactToolDuringExecution() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);
        UUID taskId = UUID.fromString("3f2a1c9e-7b41-4d8a-9e02-5c6d1f0a8b31");

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"EXECUTE\",\"parentTaskId\":\"02165e0b-b77f-4125-9c1a-21e05ef6760f\"}",
                Set.of("create_task_pdf_artifact"), taskId);

        assertThat(prompt).contains("task_id=\"" + taskId + "\"", "current task ID")
                .doesNotContain("task_id=\"02165e0b-b77f-4125-9c1a-21e05ef6760f\"");
        assertThat(prompt).contains("call create_task_pdf_artifact exactly once",
                "complete reader-facing report in Markdown", "Wait for the successful tool result",
                "Preserve each verified source URL verbatim", "do not answer with prose alone",
                "Before calling the tool, scan the Markdown body",
                "sourceTaskId, sourceFileId, taskId, task_id, fileId, file_id, runId, run_id",
                "dependencyResults", "subtask/workflow notes", "operational identifiers",
                "requested PDF filename");
    }

    @Test
    void suppliesCurrentParentIdForRequiredSubtaskListingDuringAggregation() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);
        UUID taskId = UUID.fromString("d563f39b-daef-46e3-aa9c-43352fb2a700");

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"childResults\":[]}",
                Set.of("list_subtasks", "get_task_result"), taskId);

        assertThat(prompt).contains("list_subtasks", "task_id=\"" + taskId + "\"",
                "current parent task ID");
    }

    @Test
    void instructsAggregateLeaderToExplicitlySelectChildTaskFiles() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"childResults\":[]}",
                Set.of("list_subtasks", "get_task_result"), UUID.randomUUID());

        assertThat(prompt).contains("get_task_result", "taskFiles", "sourceTaskId", "sourceFileId",
                "include only files you explicitly select", "artifacts array");
        assertThat(prompt).doesNotContain("leave artifacts empty");
    }

    @Test
    void instructsAggregateLeaderToCreateParentPdfInsteadOfSelectingChildPdfWhenToolIsEnabled() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"childResults\":[]}",
                Set.of("list_subtasks", "get_task_result", "create_task_pdf_artifact"), UUID.randomUUID());

        assertThat(prompt).contains("get_task_result", "taskFiles", "create_task_pdf_artifact exactly once",
                "uploaded directly to this parent task", "artifacts array must be empty");
        assertThat(prompt).doesNotContain("include only files you explicitly select as final deliverables");
    }

    @Test
    void elevatesAggregationPromptFieldIntoDirectInstructions() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);
        String aggregationPrompt = "Put a verbatim source URL in output.md and the final answer.";

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"prompt\":\""
                        + aggregationPrompt + "\",\"childResults\":[]}",
                Set.of("create_task_pdf_artifact"), UUID.randomUUID());

        assertThat(prompt).contains("Follow the aggregation prompt exactly:", aggregationPrompt);
        assertThat(prompt).contains("top-level summary string", "citation URL in that summary",
                "Repeat the complete answer in output.md", "leave artifacts empty",
                "do not include PDF bytes or a PDF artifact in the JSON result");
        assertThat(prompt.indexOf("Follow the aggregation prompt exactly:"))
                .isGreaterThan(prompt.indexOf("\"prompt\""));
    }

    @Test
    void prefersDedicatedAggregationPromptWhenBothTaskAndAggregatePromptsExist() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null,
                "{\"orchestrationPhase\":\"AGGREGATE\",\"prompt\":\"Plan two dependent subtasks\","
                        + "\"aggregatePrompt\":\"Return only one JSON object and select the named final PDF\","
                        + "\"childResults\":[]}",
                Set.of("list_subtasks", "get_task_result"), UUID.randomUUID());

        assertThat(prompt).contains("Return only one JSON object and select the named final PDF")
                .doesNotContain("Follow the aggregation prompt exactly:\nPlan two dependent subtasks");
    }

    @Test
    void pinsOneChildSourceUrlInAggregateResultSummary() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "ensureAggregateSourceCitations", String.class, String.class);
        method.setAccessible(true);
        String input = """
                {"orchestrationPhase":"AGGREGATE","childResults":[
                  {"result":"{\\"summary\\":\\"W3C guidance https://www.w3.org/WAI/\\"}"},
                  {"result":"See https://www.w3.org/WAI/ and https://www.w3.org/TR/WCAG22/"}],
                  "childSourceEvidence":[{"complete":true,"sources":[{"url":"https://www.w3.org/WAI/","evidenceRef":"task-source:call:hash","verified":true}]}]}
                """;

        String result = (String) method.invoke(null, input,
                "{\"summary\":\"Accessible PDFs should be usable.\",\"artifacts\":[]}");

        assertThat(result).contains("https://www.w3.org/WAI/")
                .doesNotContain("https://www.w3.org/TR/WCAG22/");
        assertThat(result.indexOf("https://www.w3.org/WAI/"))
                .isEqualTo(result.lastIndexOf("https://www.w3.org/WAI/"));
        assertThat(method.invoke(null, input, "Accessible PDFs should be usable."))
                .asString().contains("Sources:", "https://www.w3.org/WAI/");
    }

    @Test
    void pinsOnlyChildUrlsPresentInTheInjectedSearchEvidence() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "ensureAggregateSourceCitations", String.class, String.class);
        method.setAccessible(true);
        String input = """
                {"orchestrationPhase":"AGGREGATE","childResults":[
                  {"result":"First URL https://www.w3.org/WAI/standards-guidelines/wcag/ and standard https://www.w3.org/TR/WCAG21/"}],
                  "childSourceEvidence":[{"taskId":"child-a","runId":"run-a","complete":true,
                    "sources":[{"url":"https://www.w3.org/TR/WCAG21/","evidenceRef":"task-source:call:hash","verified":true}]}]}
                """;

        String result = (String) method.invoke(null, input,
                "{\"summary\":\"Accessible PDFs should follow WCAG.\",\"artifacts\":[]}");

        assertThat(result).contains("https://www.w3.org/TR/WCAG21/")
                .doesNotContain("https://www.w3.org/WAI/standards-guidelines/wcag/");
    }

    @Test
    void keepsVerifiedChildCitationsWithinThePersistedAggregateSummaryLimit() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "ensureAggregateSourceCitations", String.class, String.class);
        method.setAccessible(true);
        String url = "https://www.w3.org/WAI/WCAG21/Techniques";
        String input = "{\"orchestrationPhase\":\"AGGREGATE\",\"childResults\":["
                + "{\"result\":\"Research from " + url + "\"}],\"childSourceEvidence\":["
                + "{\"complete\":false,\"sources\":[{\"url\":\"" + url
                + "\",\"evidenceRef\":\"task-source:call:hash\",\"verified\":true}]}]}";
        String longSummary = "{\"summary\":\"" + "detail ".repeat(900) + "\",\"artifacts\":[]}";
        String candidate = "```json\n" + longSummary + "\n```";

        String result = (String) method.invoke(null, input, candidate);

        assertThat(result).contains(url, "Sources:");
        assertThat(result.indexOf(url)).isLessThan(300);
        assertThat(result.length()).isLessThan(4096);
    }

    @Test
    void doesNotAddUnsafeChildUrlsOrRewriteNonAggregateResults() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "ensureAggregateSourceCitations", String.class, String.class);
        method.setAccessible(true);
        String candidate = "{\"summary\":\"No source URL in this answer.\"}";
        String unsafeAggregate = "{\"orchestrationPhase\":\"AGGREGATE\",\"childResults\":["
                + "{\"result\":\"https://example.test/report?token=do-not-copy\"}]}";
        String plan = "{\"orchestrationPhase\":\"PLAN\",\"childResults\":["
                + "{\"result\":\"https://www.w3.org/WAI/\"}]}";

        assertThat(method.invoke(null, unsafeAggregate, candidate)).isEqualTo(candidate);
        assertThat(method.invoke(null, plan, candidate)).isEqualTo(candidate);
    }

    @Test
    void tellsDependentSubtaskToUseResultsWithoutLeakingExecutionMetadata() throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "agentInput", String.class, Set.class, UUID.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null,
                "{\"prompt\":\"Summarize\",\"dependencyResults\":[{\"taskId\":\"a\",\"result\":\"facts\"}]}",
                Set.of(), UUID.randomUUID());

        assertThat(prompt).contains("dependencyResults", "Use dependencyResults as source material",
                "Synthesize them into the user's requested deliverable",
                "Do not copy task IDs, file IDs, upstream artifact names, tool-call logs",
                "describe only the substantive evidence gap");
    }

    @Test
    void streamsAgentScopeEventsAndCompletesOnlyOnAgentEnd() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("safe answer"), events::add);
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));
        RuntimeTask task = task("attempt-1", "lease-1", "corr-1");

        assertThat(runtime.submit(task)).isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).output()).isEqualTo("safe answer");
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.SUCCEEDED);
        assertThat(events).extracting(AgentScopeExecutionEvent::kind)
                .contains(AgentScopeExecutionEvent.Kind.AGENT_STARTED,
                        AgentScopeExecutionEvent.Kind.AGENT_RESULT,
                        AgentScopeExecutionEvent.Kind.AGENT_ENDED);
        assertThat(events).filteredOn(AgentScopeExecutionEvent::terminal).hasSize(1);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.taskId()).isEqualTo(task.id().toString());
            assertThat(event.attemptId()).isEqualTo("attempt-1");
            assertThat(event.leaseId()).isEqualTo("lease-1");
            assertThat(event.correlationId()).isEqualTo("corr-1");
            assertThat(event.runtime()).isEqualTo("agentscope-test");
        });
    }

    @Test
    void failsWhenRequiredMcpToolWasNotCalled() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("safe answer"), event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));

        RuntimeTask task = taskWithInput("attempt-required-tool", "lease-required-tool", "corr-required-tool",
                "{\"prompt\":\"answer\",\"requiredMcpTools\":[\"web_search\"]}");
        assertThat(runtime.submit(task)).isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).output()).contains("REQUIRED_TOOL_NOT_CALLED: web_search");
    }

    @Test
    void publishesToolCallsThroughTheWorkerRuntimeEventSink() {
        List<RuntimeEvent> runtimeEvents = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });
        runtime.start(context(1, ignored -> { }));
        runtime.setEventSink(runtimeEvents::add);

        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        runtime.reportRuntimeEvent(new AgentScopeExecutionEvent(
                taskId.toString(), "attempt-tool", "lease-tool", "event-tool-start",
                "corr-tool", "agentscope-test", AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED,
                "tool web_search started", false, true, false), 1);
        runtime.reportRuntimeEvent(new AgentScopeExecutionEvent(
                taskId.toString(), "attempt-tool", "lease-tool", "event-tool-end",
                "corr-tool", "agentscope-test", AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED,
                "tool web_search completed", false, true, false), 2);

        assertThat(runtimeEvents).extracting(RuntimeEvent::eventType)
                .containsExactly("tool.called", "tool.finished");
        assertThat(runtimeEvents.get(0).payloadJson()).contains("\"tool\":\"web_search\"");
        assertThat(runtimeEvents.get(1).payloadJson()).contains("\"ok\":true");
        assertThat(runtimeEvents.get(0).payloadJson()).contains("\"sequence\":1");
        assertThat(runtimeEvents.get(1).payloadJson()).contains("\"sequence\":2");
    }

    @Test
    void reportsFailedToolCompletionWithStableCategoryAndNoToolContent() {
        List<RuntimeEvent> runtimeEvents = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });
        runtime.start(context(1, ignored -> { }));
        runtime.setEventSink(runtimeEvents::add);

        runtime.reportRuntimeEvent(new AgentScopeExecutionEvent(
                "00000000-0000-0000-0000-000000000099", "attempt-tool", "lease-tool", "event-tool-error",
                "corr-tool", "agentscope-test", AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED,
                "tool web_search failed: TOOL_EXECUTION_FAILED", false, false, false), 2);

        assertThat(runtimeEvents).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("tool.finished");
            assertThat(event.payloadJson()).contains("\"tool\":\"web_search\"", "\"ok\":false",
                    "TOOL_EXECUTION_FAILED", "\"sequence\":2");
            assertThat(event.payloadJson()).doesNotContain("raw response", "secret", "authorization");
        });
    }

    @Test
    void streamsSuccessfulAndFailedToolResultsToRuntimeEventSinkWithoutToolContent() throws Exception {
        assertToolLifecycleThroughAgentStream(false, true, false);
        assertToolLifecycleThroughAgentStream(true, false, false);
    }

    @Test
    void requiredToolGateOnlyAcceptsSuccessfulToolResultFromAgentStream() throws Exception {
        assertToolLifecycleThroughAgentStream(false, true, true);
        assertToolLifecycleThroughAgentStream(true, false, true);
    }

    private void assertToolLifecycleThroughAgentStream(boolean toolFails, boolean expectedSuccess,
            boolean requiredTool)
            throws Exception {
        List<RuntimeEvent> runtimeEvents = new CopyOnWriteArrayList<>();
        List<RuntimeResult> runtimeResults = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        runtime = new AgentScopeRuntime((task, ignoredContext) -> {
            try {
                Toolkit toolkit = new Toolkit();
                toolkit.registerAgentTool(new AgentTool() {
                    @Override public String getName() { return "web_search"; }
                    @Override public String getDescription() { return "test search"; }
                    @Override public Map<String, Object> getParameters() { return Map.of(); }
                    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(toolFails
                                ? ToolResultBlock.error("PRIVATE_TOOL_RESPONSE_DO_NOT_EMIT")
                                : ToolResultBlock.text("PRIVATE_TOOL_RESPONSE_DO_NOT_EMIT"));
                    }
                });
                HarnessAgent agent = HarnessAgent.builder()
                        .name("tool-lifecycle-agent")
                        .agentId("agent-" + task.id())
                        .defaultSessionId(task.metadata().get("attemptId"))
                        .model(new Model() {
                            @Override
                            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                    GenerateOptions options) {
                                if (modelCalls.getAndIncrement() == 0) {
                                    return Flux.just(ChatResponse.builder().id("tool-call")
                                            .content(List.of(ToolUseBlock.builder()
                                                    .id("call-web-search").name("web_search").input(Map.of()).build()))
                                            .finishReason("tool_calls").build());
                                }
                                return Flux.just(ChatResponse.builder().id("final-answer")
                                        .content(List.of(TextBlock.builder().text("safe final answer").build()))
                                        .finishReason("stop").build());
                            }

                            @Override
                            public String getModelName() {
                                return "tool-lifecycle-model";
                            }
                        })
                        .toolkit(toolkit)
                        .workspace(Files.createTempDirectory("agentscope-tool-lifecycle-"))
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableSubagents()
                        .disableSessionPersistence()
                        .maxIters(2)
                        .build();
                agents.add(agent);
                return agent;
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }, event -> { }, WorkspaceActiveGuard.noop());
        runtime.start(context(1, result -> {
            runtimeResults.add(result);
            completed.countDown();
        }));
        runtime.setEventSink(runtimeEvents::add);
        RuntimeTask task = requiredTool
                ? taskWithInput("attempt-tool-stream", "lease-tool-stream", "corr-tool-stream",
                        "{\"prompt\":\"answer\",\"requiredMcpTools\":[\"web_search\"]}")
                : task("attempt-tool-stream", "lease-tool-stream", "corr-tool-stream");

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        List<RuntimeEvent> toolEvents = runtimeEvents.stream()
                .filter(event -> event.taskId().equals(task.id()))
                .toList();
        String expectedCategory = toolFails ? "TOOL_EXECUTION_FAILED" : "";
        assertThat(toolEvents).extracting(RuntimeEvent::eventType)
                .containsExactly("tool.called", "tool.finished");
        assertThat(toolEvents.get(0).payloadJson()).contains("\"tool\":\"web_search\"",
                "\"callId\":\"call-580b5f533bc58ff47a1850bb312c059e6f21323d3a1b21cabf978fde2a000b31\"",
                "\"sequence\":1");
        assertThat(toolEvents.get(1).payloadJson()).contains("\"tool\":\"web_search\"",
                "\"callId\":\"call-580b5f533bc58ff47a1850bb312c059e6f21323d3a1b21cabf978fde2a000b31\"",
                "\"ok\":" + expectedSuccess, "\"sequence\":2");
        if (toolFails) {
            assertThat(toolEvents.get(1).payloadJson()).contains(expectedCategory);
        }
        assertThat(toolEvents).allSatisfy(event -> assertThat(event.payloadJson())
                .doesNotContain("PRIVATE_TOOL_RESPONSE_DO_NOT_EMIT"));
        if (requiredTool) {
            assertThat(runtimeResults).singleElement().satisfies(result -> {
                assertThat(result.success()).isEqualTo(expectedSuccess);
                if (toolFails) {
                    assertThat(result.output()).contains("REQUIRED_TOOL_NOT_CALLED: web_search");
                }
            });
        }
        runtime.stop();
        runtime = null;
    }

    @Test
    void agentResultIsOnlyASafeFinalTextCandidateAndDoesNotCompleteEarly() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("Authorization: Bearer secret-token"), event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));

        assertThat(runtime.submit(task("attempt-2", "lease-2", "corr-2")).accepted()).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).output()).doesNotContain("secret-token");
        assertThat(results.get(0).output()).contains("[REDACTED]");
    }

    @Test
    void factoryFailureIsRejectedBeforeAcceptanceAndPublishesNoRuntimeResult() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        runtime = new AgentScopeRuntime((task, context) -> {
            throw new IllegalStateException("factory secret must not be returned");
        }, event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            failed.countDown();
        }));

        RuntimeTask task = task("attempt-factory", "lease-factory", "corr-factory");
        assertThat(runtime.submit(task)).isEqualTo(RuntimeSubmission.rejected("RUNTIME_UNAVAILABLE"));
        assertThat(failed.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(results).isEmpty();
    }

    @Test
    void fluxFailurePublishesFailedRuntimeResult() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        runtime = newRuntime(new FailingModel(), event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            failed.countDown();
        }));

        assertThat(runtime.submit(task("attempt-error", "lease-error", "corr-error")).accepted()).isTrue();
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isEqualTo("AgentScope execution failed");
        });
    }

    @Test
    void duplicateSubmitIsRejectedAndConcurrencyLimitIsPreserved() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask first = task("attempt-running", "lease-running", "corr-running");
        RuntimeTask second = task("attempt-second", "lease-second", "corr-second");

        assertThat(runtime.submit(first).accepted()).isTrue();
        assertThat(runtime.submit(first)).isEqualTo(RuntimeSubmission.rejected("task already exists"));
        assertThat(runtime.submit(second)).isEqualTo(RuntimeSubmission.rejected("maximum concurrency reached"));
        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(1, 1));
    }

    @Test
    void cancelInterruptsAgentDisposesStreamAndClearsExecution() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask task = task("attempt-cancel", "lease-cancel", "corr-cancel");

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(runtime.cancel(task.id())).isTrue();
        assertThat(runtime.cancel(task.id())).isFalse();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runtime.snapshot().running()).isZero();
    }

    @Test
    void stopInterruptsAndClosesAllActiveSessions() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(2, result -> { }));
        assertThat(runtime.submit(task("attempt-stop-1", "lease-stop-1", "corr-stop-1")).accepted()).isTrue();
        assertThat(runtime.submit(task("attempt-stop-2", "lease-stop-2", "corr-stop-2")).accepted()).isTrue();

        runtime.stop();

        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(0, 0));
        assertThat(runtime.status(UUID.randomUUID())).isEmpty();
    }

    @Test
    void requiresRuntimeToBeStartedBeforeSubmission() {
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });

        assertThatThrownBy(() -> runtime.submit(task("attempt-not-started", "lease-not-started", "corr-not-started")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime is not started");
    }

    @Test
    void stopWaitsForAnInFlightSubmitAndLeavesNoOrphanExecution() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicReference<RuntimeSubmission> submission = new AtomicReference<>();
        runtime = new AgentScopeRuntime((task, context) -> {
            factoryEntered.countDown();
            await(releaseFactory);
            return newHarness(new DeterministicModel("race-safe"), task);
        }, event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask task = task("attempt-race", "lease-race", "corr-race");

        Thread submitThread = new Thread(() -> submission.set(runtime.submit(task)), "agentscope-submit-test");
        submitThread.start();
        assertThat(factoryEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread stopThread = new Thread(() -> {
            runtime.stop();
            stopReturned.countDown();
        }, "agentscope-stop-test");
        stopThread.start();

        assertThat(stopReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        releaseFactory.countDown();
        submitThread.join(2000);
        stopThread.join(2000);

        assertThat(submission.get()).isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(stopReturned.getCount()).isZero();
        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(0, 0));
    }

    @Test
    void submitAfterStopFailsClearly() {
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });
        runtime.start(context(1, result -> { }));
        runtime.stop();

        assertThatThrownBy(() -> runtime.submit(task("attempt-stopped", "lease-stopped", "corr-stopped")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime is not started");
    }

    @Test
    void installsDisposableAtomicallyAndDisposesItAfterSynchronousClose() {
        RuntimeTask task = task("attempt-disposable", "lease-disposable", "corr-disposable");
        HarnessAgent agent = newHarness(new BlockingModel(), task);
        AgentScopeRuntime.Execution execution = new AgentScopeRuntime.Execution(task, agent,
                translator(task), "attempt-disposable", "lease-disposable", 1);
        RecordingDisposable disposable = new RecordingDisposable(false);

        execution.close();

        assertThat(execution.installDisposable(disposable)).isFalse();
        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void cleanupContinuesWhenDisposableDisposalFailsAndDoesNotLeakErrorDetails() {
        RuntimeTask task = task("attempt-cleanup", "lease-cleanup", "corr-cleanup");
        HarnessAgent agent = newHarness(new BlockingModel(), task);
        AgentScopeEventTranslator translator = translator(task);
        AgentScopeRuntime.Execution execution = new AgentScopeRuntime.Execution(task, agent, translator,
                "attempt-cleanup", "lease-cleanup", 1);
        RecordingDisposable disposable = new RecordingDisposable(true);
        assertThat(execution.installDisposable(disposable)).isTrue();

        execution.close();

        assertThat(disposable.disposed).isTrue();
        AgentScopeExecutionEvent afterClose = translator.translate(
                new io.agentscope.core.event.AgentStartEvent("late-cleanup", "2026-08-25T00:00:00Z",
                        "session", "reply", "agent", "assistant")
                        .withMetadataEntry("attemptId", "attempt-cleanup"));
        assertThat(afterClose.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(afterClose.safeMessage()).isEqualTo("translator closed");
    }

    @Test
    void rejectsLateEventsAfterAgentEndBeforeEventSink() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-late", "lease-late", "corr-late");
        assertThat(runtime.submit(task).accepted()).isTrue();

        Object execution = activeExecution(task.id());
        invokeOnEvent(execution, new io.agentscope.core.event.AgentEndEvent(
                "end-late", "2026-08-25T00:00:00Z", "reply")
                .withMetadataEntry("attemptId", "attempt-late"));
        int eventCountAfterEnd = events.size();
        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "after-end", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-late"));

        assertThat(events).hasSize(eventCountAfterEnd);
        assertThat(results).hasSize(1);
    }

    @Test
    void resultSinkFailureStillCleansExecutionAndKeepsTerminalCommitted() throws Exception {
        AtomicReference<Integer> sinkCalls = new AtomicReference<>(0);
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, result -> {
            sinkCalls.updateAndGet(calls -> calls + 1);
            throw new IllegalStateException("result sink secret must not leak");
        }));
        RuntimeTask task = task("attempt-sink-error", "lease-sink-error", "corr-sink-error");
        assertThat(runtime.submit(task).accepted()).isTrue();

        AgentScopeRuntime.Execution execution = (AgentScopeRuntime.Execution) activeExecution(task.id());

        assertThatThrownBy(() -> runtime.complete(RuntimeResult.success(task.id(), "external", NOW)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sinkCalls).hasValue(1);
        assertThat(activeExecution(task.id())).isNull();
        assertThat(executionClosed(execution)).isTrue();
        assertThat(executionDisposable(execution)).isNull();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.SUCCEEDED);
        assertThat(runtime.snapshot().running()).isZero();
        assertThat(runtime.complete(RuntimeResult.success(task.id(), "duplicate", NOW)))
                .isEqualTo(io.agentteams.runtime.CompletionStatus.DUPLICATE);

        int eventsBeforeLateEvent = events.size();
        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "late-after-sink-error", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-sink-error"));
        assertThat(events).hasSize(eventsBeforeLateEvent);
    }

    @Test
    void staleAttemptEventIsRecordedButDoesNotFailCurrentAttempt() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-current", "lease-current", "corr-current");
        assertThat(runtime.submit(task).accepted()).isTrue();
        Object execution = activeExecution(task.id());

        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "stale-attempt", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-old"));

        assertThat(events).last().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
            assertThat(event.safeMessage()).isEqualTo("stale execution context");
        });
        assertThat(results).isEmpty();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.RUNNING);

        invokeOnEvent(execution, new io.agentscope.core.event.ExceedMaxItersEvent(
                "real-error", "2026-08-25T00:00:00Z", "reply", 2, 2)
                .withMetadataEntry("attemptId", "attempt-current"));

        assertThat(results).singleElement().satisfies(result -> assertThat(result.success()).isFalse());
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.FAILED);
    }

    @Test
    void staleAttemptAgentResultCannotPolluteCurrentResultCandidate() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-current", "lease-current", "corr-current");
        assertThat(runtime.submit(task).accepted()).isTrue();
        Object execution = activeExecution(task.id());

        invokeOnEvent(execution, new io.agentscope.core.event.AgentResultEvent(
                "stale-result", "2026-08-25T00:00:00Z", new UserMessage("old attempt result"))
                .withMetadata(Map.of("attemptId", "attempt-old", "leaseId", "lease-old")));
        invokeOnEvent(execution, new io.agentscope.core.event.AgentEndEvent(
                "current-end", "2026-08-25T00:00:00Z", "reply")
                .withMetadata(Map.of("attemptId", "attempt-current", "leaseId", "lease-current")));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEmpty();
        });
        assertThat(events).filteredOn(event -> event.eventId().equals("stale-result"))
                .singleElement().satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
                    assertThat(event.duplicate()).isFalse();
                });
    }

    @Test
    void inactiveWorkspaceTurnsLateAgentScopeEventIntoStaleAndClosesAgent() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        AtomicReference<SandboxWorkspaceException.Reason> reason =
                new AtomicReference<>(SandboxWorkspaceException.Reason.LOST);
        AtomicReference<Boolean> active = new AtomicReference<>(true);
        runtime = new AgentScopeRuntime((task, context) -> {
            HarnessAgent agent = newHarness(new BlockingModel(), task);
            agents.add(agent);
            return agent;
        }, events::add, (task, context) -> {
            if (!active.get()) {
                throw new SandboxWorkspaceException(reason.get());
            }
        });
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-gate", "lease-gate", "corr-gate");
        assertThat(runtime.submit(task).accepted()).isTrue();
        AgentScopeRuntime.Execution execution = (AgentScopeRuntime.Execution) activeExecution(task.id());
        active.set(false);

        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "lost-event", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-gate"));

        assertThat(events).last().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
            assertThat(event.safeMessage()).isEqualTo("sandbox workspace is stale");
        });
        assertThat(results).singleElement().satisfies(result -> assertThat(result.success()).isFalse());
        assertThat(executionClosed(execution)).isTrue();
        assertThat(activeExecution(task.id())).isNull();
    }

    @Test
    void expiredLeaseClosesExecutionWithoutSubmittingResult() throws Exception {
        AdjustableClock clock = new AdjustableClock(NOW);
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, clock, results::add));
        RuntimeTask task = task("attempt-expiry", "lease-expiry", "corr-expiry",
                NOW.plusSeconds(30).toString());
        assertThat(runtime.submit(task).accepted()).isTrue();
        AgentScopeRuntime.Execution execution = (AgentScopeRuntime.Execution) activeExecution(task.id());

        clock.set(NOW.plusSeconds(30));
        runtime.expireLeases();

        assertThat(results).isEmpty();
        assertThat(executionClosed(execution)).isTrue();
        assertThat(executionDisposable(execution)).isNull();
        assertThat(activeExecution(task.id())).isNull();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runtime.snapshot().running()).isZero();
        assertThat(runtime.complete(RuntimeResult.success(task.id(), "late", clock.instant())))
                .isEqualTo(io.agentteams.runtime.CompletionStatus.DUPLICATE);
    }

    @Test
    void workspaceGuardBlocksEventsAndCompletesAsFailure() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("must not be accepted"), events::add,
                (task, context) -> { throw new SandboxWorkspaceException(
                        SandboxWorkspaceException.Reason.LOST); });
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));

        assertThat(runtime.submit(task("attempt-workspace-lost", "lease-workspace-lost",
                "corr-workspace-lost")).accepted()).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isEqualTo("Sandbox workspace is stale");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
            assertThat(event.safeMessage()).isEqualTo("sandbox workspace is stale");
        });
    }

    private AgentScopeRuntime newRuntime(Model model, Consumer<AgentScopeExecutionEvent> eventSink) {
        return newRuntime(model, eventSink, WorkspaceActiveGuard.noop());
    }

    private AgentScopeRuntime newRuntime(Model model, Consumer<AgentScopeExecutionEvent> eventSink,
            WorkspaceActiveGuard guard) {
        return new AgentScopeRuntime((task, context) -> {
            Path workspace;
            try {
                workspace = Files.createTempDirectory("agentscope-runtime-test-");
            } catch (java.io.IOException error) {
                throw new IllegalStateException("unable to create test workspace", error);
            }
            HarnessAgent agent = newHarness(model, task, workspace);
            agents.add(agent);
            return agent;
        }, eventSink, guard);
    }

    private HarnessAgent newHarness(Model model, RuntimeTask task) {
        try {
            return newHarness(model, task, Files.createTempDirectory("agentscope-runtime-test-"));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("unable to create test workspace", error);
        }
    }

    private static HarnessAgent newHarness(Model model, RuntimeTask task, Path workspace) {
        return HarnessAgent.builder()
                .name("test-agent")
                .agentId("agent-" + task.id())
                .defaultSessionId(task.metadata().get("attemptId"))
                .model(model)
                .workspace(workspace)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableSessionPersistence()
                .maxIters(2)
                .build();
    }

    private AgentScopeEventTranslator translator(RuntimeTask task) {
        return new AgentScopeEventTranslator(task.id().toString(), task.metadata().get("attemptId"),
                task.metadata().get("leaseId"), task.metadata().get("correlationId"), "agentscope-test");
    }

    private Object activeExecution(UUID taskId) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.class.getDeclaredField("executions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> executions = (Map<UUID, Object>) field.get(runtime);
        return executions.get(taskId);
    }

    private void invokeOnEvent(Object execution, io.agentscope.core.event.AgentEvent event) throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "onEvent", execution.getClass(), io.agentscope.core.event.AgentEvent.class);
        method.setAccessible(true);
        method.invoke(runtime, execution, event);
    }

    private static boolean executionClosed(AgentScopeRuntime.Execution execution) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.Execution.class.getDeclaredField("closed");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(execution)).get();
    }

    private static Object executionDisposable(AgentScopeRuntime.Execution execution) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.Execution.class.getDeclaredField("disposable");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(execution)).get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", error);
        }
    }

    private static final class RecordingDisposable implements Disposable {
        private final boolean failOnDispose;
        private boolean disposed;

        private RecordingDisposable(boolean failOnDispose) {
            this.failOnDispose = failOnDispose;
        }

        @Override
        public void dispose() {
            disposed = true;
            if (failOnDispose) {
                throw new IllegalStateException("disposable secret must not leak");
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private static AgentRuntimeContext context(int maxConcurrency, Consumer<RuntimeResult> sink) {
        return context(maxConcurrency, Clock.fixed(NOW, ZoneOffset.UTC), sink);
    }

    private static AgentRuntimeContext context(int maxConcurrency, Clock clock,
            Consumer<RuntimeResult> sink) {
        return new AgentRuntimeContext("agentscope-test", maxConcurrency,
                clock, sink::accept, Map.of());
    }

    private static RuntimeTask task(String attemptId, String leaseId, String correlationId) {
        return task(attemptId, leaseId, correlationId, null);
    }

    private static RuntimeTask task(String attemptId, String leaseId, String correlationId,
            String leaseExpiresAt) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of(
                "attemptId", attemptId,
                "leaseId", leaseId,
                "correlationId", correlationId,
                "agentId", "worker-test"));
        if (leaseExpiresAt != null) {
            metadata.put("leaseExpiresAt", leaseExpiresAt);
        }
        return new RuntimeTask(UUID.randomUUID(), "chat", "hello", metadata);
    }

    private static RuntimeTask taskWithInput(String attemptId, String leaseId, String correlationId,
            String inputJson) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of(
                "attemptId", attemptId,
                "leaseId", leaseId,
                "correlationId", correlationId,
                "agentId", "worker-test"));
        return new RuntimeTask(UUID.randomUUID(), "chat", inputJson, metadata);
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        private AdjustableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        private void set(Instant instant) {
            current.set(instant);
        }
    }

    private static final class DeterministicModel implements Model {
        private final String response;

        private DeterministicModel(String response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            ContentBlock text = TextBlock.builder().text(response).build();
            return Flux.just(ChatResponse.builder().id("fake-response")
                    .content(List.of(text)).usage(new ChatUsage(3, 2, 0.01)).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "fake-agentscope-model";
        }
    }

    private static final class FailingModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.error(new IllegalStateException("model secret must not leak"));
        }

        @Override
        public String getModelName() {
            return "failing-agentscope-model";
        }
    }

    private static final class BlockingModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.never();
        }

        @Override
        public String getModelName() {
            return "blocking-agentscope-model";
        }
    }

}
