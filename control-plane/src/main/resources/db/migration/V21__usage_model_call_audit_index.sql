-- The usage API always bounds model-call aggregates by occurred_at and then groups by provider/model.
CREATE INDEX model_call_audits_usage_range_idx
    ON model_call_audits (occurred_at, provider, model);
