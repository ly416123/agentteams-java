-- 任务业务生命周期闭环（G02）：归档属性、补充要求、业务结果版本。
-- 设计规格：docs/superpowers/specs/2026-09-10-task-review-lifecycle-design.md（D1-D10）。

-- 归档是任务独立属性（D6）：不进入 TaskPhase 状态机，phase 不因归档改变。
ALTER TABLE tasks ADD COLUMN archive_status TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (archive_status IN ('ACTIVE', 'ARCHIVED'));
ALTER TABLE tasks ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN archive_actor TEXT;
CREATE INDEX tasks_archive_idx ON tasks (archive_status);

-- 补充要求（D5）：content-only 非破坏性调整；consumed_run_id 支撑执行上下文幂等并入。
CREATE TABLE task_adjustments (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    content JSONB NOT NULL,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    consumed_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_adjustments_content_object CHECK (jsonb_typeof(content) = 'object')
);
CREATE INDEX task_adjustments_task_idx ON task_adjustments (task_id, created_at);

-- 业务结果版本（D1）：评审对象为「已提交待验收」的交付物；manifest 保持 run 级观测性质。
-- subtask_id 预留（D3）：本轮恒空，子任务产物评审待 G03 方向决策后启用。
CREATE TABLE task_result_versions (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    run_id UUID,
    manifest_id UUID,
    subtask_id UUID,
    seq INTEGER NOT NULL,
    status TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    content JSONB,
    submitted_by TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    review_actor TEXT,
    review_comment TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_result_versions_status_check CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REVISION_REQUIRED')),
    CONSTRAINT task_result_versions_task_seq_unique UNIQUE (task_id, seq)
);
CREATE INDEX task_result_versions_task_idx ON task_result_versions (task_id, seq DESC);
CREATE INDEX task_result_versions_manifest_idx ON task_result_versions (manifest_id);
