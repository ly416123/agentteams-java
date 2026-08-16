package io.agentteams.manager;

import java.util.List;

public record CreateTaskIntent(String intent, String title, String description, List<String> requiredCapabilities,
        int priority, boolean requiresApproval) {
    public CreateTaskIntent {
        if (!"CREATE_TASK".equals(intent)) throw new IllegalArgumentException("unsupported intent");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (description == null) throw new IllegalArgumentException("description must not be null");
        requiredCapabilities = List.copyOf(requiredCapabilities == null ? List.of() : requiredCapabilities);
        if (priority < 0 || priority > 100) throw new IllegalArgumentException("priority must be between 0 and 100");
    }
}
