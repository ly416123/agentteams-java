-- Gateway commands belong to an attempt so the control plane can invalidate
-- stale assignments when a runtime rejects them or a lease expires. Replay is
-- limited to uncancelled commands: cancelled ones were superseded by a newer
-- attempt and must never be redelivered after a worker reconnect.
ALTER TABLE gateway_commands
    ADD COLUMN attempt_id TEXT,
    ADD COLUMN cancelled_at TIMESTAMPTZ;

CREATE INDEX gateway_commands_attempt_pending_idx
    ON gateway_commands (attempt_id) WHERE cancelled_at IS NULL;
