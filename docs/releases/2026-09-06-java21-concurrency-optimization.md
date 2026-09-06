# Java 21 与并发优化发布说明

发布日期：2026-09-06

当前版本：`0.1.0-SNAPSHOT`

## 本次变更

- 将项目编译基线统一到 Java 21，并同步更新 Docker、构建脚本和运行环境约束。
- 为 Manager Conversation 与 HTTP Runtime 的阻塞式 SSE 读取增加虚拟线程执行器，
  通过 `agentteams.concurrency.virtual-threads.enabled` 或
  `AGENTTEAMS_VIRTUAL_THREADS_ENABLED` 控制，默认关闭。
- 保留并发配额、超时、取消、资源释放和幂等保护，避免虚拟线程绕过现有资源边界。
- 完善长连接、取消、超时和平台线程 / 虚拟线程双模式集成验收。
- 增加组织、Team、Work、模板和对话五大领域的 100 个前端合同测试。
- 管理界面统一显示中文标签、状态、错误提示和操作文案。
- 修复共享 Correlation ID Filter 的自动配置冲突，完善可观测性装配测试。
- 补充 NATS、Outbox、Worker、Sandbox 和生产配置的合同校验。

## 验证结果

- Maven 全量测试：1,481 个测试通过，失败 0，错误 0，跳过 0。
- Java 21 虚拟线程对话集成验收：平台线程与虚拟线程两种模式均覆盖长 SSE、取消和超时。
- 前端合同测试覆盖组织、Team、Work、模板和对话领域。
- 架构图、API 合同、生产配置、可观测性和 Kind Manifest 校验通过。

## 发布注意事项

- 当前仍是 `0.1.0-SNAPSHOT`，本次不直接升级正式版本号。
- 虚拟线程开关默认关闭；上线前应结合目标环境的并发、尾延迟、JFR 和资源监控结果逐步灰度。
- 本次未执行正式压力测试，不能据此宣称生产吞吐或资源收益已经达标。
- L5 环境已清理无效 Terminated Worker 资源；保留的历史 ReplicaSet 仅作为回滚记录，未删除。

## 回滚方式

1. 将 `AGENTTEAMS_VIRTUAL_THREADS_ENABLED` 设置为 `false`，或移除对应配置，恢复平台线程执行器。
2. 如需整体回滚，回退到合并本次变更前的 `main` 提交，并按现有部署流程重新发布。
