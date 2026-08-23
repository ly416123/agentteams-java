CREATE TABLE resource_scopes (
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (resource_type, resource_id),
    CONSTRAINT resource_scopes_tenant_non_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT resource_scopes_project_non_blank CHECK (length(btrim(project_id)) > 0),
    CONSTRAINT resource_scopes_team_non_blank CHECK (length(btrim(team)) > 0)
);

CREATE INDEX resource_scopes_project_idx
    ON resource_scopes (tenant_id, project_id, team, resource_type);
