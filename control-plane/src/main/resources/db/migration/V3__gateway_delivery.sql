CREATE TABLE gateway_agent_sequences (
    agent_id TEXT PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT gateway_agent_sequences_non_negative CHECK (last_sequence >= 0)
);

CREATE TABLE gateway_commands (
    agent_id TEXT NOT NULL,
    sequence BIGINT NOT NULL,
    event_id TEXT NOT NULL,
    command_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (agent_id, sequence),
    UNIQUE (agent_id, event_id),
    CONSTRAINT gateway_commands_sequence_positive CHECK (sequence > 0)
);

CREATE TABLE gateway_command_deliveries (
    agent_id TEXT NOT NULL,
    connection_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (agent_id, connection_id, sequence),
    FOREIGN KEY (agent_id, sequence)
        REFERENCES gateway_commands (agent_id, sequence)
);

CREATE INDEX gateway_command_deliveries_connection_idx
    ON gateway_command_deliveries (agent_id, connection_id, sequence);

CREATE TABLE gateway_ack_cursors (
    agent_id TEXT PRIMARY KEY,
    last_ack_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT gateway_ack_cursors_non_negative CHECK (last_ack_sequence >= 0)
);

CREATE TABLE gateway_inbound_events (
    event_id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    connection_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gateway_agent_state (
    agent_id TEXT PRIMARY KEY,
    presence TEXT NOT NULL,
    phase TEXT NOT NULL,
    runtime TEXT NOT NULL,
    runtime_version TEXT NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    connected_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ NOT NULL,
    disconnected_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT gateway_agent_state_capabilities_object
        CHECK (jsonb_typeof(capabilities) = 'object')
);

CREATE INDEX gateway_agent_state_presence_idx ON gateway_agent_state (presence);
