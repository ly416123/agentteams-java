-- 多 Agent 协作真调度（G03）：子任务升格为一等任务实体（D6 方案 A）。
-- 设计规格：docs/superpowers/specs/2026-09-11-g03-multi-agent-delegation-design.md。
-- 子任务行是完整任务：复用 TaskPhase 状态机与 attempt/lease/Team 调度底座；
-- parent_task_id 自引用，kind 区分 MAIN/SUBTASK，调度器出队对 kind 不敏感。
ALTER TABLE tasks ADD COLUMN parent_task_id UUID REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'MAIN' CHECK (kind IN ('MAIN', 'SUBTASK'));
CREATE INDEX tasks_parent_idx ON tasks (parent_task_id);
