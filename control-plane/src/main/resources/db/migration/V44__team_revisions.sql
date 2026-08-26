ALTER TABLE teams ADD COLUMN current_revision BIGINT;

CREATE TABLE team_revisions (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    leader_agent_id UUID NOT NULL REFERENCES agents(id),
    overlay JSONB NOT NULL,
    digest TEXT NOT NULL,
    status TEXT NOT NULL,
    rollback_of_revision BIGINT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key TEXT NOT NULL,
    PRIMARY KEY (team_id, revision),
    UNIQUE (team_id, idempotency_key),
    CONSTRAINT team_revisions_revision_positive CHECK (revision > 0),
    CONSTRAINT team_revisions_overlay_object CHECK (jsonb_typeof(overlay) = 'object'),
    CONSTRAINT team_revisions_digest_non_blank CHECK (length(trim(digest)) > 0),
    CONSTRAINT team_revisions_status_valid CHECK (status IN
        ('DRAFT', 'REVIEWING', 'PUBLISHED', 'DEPRECATED', 'REJECTED', 'ROLLED_BACK')),
    CONSTRAINT team_revisions_version_non_negative CHECK (version >= 0),
    CONSTRAINT team_revisions_rollback_positive CHECK
        (rollback_of_revision IS NULL OR rollback_of_revision > 0)
);

CREATE UNIQUE INDEX team_revisions_one_published
    ON team_revisions(team_id) WHERE status = 'PUBLISHED';

CREATE TABLE team_revision_members (
    team_id UUID NOT NULL,
    team_revision BIGINT NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    member_index INTEGER NOT NULL,
    PRIMARY KEY (team_id, team_revision, agent_id),
    UNIQUE (team_id, team_revision, member_index),
    FOREIGN KEY (team_id, team_revision) REFERENCES team_revisions(team_id, revision) ON DELETE CASCADE,
    CONSTRAINT team_revision_members_index_non_negative CHECK (member_index >= 0)
);

CREATE INDEX team_revision_members_agent_idx ON team_revision_members(agent_id, team_id, team_revision);
