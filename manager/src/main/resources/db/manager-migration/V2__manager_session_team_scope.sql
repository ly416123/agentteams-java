ALTER TABLE manager_sessions
    ADD COLUMN team_id VARCHAR(255) NOT NULL DEFAULT 'legacy';

ALTER TABLE manager_sessions
    DROP CONSTRAINT IF EXISTS manager_sessions_tenant_id_project_id_actor_idempotency_key_key;

ALTER TABLE manager_sessions
    ADD CONSTRAINT manager_sessions_scope_idempotency_key
    UNIQUE (tenant_id, project_id, team_id, actor, idempotency_key);

ALTER TABLE manager_sessions
    ALTER COLUMN team_id DROP DEFAULT;

CREATE INDEX manager_sessions_scope_updated_idx
    ON manager_sessions (tenant_id, project_id, team_id, actor, updated_at DESC, id DESC);
