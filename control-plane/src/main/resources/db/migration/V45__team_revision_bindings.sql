CREATE TABLE team_deployments (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL,
    team_revision BIGINT NOT NULL,
    status TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    idempotency_key TEXT NOT NULL,
    FOREIGN KEY (team_id, team_revision) REFERENCES team_revisions(team_id, revision),
    UNIQUE (team_id, idempotency_key),
    CONSTRAINT team_deployments_status_valid CHECK (status IN
        ('PENDING', 'SUCCEEDED', 'PARTIAL_FAILURE', 'FAILED')),
    CONSTRAINT team_deployments_revision_positive CHECK (team_revision > 0),
    CONSTRAINT team_deployments_version_non_negative CHECK (version >= 0)
);

CREATE OR REPLACE FUNCTION ensure_team_deployment_published_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM team_revisions
                   WHERE team_id = NEW.team_id AND revision = NEW.team_revision
                     AND status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'team deployment requires a PUBLISHED revision';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER team_deployments_published_revision_guard
    BEFORE INSERT OR UPDATE OF team_id, team_revision ON team_deployments
    FOR EACH ROW EXECUTE FUNCTION ensure_team_deployment_published_revision();

CREATE TABLE team_deployment_members (
    deployment_id UUID NOT NULL REFERENCES team_deployments(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id),
    base_manifest JSONB,
    task_overlay JSONB NOT NULL DEFAULT '{}'::jsonb,
    binding_id UUID REFERENCES config_bindings(id),
    status TEXT NOT NULL,
    failure_code TEXT,
    PRIMARY KEY (deployment_id, agent_id),
    CONSTRAINT team_deployment_members_status_valid CHECK (status IN
        ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT team_deployment_members_task_overlay_object CHECK (jsonb_typeof(task_overlay) = 'object')
);

CREATE INDEX team_deployment_members_status_idx
    ON team_deployment_members(deployment_id, status);

CREATE TABLE team_deployment_operations (
    deployment_id UUID NOT NULL REFERENCES team_deployments(id) ON DELETE CASCADE,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (deployment_id, operation, idempotency_key),
    CONSTRAINT team_deployment_operations_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT team_deployment_operations_name_valid CHECK (operation = 'RETRY')
);
