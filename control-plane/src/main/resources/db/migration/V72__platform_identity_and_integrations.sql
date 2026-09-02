CREATE TABLE principals (
    id UUID PRIMARY KEY,
    type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT principals_type_check CHECK (type IN ('USER', 'SERVICE_ACCOUNT')),
    CONSTRAINT principals_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT principals_version_non_negative CHECK (version >= 0)
);

CREATE INDEX principals_active_idx ON principals (status, type, updated_at DESC);

CREATE TABLE platform_users (
    id UUID PRIMARY KEY,
    principal_id UUID NOT NULL UNIQUE REFERENCES principals(id),
    display_name TEXT NOT NULL,
    email TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT platform_users_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT platform_users_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT platform_users_version_non_negative CHECK (version >= 0)
);

CREATE INDEX platform_users_status_idx ON platform_users (status, updated_at DESC);

CREATE TABLE integrations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    tenant_id UUID,
    project_id UUID,
    team_id UUID REFERENCES teams(id),
    name TEXT NOT NULL,
    channel_type TEXT NOT NULL,
    user_assertion_mode TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT integrations_tenant_fk FOREIGN KEY (organization_id, tenant_id)
        REFERENCES tenants (organization_id, id),
    CONSTRAINT integrations_project_fk FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT integrations_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT integrations_channel_type_check CHECK (channel_type IN ('SDK', 'DINGTALK', 'MATRIX', 'GATEWAY')),
    CONSTRAINT integrations_user_assertion_mode_check
        CHECK (user_assertion_mode IN ('SERVICE_ONLY', 'DELEGATED_USER', 'OIDC_USER_REQUIRED')),
    CONSTRAINT integrations_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT integrations_version_non_negative CHECK (version >= 0)
);

CREATE INDEX integrations_organization_idx ON integrations (organization_id, status, updated_at DESC);

CREATE TABLE integration_credentials (
    id UUID PRIMARY KEY,
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE CASCADE,
    access_key_id TEXT NOT NULL UNIQUE,
    algorithm TEXT NOT NULL,
    secret_ciphertext TEXT,
    public_key TEXT,
    allowed_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_tenants JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_projects JSONB NOT NULL DEFAULT '[]'::jsonb,
    ip_allowlist JSONB,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT integration_credentials_access_key_not_blank CHECK (length(btrim(access_key_id)) > 0),
    CONSTRAINT integration_credentials_algorithm_check CHECK (algorithm IN ('HMAC_SHA256', 'ED25519', 'RSA_PSS')),
    CONSTRAINT integration_credentials_material_check CHECK (
        (algorithm = 'HMAC_SHA256' AND secret_ciphertext IS NOT NULL AND public_key IS NULL)
        OR (algorithm IN ('ED25519', 'RSA_PSS') AND secret_ciphertext IS NULL AND public_key IS NOT NULL)
    ),
    CONSTRAINT integration_credentials_scopes_array CHECK (jsonb_typeof(allowed_scopes) = 'array'),
    CONSTRAINT integration_credentials_tenants_array CHECK (jsonb_typeof(allowed_tenants) = 'array'),
    CONSTRAINT integration_credentials_projects_array CHECK (jsonb_typeof(allowed_projects) = 'array'),
    CONSTRAINT integration_credentials_ip_allowlist_array CHECK
        (ip_allowlist IS NULL OR jsonb_typeof(ip_allowlist) = 'array'),
    CONSTRAINT integration_credentials_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT integration_credentials_version_non_negative CHECK (version >= 0)
);

CREATE INDEX integration_credentials_active_idx
    ON integration_credentials (integration_id, status, expires_at);

CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    integration_id UUID REFERENCES integrations(id) ON DELETE CASCADE,
    issuer TEXT,
    external_organization_id TEXT,
    external_user_id TEXT NOT NULL,
    internal_user_id UUID NOT NULL REFERENCES platform_users(id),
    display_name TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT external_identities_source_check CHECK (
        (integration_id IS NOT NULL AND issuer IS NULL AND external_organization_id IS NOT NULL)
        OR (integration_id IS NULL AND issuer IS NOT NULL AND external_organization_id IS NULL)
    ),
    CONSTRAINT external_identities_issuer_not_blank CHECK (issuer IS NULL OR length(btrim(issuer)) > 0),
    CONSTRAINT external_identities_organization_not_blank CHECK
        (external_organization_id IS NULL OR length(btrim(external_organization_id)) > 0),
    CONSTRAINT external_identities_user_not_blank CHECK (length(btrim(external_user_id)) > 0),
    CONSTRAINT external_identities_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT external_identities_version_non_negative CHECK (version >= 0)
);

CREATE UNIQUE INDEX external_identities_sdk_unique
    ON external_identities (integration_id, external_organization_id, external_user_id)
    WHERE integration_id IS NOT NULL;

CREATE UNIQUE INDEX external_identities_oidc_unique
    ON external_identities (issuer, external_user_id)
    WHERE issuer IS NOT NULL;

CREATE INDEX external_identities_internal_user_idx
    ON external_identities (internal_user_id, status);

CREATE TABLE provisioning_policies (
    id UUID PRIMARY KEY,
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE CASCADE,
    external_group TEXT NOT NULL,
    target_tenant UUID,
    target_project UUID,
    target_role TEXT NOT NULL,
    default_team UUID REFERENCES teams(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT provisioning_policies_tenant_fk FOREIGN KEY (target_tenant)
        REFERENCES tenants(id),
    CONSTRAINT provisioning_policies_project_fk FOREIGN KEY (target_project)
        REFERENCES projects(id),
    CONSTRAINT provisioning_policies_group_not_blank CHECK (length(btrim(external_group)) > 0),
    CONSTRAINT provisioning_policies_role_check
        CHECK (target_role IN ('ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT provisioning_policies_version_non_negative CHECK (version >= 0),
    CONSTRAINT provisioning_policies_scope_check CHECK (target_project IS NULL OR target_tenant IS NOT NULL)
);

CREATE UNIQUE INDEX provisioning_policies_integration_group_unique
    ON provisioning_policies (integration_id, external_group);

CREATE TABLE integration_request_nonces (
    id UUID PRIMARY KEY,
    credential_id UUID NOT NULL REFERENCES integration_credentials(id) ON DELETE CASCADE,
    nonce TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT integration_request_nonces_nonce_not_blank CHECK (length(btrim(nonce)) > 0),
    CONSTRAINT integration_request_nonces_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT integration_request_nonces_unique UNIQUE (credential_id, nonce)
);

CREATE INDEX integration_request_nonces_expiry_idx
    ON integration_request_nonces (expires_at);

CREATE TABLE provisioning_idempotency (
    id UUID PRIMARY KEY,
    integration_id UUID NOT NULL REFERENCES integrations(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    internal_user_id UUID NOT NULL REFERENCES platform_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT provisioning_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT provisioning_idempotency_hash_not_blank CHECK (length(btrim(request_hash)) > 0),
    CONSTRAINT provisioning_idempotency_unique UNIQUE (integration_id, idempotency_key)
);
