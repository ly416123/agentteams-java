-- Recreate the canonical organization/tenant scope for installations that were
-- initialized with the legacy project authorization tables.  The bridge is
-- intentionally derived from active projects and memberships, so it is safe on
-- a fresh database and does not invent access for tenants without a project.
DO $migration$
DECLARE
    legacy_key TEXT;
    organization_uuid UUID;
    tenant_uuid UUID;
    migration_now TIMESTAMPTZ := now();
BEGIN
    FOR legacy_key IN
        SELECT DISTINCT tenant_id
          FROM projects
         WHERE status = 'ACTIVE'
    LOOP
        IF EXISTS (SELECT 1 FROM legacy_tenant_mappings WHERE legacy_tenant_key = legacy_key) THEN
            CONTINUE;
        END IF;

        organization_uuid := md5('agentteams-legacy-organization:' || legacy_key)::uuid;
        tenant_uuid := md5('agentteams-legacy-tenant:' || legacy_key)::uuid;

        INSERT INTO organizations(id, external_key, display_name, status, created_at, updated_at, version)
        VALUES (organization_uuid, 'legacy-' || legacy_key,
                '兼容组织 - ' || legacy_key, 'ACTIVE', migration_now, migration_now, 0)
        ON CONFLICT (id) DO NOTHING;

        SELECT id INTO organization_uuid
          FROM organizations
         WHERE external_key = 'legacy-' || legacy_key;

        INSERT INTO tenants(id, organization_id, external_key, display_name, status,
                            created_at, updated_at, version)
        VALUES (tenant_uuid, organization_uuid, legacy_key,
                '兼容租户 - ' || legacy_key, 'ACTIVE', migration_now, migration_now, 0)
        ON CONFLICT (organization_id, external_key) DO NOTHING;

        SELECT id INTO tenant_uuid
          FROM tenants
         WHERE organization_id = organization_uuid AND external_key = legacy_key;

        INSERT INTO legacy_tenant_mappings(legacy_tenant_key, organization_id, tenant_id, created_at)
        VALUES (legacy_key, organization_uuid, tenant_uuid, migration_now)
        ON CONFLICT (legacy_tenant_key) DO NOTHING;

        INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
        SELECT organization_uuid, membership.subject,
               CASE WHEN bool_or(membership.role IN ('OWNER', 'ADMIN')) THEN 'ADMIN' ELSE 'MEMBER' END,
               migration_now, migration_now
          FROM project_memberships membership
          JOIN projects project ON project.tenant_id = membership.tenant_id
                               AND project.id = membership.project_id
         WHERE membership.tenant_id = legacy_key
           AND project.status = 'ACTIVE'
         GROUP BY membership.subject
        ON CONFLICT (organization_id, subject) DO NOTHING;

        INSERT INTO tenant_memberships(organization_id, tenant_id, subject, role, created_at, updated_at)
        SELECT organization_uuid, tenant_uuid, membership.subject,
               CASE WHEN bool_or(membership.role IN ('OWNER', 'ADMIN')) THEN 'ADMIN' ELSE 'MEMBER' END,
               migration_now, migration_now
          FROM project_memberships membership
          JOIN projects project ON project.tenant_id = membership.tenant_id
                               AND project.id = membership.project_id
         WHERE membership.tenant_id = legacy_key
           AND project.status = 'ACTIVE'
         GROUP BY membership.subject
        ON CONFLICT (tenant_id, subject) DO NOTHING;
    END LOOP;
END
$migration$;
