ALTER TABLE matrix_outbox_messages
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN room_binding_id UUID REFERENCES matrix_room_bindings(id);

-- Messages queued before delivery-scope tracking cannot be safely revalidated.
UPDATE matrix_outbox_messages
   SET status = 'SUPPRESSED',
       last_error = 'delivery scope unavailable; message requires re-projection',
       updated_at = CURRENT_TIMESTAMP
 WHERE status IN ('PENDING', 'IN_FLIGHT');

CREATE INDEX matrix_outbox_binding_idx
    ON matrix_outbox_messages(tenant_id, room_binding_id, status);
