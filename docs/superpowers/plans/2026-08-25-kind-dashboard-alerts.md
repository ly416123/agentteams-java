# Kind Dashboard 告警端到端验收计划

## 目标

在 Kind recovery job 中验证 Dashboard 告警从模型调用审计数据到通知投递的真实闭环：

- V40 `dashboard_alert_events` 表可用于持久化事件；
- Scheduler 通过 `SchedulerLeaseService` 执行一次项目范围评估；
- 相同规则和时间窗口重复评估不会重复投递；
- Webhook 返回 500 时事件进入 `FAILED` 并记录下一次重试时间；
- Webhook 恢复 2xx 后，调度器重试并将同一事件更新为 `SENT`；
- `/api/v1/dashboard/alerts/events` 能读到最终事件状态。

## 实施步骤

- [x] 增加 Kind Dashboard 告警验收的失败契约测试。
- [x] 增加确定性 HTTP 接收器的 Kind 资源和可切换成功/失败模式。
- [x] 增加验收脚本：注入合成审计数据，等待事件状态变化，校验去重、失败、重试和 API 查询结果。
- [x] 在 Kind recovery workflow 中启用告警调度器并接线验收脚本及诊断信息。
- [x] 运行 Python/Helm/Maven 本地验证；真实 Kind 集群验收留给 CI 执行，本机因无 Docker socket 未执行。

## 验收判定

本地无 Kind/Docker 时，只判定脚本、YAML、工作流和 Java 回归通过；不得把未运行的集群验收标记为通过。CI 中要求输出 `KIND_DASHBOARD_ALERTS_OK`，且失败时输出事件和接收器日志。
