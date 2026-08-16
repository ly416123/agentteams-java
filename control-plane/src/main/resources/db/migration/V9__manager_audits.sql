CREATE TABLE model_call_audits (
    id UUID PRIMARY KEY,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    latency_millis BIGINT NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64),
    outcome TEXT NOT NULL,
    error_category TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT model_call_audits_latency_non_negative CHECK (latency_millis >= 0),
    CONSTRAINT model_call_audits_prompt_tokens_non_negative CHECK (prompt_tokens >= 0),
    CONSTRAINT model_call_audits_completion_tokens_non_negative CHECK (completion_tokens >= 0)
);

CREATE INDEX model_call_audits_occurred_idx ON model_call_audits(occurred_at, id);
