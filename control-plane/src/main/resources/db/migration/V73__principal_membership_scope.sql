DROP INDEX IF EXISTS tenant_memberships_subject_idx;
DROP INDEX IF EXISTS project_memberships_active_idx;

-- V60 stored the external subject directly in membership rows. The new model
-- intentionally removes that column, but an upgrade must first preserve the
-- existing membership relationships in an internal principal. This is a
-- one-time data migration, not an API compatibility path: no external
-- identity mapping is created and the subject column is dropped below.
CREATE TEMP TABLE v73_subject_principals (
    subject TEXT PRIMARY KEY,
    principal_id UUID NOT NULL,
    user_id UUID NOT NULL
);

INSERT INTO v73_subject_principals(subject, principal_id, user_id)
SELECT subject,
       md5('agentteams-v73-principal:' || subject)::uuid,
       md5('agentteams-v73-user:' || subject)::uuid
  FROM (
      SELECT subject FROM organization_memberships
      UNION
      SELECT subject FROM tenant_memberships
      UNION
      SELECT subject FROM project_memberships
      UNION
      SELECT subject FROM project_membership_idempotency
      UNION
      SELECT subject FROM project_invitations
  ) subjects
 WHERE subject IS NOT NULL AND length(btrim(subject)) > 0;

INSERT INTO principals(id, type, status, created_at, updated_at, version)
SELECT principal_id, 'USER', 'ACTIVE', now(), now(), 0
  FROM v73_subject_principals
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_users(id, principal_id, display_name, email, status, created_at, updated_at, version)
SELECT user_id, principal_id, subject, NULL, 'ACTIVE', now(), now(), 0
  FROM v73_subject_principals
ON CONFLICT (id) DO NOTHING;

ALTER TABLE organization_memberships
    DROP CONSTRAINT organization_memberships_pkey,
    DROP CONSTRAINT organization_memberships_subject_not_blank,
    ADD COLUMN principal_id UUID;

UPDATE organization_memberships memberships
   SET principal_id = subjects.principal_id
  FROM v73_subject_principals subjects
 WHERE memberships.principal_id IS NULL;

ALTER TABLE organization_memberships
    ALTER COLUMN principal_id SET NOT NULL,
    DROP COLUMN subject,
    ADD CONSTRAINT organization_memberships_pkey PRIMARY KEY (organization_id, principal_id);

ALTER TABLE tenant_memberships
    DROP CONSTRAINT tenant_memberships_pkey,
    DROP CONSTRAINT tenant_memberships_subject_not_blank,
    ADD COLUMN principal_id UUID;

UPDATE tenant_memberships memberships
   SET principal_id = subjects.principal_id
  FROM v73_subject_principals subjects
 WHERE memberships.principal_id IS NULL;

ALTER TABLE tenant_memberships
    ALTER COLUMN principal_id SET NOT NULL,
    DROP COLUMN subject,
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (tenant_id, principal_id);

ALTER TABLE project_memberships
    DROP CONSTRAINT project_memberships_pkey,
    DROP CONSTRAINT project_memberships_subject_not_blank,
    ADD COLUMN principal_id UUID;

UPDATE project_memberships memberships
   SET principal_id = subjects.principal_id
  FROM v73_subject_principals subjects
 WHERE memberships.principal_id IS NULL;

ALTER TABLE project_memberships
    ALTER COLUMN principal_id SET NOT NULL,
    DROP COLUMN subject,
    ADD CONSTRAINT project_memberships_pkey PRIMARY KEY (tenant_id, project_id, principal_id);

ALTER TABLE project_membership_idempotency
    DROP CONSTRAINT project_membership_idempotency_subject_not_blank,
    ADD COLUMN principal_id UUID;

UPDATE project_membership_idempotency idempotency
   SET principal_id = subjects.principal_id
  FROM v73_subject_principals subjects
 WHERE idempotency.principal_id IS NULL;

ALTER TABLE project_membership_idempotency
    ALTER COLUMN principal_id SET NOT NULL,
    DROP COLUMN subject;

ALTER TABLE project_invitations
    DROP CONSTRAINT project_invitations_subject_not_blank,
    ADD COLUMN principal_id UUID;

UPDATE project_invitations invitations
   SET principal_id = subjects.principal_id
  FROM v73_subject_principals subjects
 WHERE invitations.principal_id IS NULL;

ALTER TABLE project_invitations
    ALTER COLUMN principal_id SET NOT NULL,
    DROP COLUMN subject;

CREATE INDEX organization_memberships_principal_idx
    ON organization_memberships (principal_id, organization_id);

CREATE INDEX tenant_memberships_principal_idx
    ON tenant_memberships (principal_id, tenant_id);

CREATE INDEX project_memberships_active_idx
    ON project_memberships (tenant_id, project_id, status, principal_id);

ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_principal_status_idx_check
    CHECK (principal_id IS NOT NULL);
