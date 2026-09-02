CREATE TABLE project_membership_role_change_idempotency (
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    expected_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id, idempotency_key),
    CONSTRAINT project_role_change_idempotency_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT project_role_change_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT project_role_change_idempotency_hash_not_blank CHECK (length(btrim(request_hash)) > 0),
    CONSTRAINT project_role_change_idempotency_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT project_role_change_idempotency_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT project_role_change_idempotency_version_non_negative CHECK (expected_version >= 0)
);
