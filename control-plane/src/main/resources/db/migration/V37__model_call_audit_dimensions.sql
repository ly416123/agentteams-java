ALTER TABLE model_call_audits
    ADD COLUMN worker_id TEXT,
    ADD COLUMN task_id TEXT,
    ADD COLUMN team_id TEXT,
    ADD COLUMN tool_id TEXT,
    ADD COLUMN quota_id TEXT,
    ADD COLUMN quota_dimension TEXT;
