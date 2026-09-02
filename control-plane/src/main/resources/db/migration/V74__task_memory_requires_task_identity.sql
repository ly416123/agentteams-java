ALTER TABLE memories DROP CONSTRAINT memories_scope_owner_check;
ALTER TABLE memories ADD CONSTRAINT memories_scope_owner_check CHECK (
    (scope = 'USER_PRIVATE' AND subject_id IS NOT NULL)
    OR (scope = 'ORGANIZATION_SHARED')
    OR (scope = 'PROJECT_SHARED' AND project_id IS NOT NULL)
    OR (scope = 'TEAM_SHARED' AND team_id IS NOT NULL)
    OR (scope = 'TASK' AND task_id IS NOT NULL AND (project_id IS NOT NULL OR team_id IS NOT NULL))
);

CREATE INDEX memories_task_scope_idx ON memories (organization_id, tenant_id, task_id, updated_at DESC);
