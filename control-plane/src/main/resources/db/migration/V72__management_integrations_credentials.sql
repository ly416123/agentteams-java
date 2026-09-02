CREATE TABLE integrations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    external_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT integrations_external_key_not_blank CHECK (length(btrim(external_key)) > 0),
    CONSTRAINT integrations_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT integrations_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT integrations_version_non_negative CHECK (version >= 0),
    CONSTRAINT integrations_organization_external_key_unique UNIQUE (organization_id, external_key)
);

CREATE TABLE integration_credentials (
    id UUID PRIMARY KEY,
    integration_id UUID NOT NULL REFERENCES integrations (id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    access_key_id TEXT NOT NULL UNIQUE,
    credential_ref TEXT NOT NULL,
    algorithm TEXT NOT NULL DEFAULT 'HMAC_SHA256',
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT integration_credentials_label_not_blank CHECK (length(btrim(label)) > 0),
    CONSTRAINT integration_credentials_ref_not_blank CHECK (length(btrim(credential_ref)) > 0),
    CONSTRAINT integration_credentials_algorithm_check CHECK (algorithm IN ('HMAC_SHA256')),
    CONSTRAINT integration_credentials_status_check CHECK (status IN ('ACTIVE', 'REVOKED', 'SUSPENDED')),
    CONSTRAINT integration_credentials_version_positive CHECK (version > 0)
);

CREATE TABLE integration_provisioning_policies (
    integration_id UUID PRIMARY KEY REFERENCES integrations (id) ON DELETE CASCADE,
    allow_auto_create BOOLEAN NOT NULL DEFAULT FALSE,
    allow_platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT integration_provisioning_policy_version_non_negative CHECK (version >= 0)
);

CREATE INDEX integrations_organization_status_idx
    ON integrations (organization_id, status, updated_at DESC);
CREATE INDEX integration_credentials_active_access_key_idx
    ON integration_credentials (access_key_id, status, expires_at);
