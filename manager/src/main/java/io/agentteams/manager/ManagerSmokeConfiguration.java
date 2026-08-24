package io.agentteams.manager;

import java.util.Map;

/** Environment-backed runtime configuration for the local Manager smoke entry point. */
public record ManagerSmokeConfiguration(boolean remoteQuotaEnabled, String managerId,
        String gatewayHost, int gatewayPort, String tenantId, String projectId) {

    private static final String DEFAULT_MANAGER_ID = "manager-smoke";
    private static final String DEFAULT_GATEWAY_HOST = "agentteams-agentteams-java-gateway";
    private static final int DEFAULT_GATEWAY_PORT = 9090;

    public static ManagerSmokeConfiguration fromEnvironment() {
        return from(System.getenv());
    }

    public static ManagerSmokeConfiguration from(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        boolean remoteQuotaEnabled = booleanValue(environment, "AGENTTEAMS_QUOTA_REMOTE_ENABLED", false);
        String managerId = text(environment.get("AGENTTEAMS_MANAGER_ID"));
        String tenantId = text(environment.get("AGENTTEAMS_SCOPE_TENANT"));
        String projectId = text(environment.get("AGENTTEAMS_SCOPE_PROJECT"));
        if ((tenantId == null) != (projectId == null)) {
            throw new IllegalArgumentException("tenant and project scope must be supplied together");
        }
        if (remoteQuotaEnabled) {
            if (managerId == null) {
                throw new IllegalArgumentException(
                        "AGENTTEAMS_MANAGER_ID is required when remote quota is enabled");
            }
            if (tenantId == null || projectId == null) {
                throw new IllegalArgumentException(
                        "tenant and project scope must be supplied when remote quota is enabled");
            }
        }
        String gatewayHost = text(environment.get("AGENTTEAMS_GATEWAY_HOST"));
        if (gatewayHost == null) {
            gatewayHost = DEFAULT_GATEWAY_HOST;
        }
        int gatewayPort = positivePort(environment.get("AGENTTEAMS_GATEWAY_PORT"));
        return new ManagerSmokeConfiguration(remoteQuotaEnabled,
                managerId == null ? DEFAULT_MANAGER_ID : managerId,
                gatewayHost, gatewayPort, tenantId, projectId);
    }

    private static int positivePort(String value) {
        String text = text(value);
        if (text == null) {
            return DEFAULT_GATEWAY_PORT;
        }
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("AGENTTEAMS_GATEWAY_PORT must be between 1 and 65535");
        }
    }

    private static boolean booleanValue(Map<String, String> environment, String name, boolean fallback) {
        String value = text(environment.get(name));
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
