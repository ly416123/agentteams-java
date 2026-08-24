ALTER TABLE project_memberships
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE project_memberships
    ADD CONSTRAINT project_memberships_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

CREATE INDEX project_memberships_active_idx
    ON project_memberships (tenant_id, project_id, status, subject);
