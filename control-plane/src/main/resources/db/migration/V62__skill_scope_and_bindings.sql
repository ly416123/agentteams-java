ALTER TABLE skills
    ADD COLUMN organization_id TEXT,
    ADD COLUMN tenant_id TEXT;

ALTER TABLE skill_versions
    ADD COLUMN organization_id TEXT,
    ADD COLUMN tenant_id TEXT;

ALTER TABLE skills
    ADD CONSTRAINT skills_scope_pair_check CHECK ((organization_id IS NULL) = (tenant_id IS NULL));

ALTER TABLE skill_versions
    ADD CONSTRAINT skill_versions_scope_pair_check CHECK ((organization_id IS NULL) = (tenant_id IS NULL));

CREATE INDEX skills_scope_idx ON skills (organization_id, tenant_id, visibility, lifecycle, name);
CREATE INDEX skill_versions_scope_idx ON skill_versions (organization_id, tenant_id, lifecycle, updated_at DESC);

CREATE TABLE skill_bindings (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    team_id TEXT,
    skill_id UUID NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    skill_version_id UUID NOT NULL REFERENCES skill_versions (id) ON DELETE CASCADE,
    digest TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by TEXT NOT NULL,
    CONSTRAINT skill_bindings_scope_not_blank CHECK (length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT skill_bindings_digest_not_blank CHECK (length(btrim(digest)) > 0),
    CONSTRAINT skill_bindings_created_by_not_blank CHECK (length(btrim(created_by)) > 0),
    CONSTRAINT skill_bindings_skill_version_unique UNIQUE (organization_id, tenant_id, skill_id, skill_version_id, digest)
);

CREATE INDEX skill_bindings_tenant_idx ON skill_bindings (organization_id, tenant_id, project_id, team_id);
