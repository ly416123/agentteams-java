# Task Sandbox Runtime 分层设计

## 1. 目标

为 AgentTeams 增加任务级 Sandbox（隔离执行环境）抽象，使 Team、Worker、Task 和 Kubernetes Node 保持职责分离：

- Team 只负责成员、权限、配额和调度策略；
- Worker 负责长驻 Agent 连接和任务接收；
- Task Attempt 负责一次具体执行；
- Sandbox 负责为需要隔离的 Attempt 提供临时执行环境；
- Kubernetes Node 只提供共享计算容量，不与 Team、Worker 或 Task 建立一对一绑定。

本阶段只建立可替换的生命周期、持久化和调度边界，默认任务继续使用现有 Worker 执行路径，不改变现有 QwenPaw、Team 调度和 Lease 协议。

## 2. 范围与非目标

### 2.1 本阶段范围

- 新增 \`SandboxRuntimePort\` 应用端口；
- 新增 \`TaskSandbox\` 绑定模型和 \`task_sandboxes\` 持久化表；
- 定义 Sandbox 生命周期、幂等键、租约续期、回收和故障恢复规则；
- 新增 \`SandboxProfile\`：\`NONE\`、\`ISOLATED\`、\`HARDENED\`；
- 新增 Fake Provider 和完整单元/契约测试；
- 为 Kubernetes Provider 预留 \`RuntimeClass\`、资源配额和网络策略字段；
- 通过 Outbox 或异步任务触发外部 Sandbox Provider，禁止在数据库事务内进行网络调用。

### 2.2 非目标

- 本阶段不默认启用 Sandbox；
- 本阶段不要求在现有 Kind 集群中安装 gVisor、Kata 或 CubeSandbox；
- 本阶段不把 TaskPhase 扩展为 Sandbox 状态；
- 本阶段不允许 Control Plane 访问 Docker Socket；
- 本阶段不实现 CubeSandbox 专用 API、AgentHub、快照迁移或跨节点恢复；
- 本阶段不把 Team 或 Worker 绑定到 Kubernetes Node。

### 2.3 本地验收边界（2026-08-28）

本机通过 Colima 提供 Docker daemon（Docker Server 29.5.2），已在现有 Kind 集群执行默认
部署路径验收：安装脚本、Task API 生命周期与拒绝路径、Dashboard 告警投递、QwenPaw
Worker 链路均通过。该验收只证明默认 `NONE` 路径不回归，不代表 gVisor/Kata 已在本机可用；
真实 `RuntimeClass` 仍需具备对应运行时的独立 Linux/KVM 集群验证。

## 3. 方案决策

### 3.1 采用 Provider 端口，不绑定具体产品

Control Plane 只依赖以下端口：

\`\`\`java
public interface SandboxRuntimePort {
    SandboxHandle provision(SandboxRequest request);

    SandboxStatus inspect(String providerSandboxId);

    void renew(String providerSandboxId, Instant expiresAt);

    void terminate(String providerSandboxId, TerminationReason reason);
}
\`\`\`

Provider 实现由部署环境选择：

| Provider | 用途 | 本阶段状态 |
|---|---|---|
| \`FakeSandboxRuntime\` | 单元测试、Kind 契约测试 | 必须实现 |
| \`KubernetesSandboxRuntime\` | 创建带 \`RuntimeClass\` 的 Job/Pod | 接口和配置预留 |
| \`CubeSandboxRuntime\` | CubeSandbox HTTP/E2B 兼容 API | 后续独立适配 |

首个真实运行时采用 Kubernetes \`RuntimeClass\` 适配方式：\`ISOLATED\` 映射到 gVisor，\`HARDENED\` 映射到 Kata。RuntimeClass 名称由部署配置提供，不能硬编码为某个集群的名称。

### 3.2 Profile 语义

\`\`\`text
NONE      可信任务，直接使用 Worker
ISOLATED  用户代码、普通插件、浏览器和工具任务
HARDENED  高风险代码、跨租户执行和强合规任务
\`\`\`

默认值为 \`NONE\`。只有任务策略、Team policy 或受信任的系统配置明确要求时，才创建 Sandbox。

### 3.3 Sandbox 不进入 TaskPhase

Task 继续使用现有状态机：

\`\`\`text
DRAFT → QUEUED → ASSIGNED → ACCEPTED → RUNNING → SUCCEEDED/FAILED
\`\`\`

Sandbox 使用独立状态：

\`\`\`text
REQUESTED → PROVISIONING → READY → RUNNING → STOPPING → DESTROYED
                         ├→ FAILED
                         ├→ EXPIRED
                         └→ LOST
\`\`\`

Task 是业务事实，Sandbox 是基础设施投影。Sandbox 创建失败可以触发 Attempt 重新排队，不应自动把业务 Task 标记为不可恢复的失败。

## 4. 组件设计

### 4.1 Control Plane

Control Plane 在创建 Assignment 和 Agent Lease 的同一数据库事务中创建 \`TaskSandbox\` 请求记录，但不调用 Provider：

1. 解析任务的 Sandbox profile；
2. 写入 \`task_sandboxes(status=REQUESTED)\`；
3. 写入 \`SandboxProvisionRequested\` Outbox 事件；
4. 提交 Assignment、Lease 和 Sandbox 请求；
5. 事务提交后由异步消费者调用 \`SandboxRuntimePort\`；
6. Provider 返回后更新 Sandbox 状态，并发布 \`SandboxReady\` 或 \`SandboxProvisionFailed\`；
7. 只有 Sandbox 为 \`READY\` 时，Worker 才能接受需要隔离的任务。

现有 \`TaskAssignmentService\` 继续负责 Team 成员选择、Attempt、Assignment 和 Lease，不把 Provider 调用塞入调度事务。

### 4.2 TaskSandbox Controller

Kubernetes Provider 使用独立的 \`TaskSandbox\` CRD 和 Controller：

- Control Plane 只写入带有 Task/Attempt 引用的 CRD；
- Controller 创建 Job 或 Pod，并设置 \`runtimeClassName\`、资源请求、TTL 和 ServiceAccount；
- Controller 将 Pod/Job 状态回写到 \`TaskSandbox.status\`；
- Controller 负责删除子资源，不授予 Control Plane 创建任意 Pod、Deployment 或 Secret 的权限；
- CRD 删除使用 finalizer，确保 Provider 资源完成回收后再删除绑定记录。

### 4.3 Worker

Worker 任务消息增加可选的 Sandbox 引用：

\`\`\`json
{
  "taskId": "...",
  "attemptId": "...",
  "sandbox": {
    "profile": "ISOLATED",
    "sandboxId": "...",
    "endpointRef": "...",
    "expiresAt": "..."
  }
}
\`\`\`

消息只传 Sandbox 标识和受控 Endpoint 引用，不传 Provider 凭据、Secret 明文或宿主机路径。没有 Sandbox 的任务保持现有消息格式兼容。

## 5. 数据模型

新增 \`task_sandboxes\` 表：

\`\`\`text
id UUID PRIMARY KEY
tenant_id TEXT NOT NULL
project_id TEXT NOT NULL
team_id UUID
task_id UUID NOT NULL REFERENCES tasks(id)
attempt_id UUID NOT NULL REFERENCES task_attempts(id)
agent_id UUID REFERENCES agents(id)
profile TEXT NOT NULL
provider TEXT NOT NULL
provider_sandbox_id TEXT
template TEXT NOT NULL
status TEXT NOT NULL
endpoint_ref TEXT
idempotency_key TEXT NOT NULL
requested_at TIMESTAMPTZ NOT NULL
ready_at TIMESTAMPTZ
expires_at TIMESTAMPTZ NOT NULL
destroyed_at TIMESTAMPTZ
failure_code TEXT
redacted_failure_message TEXT
details JSONB NOT NULL DEFAULT '{}'::jsonb
version BIGINT NOT NULL DEFAULT 0
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
\`\`\`

约束和索引：

- \`UNIQUE(attempt_id)\`：一个 Attempt 最多绑定一个 Sandbox；
- \`UNIQUE(idempotency_key)\`：Provider 重试不会重复创建；
- \`UNIQUE(provider, provider_sandbox_id)\`：避免同一个外部 Sandbox 被重复绑定；
- 按 \`status, expires_at\` 建立回收扫描索引；
- 按 \`tenant_id, project_id, team_id\` 建立配额统计索引；
- \`details\` 只保存脱敏 Provider 元数据，不保存凭据、请求正文或完整环境变量。

## 6. 生命周期与故障处理

### 6.1 创建

- 以 \`attemptId\` 生成幂等键；
- Provider 超时不直接重建，先查询同一幂等键的状态；
- \`REQUESTED\` 超过 2 分钟进入 \`FAILED\`，错误码为 \`SANDBOX_PROVISION_TIMEOUT\`；
- 可恢复的 Provider 错误最多重试 3 次，重试间隔为 1 秒、2 秒、4 秒；
- 超过重试次数后释放 Agent Lease，将 Task 重新置为 \`QUEUED\`，保留失败 Attempt 审计。

### 6.2 运行

- Worker 心跳同时续期 Agent Lease 和 Sandbox TTL；
- Sandbox TTL 必须晚于 Agent Lease，避免 Worker 认为任务有效但 Sandbox 已被回收；
- 所有状态更新携带 \`attemptId\` 和版本号，拒绝旧 Attempt 更新当前 Sandbox；
- \`LOST\` Sandbox 不允许原 Attempt 继续提交结果，必须通过新的 Attempt 重试。

### 6.3 完成和回收

- Task 进入 \`SUCCEEDED\`、\`FAILED\`、\`CANCELLED\` 后发布销毁事件；
- Sandbox 进入 \`STOPPING\` 后调用 Provider \`terminate\`；
- 销毁失败进入回收队列，最多重试 10 次；
- 业务结果已落库时，Sandbox 回收失败不回滚 Task 结果；
- 超过保留时间的 \`DESTROYED\` 记录只保留审计摘要，不保留运行时 Endpoint。

## 7. 安全边界

- Sandbox Pod 使用独立 ServiceAccount，并关闭自动挂载 Kubernetes token；
- 默认不允许 \`privileged\`、\`hostNetwork\`、\`hostPID\`、\`hostPath\` 和宿主机设备；
- 每个 Profile 使用独立 NetworkPolicy，默认拒绝出站，只允许配置的 Model、Artifact 和必要服务；
- Team 配置只能选择允许的 Profile，不能直接指定任意 \`RuntimeClass\`；
- CPU、内存、临时存储和最大生命周期由 Team/Project 配额限制；
- Secret 通过引用注入，由 Secret Provider 或受控 Endpoint 提供，Sandbox 事件和日志不得包含明文；
- Provider 访问凭据只存储在 Control Plane 的 Secret 中，不写入 Task、Outbox 或 Worker 消息。

## 8. 可观测性与审计

新增指标：

\`\`\`text
agentteams.sandbox.provision.requests
agentteams.sandbox.provision.duration
agentteams.sandbox.provision.failures
agentteams.sandbox.active
agentteams.sandbox.expired
agentteams.sandbox.terminate.failures
\`\`\`

每个 Sandbox 事件必须关联：

\`\`\`text
tenant_id, project_id, team_id, task_id, attempt_id, agent_id,
profile, provider, provider_sandbox_id, correlation_id
\`\`\`

日志只输出 ID、状态、错误类别和耗时，不输出执行代码、Secret、完整请求体或 Provider Token。

## 9. 测试和验收

### 9.1 单元测试

- Profile 默认值为 \`NONE\`，非法 Profile 被拒绝；
- 相同 Attempt 和幂等键只产生一个 Sandbox；
- 创建超时按固定退避重试，并最终释放 Lease；
- Sandbox TTL 始终晚于 Agent Lease；
- 旧 Attempt 无法更新新 Attempt 的 Sandbox；
- Task 终态触发销毁，销毁失败进入可重试队列；
- Sandbox Provider 异常不会在数据库事务内产生部分 Assignment。

### 9.2 集成测试

- Fake Provider 验证 \`REQUESTED → READY → RUNNING → DESTROYED\`；
- Provider 返回 \`FAILED\` 时 Task 重新排队且审计保留；
- Worker 只接收 \`READY\` Sandbox 的任务；
- Task 重试创建新的 Sandbox，旧 Sandbox 不再接受结果；
- 多个 Team 的 Sandbox 配额互不串用；
- Outbox 重放不会重复创建或销毁 Sandbox。

### 9.3 Kubernetes 验收

Kind CI 只执行 CRD、Helm、RBAC、NetworkPolicy、Fake Provider 和状态机验收，不依赖 gVisor/Kata 的节点运行时。

真实 RuntimeClass 验收在具备 Linux/KVM 和对应运行时的独立环境中执行：

- \`ISOLATED\` Pod 使用配置的 gVisor RuntimeClass；
- \`HARDENED\` Pod 使用配置的 Kata RuntimeClass；
- 未配置 RuntimeClass 的环境不会错误创建隔离任务；
- Pod 被删除或节点不可用时，Controller 能将 Sandbox 标记为 \`LOST\` 并触发 Attempt 恢复；
- 不同 Profile 的 Pod 不违反资源和 NetworkPolicy 限制。

## 10. 分阶段执行

### 阶段 1：领域契约和持久化

- 新增 Sandbox domain/application 类型和 \`SandboxRuntimePort\`；
- 新增 \`task_sandboxes\` Flyway migration、Repository 和事务接口；
- 增加 Fake Provider；
- 默认 Profile 为 \`NONE\`，不改变现有任务路径。

### 阶段 2：任务生命周期接入

- 在 Assignment 事务中创建 Sandbox 请求；
- 增加 Outbox 事件和异步 Provision/Terminate Worker；
- 接入 Lease 续期、Task 终态回收和 Attempt fencing；
- 将 Sandbox 引用加入任务消息，并保持空值兼容。

### 阶段 3：Kubernetes Provider

- 新增 \`TaskSandbox\` CRD 和 Operator Controller；
- 实现 \`RuntimeClass\`、资源、TTL、ServiceAccount 和 NetworkPolicy 渲染；
- 增加 Helm 配置、RBAC 和静态校验；
- 在独立 Linux/KVM 环境验证 gVisor 与 Kata。

### 阶段 4：策略和运营能力

- Team/Project Sandbox 并发配额；
- Sandbox Pool 和预热；
- Dashboard 资源与成本维度；
- 按 Profile 的限流、审计和告警。

### 阶段 5：可选 Provider

在确实需要 Agent 长驻、快照/克隆、高密度托管或 E2B 兼容接口时，再实现 CubeSandbox Provider。CubeSandbox 不进入核心领域模型，也不改变 Kubernetes Provider 的契约。

## 11. 成功标准

- 现有无 Sandbox 任务的行为、协议、Kind CI 和 QwenPaw 验收全部保持不变；
- Sandbox 创建、续期、丢失、重试和销毁均可通过数据库状态和事件恢复；
- 同一 Attempt 不会创建多个外部 Sandbox；
- Task 重试不会接受旧 Sandbox 的结果；
- Control Plane 不需要 Docker Socket 或任意 Pod 管理权限；
- gVisor、Kata、CubeSandbox 可以在不修改 Task/Team 领域模型的前提下替换。

## 12. 当前实施状态（2026-08-25）

已完成并独立提交：应用契约与 Fake Provider、Flyway V41 与 Attempt 一对一
持久化、Assignment/Lease/Outbox 生命周期接入、TaskSandbox CRD/Operator、Helm
安全配置和默认关闭的 Kind 契约。默认 `NONE` 路径保持兼容；Provider 调用位于
数据库事务之外；Operator 只拥有命名空间内 TaskSandbox、Job、Worker/Team
子资源的权限，不接触 Docker Socket。

已验证：`mvn -q -Pintegration-tests verify` 退出码 0，Operator 测试、Sandbox
安全契约、Helm lint/template、Kind 清单校验通过。macOS 本地 Kind 只用于默认路径和
契约验收；真实 gVisor/Kata RuntimeClass 已在独立 Ubuntu/KVM 节点
`ly-macbookair7-2`（`192.168.122.55`）上通过仓库脚本验收：两个 profile 均达到
`READY`，Job/Pod 的
`runtimeClassName` 分别为 `gvisor` 与 `kata-qemu`，并记录了真实 guest/host kernel。
随后删除两个临时验收资源的生成 Job，均观察到 `status.phase=LOST`，并通过
`terminationRequested=true` 完成清理。本次证据不覆盖节点故障恢复、RuntimeClass 缺失
恢复、Attempt 自动恢复或 L6 预发布环境；这些仍需在受控环境中单独执行，不能由 Fake
Provider、Kind 静态渲染或本次 RuntimeClass 冒烟替代。
