CREATE TABLE dashboard_alert_rules (
    rule TEXT PRIMARY KEY,
    severity TEXT NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT dashboard_alert_rules_rule_non_blank CHECK (btrim(rule) <> ''),
    CONSTRAINT dashboard_alert_rules_severity_non_blank CHECK (btrim(severity) <> ''),
    CONSTRAINT dashboard_alert_rules_threshold_finite_non_negative
        CHECK (threshold >= 0 AND threshold < 'Infinity'::double precision)
);

INSERT INTO dashboard_alert_rules (rule, severity, threshold, enabled)
VALUES
    ('FAILURE_RATE', 'CRITICAL', 0.10, TRUE),
    ('AVERAGE_LATENCY', 'WARNING', 5000, TRUE),
    ('COST', 'WARNING', 100, TRUE);
