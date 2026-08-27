CREATE TABLE project_invitations (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'INVITED',
    accepted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT project_invitations_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT project_invitations_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT project_invitations_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT project_invitations_token_hash_not_blank CHECK (length(btrim(token_hash)) > 0),
    CONSTRAINT project_invitations_status_check CHECK (status IN ('INVITED', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT project_invitations_version_non_negative CHECK (version >= 0)
);

CREATE UNIQUE INDEX project_invitations_token_hash_idx
    ON project_invitations (tenant_id, token_hash);

CREATE INDEX project_invitations_pending_idx
    ON project_invitations (tenant_id, project_id, status, expires_at);

CREATE TABLE project_invitation_idempotency (
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    invitation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id, idempotency_key),
    CONSTRAINT project_invitation_idempotency_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT project_invitation_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT project_invitation_idempotency_hash_not_blank CHECK (length(btrim(request_hash)) > 0)
);
