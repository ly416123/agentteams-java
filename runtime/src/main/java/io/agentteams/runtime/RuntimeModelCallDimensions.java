package io.agentteams.runtime;

/** Safe operational identifiers available to runtime quota and audit adapters. */
public record RuntimeModelCallDimensions(String workerId, String taskId, String teamId, String toolId,
        String quotaId, String quotaDimension) {
    public RuntimeModelCallDimensions {
        workerId = normalize(workerId);
        taskId = normalize(taskId);
        teamId = normalize(teamId);
        toolId = normalize(toolId);
        quotaId = normalize(quotaId);
        quotaDimension = normalize(quotaDimension);
    }

    public static RuntimeModelCallDimensions empty() {
        return new RuntimeModelCallDimensions(null, null, null, null, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
