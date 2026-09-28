CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT,
    title TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE INDEX conversations_scope_idx
    ON conversations (tenant_id, project_id, team_id, created_at, id);

CREATE TABLE conversation_messages_cp (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    tenant_id TEXT NOT NULL,
    sender_type TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    message_type TEXT NOT NULL,
    content TEXT,
    content_ref TEXT,
    visibility TEXT NOT NULL CHECK (visibility IN ('PUBLIC', 'TEAM', 'PRIVATE', 'INTERNAL')),
    occurred_at TIMESTAMPTZ NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    idempotency_key TEXT,
    trace_id TEXT NOT NULL,
    CONSTRAINT conversation_messages_cp_content_check
        CHECK ((content IS NOT NULL) <> (content_ref IS NOT NULL)),
    CONSTRAINT conversation_messages_cp_idempotency_unique
        UNIQUE (conversation_id, idempotency_key),
    CONSTRAINT conversation_messages_cp_sequence_unique
        UNIQUE (conversation_id, sequence)
);

CREATE INDEX conversation_messages_cp_timeline_idx
    ON conversation_messages_cp (conversation_id, sequence);

ALTER TABLE tasks ADD COLUMN source_conversation_id UUID REFERENCES conversations(id);
CREATE INDEX tasks_source_conversation_idx ON tasks (source_conversation_id);
