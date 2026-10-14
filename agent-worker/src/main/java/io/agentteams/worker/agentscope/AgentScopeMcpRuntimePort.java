package io.agentteams.worker.agentscope;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentteams.runtime.RuntimeMcpServer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** AgentScope adapter for the Worker MCP runtime port. */
public final class AgentScopeMcpRuntimePort implements McpRuntimePort {
    private static final System.Logger LOG = System.getLogger(AgentScopeMcpRuntimePort.class.getName());
    static final String TASK_MCP_SERVICE_HOST = "agentteams-mcp-tools";
    static final String TASK_MCP_ENDPOINT = "http://agentteams-mcp-tools:8090/mcp";
    static final Set<String> PLATFORM_TASK_TOOLS = Set.of(
            "plan_subtasks", "list_subtasks", "get_task_result", "update_subtask_status",
            "create_task_pdf_artifact", "web_search");
    private final McpCredentialProvider credentials;
    private final McpClientFactory clients;

    public AgentScopeMcpRuntimePort(McpCredentialProvider credentials) {
        this(credentials, AgentScopeMcpRuntimePort::buildClient);
    }

    AgentScopeMcpRuntimePort(McpCredentialProvider credentials, McpClientFactory clients) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    AgentScopeMcpRuntimePort(McpCredentialProvider credentials, LegacyMcpClientFactory clients) {
        this(credentials, (server, provider, context) -> clients.create(server, provider));
    }

    @Override
    public void configure(Toolkit toolkit, List<RuntimeMcpServer> servers) {
        configure(toolkit, servers, List.of());
    }

    @Override
    public void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<io.agentteams.application.api.SkillCapabilityPolicy> policies) {
        configure(toolkit, servers, policies, Set.of());
    }

    @Override
    public void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<io.agentteams.application.api.SkillCapabilityPolicy> policies,
        Set<String> requiredTaskTools) {
        configureInternal(toolkit, servers, policies, requiredTaskTools, null, false);
    }

    @Override
    public void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<io.agentteams.application.api.SkillCapabilityPolicy> policies,
            Set<String> requiredTaskTools, McpExecutionContext executionContext) {
        configureInternal(toolkit, servers, policies, requiredTaskTools, executionContext, true);
    }

    private void configureInternal(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<io.agentteams.application.api.SkillCapabilityPolicy> policies,
            Set<String> requiredTaskTools, McpExecutionContext executionContext, boolean requireExecutionContext) {
        Objects.requireNonNull(toolkit, "toolkit");
        enableToolValidationDiagnostics();
        Set<String> requestedTaskTools = requiredTaskTools == null ? Set.of()
                : requiredTaskTools.stream().filter(PLATFORM_TASK_TOOLS::contains)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<RuntimeMcpServer> effectiveServers = new ArrayList<>(servers == null ? List.of() : servers);
        if (!requestedTaskTools.isEmpty() && effectiveServers.stream().noneMatch(
                AgentScopeMcpRuntimePort::isTaskOrchestrationServer)) {
            effectiveServers.add(platformTaskServer());
        }
        if (effectiveServers.isEmpty()) return;
        SkillRuntimePolicy runtimePolicy = SkillRuntimePolicy.from(
                policies == null ? List.of() : policies);
        LOG.log(System.Logger.Level.INFO, "Configuring MCP runtime servers={0} requestedTaskTools={1}",
                new Object[] {effectiveServers.size(), requestedTaskTools});
        for (RuntimeMcpServer server : effectiveServers) {
            boolean platformTaskEndpoint = isTaskOrchestrationServer(server);
            boolean taskToolsEnabled = platformTaskEndpoint && !requestedTaskTools.isEmpty();
            LOG.log(System.Logger.Level.INFO,
                    "MCP runtime binding reference={0} platformTaskEndpoint={1} executionContextPresent={2} enabled={3} endpoint={4}",
                    new Object[] {server.reference(), platformTaskEndpoint, executionContext != null,
                            taskToolsEnabled || runtimePolicy.allows(server), safeEndpointSummary(server.endpoint())});
            if (!taskToolsEnabled && !runtimePolicy.allows(server)) continue;
            if (requireExecutionContext && isTaskOrchestrationServer(server) && executionContext == null) {
                throw new IllegalArgumentException("task MCP execution context is required");
            }
            try {
                McpClientWrapper client = Objects.requireNonNull(
                        clients.create(server, credentials,
                                carriesTaskExecutionContext(server) ? executionContext : null),
                        "MCP client");
                Toolkit.ToolRegistration registration = toolkit.registration().mcpClient(client);
                if (taskToolsEnabled) {
                    registration.enableTools(new ArrayList<>(requestedTaskTools));
                } else if (runtimePolicy.constrained()) {
                    registration.enableTools(new ArrayList<>(runtimePolicy.allowedTools()));
                }
                registration.apply();
            } catch (RuntimeException error) {
                System.getLogger(AgentScopeMcpRuntimePort.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "MCP client registration failed server=" + server.reference()
                                + " reason=" + rootMessage(error));
                throw new IllegalStateException("MCP_RUNTIME_UNAVAILABLE");
            }
        }
    }

    private static void enableToolValidationDiagnostics() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("io.agentscope.core.tool.ToolExecutor");
        logger.setLevel(java.util.logging.Level.FINE);
        logger.setUseParentHandlers(false);
        boolean hasFineHandler = java.util.Arrays.stream(logger.getHandlers())
                .anyMatch(handler -> handler.getLevel().intValue() <= java.util.logging.Level.FINE.intValue());
        if (!hasFineHandler) {
            java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
            handler.setLevel(java.util.logging.Level.FINE);
            logger.addHandler(handler);
        }
    }

    private static RuntimeMcpServer platformTaskServer() {
        return new RuntimeMcpServer("platform-task-tools", 1, "STREAMABLE_HTTP", TASK_MCP_ENDPOINT,
                "MCP_TOOLS_TOKEN", "sha256:platform-task-tools-v1");
    }

    private static boolean isTaskOrchestrationServer(RuntimeMcpServer server) {
        try {
            URI endpoint = URI.create(server.endpoint());
            String host = endpoint.getHost();
            boolean platformMcpHost = host != null && (isPlatformMcpHost(host, TASK_MCP_SERVICE_HOST)
                    || isPlatformMcpHost(host, "agentteams-web-search"));
            return platformMcpHost
                    && "/mcp".equals(endpoint.getPath());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean carriesTaskExecutionContext(RuntimeMcpServer server) {
        return isTaskOrchestrationServer(server) || isTaskEvidenceCaptureServer(server);
    }

    static boolean isTaskEvidenceCaptureServer(RuntimeMcpServer server) {
        if (server == null || server.endpoint() == null) return false;
        try {
            URI endpoint = URI.create(server.endpoint());
            return "http".equalsIgnoreCase(endpoint.getScheme())
                    && endpoint.getUserInfo() == null
                    && "ai-news-mcp".equalsIgnoreCase(endpoint.getHost())
                    && endpoint.getPort() == 8000
                    && "/mcp".equals(endpoint.getPath())
                    && endpoint.getQuery() == null
                    && endpoint.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isPlatformMcpHost(String host, String serviceName) {
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        String normalizedService = serviceName.toLowerCase(java.util.Locale.ROOT);
        return normalizedHost.equals(normalizedService)
                || normalizedHost.equals(normalizedService + ".agentteams.svc")
                || normalizedHost.equals(normalizedService + ".agentteams.svc.cluster.local");
    }

    private static String safeEndpointSummary(String value) {
        try {
            URI endpoint = URI.create(value);
            return endpoint.getScheme() + "://" + endpoint.getHost()
                    + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort())
                    + (endpoint.getPath() == null ? "" : endpoint.getPath());
        } catch (IllegalArgumentException ignored) {
            return "invalid";
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static McpClientWrapper buildClient(RuntimeMcpServer server, McpCredentialProvider credentials,
            McpExecutionContext executionContext) {
        McpClientBuilder builder = McpClientBuilder.create("agentteams-" + server.reference());
        if (server.transport().equals("SSE")) {
            builder.sseTransport(server.endpoint());
        } else {
            builder.streamableHttpTransport(server.endpoint());
        }
        boolean captureTaskEvidence = isTaskEvidenceCaptureServer(server) && executionContext != null;
        if (server.credentialRef() != null || captureTaskEvidence) {
            String credentialRef = server.credentialRef();
            builder.httpRequestCustomizer((request, method, uri, body, context) -> {
                if (credentialRef != null) addAuthorization(request, credentials, credentialRef);
                addTaskExecutionHeaders(request, executionContext,
                        isTaskOrchestrationServer(server) || captureTaskEvidence);
            });
        }
        return builder.buildAsync().block();
    }

    static void addTaskExecutionHeaders(HttpRequest.Builder request, McpExecutionContext context,
            boolean platformTaskServer) {
        if (!platformTaskServer || context == null) return;
        request.header("X-AgentTeams-Task-Id", context.taskId().toString())
                .header("X-AgentTeams-Attempt-Id", context.attemptId())
                .header("X-AgentTeams-Lease-Id", context.leaseId())
                .header("X-AgentTeams-Call-Id", java.util.UUID.randomUUID().toString());
    }

    static void addAuthorization(HttpRequest.Builder request, McpCredentialProvider credentials,
            String credentialRef) {
        String token = credentials.resolve(credentialRef)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("MCP_CREDENTIAL_UNAVAILABLE"));
        request.header("Authorization", "Bearer " + token);
    }

    @FunctionalInterface
    interface McpClientFactory {
        McpClientWrapper create(RuntimeMcpServer server, McpCredentialProvider credentials,
                McpExecutionContext executionContext);
    }

    @FunctionalInterface
    interface LegacyMcpClientFactory {
        McpClientWrapper create(RuntimeMcpServer server, McpCredentialProvider credentials);
    }
}
