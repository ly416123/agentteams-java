package io.agentteams.manager;

/** Safe operational identifiers carried with a Manager admission request. */
public record ModelCallDimensions(String workerId, String taskId, String teamId, String toolId,
        String quotaId, String quotaDimension) {
    public ModelCallDimensions {
        requireOptionalText(workerId, "workerId");
        requireOptionalText(taskId, "taskId");
        requireOptionalText(teamId, "teamId");
        requireOptionalText(toolId, "toolId");
        requireOptionalText(quotaId, "quotaId");
        requireOptionalText(quotaDimension, "quotaDimension");
    }

    public static ModelCallDimensions empty() {
        return new ModelCallDimensions(null, null, null, null, null, null);
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
