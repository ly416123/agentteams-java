package io.agentteams.controlplane.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Deterministic, read-only alert evaluation for dashboard and future notification adapters. */
@Service
public final class DashboardAlertService {
    public static final double DEFAULT_FAILURE_RATE = 0.10;
    public static final double DEFAULT_AVERAGE_LATENCY_MILLIS = 5000;
    public static final double DEFAULT_COST_USD = 100;
    private final DashboardAlertRuleRepository rules;

    public DashboardAlertService() {
        this(defaultRules());
    }

    @Autowired
    public DashboardAlertService(DashboardAlertRuleRepository rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public List<Alert> evaluate(DashboardSummaryController.DashboardSummary summary) {
        if (summary == null) throw new IllegalArgumentException("summary is required");
        List<Alert> alerts = new ArrayList<>();
        double actualFailureRate = summary.calls() == 0 ? 0 : (double) summary.failures() / summary.calls();
        for (DashboardAlertRule rule : rules.findAll()) {
            if (!rule.enabled()) continue;
            switch (rule.rule()) {
                case "FAILURE_RATE" -> addIfExceeded(alerts, rule, actualFailureRate,
                        "failure rate exceeded configured threshold");
                case "AVERAGE_LATENCY" -> addIfExceeded(alerts, rule, summary.averageLatencyMillis(),
                        "average model latency exceeded configured threshold");
                case "COST" -> addIfExceeded(alerts, rule, summary.estimatedCostUsd(),
                        "estimated model cost exceeded configured threshold");
                default -> { /* Unknown rules are retained for future evaluators. */ }
            }
        }
        return List.copyOf(alerts);
    }

    public List<Alert> evaluate(DashboardSummaryController.DashboardSummary summary,
            double failureRate, double averageLatencyMillis, double costUsd) {
        if (summary == null) throw new IllegalArgumentException("summary is required");
        if (failureRate < 0 || averageLatencyMillis < 0 || costUsd < 0) {
            throw new IllegalArgumentException("alert thresholds must not be negative");
        }
        List<Alert> alerts = new ArrayList<>();
        double actualFailureRate = summary.calls() == 0 ? 0 : (double) summary.failures() / summary.calls();
        if (actualFailureRate > failureRate) {
            alerts.add(new Alert("FAILURE_RATE", "CRITICAL", actualFailureRate,
                    "failure rate exceeded configured threshold"));
        }
        if (summary.averageLatencyMillis() > averageLatencyMillis) {
            alerts.add(new Alert("AVERAGE_LATENCY", "WARNING", summary.averageLatencyMillis(),
                    "average model latency exceeded configured threshold"));
        }
        if (summary.estimatedCostUsd() > costUsd) {
            alerts.add(new Alert("COST", "WARNING", summary.estimatedCostUsd(),
                    "estimated model cost exceeded configured threshold"));
        }
        return List.copyOf(alerts);
    }

    private static void addIfExceeded(List<Alert> alerts, DashboardAlertRule rule, double actual, String message) {
        if (actual > rule.threshold()) {
            alerts.add(new Alert(rule.rule(), rule.severity(), actual, message));
        }
    }

    private static InMemoryDashboardAlertRuleRepository defaultRules() {
        InMemoryDashboardAlertRuleRepository repository = new InMemoryDashboardAlertRuleRepository();
        repository.save(new DashboardAlertRule("FAILURE_RATE", "CRITICAL", DEFAULT_FAILURE_RATE, true));
        repository.save(new DashboardAlertRule("AVERAGE_LATENCY", "WARNING", DEFAULT_AVERAGE_LATENCY_MILLIS, true));
        repository.save(new DashboardAlertRule("COST", "WARNING", DEFAULT_COST_USD, true));
        return repository;
    }

    public record Alert(String rule, String severity, double actual, String message) { }
}
