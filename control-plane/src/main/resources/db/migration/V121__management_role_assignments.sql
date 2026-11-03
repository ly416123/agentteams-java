CREATE TABLE management_role_assignments (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL,
    scope_type TEXT NOT NULL,
    organization_id UUID,
    role TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_by TEXT NOT NULL,
    updated_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT management_role_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT management_role_scope_check CHECK (
        (scope_type = 'PLATFORM' AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    ),
    CONSTRAINT management_role_name_check CHECK (
        (scope_type = 'PLATFORM' AND role = 'PLATFORM_ADMIN')
        OR (scope_type = 'ORGANIZATION' AND role = 'ORGANIZATION_ADMIN')
    ),
    CONSTRAINT management_role_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT management_role_version_check CHECK (version >= 0),
    CONSTRAINT management_role_organization_fk FOREIGN KEY (organization_id)
        REFERENCES organizations(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX management_role_platform_subject_unique
    ON management_role_assignments(subject) WHERE scope_type = 'PLATFORM';
CREATE UNIQUE INDEX management_role_organization_subject_unique
    ON management_role_assignments(organization_id, subject) WHERE scope_type = 'ORGANIZATION';
CREATE INDEX management_role_subject_active_idx
    ON management_role_assignments(subject, scope_type, status);
