package io.agentteams.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Maps the public HTTP API to the permission required after OIDC authentication. */
public final class ApiAuthorizationPolicy {
    private ApiAuthorizationPolicy() { }

    public static Optional<Permission> requiredPermission(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/agents")) {
            return Optional.of("GET".equals(method) ? Permission.AGENT_READ : Permission.AGENT_WRITE);
        }
        if (path.startsWith("/api/v1/tasks/") && path.contains("/artifacts")) {
            return Optional.of("GET".equals(method) ? Permission.ARTIFACT_READ : Permission.ARTIFACT_WRITE);
        }
        if (path.startsWith("/api/v1/tasks")) {
            if ("GET".equals(method)) return Optional.of(Permission.TASK_READ);
            if (path.endsWith("/cancel")) return Optional.of(Permission.TASK_CANCEL);
            if (path.endsWith("/retry")) return Optional.of(Permission.TASK_RETRY);
            if (path.endsWith("/pause")) return Optional.of(Permission.TASK_PAUSE);
            if (path.endsWith("/approve")) return Optional.of(Permission.TASK_APPROVE);
            if (path.endsWith("/reject")) return Optional.of(Permission.TASK_REJECT);
            return Optional.of(Permission.TASK_CREATE);
        }
        if (path.startsWith("/api/v1/config")) {
            return Optional.of("GET".equals(method) ? Permission.CONFIG_READ : Permission.CONFIG_WRITE);
        }
        if (path.startsWith("/api/v1/model-providers")) {
            return Optional.of("GET".equals(method) ? Permission.MODEL_READ : Permission.MODEL_WRITE);
        }
        return Optional.empty();
    }
}
