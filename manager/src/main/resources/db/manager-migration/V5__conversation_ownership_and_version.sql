ALTER TABLE conversation_sessions
    ADD COLUMN tenant_id VARCHAR(255);

ALTER TABLE conversation_sessions
    ADD COLUMN actor_subject VARCHAR(255);

ALTER TABLE conversation_sessions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
