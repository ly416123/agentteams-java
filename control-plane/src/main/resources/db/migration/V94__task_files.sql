-- G05 任务文件账本：role=INPUT（输入附件引用快照）/ OUTPUT（产物二进制）。
-- INPUT 的对象本体留在会话域（conversation_files），storage_key 空串，不建跨域 FK。
-- UNIQUE 覆盖 OUTPUT 去重；INPUT 的 sha256 为 NULL 时 PostgreSQL 视 NULL 不等，
-- INPUT 查重由应用层 (task_id, INPUT, name, source_file_id) 完成（规格 §5.1）。
CREATE TABLE task_files (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID REFERENCES task_attempts (id),
    role TEXT NOT NULL CHECK (role IN ('INPUT', 'OUTPUT')),
    name TEXT NOT NULL,
    content_type TEXT,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT,
    storage_key TEXT NOT NULL DEFAULT '',
    source_session_id UUID,
    source_file_id UUID,
    status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MISSING')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_files_unique_output UNIQUE (task_id, role, name, sha256)
);
CREATE INDEX task_files_task_idx ON task_files (task_id);
