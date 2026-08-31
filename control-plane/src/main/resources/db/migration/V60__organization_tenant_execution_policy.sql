CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    external_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT organizations_external_key_not_blank CHECK (length(btrim(external_key)) > 0),
    CONSTRAINT organizations_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT organizations_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT organizations_version_non_negative CHECK (version >= 0)
);

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    external_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT tenants_organization_fk FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT tenants_external_key_not_blank CHECK (length(btrim(external_key)) > 0),
    CONSTRAINT tenants_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT tenants_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT tenants_version_non_negative CHECK (version >= 0),
    CONSTRAINT tenants_organization_external_key_unique UNIQUE (organization_id, external_key),
    CONSTRAINT tenants_organization_id_unique UNIQUE (organization_id, id)
);

CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, subject),
    CONSTRAINT organization_memberships_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT organization_memberships_role_check CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'AUDITOR'))
);

CREATE TABLE tenant_memberships (
    organization_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    subject TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, subject),
    CONSTRAINT tenant_memberships_tenant_fk FOREIGN KEY (organization_id, tenant_id)
        REFERENCES tenants (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT tenant_memberships_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT tenant_memberships_role_check CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'AUDITOR'))
);

CREATE TABLE legacy_tenant_mappings (
    legacy_tenant_key TEXT PRIMARY KEY,
    organization_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT legacy_tenant_key_not_blank CHECK (length(btrim(legacy_tenant_key)) > 0),
    CONSTRAINT legacy_tenant_mapping_fk FOREIGN KEY (organization_id, tenant_id)
        REFERENCES tenants (organization_id, id) ON DELETE CASCADE
);

CREATE TABLE organization_tenant_execution_policies (
    id UUID PRIMARY KEY,
    scope_type TEXT NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    tenant_id UUID,
    project_id UUID,
    team_id UUID,
    policy JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT execution_policy_scope_type_check CHECK (scope_type IN ('ORGANIZATION', 'TENANT', 'PROJECT', 'TEAM')),
    CONSTRAINT execution_policy_version_non_negative CHECK (version >= 0),
    CONSTRAINT execution_policy_tenant_fk FOREIGN KEY (organization_id, tenant_id)
        REFERENCES tenants (organization_id, id) ON DELETE CASCADE
);

CREATE INDEX tenants_organization_status_idx ON tenants (organization_id, status, updated_at DESC);
CREATE INDEX tenant_memberships_subject_idx ON tenant_memberships (subject, tenant_id);
CREATE INDEX legacy_tenant_mappings_tenant_idx ON legacy_tenant_mappings (tenant_id);
CREATE INDEX execution_policies_tenant_idx ON organization_tenant_execution_policies (organization_id, tenant_id, scope_type);
CREATE UNIQUE INDEX execution_policies_scope_unique
    ON organization_tenant_execution_policies (scope_type, organization_id,
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid));
