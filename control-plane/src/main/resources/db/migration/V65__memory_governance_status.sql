ALTER TABLE memories
    ADD COLUMN governance_status TEXT NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE memories
    ADD CONSTRAINT memories_governance_status_check
    CHECK (governance_status IN ('ACTIVE', 'FROZEN', 'DELETED'));

CREATE INDEX memories_governance_status_idx
    ON memories (organization_id, tenant_id, governance_status, updated_at DESC);
