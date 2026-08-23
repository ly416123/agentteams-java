package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Policy-first MCP runtime facade for discovery and tool invocation.
 *
 * <p>All connector work goes through this class so policy, timeout, outcome classification and
 * best-effort audit behavior remain consistent across future transport adapters.</p>
 */
@Service
public final class McpToolExecutionService {
    private final McpServerService serverService;
    private final McpRuntimePolicyService policyService;
    private final McpTransportConnectorRegistry connectorRegistry;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final Executor executor;
    private final McpToolDiscoveryCache discoveryCache;
    private final McpRuntimeGuard runtimeGuard;
    private final McpObservability observability;

    @Autowired
    public McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            McpToolDiscoveryCacheProperties cacheProperties, McpRuntimeGuard runtimeGuard,
            McpObservability observability) {
        this(serverService, policyService, connectorRegistry, auditRecorder, clock, ForkJoinPool.commonPool(),
                new McpToolDiscoveryCache(clock, cacheProperties.getTtl(), cacheProperties.getCapacity(), observability),
                runtimeGuard, observability);
    }

    /** Compatibility constructor for callers that do not explicitly provide the runtime guard. */
    public McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            McpToolDiscoveryCacheProperties cacheProperties) {
        this(serverService, policyService, connectorRegistry, auditRecorder, clock, ForkJoinPool.commonPool(),
                new McpToolDiscoveryCache(clock, cacheProperties.getTtl(), cacheProperties.getCapacity()),
                new McpRuntimeGuard(clock), new McpObservability());
    }

    /** Compatibility constructor for callers that used to provide the connector collection directly. */
    public McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            List<McpTransportConnector> connectors, AuditRecorder auditRecorder) {
        this(serverService, policyService, new McpTransportConnectorRegistry(connectors), auditRecorder,
                Clock.systemUTC(), ForkJoinPool.commonPool(), new McpToolDiscoveryCache(), new McpRuntimeGuard(),
                new McpObservability());
    }

    /** Compatibility constructor for callers that used to provide a connector registry directly. */
    public McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder) {
        this(serverService, policyService, connectorRegistry, auditRecorder, Clock.systemUTC(),
                ForkJoinPool.commonPool(), new McpToolDiscoveryCache(), new McpRuntimeGuard(), new McpObservability());
    }

    McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            Executor executor) {
        this(serverService, policyService, connectorRegistry, auditRecorder, clock, executor,
                new McpToolDiscoveryCache(clock, McpToolDiscoveryCache.DEFAULT_TTL,
                        McpToolDiscoveryCache.DEFAULT_CAPACITY), new McpRuntimeGuard(clock), new McpObservability());
    }

    McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            Executor executor, McpToolDiscoveryCache discoveryCache) {
        this(serverService, policyService, connectorRegistry, auditRecorder, clock, executor, discoveryCache,
                new McpRuntimeGuard(clock), new McpObservability());
    }

    McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            Executor executor, McpToolDiscoveryCache discoveryCache, McpRuntimeGuard runtimeGuard) {
        this(serverService, policyService, connectorRegistry, auditRecorder, clock, executor, discoveryCache,
                runtimeGuard, new McpObservability());
    }

    McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            McpTransportConnectorRegistry connectorRegistry, AuditRecorder auditRecorder, Clock clock,
            Executor executor, McpToolDiscoveryCache discoveryCache, McpRuntimeGuard runtimeGuard,
            McpObservability observability) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.policyService = Objects.requireNonNull(policyService, "policyService");
        this.connectorRegistry = Objects.requireNonNull(connectorRegistry, "connectorRegistry");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.discoveryCache = Objects.requireNonNull(discoveryCache, "discoveryCache");
        this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    McpToolExecutionService(McpServerService serverService, McpRuntimePolicyService policyService,
            List<McpTransportConnector> connectors, AuditRecorder auditRecorder, Clock clock, Executor executor) {
        this(serverService, policyService, new McpTransportConnectorRegistry(connectors), auditRecorder, clock, executor);
    }

    public McpToolCallResult callTool(UUID serverId, McpToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        McpServerRecord server = serverService.get(Objects.requireNonNull(serverId, "serverId"));
        McpRuntimePolicyService.Authorization authorization = policyService.authorize(server,
                invocation.toolName(), invocation.timeout());
        if (!authorization.allowed()) {
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.DENIED, authorization.classification()));
        }
        McpTransportConnector connector = connectorRegistry.select(server.transport());
        if (connector == null) {
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.UNSUPPORTED, "TRANSPORT_UNSUPPORTED"));
        }
        McpRuntimeGuard.Lease lease = runtimeGuard.tryAcquire(server.id());
        if (!lease.granted()) {
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.DENIED, lease.rejection()));
        }
        Timer.Sample connectorTimer = observability.connectorStarted();
        try {
            Object value = runWithTimeout(() -> connector.callTool(McpConnectorTarget.from(server), invocation.toolName(),
                    invocation.arguments(), invocation.timeout()), invocation.timeout());
            lease.success();
            observability.connectorSucceeded("tool_call", connectorTimer);
            return audited("MCP_TOOL_CALL", server, invocation.toolName(), McpToolCallResult.success(value));
        } catch (TimeoutException error) {
            observability.connectorTimedOut("tool_call", connectorTimer);
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.TIMEOUT, "CONNECTOR_TIMEOUT"));
        } catch (UnsupportedOperationException error) {
            observability.connectorUnsupported("tool_call", connectorTimer);
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.UNSUPPORTED, "TRANSPORT_NOT_CONFIGURED"));
        } catch (RuntimeException error) {
            String classification = connectorClassification(error);
            observability.connectorFailed("tool_call", classification, connectorTimer);
            return audited("MCP_TOOL_CALL", server, invocation.toolName(),
                    McpToolCallResult.failure(McpOperationOutcome.CONNECTOR_ERROR, classification));
        } finally {
            lease.close();
        }
    }

    public McpToolCallResult discoverTools(UUID serverId, Duration timeout) {
        McpServerRecord server = serverService.get(Objects.requireNonNull(serverId, "serverId"));
        McpRuntimePolicyService.Authorization authorization = policyService.authorizeDiscovery(server, timeout);
        if (!authorization.allowed()) {
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.DENIED, authorization.classification()));
        }
        List<McpToolDescriptor> cachedTools = discoveryCache.get(server.id(), server.version());
        if (cachedTools != null) {
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.discoverySuccess(cachedTools), "CACHE_HIT");
        }
        McpTransportConnector connector = connectorRegistry.select(server.transport());
        if (connector == null) {
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.UNSUPPORTED, "TRANSPORT_UNSUPPORTED"));
        }
        McpRuntimeGuard.Lease lease = runtimeGuard.tryAcquire(server.id());
        if (!lease.granted()) {
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.DENIED, lease.rejection()));
        }
        Timer.Sample connectorTimer = observability.connectorStarted();
        try {
            List<McpToolDescriptor> tools = runWithTimeout(
                    () -> connector.discoverTools(McpConnectorTarget.from(server), timeout), timeout);
            discoveryCache.put(server.id(), server.version(), tools);
            lease.success();
            observability.connectorSucceeded("tools_list", connectorTimer);
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list", McpToolCallResult.discoverySuccess(tools));
        } catch (TimeoutException error) {
            observability.connectorTimedOut("tools_list", connectorTimer);
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.TIMEOUT, "CONNECTOR_TIMEOUT"));
        } catch (UnsupportedOperationException error) {
            observability.connectorUnsupported("tools_list", connectorTimer);
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.UNSUPPORTED, "TRANSPORT_NOT_CONFIGURED"));
        } catch (RuntimeException error) {
            String classification = connectorClassification(error);
            observability.connectorFailed("tools_list", classification, connectorTimer);
            return audited("MCP_TOOL_DISCOVERY", server, "tools/list",
                    McpToolCallResult.failure(McpOperationOutcome.CONNECTOR_ERROR, classification));
        } finally {
            lease.close();
        }
    }

    /** Invalidates all tools/list entries for a server and emits a stable audit event. */
    public void invalidateToolDiscoveryCache(UUID serverId) {
        McpServerRecord server = serverService.get(Objects.requireNonNull(serverId, "serverId"));
        discoveryCache.invalidate(server.id());
        audited("MCP_TOOL_DISCOVERY_CACHE", server, "tools/list",
                McpToolCallResult.success(null), "CACHE_INVALIDATED");
    }

    private static String connectorClassification(RuntimeException error) {
        if (error instanceof McpHttpConnectorException httpError) {
            return httpError.category().name();
        }
        return "CONNECTOR_EXCEPTION";
    }

    private <T> T runWithTimeout(CheckedSupplier<T> work, Duration timeout)
            throws TimeoutException {
        Future<T> future = submit(work);
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw error;
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpConnectorRuntimeException("connector interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new McpConnectorRuntimeException("connector failed", cause);
        }
    }

    private <T> Future<T> submit(CheckedSupplier<T> work) {
        FutureTask<T> task = new FutureTask<>(work::get);
        executor.execute(task);
        return task;
    }

    private McpToolCallResult audited(String action, McpServerRecord server, String tool,
            McpToolCallResult result) {
        return audited(action, server, tool, result, result.classification());
    }

    private McpToolCallResult audited(String action, McpServerRecord server, String tool,
            McpToolCallResult result, String auditClassification) {
        try {
            auditRecorder.record(new AuditEvent(UUID.randomUUID(), PrincipalContext.actorOr("runtime"), action,
                    "mcp_server", server.id().toString(), Map.of("tool", tool, "result", result.outcome().name(),
                            "classification", auditClassification, "transport", server.transport().name()), clock.instant()));
        } catch (RuntimeException ignored) {
            // Audit must not alter the runtime result and never contains credentialRef or payload data.
        }
        return result;
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class McpConnectorRuntimeException extends RuntimeException {
        private McpConnectorRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
