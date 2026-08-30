CREATE SEQUENCE conversation_event_cursor_seq AS BIGINT START WITH 1;

CREATE TABLE conversation_sessions (
    id UUID PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    team_id VARCHAR(255) NOT NULL,
    worker_id VARCHAR(255),
    task_id VARCHAR(255),
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED', 'ACTIVE', 'CANCELLING', 'CANCELLED')),
    create_idempotency_key VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX conversation_sessions_create_key_idx
    ON conversation_sessions (create_idempotency_key)
    WHERE create_idempotency_key IS NOT NULL;

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES conversation_sessions(id),
    idempotency_key VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    start_cursor BIGINT NOT NULL CHECK (start_cursor >= 0),
    end_cursor BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, idempotency_key)
);

CREATE TABLE conversation_events (
    session_id UUID NOT NULL REFERENCES conversation_sessions(id),
    cursor BIGINT NOT NULL CHECK (cursor > 0),
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, cursor)
);

CREATE INDEX conversation_events_replay_idx ON conversation_events (session_id, cursor);
CREATE INDEX conversation_messages_session_idx ON conversation_messages (session_id, created_at);
