package io.agentteams.controlplane.dashboard;

import java.util.Objects;

/** Replaceable dashboard alert configuration, intentionally independent of a storage technology. */
public record DashboardAlertRule(String rule, String severity, double threshold, boolean enabled) {
    public DashboardAlertRule {
        if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule is required");
        if (severity == null || severity.isBlank()) throw new IllegalArgumentException("severity is required");
        if (!Double.isFinite(threshold) || threshold < 0) {
            throw new IllegalArgumentException("threshold must be finite and non-negative");
        }
        rule = rule.trim().toUpperCase(java.util.Locale.ROOT);
        severity = severity.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
