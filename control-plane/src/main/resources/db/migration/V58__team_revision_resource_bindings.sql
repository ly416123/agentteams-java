CREATE TABLE team_revision_resource_bindings (
    team_id UUID NOT NULL,
    team_revision BIGINT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    resource_revision TEXT NOT NULL,
    digest TEXT NOT NULL,
    PRIMARY KEY (team_id, team_revision, resource_type, resource_id),
    FOREIGN KEY (team_id, team_revision) REFERENCES team_revisions(team_id, revision) ON DELETE CASCADE,
    CONSTRAINT team_revision_resource_bindings_type_valid CHECK
        (resource_type IN ('MODEL', 'FILE', 'SKILL', 'MCP_SERVER')),
    CONSTRAINT team_revision_resource_bindings_revision_non_blank CHECK (length(trim(resource_revision)) > 0),
    CONSTRAINT team_revision_resource_bindings_digest_non_blank CHECK (length(trim(digest)) > 0)
);

CREATE INDEX team_revision_resource_bindings_lookup_idx
    ON team_revision_resource_bindings(team_id, resource_type, resource_id, resource_revision);
