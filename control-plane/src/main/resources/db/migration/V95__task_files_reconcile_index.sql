-- G05 对账扫描支撑：findAvailableOutputs 按 role/status 过滤的随机窗口探测。
-- 部分索引只覆盖对账关心的行（MISSING/INPUT 不进索引），账本无清理路径，
-- 缩小索引体积并让过滤扫描免于全表。
CREATE INDEX task_files_reconcile_idx
    ON task_files (created_at, id)
    WHERE role = 'OUTPUT' AND status = 'AVAILABLE';
