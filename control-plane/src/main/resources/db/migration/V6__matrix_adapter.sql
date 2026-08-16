CREATE TABLE matrix_conversations (
    id UUID PRIMARY KEY,
    room_id TEXT NOT NULL UNIQUE,
    team_id UUID REFERENCES teams(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE matrix_inbox_transactions (
    id UUID PRIMARY KEY,
    transaction_id TEXT NOT NULL UNIQUE,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT matrix_inbox_transactions_id_non_blank CHECK (length(trim(transaction_id)) > 0)
);

CREATE TABLE matrix_inbox_events (
    id UUID PRIMARY KEY,
    transaction_id TEXT NOT NULL REFERENCES matrix_inbox_transactions(transaction_id),
    event_id TEXT NOT NULL UNIQUE,
    room_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    body TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT matrix_inbox_events_transaction_non_blank CHECK (length(trim(transaction_id)) > 0),
    CONSTRAINT matrix_inbox_events_event_non_blank CHECK (length(trim(event_id)) > 0),
    CONSTRAINT matrix_inbox_events_room_non_blank CHECK (length(trim(room_id)) > 0),
    CONSTRAINT matrix_inbox_events_sender_non_blank CHECK (length(trim(sender)) > 0)
);

CREATE INDEX matrix_inbox_events_transaction_idx ON matrix_inbox_events(transaction_id);

CREATE TABLE matrix_outbox_messages (
    id UUID PRIMARY KEY,
    room_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    body TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX matrix_outbox_delivery_idx ON matrix_outbox_messages(status, next_attempt_at);
