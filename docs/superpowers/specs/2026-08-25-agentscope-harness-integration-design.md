# AgentScope Harness 集成设计

## 1. 背景与目标

当前项目已经具备 AgentTeams 控制面、Agent Gateway、QwenPaw Worker、Manager 模型治理链路，以及 Task-Sandbox 生命周期抽象。下一阶段引入 AgentScope Java Harness，目标是复用成熟的 Agent 执行能力，同时保留 AgentTeams 对平台事实和资源生命周期的控制权。

本设计采用「控制面归 AgentTeams、执行面归 AgentScope」的混合架构：AgentTeams 继续管理 Task、Attempt、Lease、Team、Quota、Audit、Outbox、NATS 和 SandboxRuntimePort；AgentScope Harness 只负责 Worker 或 Manager 内部的 Agent 会话、模型调用、工具调用、事件流和 Workspace。

AgentScope 官方 Harness 当前提供事件流、Workspace、权限、中间件、多 Agent 和模型扩展能力，并支持 JDK 17+。这些能力适合放在数据面，不适合直接替换当前控制面。[官方 README](https://github.com/agentscope-ai/agentscope-java/blob/main/README_zh.md)

## 2. 范围与非目标

### 2.1 本阶段范围

- 为 Worker 增加可切换的 AgentScope 执行后端；
- 为 Manager 预留 AgentScope 模型调用适配器，并接入现有价格与审计链路；
- 将 AgentScope 事件转换为现有 ExecutionEvent 协议；
- 将 Task-Sandbox Workspace 映射到 AgentScope Workspace；
- 增加 Feature Flag、Fake Model、端到端契约测试和回滚路径；
- 保留现有 QwenPaw 路径，并将 AgentScope 作为可灰度启用的运行时。

### 2.2 非目标

- 不用 AgentScope Service 替换 AgentTeams Control Plane；
- 不把 AgentScope Session 当作 Task、Attempt 或 Lease 的事实来源；
- 不让 Worker 直接创建 Pod、容器或访问 Docker Socket；
- 不把 Team、Worker、Work 或 Task 绑定到 Kubernetes Node；
- 不在 CI 中放置 DeepSeek、QwenPaw 或其他真实凭据；
- 不一次性迁移现有 QwenPaw 任务；
- 不引入与 AgentScope 无关的控制面重构。

## 3. 方案比较与决策

| 方案 | 优点 | 风险 | 决策 |
|---|---|---|---|
| AgentScope Service 替换控制面 | 初始 Agent 能力完整 | 任务、租约、配额、审计、多租户模型需要重建，迁移面最大 | 不采用 |
| Worker/Manager 内嵌 AgentScope Harness | 复用执行能力，保留现有平台边界，可逐步灰度 | 需要做事件、Workspace 和模型审计适配 | 采用 |
| 继续自研运行时，只参考 AgentScope 设计 | 迁移风险最低 | 仍需自行维护事件、工具、权限和 Workspace 能力 | 不作为主路径 |

## 4. 目标架构

~~~text
Client
  ↓
AgentTeams Control Plane
  ├─ Task / Attempt / Lease
  ├─ Team / Quota / Audit
  ├─ Outbox / NATS
  └─ SandboxRuntimePort
          ↓
      Agent Gateway
          ↓
       Agent Worker
          ├─ QwenPaw Runtime
          ├─ AgentScope Harness Runtime
          ├─ Workspace Adapter
          ├─ Model Adapter
          ├─ Tool / Permission Adapter
          └─ Event Translator
          ↓
      ExecutionEvent → Control Plane
~~~

AgentTeams 的任务状态仍然是：

~~~text
DRAFT → QUEUED → ASSIGNED → ACCEPTED → RUNNING → SUCCEEDED/FAILED/CANCELLED
~~~

AgentScope 的 Session、AgentState 和事件流是一次 Attempt 的执行投影，不能越权修改任务状态；所有结果必须经过现有 Gateway 和 Control Plane 的 Attempt fencing、幂等和版本校验。

## 5. 端口与适配器

### 5.1 执行运行时端口

应用层新增稳定端口，避免 Worker 直接依赖 AgentScope API：

~~~java
public interface AgentExecutionRuntimePort {
    ExecutionHandle start(ExecutionRequest request, ExecutionEventSink sink);

    void cancel(ExecutionHandle handle, CancelReason reason);

    ExecutionStatus inspect(ExecutionHandle handle);
}
~~~

实现包括：

~~~text
QwenPawHttpRuntime
AgentScopeWorkerRuntime
~~~

AgentScopeWorkerRuntime 内部负责创建 HarnessAgent、订阅 streamEvents()、处理取消和关闭 Session；上层只看到项目自己的 ExecutionRequest、ExecutionEvent 和 ExecutionHandle。

### 5.2 事件转换

AgentScope 事件转换为现有事件协议：

| AgentScope 事件类别 | AgentTeams 事件 |
|---|---|
| Agent 开始 | ExecutionStarted |
| 模型调用开始/结束 | ModelCallStarted / ModelCallCompleted |
| 工具调用开始/结束 | ToolCallStarted / ToolCallCompleted |
| 文本或内容块增量 | ExecutionOutputDelta |
| Agent 正常结束 | ExecutionCompleted |
| Agent 异常结束 | ExecutionFailed |

每个转换事件必须携带 taskId、attemptId、leaseId、eventId、correlationId 和运行时标识。重复事件按 eventId + attemptId 幂等；旧 Attempt 的事件必须被拒绝。无法映射的 AgentScope 事件只进入脱敏日志和指标，不阻塞终态事件。

## 6. Worker 生命周期

~~~text
TaskAssigned
  → 校验 Attempt / Lease
  → 读取 Sandbox 引用
  → 创建或恢复 AgentScope Workspace
  → 创建 HarnessAgent Session
  → 流式转换事件
  → 续租 Agent Lease 和 Sandbox TTL
  → 提交任务终态
  → 关闭 Session
  → 触发 Sandbox 回收
~~~

规则如下：

- SandboxProfile.NONE 继续使用现有 Worker 工作目录；
- ISOLATED 和 HARDENED 只能使用 SandboxRuntimePort 返回的受控 Workspace 引用；
- Sandbox 失效、Lease 过期或 Attempt fencing 失败后，旧 Session 不得继续提交结果；
- 重试必须创建新的 Attempt、Session 和 Sandbox 绑定；
- Worker 重启后只恢复仍持有有效 Lease 的 Attempt；
- Session 恢复失败时释放当前 Lease，并由控制面按既有策略重新排队。

AgentScope Workspace 支持按 Session、User、Agent、Global 等范围隔离，当前项目统一使用 tenantId / projectId / teamId / agentId / taskId / attemptId 构造隔离上下文，并将 Task-Sandbox 作为实际文件系统边界。[Workspace 官方文档](https://java.agentscope.io/v2/zh/docs/harness/workspace.html)

## 7. Manager 模型调用与审计

Manager 的模型调用仍经过当前模型治理链路：

~~~text
ModelCatalog
  → CredentialResolver
  → ModelPriceCatalogPort
  → AgentScope Model Adapter
  → ModelCallAudit / Cost Event
~~~

AgentScope 只负责模型协议和调用执行，不拥有模型价格、租户配额或凭证事实。模型调用审计必须继续使用现有 ModelCallDimensions、ModelPriceCatalog 和 ModelCallAuditor，避免形成第二套成本统计。

DeepSeek Key 只允许从本机环境变量或本地 Secret 读取，禁止写入 Java 源码、测试固定值、Task 事件、Workspace、日志和 GitHub Actions 配置。

## 8. 工具、权限与安全

- Tool 必须经过 AgentTeams 的租户、Team 和 Sandbox 策略校验；
- AgentScope Permission 和 Middleware 作为执行层的第二道校验，不能替代控制面授权；
- Tool 输入、模型请求和错误信息必须脱敏；
- Worker 不授予 Docker Socket、宿主机路径或 Kubernetes API 权限；
- Sandbox 的网络出口由现有 NetworkPolicy 和 Provider 控制；
- AgentScope Workspace 不存放 Provider 凭证和完整环境变量；
- 运行时配置只允许选择白名单中的 QWENPAW、AGENTSCOPE。

AgentScope 官方提供 Permission System 和 Middleware 能力，可用于执行层的工具调用控制，但项目仍需保留自己的租户和资源授权边界。[权限官方文档](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html)

## 9. 配置与灰度

默认行为保持不变：

~~~properties
agent.runtime.default=QWENPAW
agent.runtime.agentscope.enabled=false
agent.runtime.agentscope.rollout-percentage=0
~~~

灰度顺序：

~~~text
Fake Model 单元测试
  → 本地 AgentScope Worker
  → Kind Fake Model 端到端
  → 单个 Agent 灰度
  → 单个 Team 灰度
  → 默认运行时切换
~~~

任意阶段出现执行、审计、租约或 Sandbox 回归，都可以把默认运行时切回 QWENPAW，不需要回滚控制面数据结构。

## 10. 可观测性

新增或补充以下指标：

~~~text
agentteams.runtime.execution.started
agentteams.runtime.execution.completed
agentteams.runtime.execution.failed
agentteams.runtime.execution.duration
agentteams.agentscope.session.active
agentteams.agentscope.event.translation.failures
agentteams.agentscope.tool.denied
agentteams.model.call.cost
~~~

日志必须包含运行时、Task、Attempt、Session 和错误类别；禁止输出 API Key、Authorization Header、完整模型请求、工具 Secret 和 Workspace 宿主路径。

## 11. 验收标准

1. 默认配置下现有 QwenPaw 测试全部保持通过。
2. AgentScope Fake Model 能完成 Worker → Gateway → Control Plane 的完整任务闭环。
3. AgentScope 事件能转换为现有 ExecutionEvent，且重复和旧 Attempt 事件被正确拒绝。
4. Task、Attempt、Lease、Sandbox 和 AgentScope Session 的状态不会互相越权覆盖。
5. Manager 的模型调用、价格、Token 用量和审计记录完整一致。
6. Secret 轮换后新模型调用使用新凭证，旧连接不会继续发送请求。
7. Worker 重启、Lease 过期、Sandbox 失效和任务重试均有可验证的收敛结果。
8. CI 使用确定性 Fake Model，不依赖外部 DeepSeek Key。
9. AgentScope 关闭后可以回退到现有 QwenPaw 路径。

## 12. 风险和明确处理

| 风险 | 处理方式 |
|---|---|
| AgentScope API 版本变化 | 固定 Maven 版本，所有调用集中在 Adapter，禁止业务层直接调用 Harness API |
| AgentScope Session 与 Task 状态不一致 | Task/Attempt/Lease 仍是唯一事实来源，Session 只做执行投影 |
| 事件语义不完整 | 先定义映射表和未知事件降级规则，再接入真实流式调用 |
| 模型价格重复计算 | 统一复用 ModelPriceCatalogPort 和现有审计器 |
| Worker 阻塞式 API 与事件流并发冲突 | 在 Adapter 内统一线程、取消和背压策略，应用层只接收有序事件 |
| Sandbox 隔离被误认为 AgentScope Workspace 隔离 | Workspace 负责文件和 Session 范围，真正的进程、网络和内核隔离仍由 Sandbox Provider 负责 |
