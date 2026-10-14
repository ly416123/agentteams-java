package io.agentteams.controlplane.template;

import io.agentteams.domain.agent.WorkerType;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.net.URI;
import java.util.UUID;

/** Restricts the ChatGPT OAuth session Secret to one configured evaluation Team scope. */
final class ChatGptPlanWorkerAccessPolicy {
    private final UUID tenantId;
    private final UUID projectId;
    private final UUID teamId;
    private final String sessionSecret;
    private final String proxyUrl;
    private final boolean enabled;

    ChatGptPlanWorkerAccessPolicy(boolean enabled, String tenantId, String projectId, String teamId,
            String sessionSecret) {
        this(enabled, tenantId, projectId, teamId, sessionSecret, "");
    }

    ChatGptPlanWorkerAccessPolicy(boolean enabled, String tenantId, String projectId, String teamId,
            String sessionSecret, String proxyUrl) {
        this.enabled = enabled;
        this.tenantId = uuidOrNull(tenantId);
        this.projectId = uuidOrNull(projectId);
        this.teamId = uuidOrNull(teamId);
        this.sessionSecret = sessionSecret == null ? "" : sessionSecret.trim();
        this.proxyUrl = validateProxyUrl(proxyUrl);
    }

    String secretFor(String provider, String tenant, String project, String team, WorkerType workerType) {
        if (provider == null || !"chatgpt-plan".equalsIgnoreCase(provider.trim())) return "";
        if (!enabled || tenantId == null || projectId == null || teamId == null || sessionSecret.isBlank()
                || !tenantId.equals(uuidOrNull(tenant)) || !projectId.equals(uuidOrNull(project))
                || !teamId.equals(uuidOrNull(team))
                || (workerType != WorkerType.LEADER && workerType != WorkerType.EXECUTOR)) {
            throw new IllegalArgumentException(
                    "ChatGPT plan credentials are restricted to the evaluation Team scope");
        }
        return sessionSecret;
    }

    String proxyUrlFor(String provider, String tenant, String project, String team, WorkerType workerType) {
        String secret = secretFor(provider, tenant, project, team, workerType);
        return secret.isEmpty() ? "" : proxyUrl;
    }

    String secretFor(String provider, Principal principal, WorkerType workerType,
            ResourceScopeRepository resourceScopes) {
        if (provider == null || !"chatgpt-plan".equalsIgnoreCase(provider.trim())) return "";
        if (!enabled || tenantId == null || projectId == null || teamId == null || sessionSecret.isBlank()
                || (workerType != WorkerType.LEADER && workerType != WorkerType.EXECUTOR)
                || principal == null || resourceScopes == null
                || !resourceScopes.matchesEvaluationTeamScope(principal, tenantId, projectId, teamId)) {
            throw new IllegalArgumentException(
                    "ChatGPT plan credentials are restricted to the evaluation Team scope");
        }
        return sessionSecret;
    }

    String proxyUrlFor(String provider, Principal principal, WorkerType workerType,
            ResourceScopeRepository resourceScopes) {
        String secret = secretFor(provider, principal, workerType, resourceScopes);
        return secret.isEmpty() ? "" : proxyUrl;
    }

    private static String validateProxyUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("ChatGPT plan proxy URL must not contain credentials");
            }
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("ChatGPT plan proxy URL is invalid");
            }
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("ChatGPT plan proxy URL")) {
                throw invalid;
            }
            throw new IllegalArgumentException("ChatGPT plan proxy URL is invalid");
        }
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalidUuid) {
            return null;
        }
    }
}
