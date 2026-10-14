package io.agentteams.worker.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/** AgentScope model adapter for OAuth-backed ChatGPT plan usage through the Responses API. */
public final class ChatGptPlanModel implements Model {
    static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses";
    static final String TOKEN_FILE_ENV = "AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE";
    private static final int MAX_EMPTY_COMPLETION_RETRIES = 2;
    private static final int MAX_TRANSIENT_PROVIDER_RETRIES = 1;
    private static final int MAX_FINALIZATION_EVIDENCE_CHARS = 32_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ChatGptPlanModel.class.getName());

    private final String modelName;
    private final URI endpoint;
    private final ChatGptPlanTokenSource tokens;
    private final HttpClient http;

    public ChatGptPlanModel(String modelName, ChatGptPlanTokenSource tokens) {
        this(modelName, URI.create(System.getenv().getOrDefault("AGENTTEAMS_CHATGPT_RESPONSES_URL", DEFAULT_ENDPOINT)),
                tokens, defaultHttpClient());
    }

    static InetSocketAddress proxyAddress(String proxyUrl) {
        final URI proxy;
        try {
            proxy = URI.create(requireText(proxyUrl, "proxyUrl"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("ChatGPT plan proxy URL is invalid");
        }
        if (proxy.getRawUserInfo() != null) {
            throw new IllegalArgumentException("ChatGPT plan proxy URL must not contain credentials");
        }
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getPort() < 1 || proxy.getPort() > 65535
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty())
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null) {
            throw new IllegalArgumentException("ChatGPT plan proxy URL is invalid");
        }
        return new InetSocketAddress(proxy.getHost(), proxy.getPort());
    }

    private static HttpClient defaultHttpClient() {
        return httpClient(System.getenv("AGENTTEAMS_CHATGPT_PROXY_URL"));
    }

    static HttpClient httpClient(String proxyUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            builder.proxy(ProxySelector.of(proxyAddress(proxyUrl)));
        }
        return builder.build();
    }

    ChatGptPlanModel(String modelName, URI endpoint, ChatGptPlanTokenSource tokens, HttpClient http) {
        this.modelName = requireText(modelName, "modelName");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.http = Objects.requireNonNull(http, "http");
    }

    public static ChatGptPlanModel fromEnvironment(String modelId) {
        String model = modelId.substring(modelId.indexOf(':') + 1).trim();
        String tokenPath = System.getenv(TOKEN_FILE_ENV);
        if (tokenPath == null || tokenPath.isBlank()) {
            throw new IllegalStateException(TOKEN_FILE_ENV + " must be configured for chatgpt-plan models");
        }
        return new ChatGptPlanModel(model, ChatGptPlanTokenSource.fromPath(Path.of(tokenPath)));
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.create(sink -> {
            AtomicReference<CompletableFuture<?>> activeCall = new AtomicReference<>();
            AtomicReference<InputStream> activeStream = new AtomicReference<>();
            sink.onCancel(() -> {
                CompletableFuture<?> call = activeCall.get();
                if (call != null) call.cancel(true);
                close(activeStream.get());
            });
            sendRequest(messages, tools, sink, 0, 0, activeCall, activeStream);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void sendRequest(List<Msg> messages, List<ToolSchema> tools,
            FluxSink<ChatResponse> sink, int emptyCompletionRetries, int transientProviderRetries,
            AtomicReference<CompletableFuture<?>> activeCall,
            AtomicReference<InputStream> activeStream) {
        if (sink.isCancelled()) return;
        final HttpRequest request;
        try {
            ObjectNode body = requestBody(modelName, messages, tools);
            LOG.log(System.Logger.Level.INFO,
                    "ChatGPT Responses request model={0} tools={1} toolChoice={2}",
                    modelName, toolNames(tools), body.path("tool_choice").toString());
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (RuntimeException error) {
            sink.error(error);
            return;
        }
        CompletableFuture<HttpResponse<InputStream>> call = http.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        activeCall.set(call);
        call.whenComplete((response, error) -> {
            if (error != null) {
                if (!sink.isCancelled()) sink.error(new ChatGptPlanException(networkErrorCode(error), unwrap(error)));
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                if (!sink.isCancelled()) sink.error(new ChatGptPlanException(httpErrorCode(response.statusCode())));
                return;
            }
            activeStream.set(response.body());
            boolean hasSuccessfulToolOutput = emptyCompletionRetries > 0
                    || hasSuccessfulNonPlanningToolOutput(messages);
            boolean completePlanHandoff = hasSuccessfulToolOutput(messages, "plan_subtasks");
            CompletableFuture.runAsync(() -> consume(response.body(), sink, hasSuccessfulToolOutput,
                    completePlanHandoff))
                    .exceptionally(failure -> {
                        Throwable root = unwrapCause(failure);
                        if (root instanceof EmptyModelResponseException) {
                            if (emptyCompletionRetries < MAX_EMPTY_COMPLETION_RETRIES) {
                                int retry = emptyCompletionRetries + 1;
                                LOG.log(System.Logger.Level.WARNING,
                                        "ChatGPT Responses returned an empty completion; retry {0}/{1} for final output",
                                        retry, MAX_EMPTY_COMPLETION_RETRIES);
                                // Once research tools have completed, force retries to be text-only.
                                // Leaving the tool schemas enabled lets the model start another
                                // search batch instead of handing off the evidence it already has.
                                sendRequest(withFinalOutputReminder(messages, retry), List.of(), sink, retry,
                                        transientProviderRetries,
                                        activeCall, activeStream);
                            } else if (!sink.isCancelled()) {
                                sink.error(new ChatGptPlanException("EMPTY_FINAL_RESPONSE"));
                            }
                        } else if (root instanceof TransientProviderException transientFailure) {
                            if (transientProviderRetries < MAX_TRANSIENT_PROVIDER_RETRIES && !sink.isCancelled()) {
                                int retry = transientProviderRetries + 1;
                                LOG.log(System.Logger.Level.WARNING,
                                        "ChatGPT Responses transient provider failure code={0}; retry {1}/{2}",
                                        transientFailure.code(), retry, MAX_TRANSIENT_PROVIDER_RETRIES);
                                CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                                        .execute(() -> sendRequest(messages, tools, sink, emptyCompletionRetries,
                                                retry, activeCall, activeStream));
                            } else if (!sink.isCancelled()) {
                                sink.error(new ChatGptPlanException(transientFailure.code()));
                            }
                        } else if (root instanceof ChatGptPlanException planFailure) {
                            if (!sink.isCancelled()) sink.error(planFailure);
                        } else if (!sink.isCancelled()) {
                            sink.error(new ChatGptPlanException("STREAM_ERROR", unwrap(failure)));
                        }
                        return null;
                    });
        });
    }

    private static List<Msg> withFinalOutputReminder(List<Msg> messages, int retry) {
        ArrayList<Msg> retryMessages = new ArrayList<>();
        StringBuilder originalRequest = new StringBuilder();
        StringBuilder evidence = new StringBuilder();
        for (Msg message : messages == null ? List.<Msg>of() : messages) {
            if (message.getRole() == io.agentscope.core.message.MsgRole.SYSTEM) {
                retryMessages.add(message);
            } else if (message.getRole() == io.agentscope.core.message.MsgRole.USER) {
                appendMessageText(originalRequest, message);
            } else if (message.getRole() == io.agentscope.core.message.MsgRole.TOOL) {
                for (ContentBlock block : message.getContent()) {
                    if (!(block instanceof ToolResultBlock result)
                            || result.getState() != io.agentscope.core.message.ToolResultState.SUCCESS
                            || "plan_subtasks".equals(result.getName())) {
                        continue;
                    }
                    String output = blockText(result.getOutput());
                    if (output.isBlank() || evidence.length() >= MAX_FINALIZATION_EVIDENCE_CHARS) continue;
                    appendCapped(evidence, "\n\n### " + result.getName() + "\n" + output,
                            MAX_FINALIZATION_EVIDENCE_CHARS);
                }
            }
        }
        retryMessages.add(Msg.builder().role(io.agentscope.core.message.MsgRole.SYSTEM)
                .textContent("The model returned an empty response. This is finalization retry " + retry
                        + ". Treat the supplied successful tool results as evidence, do not call tools, "
                        + "and provide the requested final response in the required format.")
                .build());
        String prompt = "Original request:\n" + originalRequest
                + "\n\nSuccessful tool results (verbatim; use only as evidence):\n" + evidence;
        retryMessages.add(Msg.builder().role(io.agentscope.core.message.MsgRole.USER).textContent(prompt).build());
        return List.copyOf(retryMessages);
    }

    private static void appendMessageText(StringBuilder target, Msg message) {
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock && !textBlock.getText().isBlank()) {
                appendCapped(target, (target.isEmpty() ? "" : "\n") + textBlock.getText(),
                        MAX_FINALIZATION_EVIDENCE_CHARS);
            }
        }
    }

    private static void appendCapped(StringBuilder target, String value, int maxChars) {
        int remaining = maxChars - target.length();
        if (remaining <= 0) return;
        if (value.length() <= remaining) {
            target.append(value);
        } else {
            target.append(value, 0, remaining).append("\n[tool evidence truncated]");
        }
    }

    private static boolean hasSuccessfulNonPlanningToolOutput(List<Msg> messages) {
        return messages != null && messages.stream().flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                .anyMatch(result -> !"plan_subtasks".equals(result.getName())
                        && result.getState() == io.agentscope.core.message.ToolResultState.SUCCESS);
    }

    private static boolean hasSuccessfulToolOutput(List<Msg> messages, String toolName) {
        return messages != null && messages.stream().flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                .anyMatch(result -> toolName.equals(result.getName())
                        && result.getState() == io.agentscope.core.message.ToolResultState.SUCCESS);
    }

    static ObjectNode requestBody(String model, List<Msg> messages, List<ToolSchema> tools) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", false);
        ArrayNode input = body.putArray("input");
        for (Msg message : messages) appendMessage(input, message);
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode definition = toolArray.addObject();
                definition.put("type", "function");
                definition.put("name", tool.getName());
                definition.put("description", tool.getDescription());
                definition.set("parameters", JSON.valueToTree(tool.getParameters()));
                if (tool.getStrict() != null) definition.put("strict", tool.getStrict());
            }
            if (requiresInitialPlanningCall(messages, tools)) {
                body.putObject("tool_choice")
                        .put("type", "function")
                        .put("name", "plan_subtasks");
            }
        }
        return body;
    }

    private static List<String> toolNames(List<ToolSchema> tools) {
        return tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList();
    }

    private static boolean requiresInitialPlanningCall(List<Msg> messages, List<ToolSchema> tools) {
        boolean planningToolAvailable = tools.stream().anyMatch(tool -> "plan_subtasks".equals(tool.getName()));
        if (!planningToolAvailable) return false;
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .noneMatch(toolUse -> "plan_subtasks".equals(toolUse.getName()));
    }

    private static void appendMessage(ArrayNode input, Msg message) {
        String role = switch (message.getRole()) {
            case SYSTEM -> "developer";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock) {
                if ("tool".equals(role) && textBlock.getText().startsWith("Error:")) {
                    LOG.log(System.Logger.Level.WARNING, "ChatGPT tool message error output={0}",
                            safeToolErrorSummary(textBlock.getText()));
                }
                if (!text.isEmpty()) text.append('\n');
                text.append(textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                appendText(input, role, text);
                ObjectNode call = input.addObject();
                call.put("type", "function_call");
                call.put("call_id", toolUse.getId());
                call.put("name", toolUse.getName());
                call.put("arguments", JSON.valueToTree(toolUse.getInput()).toString());
                text.setLength(0);
            } else if (block instanceof ToolResultBlock result) {
                appendText(input, role, text);
                ObjectNode output = input.addObject();
                output.put("type", "function_call_output");
                output.put("call_id", result.getId());
                String toolOutput = blockText(result.getOutput());
                if (result.getState() == io.agentscope.core.message.ToolResultState.ERROR
                        || toolOutput.startsWith("Error:")) {
                    LOG.log(System.Logger.Level.WARNING, "ChatGPT plan tool error name={0} output={1}",
                            new Object[] {result.getName(), safeToolErrorSummary(toolOutput)});
                }
                output.put("output", toolOutput);
                text.setLength(0);
            } else {
                throw new IllegalArgumentException("ChatGPT plan provider does not support content block: "
                        + block.getClass().getSimpleName());
            }
        }
        appendText(input, role, text);
    }

    private static void appendText(ArrayNode input, String role, StringBuilder text) {
        if (text.isEmpty()) return;
        ObjectNode item = input.addObject();
        item.put("role", role);
        item.put("content", text.toString());
    }

    private static String blockText(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                if (!text.isEmpty()) text.append('\n');
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    private static String safeToolErrorSummary(String value) {
        if (value == null) return "<empty>";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(authorization|api[-_ ]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s,;]+",
                        "$1$2[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    static ChatResponse parseCompletedEvent(String eventData) {
        try {
            JsonNode event = JSON.readTree(eventData);
            JsonNode response = event.path("response");
            ArrayList<ContentBlock> content = new ArrayList<>();
            Set<String> functionCallIds = new HashSet<>();
            JsonNode output = response.path("output");
            List<String> outputTypes = new ArrayList<>();
            List<String> functionCallShapes = new ArrayList<>();
            output.forEach(item -> outputTypes.add(item.path("type").asText("unknown")));
            LOG.log(System.Logger.Level.INFO,
                    "ChatGPT Responses completed status={0} incompleteReason={1} errorType={2} errorCode={3} "
                            + "outputIsArray={4} outputTypes={5}",
                    response.path("status").asText(""),
                    response.path("incomplete_details").path("reason").asText(""),
                    response.path("error").path("type").asText(""),
                    response.path("error").path("code").asText(""),
                    output.isArray(), outputTypes);
            for (JsonNode item : output) {
                if ("message".equals(item.path("type").asText())) {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) {
                            content.add(TextBlock.builder().text(part.path("text").asText()).build());
                        } else if ("refusal".equals(part.path("type").asText())) {
                            content.add(TextBlock.builder().text(part.path("refusal").asText()).build());
                        }
                    }
                } else if ("function_call".equals(item.path("type").asText())) {
                    String callId = item.path("call_id").asText();
                    String name = item.path("name").asText();
                    JsonNode argumentsNode = item.path("arguments");
                    if (callId.isBlank() || name.isBlank() || !argumentsNode.isTextual()
                            || argumentsNode.asText().isBlank() || !functionCallIds.add(callId)) {
                        throw new IllegalArgumentException("Malformed function call");
                    }
                    String arguments = argumentsNode.asText();
                    Map<String, Object> values = JSON.readValue(arguments,
                            JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                    functionCallShapes.add(functionCallShape(name, values));
                    // AgentScope's ToolExecutor validates the raw argument JSON in
                    // ToolUseBlock.content; input is used later for invocation/history.
                    content.add(new ToolUseBlock(callId, name, values, arguments, Map.of()));
                }
            }
            if (!functionCallShapes.isEmpty()) {
                LOG.log(System.Logger.Level.INFO, "ChatGPT function call argument shapes={0}", functionCallShapes);
            }
            JsonNode usageNode = response.path("usage");
            ChatUsage usage = usageNode.isObject()
                    ? new ChatUsage(usageNode.path("input_tokens").asInt(), usageNode.path("output_tokens").asInt(), 0)
                    : null;
            String finishReason = content.stream().anyMatch(ToolUseBlock.class::isInstance) ? "tool_calls" : "stop";
            return ChatResponse.builder().id(response.path("id").asText()).content(content)
                    .usage(usage).finishReason(finishReason).build();
        } catch (Exception error) {
            throw new ChatGptPlanException("INVALID_RESPONSE", error);
        }
    }

    /** Logs field names and value types only; never logs argument values or prompt content. */
    private static String functionCallShape(String name, Map<String, Object> arguments) {
        StringBuilder shape = new StringBuilder(name).append("{keys=").append(arguments.keySet());
        if ("plan_subtasks".equals(name)) {
            Object subtasks = arguments.get("subtasks");
            shape.append(",subtasksType=").append(valueType(subtasks));
            if (subtasks instanceof List<?> items) {
                shape.append(",subtaskCount=").append(items.size()).append(",subtaskShapes=[");
                for (int index = 0; index < items.size(); index++) {
                    if (index > 0) shape.append(';');
                    Object item = items.get(index);
                    if (item instanceof Map<?, ?> fields) {
                        shape.append("keys=").append(fields.keySet())
                                .append(";subtaskIdType=").append(valueType(fields.get("subtaskId")))
                                .append(";sequenceType=").append(valueType(fields.get("sequence")))
                                .append(";dependencyIdsType=").append(valueType(fields.get("dependencyIds")))
                                .append(";requiredMcpToolsType=").append(valueType(fields.get("requiredMcpTools")));
                    } else {
                        shape.append("itemType=").append(valueType(item));
                    }
                }
                shape.append(']');
            }
        }
        return shape.append('}').toString();
    }

    private static String valueType(Object value) {
        if (value == null) return "missing";
        if (value instanceof List<?> list) return "array(" + list.size() + ")";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return value.getClass().getSimpleName();
    }

    private static void consume(InputStream stream, FluxSink<ChatResponse> sink,
            boolean retryEmptyCompletion, boolean completePlanHandoff) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            Map<Integer, ObjectNode> streamedFunctionCalls = new TreeMap<>();
            Map<Integer, Map<Integer, StringBuilder>> streamedText = new TreeMap<>();
            boolean completed = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        String event = data.toString();
                        if (event.startsWith("{")) {
                            JsonNode parsed = JSON.readTree(event);
                            collectFunctionCallEvent(parsed, streamedFunctionCalls);
                            collectOutputTextEvent(parsed, streamedText);
                            if ("response.completed".equals(parsed.path("type").asText())) {
                                ChatResponse response = parseCompletedEvent(withStreamedOutput(parsed,
                                        streamedFunctionCalls, streamedText).toString());
                                if (retryEmptyCompletion && response.getContent().isEmpty()) {
                                    throw new EmptyModelResponseException();
                                }
                                if (completePlanHandoff && response.getContent().isEmpty()) {
                                    response = ChatResponse.builder().id(response.getId())
                                            .content(List.of(TextBlock.builder()
                                                    .text("Plan submitted; waiting for subtasks.").build()))
                                            .usage(response.getUsage()).finishReason("stop").build();
                                }
                                sink.next(response);
                                completed = true;
                            } else if ("error".equals(parsed.path("type").asText())
                                    || "response.failed".equals(parsed.path("type").asText())) {
                                String code = safeProviderErrorCode(parsed);
                                if (isTransientProviderError(code)) {
                                    throw new TransientProviderException(code);
                                }
                                sink.error(new ChatGptPlanException(code));
                                return;
                            }
                        }
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!completed) throw new ChatGptPlanException("INCOMPLETE_RESPONSE");
            sink.complete();
        } catch (EmptyModelResponseException error) {
            throw error;
        } catch (TransientProviderException error) {
            throw error;
        } catch (ChatGptPlanException error) {
            sink.error(error);
        } catch (Exception error) {
            sink.error(new ChatGptPlanException("STREAM_ERROR", error));
        }
    }

    private static String safeProviderErrorCode(JsonNode event) {
        JsonNode error = event.path("response").path("error");
        if (!error.isObject()) error = event.path("error");
        String code = error.path("code").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (code.isEmpty()) code = error.path("type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        return code.matches("[a-z0-9_-]{1,64}") ? "PROVIDER_" + code.toUpperCase(java.util.Locale.ROOT)
                : "PROVIDER_ERROR";
    }

    private static boolean isTransientProviderError(String code) {
        return Set.of("PROVIDER_SERVER_ERROR", "PROVIDER_RATE_LIMIT_EXCEEDED",
                "PROVIDER_TEMPORARILY_UNAVAILABLE", "PROVIDER_CONNECTION_ERROR").contains(code);
    }

    private static void collectFunctionCallEvent(JsonNode event, Map<Integer, ObjectNode> outputItems) {
        String type = event.path("type").asText();
        int outputIndex = event.path("output_index").asInt(-1);
        if (outputIndex < 0) return;
        if (("response.output_item.added".equals(type) || "response.output_item.done".equals(type))
                && "function_call".equals(event.path("item").path("type").asText())) {
            outputItems.put(outputIndex, ((ObjectNode) event.path("item")).deepCopy());
            return;
        }
        ObjectNode item = outputItems.get(outputIndex);
        if (item == null) return;
        if ("response.function_call_arguments.delta".equals(type)) {
            String current = item.path("arguments").asText("");
            item.put("arguments", current + event.path("delta").asText(""));
        } else if ("response.function_call_arguments.done".equals(type)) {
            item.put("arguments", event.path("arguments").asText(""));
        }
    }

    private static JsonNode withStreamedOutput(JsonNode completedEvent,
            Map<Integer, ObjectNode> streamedFunctionCalls,
            Map<Integer, Map<Integer, StringBuilder>> streamedText) {
        if (!completedEvent.path("response").path("output").isEmpty()
                || (streamedFunctionCalls.isEmpty() && streamedText.isEmpty())) {
            return completedEvent;
        }
        ObjectNode copy = completedEvent.deepCopy();
        ArrayNode output = JSON.createArrayNode();
        Set<Integer> indices = new java.util.TreeSet<>();
        indices.addAll(streamedFunctionCalls.keySet());
        indices.addAll(streamedText.keySet());
        for (Integer index : indices) {
            ObjectNode functionCall = streamedFunctionCalls.get(index);
            if (functionCall != null) {
                output.add(functionCall);
                continue;
            }
            ObjectNode message = JSON.createObjectNode();
            message.put("type", "message");
            ArrayNode content = message.putArray("content");
            StringBuilder text = new StringBuilder();
            streamedText.get(index).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (text.length() > 0) text.append('\n');
                        text.append(entry.getValue());
                    });
            if (!text.isEmpty()) {
                content.addObject().put("type", "output_text").put("text", text.toString());
                output.add(message);
            }
        }
        ((ObjectNode) copy.path("response")).set("output", output);
        return copy;
    }

    private static void collectOutputTextEvent(JsonNode event,
            Map<Integer, Map<Integer, StringBuilder>> outputText) {
        String type = event.path("type").asText();
        int outputIndex = event.path("output_index").asInt(-1);
        if (outputIndex < 0) return;
        int contentIndex = event.path("content_index").asInt(0);
        String text = null;
        boolean replace = false;
        if ("response.output_text.delta".equals(type)) {
            text = event.path("delta").asText("");
        } else if ("response.output_text.done".equals(type)) {
            text = event.path("text").asText("");
            replace = true;
        } else if ("response.content_part.done".equals(type)
                && "output_text".equals(event.path("part").path("type").asText())) {
            text = event.path("part").path("text").asText("");
            replace = true;
        }
        if (text == null || text.isEmpty()) return;
        StringBuilder content = outputText.computeIfAbsent(outputIndex, ignored -> new TreeMap<>())
                .computeIfAbsent(contentIndex, ignored -> new StringBuilder());
        if (replace) content.setLength(0);
        content.append(text);
    }

    private static String httpErrorCode(int status) {
        if (status == 401) return "AUTHENTICATION_FAILED";
        if (status == 403) return "AUTHORIZATION_FAILED";
        if (status == 429) return "RATE_LIMITED";
        if (status >= 500) return "PROVIDER_UNAVAILABLE";
        return "PROVIDER_REJECTED_REQUEST";
    }

    static String networkErrorCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.net.http.HttpTimeoutException) return "REQUEST_TIMEOUT";
        }
        return "NETWORK_ERROR";
    }

    private static RuntimeException unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? new RuntimeException(error.getCause().getClass().getSimpleName()) : new RuntimeException(error.getClass().getSimpleName());
    }

    private static Throwable unwrapCause(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void close(InputStream stream) {
        try { stream.close(); } catch (Exception ignored) { }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    static final class ChatGptPlanException extends RuntimeException {
        private final String code;
        ChatGptPlanException(String code) { super("ChatGPT plan request failed: " + code); this.code = code; }
        ChatGptPlanException(String code, Throwable cause) {
            super("ChatGPT plan request failed: " + code, cause); this.code = code;
        }
        String code() { return code; }
    }

    private static final class TransientProviderException extends RuntimeException {
        private final String code;
        private TransientProviderException(String code) {
            super("ChatGPT plan request failed: " + code);
            this.code = code;
        }
        private String code() { return code; }
    }

    private static final class EmptyModelResponseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
