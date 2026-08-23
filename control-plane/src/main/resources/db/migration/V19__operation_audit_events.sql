CREATE TABLE operation_audit_events (
    id UUID PRIMARY KEY,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT operation_audit_events_actor_not_blank CHECK (length(btrim(actor)) > 0),
    CONSTRAINT operation_audit_events_action_not_blank CHECK (length(btrim(action)) > 0),
    CONSTRAINT operation_audit_events_resource_type_not_blank CHECK (length(btrim(resource_type)) > 0),
    CONSTRAINT operation_audit_events_resource_id_not_blank CHECK (length(btrim(resource_id)) > 0),
    CONSTRAINT operation_audit_events_attributes_object CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE INDEX operation_audit_events_occurred_idx
    ON operation_audit_events (occurred_at DESC, id DESC);
CREATE INDEX operation_audit_events_resource_idx
    ON operation_audit_events (resource_type, resource_id, occurred_at DESC);
