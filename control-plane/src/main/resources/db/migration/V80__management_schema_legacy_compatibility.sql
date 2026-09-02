-- L5 previously applied V72-V76 from the platform identity branch. Preserve
-- those checksums and bridge its schema to the management console contract.
ALTER TABLE integrations
    ADD COLUMN IF NOT EXISTS external_key TEXT,
    ADD COLUMN IF NOT EXISTS display_name TEXT;

UPDATE integrations
   SET external_key = COALESCE(NULLIF(btrim(external_key), ''), NULLIF(btrim(name), ''), id::text),
       display_name = COALESCE(NULLIF(btrim(display_name), ''), NULLIF(btrim(name), ''), external_key, id::text);

ALTER TABLE integrations
    ALTER COLUMN external_key SET NOT NULL,
    ALTER COLUMN display_name SET NOT NULL,
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN channel_type DROP NOT NULL,
    ALTER COLUMN user_assertion_mode DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS integrations_organization_external_key_unique
    ON integrations (organization_id, external_key);

ALTER TABLE integration_credentials
    ADD COLUMN IF NOT EXISTS credential_ref TEXT;

UPDATE integration_credentials
   SET credential_ref = COALESCE(NULLIF(btrim(credential_ref), ''), 'secret://legacy/' || access_key_id)
 WHERE credential_ref IS NULL OR btrim(credential_ref) = '';

ALTER TABLE integration_credentials
    ALTER COLUMN credential_ref SET NOT NULL;

ALTER TABLE integration_credentials
    DROP CONSTRAINT IF EXISTS integration_credentials_material_check;

CREATE TABLE IF NOT EXISTS management_users (
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

INSERT INTO management_users (id, subject, display_name, status, created_at, updated_at, version)
SELECT id, 'legacy:platform-user:' || id::text, display_name, status, created_at, updated_at, version
  FROM platform_users
ON CONFLICT (id) DO NOTHING;

ALTER TABLE external_identities
    ADD COLUMN IF NOT EXISTS organization_id UUID;

UPDATE external_identities identities
   SET organization_id = integrations.organization_id
  FROM integrations
 WHERE identities.integration_id = integrations.id
   AND identities.organization_id IS NULL;

DO $$
DECLARE
    foreign_key RECORD;
BEGIN
    FOR foreign_key IN
        SELECT constraint_ref.conname AS constraint_name
          FROM pg_constraint constraint_ref
          JOIN pg_class table_ref ON table_ref.oid = constraint_ref.conrelid
          JOIN pg_attribute attribute_ref ON attribute_ref.attrelid = table_ref.oid
                AND attribute_ref.attnum = ANY (constraint_ref.conkey)
         WHERE table_ref.relname = 'external_identities'
           AND constraint_ref.contype = 'f'
           AND attribute_ref.attname = 'internal_user_id'
    LOOP
        EXECUTE format('ALTER TABLE external_identities DROP CONSTRAINT %I', foreign_key.constraint_name);
    END LOOP;
END
$$;

ALTER TABLE external_identities
    ADD CONSTRAINT external_identities_management_user_fk
    FOREIGN KEY (internal_user_id) REFERENCES management_users(id);

CREATE UNIQUE INDEX IF NOT EXISTS external_identities_management_unique
    ON external_identities (integration_id, external_organization_id, external_user_id);

CREATE TABLE IF NOT EXISTS integration_provisioning_policies (
    integration_id UUID PRIMARY KEY,
    allow_auto_create BOOLEAN NOT NULL DEFAULT FALSE,
    allow_platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT integration_provisioning_policy_version_non_negative CHECK (version >= 0)
);

INSERT INTO integration_provisioning_policies (integration_id, updated_at, version)
SELECT id, now(), 0
  FROM integrations
ON CONFLICT (integration_id) DO NOTHING;

-- The legacy branch keyed memberships by principal_id. The current Control
-- Plane authorizes by the verified OIDC subject, so retain the legacy key as
-- nullable metadata and restore subject-keyed writes for the current API.
ALTER TABLE organization_memberships ADD COLUMN IF NOT EXISTS subject TEXT;
UPDATE organization_memberships
   SET subject = 'legacy:principal:' || principal_id::text
 WHERE subject IS NULL;
ALTER TABLE organization_memberships
    DROP CONSTRAINT IF EXISTS organization_memberships_pkey,
    DROP CONSTRAINT IF EXISTS organization_memberships_principal_status_idx_check,
    ALTER COLUMN principal_id DROP NOT NULL,
    ALTER COLUMN subject SET NOT NULL,
    ADD CONSTRAINT organization_memberships_pkey PRIMARY KEY (organization_id, subject);

ALTER TABLE tenant_memberships ADD COLUMN IF NOT EXISTS subject TEXT;
UPDATE tenant_memberships
   SET subject = 'legacy:principal:' || principal_id::text
 WHERE subject IS NULL;
ALTER TABLE tenant_memberships
    DROP CONSTRAINT IF EXISTS tenant_memberships_pkey,
    ALTER COLUMN principal_id DROP NOT NULL,
    ALTER COLUMN subject SET NOT NULL,
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (tenant_id, subject);

ALTER TABLE project_memberships ADD COLUMN IF NOT EXISTS subject TEXT;
UPDATE project_memberships
   SET subject = 'legacy:principal:' || principal_id::text
 WHERE subject IS NULL;
ALTER TABLE project_memberships
    DROP CONSTRAINT IF EXISTS project_memberships_pkey,
    ALTER COLUMN principal_id DROP NOT NULL,
    ALTER COLUMN subject SET NOT NULL,
    ADD CONSTRAINT project_memberships_pkey PRIMARY KEY (tenant_id, project_id, subject);

ALTER TABLE project_membership_idempotency ADD COLUMN IF NOT EXISTS subject TEXT;
UPDATE project_membership_idempotency
   SET subject = 'legacy:principal:' || principal_id::text
 WHERE subject IS NULL;
ALTER TABLE project_membership_idempotency
    ALTER COLUMN principal_id DROP NOT NULL,
    ALTER COLUMN subject SET NOT NULL;

ALTER TABLE project_invitations ADD COLUMN IF NOT EXISTS subject TEXT;
UPDATE project_invitations
   SET subject = 'legacy:principal:' || principal_id::text
 WHERE subject IS NULL;
ALTER TABLE project_invitations
    ALTER COLUMN principal_id DROP NOT NULL,
    ALTER COLUMN subject SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS organization_memberships_subject_unique
    ON organization_memberships (organization_id, subject);
CREATE UNIQUE INDEX IF NOT EXISTS tenant_memberships_subject_unique
    ON tenant_memberships (tenant_id, subject);
CREATE UNIQUE INDEX IF NOT EXISTS project_memberships_subject_unique
    ON project_memberships (tenant_id, project_id, subject);
