ALTER TABLE matrix_channel_bindings
    ADD COLUMN room_binding_id UUID REFERENCES matrix_room_bindings(id),
    ADD COLUMN context_type TEXT;

ALTER TABLE matrix_channel_bindings
    ADD CONSTRAINT matrix_channel_bindings_room_reference_pair
    CHECK ((room_binding_id IS NULL AND context_type IS NULL)
        OR (room_binding_id IS NOT NULL AND context_type IN ('PROJECT', 'TEAM', 'CONVERSATION', 'TASK')));

CREATE INDEX matrix_channel_bindings_room_reference_idx
    ON matrix_channel_bindings (tenant_id, room_binding_id)
    WHERE room_binding_id IS NOT NULL;
