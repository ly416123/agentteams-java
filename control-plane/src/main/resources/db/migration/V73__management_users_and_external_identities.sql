CREATE TABLE management_users (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT management_users_subject_not_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT management_users_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT management_users_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT management_users_version_non_negative CHECK (version >= 0)
);

CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    integration_id UUID NOT NULL REFERENCES integrations (id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    internal_user_id UUID NOT NULL REFERENCES management_users (id),
    external_organization_id TEXT NOT NULL,
    external_user_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT external_identities_external_org_not_blank CHECK (length(btrim(external_organization_id)) > 0),
    CONSTRAINT external_identities_external_user_not_blank CHECK (length(btrim(external_user_id)) > 0),
    CONSTRAINT external_identities_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT external_identities_version_non_negative CHECK (version >= 0),
    CONSTRAINT external_identities_integration_external_unique
        UNIQUE (integration_id, external_organization_id, external_user_id)
);

CREATE INDEX external_identities_lookup_idx
    ON external_identities (integration_id, external_organization_id, external_user_id, status);
