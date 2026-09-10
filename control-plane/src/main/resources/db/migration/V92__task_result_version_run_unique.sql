-- 任务评审闭环（G02）审查修复：同 run 只允许一个结果版本（幂等去重的数据库兜底）。
-- 应用层已在任务行锁内复查 findByRun（TaskResultVersionService.onManifestPublished），
-- 本索引是并发重复投递的最后防线；V91 建表以来 run_id 由服务端唯一分配，存量数据无双写。
CREATE UNIQUE INDEX IF NOT EXISTS task_result_versions_run_unique
    ON task_result_versions (run_id) WHERE run_id IS NOT NULL;
