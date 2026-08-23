CREATE TABLE projects (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT projects_tenant_id_not_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT projects_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT projects_created_by_not_blank CHECK (length(btrim(created_by)) > 0),
    CONSTRAINT projects_version_non_negative CHECK (version >= 0),
    CONSTRAINT projects_tenant_id_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT projects_tenant_name_unique UNIQUE (tenant_id, name)
);

CREATE INDEX projects_tenant_idx ON projects (tenant_id, created_at, id);

CREATE TABLE project_memberships (
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, project_id, subject),
    CONSTRAINT project_memberships_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT project_memberships_tenant_id_not_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT project_memberships_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT project_memberships_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT project_memberships_version_non_negative CHECK (version >= 0)
);

CREATE INDEX project_memberships_project_idx ON project_memberships (tenant_id, project_id, role);

CREATE TABLE project_create_idempotency (
    tenant_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    project_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT project_create_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT project_create_idempotency_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id)
);

CREATE TABLE project_membership_idempotency (
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id, idempotency_key),
    CONSTRAINT project_membership_idempotency_project_fk
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT project_membership_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT project_membership_idempotency_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT project_membership_idempotency_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER'))
);
