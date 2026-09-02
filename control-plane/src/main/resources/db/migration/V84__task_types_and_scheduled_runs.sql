ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS task_type TEXT NOT NULL DEFAULT 'NORMAL';

ALTER TABLE tasks
    DROP CONSTRAINT IF EXISTS tasks_task_type_non_blank;

ALTER TABLE tasks
    ADD CONSTRAINT tasks_task_type_non_blank
        CHECK (task_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$');

CREATE INDEX IF NOT EXISTS tasks_type_idx ON tasks (task_type, updated_at DESC);

CREATE TABLE scheduled_task_runs (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES scheduled_tasks(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id),
    occurrence_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'TRIGGERED',
    cancel_operation_key TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT scheduled_task_runs_status_check
        CHECK (status IN ('TRIGGERED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'RECOVERY_REQUIRED')),
    CONSTRAINT scheduled_task_runs_version_non_negative CHECK (version >= 0),
    UNIQUE (schedule_id, occurrence_at)
);

CREATE INDEX scheduled_task_runs_schedule_idx ON scheduled_task_runs (schedule_id, occurrence_at DESC);
CREATE INDEX scheduled_task_runs_task_idx ON scheduled_task_runs (task_id);
