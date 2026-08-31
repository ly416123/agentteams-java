package io.agentteams.application.api;

import java.util.Locale;

/** Fail-closed guard for data crossing the public task-process event boundary. */
final class TaskProcessPayloadPolicy {
    private TaskProcessPayloadPolicy() {
    }

    static void requireSafe(TaskEventVisibility visibility, String eventType, String payload) {
        if (visibility == TaskEventVisibility.INTERNAL_ONLY) return;
        String event = eventType.toLowerCase(Locale.ROOT);
        if (event.contains("prompt") || event.contains("chain_of_thought") || event.contains("chain-of-thought")) {
            throw new IllegalArgumentException("raw prompt and chain-of-thought events are internal-only");
        }
        if (payload == null) return;
        String value = payload.toLowerCase(Locale.ROOT);
        String[] sensitiveMarkers = {"token", "password", "secret", "private_key", "private key",
                "authorization", "client_secret", "system prompt", "chain of thought"};
        for (String marker : sensitiveMarkers) {
            if (value.contains(marker)) {
                throw new IllegalArgumentException("sensitive task-process payload is internal-only");
            }
        }
    }
}
