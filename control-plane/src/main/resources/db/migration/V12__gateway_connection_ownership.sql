ALTER TABLE gateway_agent_state
    ADD COLUMN connection_id UUID;

CREATE INDEX gateway_agent_state_connection_idx
    ON gateway_agent_state (agent_id, connection_id);
