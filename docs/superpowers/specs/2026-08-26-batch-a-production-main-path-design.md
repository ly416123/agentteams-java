# 批次 A：生产主路径修复设计

**日期：** 2026-08-26
**状态：** 已实现并完成 L1-L4 验收（2026-08-27）
**代码基线：** `fd721d3`
**完成证据：** 批次 A 已通过 `c1f4602` 合并到 `main`；后续运行时、部署和 CI 修复已纳入当前基线。L5/L6 受控环境验收仍按路线图单独管理。
**关联路线图：** [2026-08-26-remaining-capabilities-roadmap-design.md](2026-08-26-remaining-capabilities-roadmap-design.md)

## 1. 目标与范围

本批次完成路线图中的生产主路径能力：

1. Kubernetes `TaskSandbox` Provider 与生命周期调度；
2. AgentScope Worker 路由与灰度；
3. Manager 正式服务入口；
4. Team Revision 与 Effective Config；
5. Sandbox、Worker Runtime、Manager 的 Helm、RBAC、NetworkPolicy 和 Secret 配置契约。

本批次不实现 Worker Template、SDK、Web Console、Channel SPI、Sandbox Pool、CubeSandbox、多区域和最终账单。gVisor/Kata 的真实隔离、外部 Secret Manager、外部 IdP 和生产发布演练只建立可复现的契约与验收入口，不在本地环境伪造 L5/L6 结果。

## 2. 现状与约束

当前仓库已有 `SandboxRuntimePort`、`task_sandboxes`、`TaskSandbox` CRD、Operator Job 渲染、AgentScope Runtime、灰度策略、Manager 模型/工具/审计基础和项目权限基础，但生产连接处仍缺少：

- Sandbox Provider 仍是同步 `FakeSandboxRuntime`，没有 Kubernetes CR 的幂等观察模型；
- `SandboxLifecycleService` 没有由 Leader 租约驱动的完整 reconcile 周期；
- Worker 仍固定使用 QwenPaw Runtime，无法按 Task 记录稳定 Runtime owner；
- Manager 只有本地 Smoke 入口，没有 HTTP API、持久化 Session 和 SSE 重放；
- Team 配置没有不可变 Revision 与 Effective Config digest；
- Helm 尚未把所有生产边界以默认关闭、最小权限和 fail-closed 方式表达。

实现必须遵守以下约束：

- PostgreSQL 是 Task、Attempt、Lease、Sandbox、配置 Revision、Binding、审计和 Manager Session 的权威状态源；
- Kubernetes 只保存基础设施期望态和状态投影；Control Plane 不创建 Pod/Job/Secret；
- 外部 Provider 调用发生在数据库事务之外，重试通过持久化意图、版本号和幂等键恢复；
- Secret 只通过引用和短期读取进入进程，不进入事件、日志、配置 Revision 或指标标签；
- 所有写接口要求 `Idempotency-Key` 或稳定业务唯一键，并返回稳定错误分类。

## 3. 公共契约

### 3.1 Sandbox Provider

在 `application-contracts` 中将 Sandbox Provider 抽象为可异步观察的契约：

```java
public interface SandboxRuntimePort {
    SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command);
    SandboxObservation inspect(SandboxProviderRef providerRef);
    SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command);
    SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command);
}
```

`SandboxProviderRef` 包含 `provider`、稳定 `resourceId` 和 `resourceUid`；`SandboxObservation` 包含 Provider phase、Endpoint、过期时间、generation、workload UID 和脱敏 `SandboxFailure`。Fake Provider 同步返回 `READY`，Kubernetes Provider 首次返回 `PROVISIONING`。

Kubernetes Provider 的 `resourceId` 固定为 `namespace/task-sandbox-<attemptIdWithoutHyphens>`，不使用随机 Job UID。相同 Attempt、相同 CR UID、相同 spec 和幂等键的重复调用返回当前 Receipt；任何不一致都返回 `IDEMPOTENCY_CONFLICT`，不得覆盖原 CR。

### 3.2 Worker Runtime

Worker 组合根统一依赖 `AgentRuntime`。`WorkerRuntimeRouter` 保存 `taskId -> delegate` owner：首次提交依据 tenant、project、Team、Agent 和稳定 Task ID 选择 QwenPaw 或 AgentScope，后续 cancel、stop、结果回调始终回到原 delegate。缺少稳定 scope 时 fail-closed 到 QwenPaw；显式 AgentScope 但缺少 Harness/Model 配置时启动失败；灰度命中但运行时不可用时返回 `RUNTIME_UNAVAILABLE`。

### 3.3 Team Revision 与 Effective Config

Team Revision 使用不可变状态机 `DRAFT -> PUBLISHED -> DEPRECATED`。已发布 Revision 不可更新，Rollback 创建新的 Revision。Effective Config 按固定优先级合成 Team、AgentSpec、Worker 和项目策略，数组去重，权限只能收紧，Sandbox profile 只能提升安全级别，并用规范化 JSON 计算稳定 digest。

### 3.4 Manager API

Manager 暴露以下入口：

```text
POST /api/v1/manager/sessions
POST /api/v1/manager/sessions/{sessionId}/messages
GET  /api/v1/manager/sessions/{sessionId}
GET  /api/v1/manager/sessions/{sessionId}/events
POST /api/v1/manager/sessions/{sessionId}/cancel
```

首期只注册 `create_task` 和只读查询工具。请求必须带 scope、幂等键和预期 Session 版本；模型输出只能经过 JSON Schema、权限、审批、工具幂等和审计链路，不得直接执行 HTTP、SQL、Kubernetes 或 Shell。

## 4. 组件与数据流

```text
Task/Attempt/Lease
       |
       v
task_sandboxes + Outbox
       |
       v
SandboxLifecycleScheduler -- Leader lease --> KubernetesSandboxRuntime
                                                |
                                                v
                                      TaskSandbox CR + Operator
                                                |
                                      restricted Job + Service

TaskAssigned --> WorkerRuntimeRouter --> QwenPaw / AgentScope

Client --> Manager Controller --> Session Repository
                         |
                         +--> ModelProvider --> Tool Registry --> typed Control Plane API
                         +--> Manager Event Repository --> SSE cursor replay
```

Sandbox 只有在 CR UID、最新 generation、Runner Ready、Service Endpoint 和健康检查均满足时才由 Control Plane 标记 `READY` 并发布唯一 `TaskAssigned`。Task 完成、失败、取消或 Attempt 被替代时，使用同一 Attempt ID 写入 termination intent；Operator 观察 intent 后删除 Job/Service，完成后更新 CR 状态并移除 finalizer。

## 5. 并行实现泳道

### 泳道 A：公共契约与 Sandbox

先完成 `application-contracts` Sandbox 类型、数据库迁移和 Fake Provider 兼容层，再实现 Kubernetes Provider、生命周期 Scheduler、Operator finalizer/generation 保护和 Sandbox 状态观察。该泳道负责与 Task Assignment 的唯一 `TaskAssigned` 事务边界。

### 泳道 B：Worker Runtime

在公共 `AgentRuntime` 契约稳定后，新增 Factory、Router 和 AgentScope 配置工厂，修改 Worker 组合根及 Operator Runtime env 注入。该泳道不持有 Kubernetes Client，也不修改 Sandbox；Workspace 只读取 Gateway/Control Plane 投影。

### 泳道 C：Manager

新增 Spring Boot 应用、Controller、Session/Message/ToolCall/Event 持久化、SSE 游标和统一错误映射。Manager 通过 typed HTTP/内部 Port 调用 Control Plane，不直接访问 Control Plane persistence package。

### 泳道 D：Team/Effective Config

新增 Team Revision、发布/回滚、Effective Config 合成、Binding 与成员失败重试。该泳道复用现有 Team、AgentSpec、Config Deployment 和权限服务，不重复创建 Team 或 Worker 状态机。

### 泳道 E：交付与安全

补齐 Helm values schema、Sandbox CR 最小 RBAC、Worker Runtime ConfigMap、Manager Deployment/Service/NetworkPolicy/PDB、安全上下文和 CI 静态契约。该泳道可与 Java 领域实现并行；模板字段以公共契约合并结果为准。

公共契约阶段完成后，泳道 A、B、C、D 可并行开发；泳道 E 中不依赖 Java 类型的 NetworkPolicy、securityContext、PDB 和 HPA 静态测试可提前开发。最终按 A→Operator→Kind、B→Worker/Gateway、C→Manager、D→Team API 四条链路联合验收。

## 6. 错误、恢复与安全

- Provider 错误只允许固定分类：`RUNTIME_CLASS_NOT_FOUND`、`POLICY_REJECTED`、`RESOURCE_QUOTA_EXCEEDED`、`KUBERNETES_UNAVAILABLE`、`STATUS_TIMEOUT`、`PROVIDER_RESOURCE_LOST`、`IDEMPOTENCY_CONFLICT`、`PROVIDER_RESPONSE_INVALID`；
- Manager 错误分类包含 `MODEL_UNAVAILABLE`、`MODEL_OUTPUT_INVALID`、`QUOTA_REJECTED`、`AUTHORIZATION_REJECTED`、`TOOL_INPUT_INVALID`、`TOOL_CONFLICT`、`TOOL_TEMPORARY_FAILURE`、`SESSION_VERSION_CONFLICT` 和 `SESSION_CANCELLED`；
- 所有版本更新使用 expected version；旧 generation、旧 Attempt、迟到 ACK 和重复消息不得覆盖新状态；
- 调度器单条 Provider 失败不阻断批次，过期 operation lease 由下一 Leader 回收；
- Manager SSE 使用持久化递增游标，进程重启后从指定 cursor 重放；
- Helm 默认关闭生产 Provider、AgentScope 和 Manager；启用但配置不完整时启动失败；
- 日志和 CI Artifact 只保留 hash、资源 ID、错误分类和 correlation ID，不记录 Token、Secret、完整 Prompt/Response 或供应商原文。

## 7. 验收策略

每条泳道采用 TDD：

1. 为幂等、版本、状态和安全默认值写失败单测；
2. 运行指定测试确认失败原因是能力缺失；
3. 编写最小实现并运行单模块测试；
4. 用 Testcontainers 覆盖 Flyway、唯一约束、锁、重启恢复和并发；
5. 用 Helm lint/template 与静态脚本验证 RBAC、NetworkPolicy、ServiceAccount、securityContext、PDB 和配置 schema；
6. 在 Kind 可用时运行跨服务验收；无 Docker/Kind 时明确记录环境限制，不将跳过视为通过。

批次 A 的本地完成门槛为：Java 单元测试通过、迁移从空库和当前 `V42` 升级通过、Helm 静态校验通过、`git diff --check` 通过、敏感信息扫描通过。真实 gVisor/Kata、外部 Secret、外部 IdP、生产镜像签名和 2 小时长期运行属于受控 L5/L6 环境。

## 8. 非目标

- 不把 Team、Worker 或 Task 绑定到 Kubernetes Node；
- 不在 Control Plane 中获得 Pod、Job、Secret、Docker Socket 或宿主机权限；
- 不在本批次引入 Console、SDK、Channel、Pool、CubeSandbox、多区域或最终账单；
- 不用进程内 Map 作为跨进程业务事实；Worker Router 的内存 owner 只用于当前进程内路由，权威状态仍由 Attempt/Lease 持久化事实决定。
