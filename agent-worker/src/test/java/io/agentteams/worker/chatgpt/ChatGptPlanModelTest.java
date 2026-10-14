package io.agentteams.worker.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptPlanModelTest {
    @TempDir Path tempDir;

    @Test
    void createsResponsesRequestWithStreamingAndNoServerSideStorage() throws Exception {
        Msg user = Msg.builder().role(io.agentscope.core.message.MsgRole.USER).textContent("hello").build();
        ToolSchema tool = ToolSchema.builder().name("web_search").description("Search web")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();

        JsonNode request = ChatGptPlanModel.requestBody("gpt-test", List.of(user), List.of(tool));

        assertThat(request.path("model").asText()).isEqualTo("gpt-test");
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("input").toString()).contains("hello");
        assertThat(request.path("tools").get(0).path("name").asText()).isEqualTo("web_search");
    }

    @Test
    void forcesThePlanningFunctionWhenItIsAvailableForTheFirstTime() {
        Msg user = Msg.builder().role(MsgRole.USER).textContent("plan the task").build();
        ToolSchema tool = ToolSchema.builder().name("plan_subtasks").description("Plan subtasks")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();

        JsonNode request = ChatGptPlanModel.requestBody("gpt-test", List.of(user), List.of(tool));

        assertThat(request.path("tool_choice").path("type").asText()).isEqualTo("function");
        assertThat(request.path("tool_choice").path("name").asText()).isEqualTo("plan_subtasks");
    }

    @Test
    void doesNotForceThePlanningFunctionAgainAfterItsResultIsInConversationHistory() {
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT)
                .content(List.of(new ToolUseBlock("call-plan", "plan_subtasks", Map.of("task_id", "task-1"))))
                .build();
        ToolSchema tool = ToolSchema.builder().name("plan_subtasks").description("Plan subtasks")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();

        JsonNode request = ChatGptPlanModel.requestBody("gpt-test", List.of(assistant), List.of(tool));

        assertThat(request.has("tool_choice")).isFalse();
    }

    @Test
    void mapsSystemAndFunctionCallRoundTripToResponsesInputInOrder() {
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("Follow policy").build();
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT)
                .content(List.of(new ToolUseBlock("call-1", "web_search", Map.of("query", "AI"))))
                .build();
        Msg tool = Msg.builder().role(MsgRole.TOOL)
                .content(List.of(new ToolResultBlock("call-1", "web_search",
                        List.of(TextBlock.builder().text("result").build()))))
                .build();

        JsonNode input = ChatGptPlanModel.requestBody("gpt-test", List.of(system, assistant, tool), List.of())
                .path("input");

        assertThat(input).hasSize(3);
        assertThat(input.get(0).path("role").asText()).isEqualTo("developer");
        assertThat(input.get(0).path("content").asText()).isEqualTo("Follow policy");
        assertThat(input.get(1).path("type").asText()).isEqualTo("function_call");
        assertThat(input.get(1).path("call_id").asText()).isEqualTo("call-1");
        assertThat(input.get(1).path("arguments").asText()).isEqualTo("{\"query\":\"AI\"}");
        assertThat(input.get(2).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(2).path("call_id").asText()).isEqualTo("call-1");
        assertThat(input.get(2).path("output").asText()).isEqualTo("result");
    }

    @Test
    void configuresAnHttpConnectProxyWithoutAcceptingProxyCredentials() {
        var proxy = ChatGptPlanModel.proxyAddress("http://192.168.125.227:7892");

        assertThat(proxy.getHostString()).isEqualTo("192.168.125.227");
        assertThat(proxy.getPort()).isEqualTo(7892);
        assertThatThrownBy(() -> ChatGptPlanModel.proxyAddress("http://user:secret@proxy.example:7892"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ChatGPT plan proxy URL must not contain credentials");

        var configuredProxy = ChatGptPlanModel.httpClient("http://192.168.125.227:7892")
                .proxy().orElseThrow().select(URI.create("https://api.openai.com/v1/responses")).get(0);
        assertThat(configuredProxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(((InetSocketAddress) configuredProxy.address()).getPort()).isEqualTo(7892);
    }

    @Test
    void readsRotatedAccessTokenFromProjectedSecretFileOnEveryRequest() throws Exception {
        Path tokenFile = tempDir.resolve("access-token");
        Files.writeString(tokenFile, "first-token\n");
        ChatGptPlanTokenSource source = ChatGptPlanTokenSource.fromPath(tokenFile);

        assertThat(source.accessToken()).isEqualTo("first-token");
        Files.writeString(tokenFile, "rotated-token\n");
        assertThat(source.accessToken()).isEqualTo("rotated-token");
    }

    @Test
    void failsClosedWhenProjectedTokenFileIsMissingOrEmpty() throws Exception {
        ChatGptPlanTokenSource source = ChatGptPlanTokenSource.fromPath(tempDir.resolve("missing"));

        assertThatThrownBy(source::accessToken).isInstanceOf(IllegalStateException.class)
                .hasMessage("ChatGPT plan access token is unavailable");
        Path empty = tempDir.resolve("empty");
        Files.createFile(empty);
        assertThatThrownBy(ChatGptPlanTokenSource.fromPath(empty)::accessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ChatGPT plan access token is unavailable");
    }

    @Test
    void parsesCompletedResponsesWithLocalFunctionCalls() {
        String event = """
                {"type":"response.completed","response":{"id":"resp-1","output":[
                  {"type":"message","content":[{"type":"output_text","text":"Searching now"}]},
                  {"type":"function_call","call_id":"call-1","name":"web_search","arguments":"{\\"query\\":\\"AI news\\"}"}
                ],"usage":{"input_tokens":12,"output_tokens":4}}}
                """;

        var response = ChatGptPlanModel.parseCompletedEvent(event);

        assertThat(response.getId()).isEqualTo("resp-1");
        assertThat(response.getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getContent()).anySatisfy(block -> assertThat(((TextBlock) block).getText())
                .isEqualTo("Searching now"));
        assertThat(response.getContent()).filteredOn(ToolUseBlock.class::isInstance).singleElement()
                .satisfies(block -> {
                    ToolUseBlock call = (ToolUseBlock) block;
                    assertThat(call.getId()).isEqualTo("call-1");
                    assertThat(call.getName()).isEqualTo("web_search");
                    assertThat(call.getInput()).containsEntry("query", "AI news");
                    assertThat(call.getContent()).isEqualTo("{\"query\":\"AI news\"}");
                });
        assertThat(response.getUsage().getInputTokens()).isEqualTo(12);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(4);
    }

    @Test
    void rejectsMalformedOrDuplicateFunctionCallIds() {
        String missingId = """
                {"type":"response.completed","response":{"output":[
                  {"type":"function_call","name":"web_search","arguments":"{}"}
                ]}}
                """;
        String duplicateId = """
                {"type":"response.completed","response":{"output":[
                  {"type":"function_call","call_id":"call-1","name":"web_search","arguments":"{}"},
                  {"type":"function_call","call_id":"call-1","name":"search_news","arguments":"{}"}
                ]}}
                """;

        assertThatThrownBy(() -> ChatGptPlanModel.parseCompletedEvent(missingId))
                .hasMessageContaining("INVALID_RESPONSE");
        assertThatThrownBy(() -> ChatGptPlanModel.parseCompletedEvent(duplicateId))
                .hasMessageContaining("INVALID_RESPONSE");
    }

    @Test
    void classifiesProviderTimeoutsWithoutExposingDetails() {
        assertThat(ChatGptPlanModel.networkErrorCode(new java.net.http.HttpTimeoutException("private detail")))
                .isEqualTo("REQUEST_TIMEOUT");
    }

    @Test
    void sendsOAuthRequestAndConsumesResponsesSseThroughCompletion() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/responses", exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("Authorization") + "\n"
                    + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = ("event: response.completed\ndata: {\"type\":\"response.completed\","
                    + "\"response\":{\"id\":\"resp-sse\",\"output\":[{\"type\":\"message\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"Done\"}]}]}}\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());

            var response = model.stream(List.of(Msg.builder()
                            .role(io.agentscope.core.message.MsgRole.USER).textContent("question").build()),
                    List.of(), null).blockLast(Duration.ofSeconds(5));

            assertThat(response.getId()).isEqualTo("resp-sse");
            assertThat(((TextBlock) response.getContent().get(0)).getText()).isEqualTo("Done");
            assertThat(observed.get()).contains("Bearer oauth-access-token", "\"store\":false",
                    "\"stream\":true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOneTransientProviderFailureFromResponsesStream() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            int attempt = attempts.incrementAndGet();
            String body = attempt == 1
                    ? "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\"}}}\n\n"
                    : "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-retry\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Recovered\"}]}]}}\n\n";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());

            var response = model.stream(List.of(Msg.builder().role(MsgRole.USER)
                            .textContent("question").build()), List.of(), null)
                    .blockLast(Duration.ofSeconds(8));

            assertThat(response.getId()).isEqualTo("resp-retry");
            assertThat(attempts).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reconstructsFunctionCallsFromStreamingItemsWhenCompletedResponseOutputIsEmpty() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/responses", exchange -> {
            observed.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String response = """
                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc-plan","call_id":"call-plan","name":"plan_subtasks","arguments":""}}

                    event: response.function_call_arguments.delta
                    data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"task_id\\":\\"task-1\\"}"}

                    event: response.function_call_arguments.done
                    data: {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\\"task_id\\":\\"task-1\\"}"}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc-plan","call_id":"call-plan","name":"plan_subtasks","arguments":"{\\"task_id\\":\\"task-1\\"}"}}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp-empty-output","status":"completed","output":[]}}

                    """;
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());
            ToolSchema planTool = ToolSchema.builder().name("plan_subtasks").description("Plan subtasks")
                    .parameters(Map.of("type", "object", "properties", Map.of())).build();

            var response = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("plan").build()),
                    List.of(planTool), null).blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).filteredOn(ToolUseBlock.class::isInstance).singleElement()
                    .satisfies(block -> {
                        ToolUseBlock call = (ToolUseBlock) block;
                        assertThat(call.getName()).isEqualTo("plan_subtasks");
                        assertThat(call.getInput()).containsEntry("task_id", "task-1");
                    });
            assertThat(observed.get()).contains("\"tool_choice\":{\"type\":\"function\",\"name\":\"plan_subtasks\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reconstructsFinalTextFromStreamingDeltasWhenCompletedResponseOutputIsEmpty() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            String response = """
                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","content":[]}}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","output_index":0,"delta":"最终中文答复"}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp-empty-text","status":"completed","output":[]}}

                    """;
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());

            var response = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("总结证据")
                    .build()), List.of(), null).blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).filteredOn(TextBlock.class::isInstance).singleElement()
                    .satisfies(block -> assertThat(((TextBlock) block).getText()).isEqualTo("最终中文答复"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOneEmptyCompletionWithAFinalResponseReminder() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            int requestNumber = requests.size();
            String eventData = requestNumber == 1
                    ? "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-empty\",\"output\":[]}}"
                    : "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-final\",\"output\":["
                            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final answer\"}]}]}}";
            String response = "event: response.completed\ndata: " + eventData + "\n\n";
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());

            Msg user = Msg.builder().role(MsgRole.USER).textContent("answer").build();
            Msg taskResult = Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(new ToolResultBlock("call-result", "get_task_result",
                            List.of(TextBlock.builder().text("child result and selected PDF metadata").build()))
                            .withState(io.agentscope.core.message.ToolResultState.SUCCESS)))
                    .build();
            var response = model.stream(List.of(user, taskResult), List.of(), null)
                    .blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).singleElement().satisfies(block ->
                    assertThat(((TextBlock) block).getText()).isEqualTo("final answer"));
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).contains("returned an empty response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesAnEmptyCompletionAfterSuccessfulResearchTools() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            int requestNumber = requests.size();
            String eventData = requestNumber == 1
                    ? "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-empty\",\"output\":[]}}"
                    : "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-final\",\"output\":["
                            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"research brief\"}]}]}}";
            byte[] body = ("event: response.completed\ndata: " + eventData + "\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());
            Msg user = Msg.builder().role(MsgRole.USER).textContent("research").build();
            Msg searchResult = Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(new ToolResultBlock("call-search", "web_search",
                            List.of(TextBlock.builder().text("verified research result").build()))
                            .withState(io.agentscope.core.message.ToolResultState.SUCCESS)))
                    .build();
            ToolSchema webSearch = ToolSchema.builder().name("web_search").description("Search web")
                    .parameters(Map.of("type", "object", "properties", Map.of())).build();

            var response = model.stream(List.of(user, searchResult), List.of(webSearch), null)
                    .blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).singleElement().satisfies(block ->
                    assertThat(((TextBlock) block).getText()).isEqualTo("research brief"));
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).contains("returned an empty response");
            ObjectMapper json = new ObjectMapper();
            JsonNode initialRequest = json.readTree(requests.get(0));
            JsonNode finalizationRequest = json.readTree(requests.get(1));
            assertThat(initialRequest.path("tools")).hasSize(1);
            assertThat(finalizationRequest.has("tools")).isFalse();
            assertThat(finalizationRequest.path("input").toString())
                    .contains("research", "verified research result")
                    .doesNotContain("function_call_output");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTwiceWhenResponsesRemainEmptyAfterSuccessfulResearchTools() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            int requestNumber = requests.size();
            String eventData = requestNumber < 3
                    ? "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-empty\",\"output\":[]}}"
                    : "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-final\",\"output\":["
                            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"research brief\"}]}]}}";
            byte[] body = ("event: response.completed\ndata: " + eventData + "\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());
            Msg user = Msg.builder().role(MsgRole.USER).textContent("research").build();
            Msg searchResult = Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(new ToolResultBlock("call-search", "web_search",
                            List.of(TextBlock.builder().text("verified research result").build()))
                            .withState(io.agentscope.core.message.ToolResultState.SUCCESS)))
                    .build();

            var response = model.stream(List.of(user, searchResult), List.of(), null)
                    .blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).singleElement().satisfies(block ->
                    assertThat(((TextBlock) block).getText()).isEqualTo("research brief"));
            assertThat(requests).hasSize(3);
            assertThat(requests.get(1)).contains("returned an empty response");
            assertThat(requests.get(2)).contains("returned an empty response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsAfterTwoFinalizationRetriesInsteadOfReturningEmptySuccess() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = ("event: response.completed\ndata: {\"type\":\"response.completed\","
                    + "\"response\":{\"id\":\"resp-empty\",\"output\":[]}}\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());
            Msg user = Msg.builder().role(MsgRole.USER).textContent("research").build();
            Msg searchResult = Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(new ToolResultBlock("call-search", "web_search",
                            List.of(TextBlock.builder().text("verified research result").build()))
                            .withState(io.agentscope.core.message.ToolResultState.SUCCESS)))
                    .build();

            assertThatThrownBy(() -> model.stream(List.of(user, searchResult), List.of(), null)
                    .blockLast(Duration.ofSeconds(5))).hasMessageContaining("EMPTY_FINAL_RESPONSE");
            assertThat(requests).hasSize(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emitsPlanningHandoffWhenModelStopsImmediatelyAfterSubmittingPlan() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-empty\",\"output\":[]}}\n\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "oauth-access-token",
                    HttpClient.newHttpClient());
            Msg planResult = Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(new ToolResultBlock("call-plan", "plan_subtasks",
                            List.of(TextBlock.builder().text("plan accepted").build()))
                            .withState(io.agentscope.core.message.ToolResultState.SUCCESS)))
                    .build();

            var response = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("plan").build(),
                    planResult), List.of(), null).blockLast(Duration.ofSeconds(5));

            assertThat(response.getContent()).singleElement().satisfies(block ->
                    assertThat(((TextBlock) block).getText()).contains("waiting for subtasks"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotExposeProviderErrorBodyInExceptionMessage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = "sensitive-provider-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "token",
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> model.stream(List.of(), List.of(), null).blockLast(Duration.ofSeconds(5)))
                    .hasMessageContaining("AUTHENTICATION_FAILED")
                    .hasMessageNotContaining("sensitive-provider-body");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEventStreamThatEndsBeforeResponseCompleted() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\"}\n\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatGptPlanModel("gpt-test", URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/responses"), () -> "token",
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> model.stream(List.of(), List.of(), null).blockLast(Duration.ofSeconds(5)))
                    .hasMessageContaining("INCOMPLETE_RESPONSE");
        } finally {
            server.stop(0);
        }
    }
}
