-- Keep the time-bounded usage group queries selective for model and status dimensions.
CREATE INDEX model_call_audits_usage_model_idx
    ON model_call_audits (occurred_at, model);

CREATE INDEX model_call_audits_usage_status_idx
    ON model_call_audits (occurred_at, outcome);
