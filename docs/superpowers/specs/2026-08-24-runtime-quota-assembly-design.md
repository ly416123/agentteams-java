# Worker/Manager 远程配额生产组装设计

Date: 2026-08-24

## 目标

把现有的远程配额客户端真正接入 Worker 和 Manager 的可执行组装路径，并用 Kind 真实 Worker 任务确认项目级调用计数由 Control Plane 持久化配额服务产生。

## 方案

- Worker 沿用 `GrpcRuntimeQuotaPort`，在 Kind recovery 创建真实 Worker 时显式开启远程 quota，并注入 `tenant/project` scope。
- Manager 沿用 `ManagerQuotaPortFactory` 和 `ProjectScopedModelCallAdmission`，让 `ManagerSmokeApplication` 的真实 DeepSeek 调用在 Provider 请求前 acquire、完成或失败后幂等 release。
- Kind 新增 Worker quota admission 验收：创建项目配额策略，提交确定性 QwenPaw 任务，等待成功，检查 `daily_calls`/`daily_tokens` 增加且 `current_concurrent_calls` 最终归零。
- 不新增 HTTP API，不把本地 admission 或估算成本当作远程持久化配额；远程关闭时保持现有兼容行为。

## 安全与失败处理

- Manager 远程 quota 开启时必须提供 tenant、project、Gateway 地址和 Manager ID；缺失或非法配置启动失败。
- DeepSeek API Key 继续只从本地 `apikey`/环境变量读取，不进入参数、日志或 CI。
- quota 拒绝在 Provider 调用前终止；Provider 异常和进程退出通过 `finally` 释放 lease。

## 实施状态（2026-08-24）

- Manager smoke 已接入 `ManagerSmokeConfiguration`、Gateway gRPC channel 和
  `ProjectScopedModelCallAdmission`；未开启远程 quota 时保留兼容的 no-op 行为。
- Kind recovery 创建的真实 Worker 已显式开启远程 quota，并绑定
  `tenant-a/project-a`；新增 `Run Kind Worker quota admission` 步骤。
- 验收脚本会为目标 Agent 创建临时 Team 并建立唯一成员关系，再创建 QwenPaw
  任务，避免 `--agent-id` 仅作为参数而实际被其他 READY Worker 接走。
- 本地 Kind 真实验收已输出
  `KIND_WORKER_QUOTA_ADMISSION_OK`：`daily_calls_delta=1`、
  `daily_tokens_delta=1024`、`current_concurrent_calls=0`。
- 仍未宣称 Manager 生产 API、真实 DeepSeek CI 调用或跨租户计费闭环已完成；本轮只
  组装并验证本地 Manager smoke 和 Kind Worker 的远程配额路径。

## 验收

- Manager 单元测试覆盖远程开启、scope 透传、拒绝短路和 release。
- Kind 验收输出 `KIND_WORKER_QUOTA_ADMISSION_OK`，并证明计数变化来自 Worker 远程调用。
- Java clean test、Kind/Helm 静态检查和相关 Python 契约测试通过。
