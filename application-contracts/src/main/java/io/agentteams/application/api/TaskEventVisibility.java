package io.agentteams.application.api;

import java.util.Locale;

/** Visibility levels applied to public task events and artifact references. */
public enum TaskEventVisibility {
    REQUESTER,
    PROJECT_MEMBER,
    TENANT_ADMIN,
    SECURITY_AUDITOR,
    INTERNAL_ONLY;

    public static TaskEventVisibility from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("visibility must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported task event visibility", exception);
        }
    }
}
