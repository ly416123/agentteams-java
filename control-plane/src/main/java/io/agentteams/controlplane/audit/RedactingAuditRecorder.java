package io.agentteams.controlplane.audit;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Removes credentials and bearer material before an audit event reaches a sink. */
public final class RedactingAuditRecorder implements AuditRecorder {
    private static final Set<String> SENSITIVE = Set.of("apikey", "token", "authorization", "secret", "password");
    private final AuditRecorder delegate;

    public RedactingAuditRecorder(AuditRecorder delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }

    @Override
    public void record(AuditEvent event) {
        Map<String, String> redacted = new java.util.HashMap<>();
        event.attributes().forEach((key, value) -> redacted.put(key,
                SENSITIVE.contains(key.toLowerCase(java.util.Locale.ROOT)) ? "[REDACTED]" : value));
        delegate.record(new AuditEvent(event.id(), event.actor(), event.action(), event.resourceType(),
                event.resourceId(), redacted, event.occurredAt()));
    }
}
