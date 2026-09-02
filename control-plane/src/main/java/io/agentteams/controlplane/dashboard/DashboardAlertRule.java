package io.agentteams.controlplane.dashboard;

/** Replaceable dashboard alert configuration, intentionally independent of a storage technology. */
public record DashboardAlertRule(String rule, String severity, double threshold, boolean enabled, long version) {
    public DashboardAlertRule(String rule, String severity, double threshold, boolean enabled) {
        this(rule, severity, threshold, enabled, 0);
    }

    public DashboardAlertRule {
        if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule is required");
        if (severity == null || severity.isBlank()) throw new IllegalArgumentException("severity is required");
        if (!Double.isFinite(threshold) || threshold < 0) {
            throw new IllegalArgumentException("threshold must be finite and non-negative");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        rule = rule.trim().toUpperCase(java.util.Locale.ROOT);
        severity = severity.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
