# AgentTeams Java 运行时生产闭环设计

**日期：** 2026-08-26
**状态：** 批次 A L1-L4 已完成；L5 RuntimeClass/TaskSandbox READY 与工作负载丢失状态投影已在独立 Ubuntu/KVM 节点通过；节点故障恢复与 L6 受控环境验收独立管理
**优先级：** P0
**代码基线：** `fd721d3`

## 1. 目标

本规格完成当前执行面的 3 个生产断点：

1. 将数据库中的 Task-Sandbox 生命周期连接到 Kubernetes `TaskSandbox` CRD 和 Operator；
2. 将已经存在的 AgentScope Runtime、Workspace 和灰度策略接入真实 Worker；
3. 将 Manager 从本地 Smoke 程序变成可部署、可鉴权、可审计的正式服务。

完成后，Task 仍由 Control Plane 管理，Sandbox 仍按 Attempt 隔离，Worker 可在 QwenPaw 与 AgentScope 之间灰度，Manager 只能通过受权限控制的业务工具产生副作用。

## 2. 当前断点

### 2.1 Sandbox

- `SandboxRuntimePort`、`task_sandboxes`、Assignment/Lease 生命周期和 `SandboxLifecycleService` 已存在；
- `TaskSandbox` CRD、Operator Reconciler 和受限 Job 渲染已存在；
- Control Plane 已支持 `fake` 与 Kubernetes Sandbox Provider，生命周期由带数据库租约的 Scheduler 驱动；
- `KubernetesSandboxRuntime`、Provider 状态迁移、幂等 provision/renew/terminate 和观察逻辑已实现；
- Operator 已具备 finalizer、删除收敛、Service/Job 资源管理和旧 generation 状态保护；
- gVisor/Kata 真实 RuntimeClass、TaskSandbox READY，以及删除生成 Job 后进入 `LOST` 的工作负载丢失状态投影，已在独立 Ubuntu/KVM 节点通过；节点故障恢复、RuntimeClass 缺失恢复、Attempt 自动恢复和生产外部依赖的 L5/L6 验收尚未在本仓库 CI 中执行。

### 2.2 AgentScope

- `AgentScopeRuntime`、Harness Factory、事件翻译、Workspace Factory 和模型适配器已存在；
- `AgentScopeRolloutPolicy` 和 Helm ConfigMap 已存在；
- Worker 已通过 `WorkerRuntimeFactory` 和 `WorkerRuntimeRouter` 支持 QwenPaw/AgentScope 灰度路由；
- Worker CR 的 runtime 已注入 `AGENTTEAMS_RUNTIME`，并通过配置工厂构造 AgentScope Harness；
- Sandbox 访问收敛为只读 `SandboxStateProbePort`，不向 Worker 暴露 Kubernetes 写权限。

### 2.3 Manager

- DeepSeek/OpenAI Compatible Provider、结构化输出、工具注册、Quota、价格和审计已存在；
- Manager 已提供 Spring Boot HTTP 入口、OIDC 鉴权、JDBC 会话/事件持久化、幂等 cursor 和 Helm Deployment；
- Manager 仍需在生产交付批次中接入签名镜像、外部 Secret/证书轮换和预发布发布门禁。

## 3. 总体架构

```text
Client / Matrix / Console
          |
          v
    Manager Service ---------> Control Plane typed APIs
          |                              |
          | model call                   | Task / Attempt / Lease
          v                              v
   Model Provider                 task_sandboxes (PostgreSQL)
                                         |
                                  SandboxLifecycleScheduler
                                         |
                                  KubernetesSandboxRuntime
                                         |
                                  TaskSandbox CRD
                                         |
                                      Operator
                                         |
                              restricted Job + RuntimeClass
                                         |
                                     Agent Worker
                              QwenPaw | AgentScope Router
```

Control Plane 只获得命名空间内 `TaskSandbox` CR 的最小权限，不能创建 Pod、Job、Secret、Deployment，也不能访问 Docker Socket。Operator 是唯一创建 Sandbox Job 的组件。

## 4. Kubernetes Sandbox Adapter

### 4.1 异步 Provider 契约

当前 `provision()` 返回后被 `SandboxLifecycleService` 立即标记为 `READY`，这只适合
Fake Provider，不适合 Kubernetes。生产闭环首先将 Port 改成显式异步观察模型：

```java
public interface SandboxRuntimePort {
    SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command);
    SandboxObservation inspect(SandboxProviderRef providerRef);
    SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command);
    SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command);
}

public record SandboxProviderRef(String provider, String resourceId, String resourceUid) {}

public record SandboxProvisionReceipt(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        long observedGeneration) {}

public record SandboxObservation(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        String endpointRef,
        Instant expiresAt,
        long observedGeneration,
        String workloadUid,
        SandboxFailure failure) {}
```

变更方法使用 `ensure` 命名，表示重复调用安全。Fake Provider 同步返回 `READY`；
Kubernetes Provider 初次通常返回 `PROVISIONING`，Control Plane 只有在后续
`inspect` 观察到相同 CR UID、最新 generation、Runner Ready 和有效 Endpoint 后
才能把数据库标记为 `READY` 并发布 `TaskAssigned`。

### 4.2 实现类型

新增：

```java
public final class KubernetesSandboxRuntime implements SandboxRuntimePort {
    private final KubernetesClient client;
    private final String namespace;
    private final Clock clock;

    public SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command);
    public SandboxObservation inspect(SandboxProviderRef providerRef);
    public SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command);
    public SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command);
}
```

Provider ID 使用稳定 CR 名称，不使用随机 Pod/Job UID：

名称由 `"task-sandbox-" + attemptId.toString().replace("-", "")` 生成。

`ensureProvisioned` 使用 server-side apply 创建或读取同名 `TaskSandbox`。重复请求必须比较 `taskId`、`attemptId`、profile、template 和幂等键：完全一致返回当前 Receipt；不一致返回 `IDEMPOTENCY_CONFLICT`，不得覆盖原 CR。`resourceId` 保存 `namespace/name`，`resourceUid` 保存 CR UID，不能使用 Job UID。

### 4.3 CR Spec

在现有字段基础上固定：

```yaml
spec:
  taskId: UUID
  attemptId: UUID
  idempotencyKey: sandbox:84a7d507-6c30-4e5f-a42b-1a04c78089c6
  profile: ISOLATED | HARDENED
  runtimeClassName: string
  template: string
  expiresAt: RFC3339
  terminationRequested: boolean
  terminationReason: TASK_COMPLETED | TASK_FAILED | TASK_CANCELLED | LEASE_EXPIRED | SUPERSEDED
```

RuntimeClass 由 Operator 根据 profile 和部署配置再次校验。Control Plane 不能从 Task 输入直接指定任意 RuntimeClass、镜像、ServiceAccount、Volume 或安全上下文。

### 4.4 状态映射

| CR 状态 | 数据库状态 | Provider 返回 |
|---|---|---|
| 不存在 | `LOST` 或尚未创建 | `LOST` |
| `PENDING`/`PROVISIONING` | `PROVISIONING` | `PROVISIONING` |
| `READY` | `READY`/`RUNNING` | `READY` |
| `FAILED` | `FAILED` | `FAILED` |
| `STOPPING` | `STOPPING` | `STOPPING` |
| `DESTROYED` | `DESTROYED` | `DESTROYED` |

Provider 错误分类固定为：

- `RUNTIME_CLASS_NOT_FOUND`；
- `POLICY_REJECTED`；
- `RESOURCE_QUOTA_EXCEEDED`；
- `KUBERNETES_UNAVAILABLE`；
- `STATUS_TIMEOUT`；
- `PROVIDER_RESOURCE_LOST`；
- `IDEMPOTENCY_CONFLICT`；
- `PROVIDER_RESPONSE_INVALID`。

Kubernetes Status message 只保存经过长度限制和模式清理的摘要。

### 4.5 Endpoint、续期和终止

Operator 为 Sandbox Runner 创建 ClusterIP Service。Endpoint 固定为：

例如 `sandbox+grpc://task-sandbox-84a7d5076c304e5fa42b1a04c78089c6.agentteams.svc:7443/84a7d507-6c30-4e5f-a42b-1a04c78089c6`。

因此需同步扩展 `GatewayRuntimeAdapter.validateEndpoint`：只接受 `sandbox+grpc`、
集群内 DNS authority、固定端口 7443、无 userInfo/query/fragment、无路径穿越。
生产不使用 Pod IP、Job 名称、宿主机路径或 `file://`。Worker 使用 Sandbox ID、
Task ID、Attempt ID、服务端证书和 TTL 联合验证 endpoint。

- `renew` 只更新 `spec.expiresAt`，且新时间必须晚于当前值；
- 旧 Attempt 或已终态 Sandbox 的续期返回稳定冲突，不复活资源；
- `terminate` 先设置 `terminationRequested=true`，由 Operator 删除 Job、更新 CR 状态，再移除 finalizer；
- Control Plane 观察 `DESTROYED` 后更新数据库，不直接强删 CR；
- 超过终止宽限期仍未销毁时记录告警，但不越权删除 Pod/Job。

## 5. Sandbox 生命周期调度

新增 `SandboxLifecycleScheduler`，复用 `SchedulerLeaseService`：

```java
@Scheduled(fixedDelayString = "${agentteams.sandbox.poll-interval-ms:1000}")
void reconcile();
```

单次 Leader 周期按顺序执行：

1. `recoverStaleOperations(now, operationTimeout, batchSize)`；
2. `provisionRequested(now, batchSize)`；
3. `observeActive(now, batchSize)`；
4. `renewExpiring(now, renewAhead, extension, batchSize)`；
5. `markExpiredOrLost(now, batchSize)`；
6. `terminateStopping(now, batchSize)`；
7. `observeStopping(now, batchSize)`。

现有 `SandboxLifecycleService` 需要补充 inspect/observe 方法。外部 Provider 调用必须发生在数据库事务之外；每次状态更新携带 expected version。多个副本重复 inspect 是允许的，重复 provision/terminate 必须幂等。每条记录通过 `operationOwner/operationExpiresAt` 领取；进程崩溃后由下一 Leader 回收过期操作租约。单条 Provider 失败不能中断整个批次。

Task 被 Worker 接受后，Sandbox 从 `READY` 进入 `RUNNING`；Task Lease 心跳在续租
Task 的同时刷新 Sandbox 的业务租约，但 Provider TTL 仍由生命周期调度器按
`renewAhead` 批量续期。心跳丢失只触发 Sandbox 进入待回收状态，不能绕过 Task
Lease fencing 直接重放任务。任务完成、失败、取消或 Attempt 被替代时，均以同一
Attempt ID 请求终止 Sandbox；迟到心跳和旧 Attempt 的续期返回稳定冲突。

配置项：

```yaml
agentteams:
  sandbox:
    enabled: false
    provider: fake
    namespace: agentteams
    poll-interval-ms: 1000
    batch-size: 16
    renew-before: 5m
    renew-extension: 30m
    lost-after: 2m
    termination-grace-period: 2m
    operation-timeout: 2m
    max-provision-attempts: 5
    max-terminate-attempts: 10
    base-retry-delay: 1s
    max-retry-delay: 1m
```

`enabled=true` 且 Provider Bean 不存在时 Control Plane 启动失败。生产值使用 `provider=kubernetes`；默认值继续关闭。

## 6. Operator 强化

`TaskSandboxReconciler` 必须实现：

- finalizer `agentteams.io/task-sandbox-cleanup`；
- generation/observedGeneration 检查，旧 Job 状态不能覆盖新 Spec；
- Sandbox 从未 Ready 时 Job 不存在可以重建；曾经 Ready 后工作负载 UID 消失必须标记 `LOST`，不得静默创建新 Job 并沿用旧 Attempt；
- `terminationRequested` 或 CR 删除时先删除 Job，观察资源消失后设置 `DESTROYED` 并移除 finalizer；
- Job 失败按 Kubernetes reason 映射稳定错误，不输出容器日志；
- `expiresAt` 到期后主动进入终止流程；
- 仅创建固定 Sandbox 镜像和受控模板，不接受用户提供的镜像字段。
- 同时创建 ClusterIP Service；只有 Pod Ready、Service Endpoint 存在且 Runner 健康检查成功时才设置 `READY`。

Operator 需要行为级测试覆盖重复 reconcile、Job 漂移、status conflict、API 429/500、删除和 finalizer。

## 7. Sandbox 持久化扩展

在现有 `task_sandboxes` 上增加：

```text
provider TEXT NOT NULL
provider_resource_id TEXT
provider_resource_uid TEXT
observed_generation BIGINT
workload_uid TEXT
desired_state TEXT NOT NULL
operation_owner TEXT
operation_expires_at TIMESTAMPTZ
operation_kind TEXT
retry_count INTEGER NOT NULL
next_attempt_at TIMESTAMPTZ NOT NULL
last_dispatched_at TIMESTAMPTZ
dispatch_event_id UUID
details JSONB NOT NULL
```

约束：`attempt_id` 唯一；`(provider, provider_resource_id)` 唯一；retry 非负；
`dispatch_event_id` 唯一；`desired_state` 只允许 `ACTIVE/TERMINATED`。增加
`(status,next_attempt_at)` 和 `operation_expires_at` 部分索引。`details` 只保存
RuntimeClass、UID 和稳定状态原因，不保存 Pod 环境、Secret、Token 或容器日志。

只有相同 Attempt、相同 CR UID、非回退的 Provider phase 和更高或相同
`observedGeneration` 才能更新数据库。数据库 `RUNNING` 不因 CR 报告 `READY`
回退。Ready 后追加 `TaskAssigned` 与设置唯一 `dispatch_event_id` 必须在同一事务。

## 8. AgentScope Worker 接线

### 8.1 统一 Runtime Router

新增：

```java
public final class WorkerRuntimeRouter implements AgentRuntime {
    private final AgentRuntime qwenPaw;
    private final AgentRuntime agentScope;
    private final AgentScopeRolloutPolicy rollout;
    private final ConcurrentMap<UUID, AgentRuntime> owners;
}
```

Router 在 `submit` 时基于 Task scope、Team ID、Agent ID 和稳定 Task ID 选择 Runtime，并记录 `taskId -> delegate`。`cancel`、`stop` 和结果回调必须路由到原 Delegate，运行中修改 rollout 不迁移已有 Attempt。

缺少 tenant/team/agent 稳定标识时策略 fail-closed 到 QwenPaw。AgentScope 未配置 Harness/Model 时：

- 显式 `AGENTTEAMS_RUNTIME=AGENTSCOPE`：Worker 启动失败；
- 灰度选择到 AgentScope：任务在接受前返回 `RUNTIME_UNAVAILABLE`，Control Plane 可重新排队至 QwenPaw Worker；
- 默认关闭：不初始化 AgentScope 网络连接。

### 8.2 Worker 组合根

把 `QwenPawWorker` 中字段：

```java
private final QwenPawRuntime runtime;
```

改为项目公共 `AgentRuntime`。引入 `WorkerRuntimeFactory` 创建 QwenPaw、AgentScope 和 Router。`rejectUnimplementedRuntime` 删除，但保留未知 Runtime 的启动失败。

Operator `WorkerResourceFactory` 必须：

- 将 `spec.runtime` 写入 `AGENTTEAMS_RUNTIME`，覆盖用户 env 中冲突值；
- 通过 `envFrom` 引用 Helm 生成的 Agent Runtime ConfigMap；
- 在 Hello capabilities 中声明 `runtime-qwenpaw-v1`、`runtime-agentscope-v1`，以及需要时的 `sandbox-assignment-v1`、`sandbox-remote-workspace-v1`；
- 镜像不具备 AgentScope 能力时不能被灰度策略选中。

### 8.3 AgentScope Factory

新增生产 `ConfiguredAgentScopeHarnessFactory`：

- 模型通过现有 Provider/Secret 配置构建；
- Workspace 只接受 `AgentScopeWorkspaceFactory` 返回的受控路径；
- Tool 先经过 AgentTeams 权限和出站策略，再经过 AgentScope Permission/Middleware；
- Session 关闭必须释放模型流、工具调用、Workspace Handle 和 Sandbox 续期；
- 事件仍通过 `AgentScopeEventTranslator` 进入现有 ExecutionEvent，不建立第二套业务事件。

### 8.4 Worker Sandbox 只读边界

Worker 不应持有完整的 Provision/Renew/Terminate Port。新增：

```java
public interface SandboxStateProbePort {
    SandboxExecutionState inspect(UUID sandboxId, UUID taskId, UUID attemptId);
}
```

生产实现通过 Gateway RPC 或受鉴权的 Control Plane 内部 API 读取数据库投影，
不使用 Kubernetes Client。`AgentScopeWorkspaceFactory` 依赖该只读 Port；Worker
只有 endpoint mTLS 身份，不能创建、修改或删除 TaskSandbox CR。

## 9. 正式 Manager 服务

### 9.1 部署边界

Manager 是独立 Spring Boot Deployment，不嵌入 Control Plane 进程。它：

- 通过 OIDC 或内部 Workload Token 接收请求；
- 通过 Control Plane HTTP API 调用类型化工具；
- 通过 Gateway gRPC 使用项目配额；
- 使用独立 Manager 数据库连接写模型审计或调用 Control Plane 内部审计 API；
- 不持有 Kubernetes 权限，不直接访问业务表执行写操作。

### 9.2 API

```text
POST /api/v1/manager/sessions
POST /api/v1/manager/sessions/{sessionId}/messages
GET  /api/v1/manager/sessions/{sessionId}
GET  /api/v1/manager/sessions/{sessionId}/events
POST /api/v1/manager/sessions/{sessionId}/cancel
```

写请求要求 `Idempotency-Key`。Message 请求包含用户文本、可选 Team/Task 上下文和 expected session version，不接受任意 Tool URL 或 Kubernetes 指令。

### 9.3 持久化

新增：

- `manager_sessions`：scope、actor、状态、版本、创建/更新时间；
- `manager_messages`：session、角色、内容 hash、加密或脱敏摘要、状态；
- `manager_tool_calls`：工具、输入 hash、幂等键、审批状态、结果资源 ID；
- `manager_events`：用于 SSE 重放的递增序号和脱敏事件。

默认不持久化完整 Prompt/Response；如果项目策略允许保存内容，必须使用独立加密字段、保留期限和审计权限。

### 9.4 工具与审批

首期正式工具只包括现有 `create_task` 和只读查询。新增工具必须注册：JSON Schema、权限、幂等策略、超时、审批要求和审计事件。模型输出永远不能直接触发 HTTP、SQL、Kubernetes 或 Shell。

错误分类：

- `MODEL_UNAVAILABLE`、`MODEL_RATE_LIMITED`、`MODEL_OUTPUT_INVALID`；
- `QUOTA_REJECTED`、`AUTHORIZATION_REJECTED`；
- `TOOL_INPUT_INVALID`、`TOOL_CONFLICT`、`TOOL_TEMPORARY_FAILURE`；
- `APPROVAL_REQUIRED`、`SESSION_VERSION_CONFLICT`、`SESSION_CANCELLED`。

### 9.5 Helm

新增 Manager image、Deployment、Service、ServiceMonitor、NetworkPolicy 和 PDB。模型 Key 只从 `existingSecret` 挂载；values 只保存 Secret 名称和 key 名。生产默认 `manager.enabled=false`，配置不完整时 fail-fast。

## 10. 核心实现文件

预计新增：

- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/KubernetesSandboxRuntime.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxLifecycleScheduler.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxRuntimeProperties.java`
- `agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeFactory.java`
- `agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeRouter.java`
- `agent-worker/src/main/java/io/agentteams/worker/agentscope/ConfiguredAgentScopeHarnessFactory.java`
- `manager/src/main/java/io/agentteams/manager/ManagerApplication.java`
- `manager/src/main/java/io/agentteams/manager/api/ManagerSessionController.java`
- `manager/src/main/java/io/agentteams/manager/session/JdbcManagerSessionRepository.java`
- `deploy/docker/manager.Dockerfile`
- `deploy/helm/agentteams-java/templates/manager.yaml`

预计修改：

- `control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxLifecycleService.java`
- `agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxReconciler.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxResourceFactory.java`
- `runtime/src/main/java/io/agentteams/runtime/GatewayRuntimeAdapter.java`
- `operator/src/main/java/io/agentteams/operator/WorkerResourceFactory.java`
- `deploy/helm/agentteams-java/values.yaml`
- `deploy/helm/agentteams-java/templates/rbac.yaml`
- `.github/workflows/ci.yml`

Flyway 迁移编号在实施分支中按当时最大版本顺序分配，避免与并行功能冲突。

## 11. 验收标准

### L1：单元和契约

- Kubernetes Provider 重复 provision 返回同一 CR，冲突请求被拒绝；
- Scheduler 多副本只有 Leader 执行状态变更；
- Worker Router 的稳定分桶、显式 allowlist、回滚和运行中 owner 路由通过；
- `AGENTTEAMS_RUNTIME=AGENTSCOPE` 不再被固定拒绝，缺配置时返回明确启动错误；
- Manager 无效 JSON、重复 Tool、权限拒绝和模型超时不产生重复 Task。

### L2：持久化

- Sandbox 状态在 Control Plane 重启后恢复，旧版本更新被拒绝；
- Manager Session、SSE 游标和 Tool 幂等结果在进程重启后可恢复；
- 并发 provision、终止、消息发送和 Tool 调用不产生重复资源。

### L3：Helm 和安全

- Control Plane 只有 TaskSandbox CR 权限，没有 Pod/Job/Secret 权限；
- Operator 只有命名空间内 TaskSandbox 和受控 Job 权限；
- Sandbox Job 禁止 privileged、hostPath、hostNetwork、hostPID 和 ServiceAccount Token；
- Manager 不拥有 Kubernetes RBAC，Secret 只通过挂载进入；
- Helm lint/template、RBAC 和 NetworkPolicy 校验通过。

### L4：Kind

- `NONE` 任务保持当前 QwenPaw 行为；
- `ISOLATED` 任务经历 REQUESTED → PROVISIONING → READY → DESTROYED，Worker 只在 READY 后接收；
- 删除 Sandbox Job 后 Operator 恢复或标记 LOST，Attempt 按策略恢复；
- AgentScope Fake Model 完成 Worker → Gateway → Control Plane 全链路，重复和旧 Attempt 事件被拒绝；
- 灰度关闭后新任务回到 QwenPaw，运行中任务不迁移；
- Manager 正式 API 使用确定性模型创建一个 Task，重复请求仍只有一个 Task。

### L5：Linux/KVM

- ISOLATED 使用配置的 gVisor RuntimeClass；
- HARDENED 使用 Kata RuntimeClass；
- 两种 Profile 都满足文件、网络、ServiceAccount 和宿主机隔离；
- 节点故障、Pod 删除和 RuntimeClass 缺失得到稳定状态与恢复行为。

### L6：预发布

- 使用外部 Secret 和真实模型完成 AgentScope 与 Manager 冒烟，不输出凭据；
- 执行 Secret/endpoint 轮换，旧连接在新连接验证失败时继续服务，成功后原子切换；
- 连续运行 2 小时并穿插 Worker、Gateway、Control Plane、Operator 和 Manager 滚动重启，无任务重复、Sandbox 泄漏或配额泄漏。
