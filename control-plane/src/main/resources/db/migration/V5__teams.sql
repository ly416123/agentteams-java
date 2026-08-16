CREATE TABLE teams (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT teams_version_non_negative CHECK (version >= 0),
    CONSTRAINT teams_status_non_blank CHECK (length(trim(status)) > 0)
);

CREATE TABLE team_policies (
    team_id UUID PRIMARY KEY REFERENCES teams(id) ON DELETE CASCADE,
    max_concurrent_tasks INTEGER NOT NULL DEFAULT 1,
    require_human_approval BOOLEAN NOT NULL DEFAULT false,
    allowed_runtimes JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT team_policies_max_concurrent_positive CHECK (max_concurrent_tasks > 0),
    CONSTRAINT team_policies_allowed_runtimes_array CHECK (jsonb_typeof(allowed_runtimes) = 'array'),
    CONSTRAINT team_policies_required_capabilities_array CHECK (jsonb_typeof(required_capabilities) = 'array')
);

CREATE TABLE team_memberships (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id),
    role TEXT NOT NULL,
    status TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (team_id, agent_id),
    CONSTRAINT team_memberships_version_non_negative CHECK (version >= 0),
    CONSTRAINT team_memberships_role_non_blank CHECK (length(trim(role)) > 0),
    CONSTRAINT team_memberships_status_non_blank CHECK (length(trim(status)) > 0)
);

CREATE INDEX team_memberships_agent_idx ON team_memberships(agent_id, status);

CREATE TABLE team_tasks (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    approval_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (team_id, task_id),
    CONSTRAINT team_tasks_version_non_negative CHECK (version >= 0)
);

CREATE TABLE team_task_assignments (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL,
    task_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    membership_id UUID NOT NULL REFERENCES team_memberships(id),
    status TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (team_id, task_id) REFERENCES team_tasks(team_id, task_id) ON DELETE CASCADE,
    CONSTRAINT team_task_assignments_version_non_negative CHECK (version >= 0)
);

CREATE INDEX team_task_assignments_active_idx
    ON team_task_assignments(team_id, status)
    WHERE released_at IS NULL;

CREATE TABLE team_events (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT team_events_version_non_negative CHECK (version >= 0),
    CONSTRAINT team_events_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX team_events_team_idx ON team_events(team_id, occurred_at, id);
