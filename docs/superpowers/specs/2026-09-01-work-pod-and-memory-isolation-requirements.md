# Worker Pod 与记忆隔离需求基线

**状态：** 已确认，作为项目约束

**生效范围：** Organization、Tenant、Project、Team、Agent、Worker、Task、Attempt、Sandbox、记忆和调度相关的产品、API、Control Plane、Operator 与 Runtime 实现。

**关联设计：**

- [企业级 Agent 执行平面架构设计](2026-08-31-enterprise-execution-plane-design.md)
- [Task Sandbox Runtime 分层设计](2026-08-25-task-sandbox-runtime-design.md)
- [Team CRD 与多 Agent 调度设计](2026-08-21-team-crd-scheduling-design.md)
- [外部 SDK 接入与内部身份授权设计](2026-09-01-external-sdk-identity-provisioning-design.md)

## 1. 术语和边界

### 1.1 长驻 Worker Pod

Worker Pod 是运行 Agent Runtime 的长驻工作负载。它通过 `AGENTTEAMS_AGENT_ID` 连接 Agent Gateway，并对应一个逻辑 Agent 身份。Worker Pod 不承担 Organization、用户或持久化记忆的存储职责，也不得执行未经隔离的不可信用户代码。

### 1.2 Task Sandbox Pod

Task Sandbox Pod 是按 Task Attempt 创建的临时隔离执行环境，用于用户代码、脚本、浏览器、插件和高风险工具。它与长驻 Worker Pod 是两类不同资源，生命周期、权限和回收策略不得混用。

### 1.3 逻辑资源层级

```text
Organization
  └── Tenant
       └── Project
            └── Team
                 └── Agent / Worker
                      └── Task / Attempt / Sandbox
```

用户 `subjectId` 是身份、授权和记忆隔离维度，不是 Worker Pod 的唯一调度维度。

## 2. Worker Pod 创建和激活约束

### 2.1 禁止在企业生命周期中隐式创建 Worker Pod

以下操作不得自动创建长驻 Worker Pod：

- Organization 注册；
- Organization 激活；
- 用户初始化或外部身份绑定；
- Tenant、Project 或 Team 创建；
- AgentSpec 创建或发布。

Organization 激活只负责使企业的控制面资源、身份、权限、策略和配额进入可用状态。Worker 资源必须通过显式的 Worker/Agent 实例化或显式部署操作创建。

### 2.2 标准 Worker 激活流程

```text
Organization 激活
  → 创建或发布 AgentSpec / Worker Template
  → 显式实例化 Worker / Agent
  → 创建或更新 Worker CR
  → Operator 创建 Worker Deployment 和 Service
  → Worker 连接 Gateway 并完成 Hello
  → Agent 状态变为 READY
  → Scheduler 才允许分配任务
```

Worker Pod 不采用“首次任务自动懒启动”作为默认行为。任务只能分配给已经达到 `READY` 的 Worker；Pod 冷启动、镜像拉取或 Worker 连接失败不能被隐藏在任务请求中。

### 2.3 当前实现的强制边界

- `POST /api/v1/agents` 只创建逻辑 Agent，初始状态为 `PROVISIONING`，不得被解释为已创建 Pod。
- AgentSpec `publish` 只发布配置；配置 Deployment 只向已有 Agent 下发配置，不负责隐式创建 Worker Pod。
- Worker Template 实例化必须经过明确的 Worker Provisioning 流程，最终产出 Worker CR；不能只创建数据库 Agent 记录后宣称 Worker 已激活。
- Operator 是创建 Worker Deployment、Service 及其 Pod 的唯一组件；Control Plane 不直接创建任意 Deployment 或 Pod。
- Worker CR 默认 `replicas=1`。如果配置多个副本，必须明确处理同一 Agent 身份的多连接、任务租约和结果去重问题。

## 3. Worker 归属、调度和并发

### 3.1 归属约束

默认 Worker 归属范围为 `Tenant + Project + Team`：

- 一个 Team 可以拥有多个 Worker/Agent；
- 一个 Project 可以包含多个 Team；
- 不同用户在同一 Project 内可以共享同一组 Worker；
- Worker 不得跨 Organization、Tenant 或 Project 共享；
- 用户身份不直接决定固定 Worker，也不得把用户与某个 Pod 永久绑定。

### 3.2 任务调度

每次执行以 `Task Attempt + Assignment + Agent Lease` 为最小调度单位：

- 带 `teamId` 的任务只能分配给该 Team 的 ACTIVE 成员；
- 候选 Agent 必须处于 `READY`，并满足 Team policy、任务所需能力、允许的 Runtime 和审批要求；
- Team 的 `maxConcurrentTasks` 约束 Team 总并发；
- 一个 Worker Runtime 默认 `maxConcurrency=1`；需要扩容时优先增加 Worker/Agent，而不是依赖共享进程承载更多状态；
- 同一 Worker 可以先后处理不同用户的任务；只有 Runtime 明确支持并发且完成租约、任务状态和上下文隔离时，才允许并行处理多个任务。

### 3.3 无 Team 任务

无 `teamId` 的任务必须在当前 `Tenant + Project` 范围内匹配 READY Worker。禁止使用跨 Project、跨 Tenant 或全局 READY Worker 兜底。

无 Team 任务的调度查询必须显式携带并校验 Organization、Tenant 和 Project scope；仅依赖 Agent 的 `READY` 状态、能力匹配或数据库资源 ID 不足以证明租户隔离。

## 4. Sandbox 策略

Sandbox profile 按安全等级单调选择：

```text
NONE < ISOLATED < HARDENED < DEDICATED
```

| Profile | 默认适用场景 | 执行边界 |
| --- | --- | --- |
| `NONE` | 普通对话、可信编排 | 直接使用长驻 Worker，不执行不可信代码 |
| `ISOLATED` | 用户代码、脚本、浏览器、普通插件 | 独立 Task Sandbox，默认使用 gVisor 或等效隔离运行时 |
| `HARDENED` | 高风险代码、跨租户数据、强合规任务 | Kata、MicroVM 或等效硬隔离运行时 |
| `DEDICATED` | 专属企业和特殊合规 | 客户专属节点池或专属集群 |

约束如下：

- 默认 profile 为 `NONE`；不能因为普通企业激活就创建 Sandbox Pod。
- Sandbox 按 Attempt 一对一绑定；Task 重试必须创建新的 Attempt 和新的 Sandbox。
- 只有 Sandbox 为 `READY` 时，Worker 才能接受需要隔离的任务。
- Task 进入 `SUCCEEDED`、`FAILED` 或 `CANCELLED` 后，Sandbox 必须进入回收流程。
- Sandbox 只接收当前任务所需的最小脱敏上下文，不接收完整用户历史、其他用户行为、原始审计日志或 Secret。
- Sandbox 默认关闭 Kubernetes Token、特权模式、Host Network、Host PID、Host Path 和宿主机设备。

## 5. 记忆隔离约束

### 5.1 记忆作用域

记忆必须明确标记以下作用域之一：

- `USER_PRIVATE`：用户私人记忆；
- `ORGANIZATION_SHARED`：企业共享记忆；
- `PROJECT_SHARED`：项目共享记忆；
- `TEAM_SHARED`：Team 共享记忆；
- `TASK`：任务或 Attempt 记忆。

每条记忆必须携带 `organizationId` 和 `tenantId`，并按作用域补充 `projectId`、`teamId` 或 `subjectId`。记忆内容通过受控 `contentRef` 保存，数据库和事件只保存必要的元数据与脱敏摘要。

### 5.2 默认访问规则

- `USER_PRIVATE` 只能由相同 `subjectId` 的用户读取；
- `ORGANIZATION_SHARED` 仅对同一 Organization/Tenant 且符合企业策略的执行上下文可读；
- `PROJECT_SHARED` 必须匹配当前 Project；
- `TEAM_SHARED` 必须匹配当前 Team；
- `TASK` 必须匹配当前 Task 所属 Project 或 Team；
- 不同 Organization、Tenant 或 Project 的记忆不得因为复用 Worker Pod 而进入同一 Prompt；
- 记忆治理权限不等于私人记忆内容读取权限。

### 5.3 Context Assembly

每次 Task Attempt 执行前必须通过 Context Assembly 生成最小上下文。Context Assembly 必须同时校验：

1. `organizationId`、`tenantId`、`projectId`、`teamId` 和 `subjectId`；
2. 记忆 scope 与当前执行上下文的匹配关系；
3. `ACTIVE` governance status 和未过期状态；
4. `CONFIRMED` consent；
5. `RESTRICTED` 敏感等级的显式治理投影；
6. 企业策略、Token 预算和最小化原则。

Context Assembly 只能向 Runtime 提供摘要和必要来源标识，不得将全量记忆表、完整历史对话、审计流水或凭据注入 Worker Pod 或 Sandbox Pod。

### 5.4 用户和管理员治理

- 用户私人记忆默认不用于模型训练；
- 敏感或长期记忆必须先进入候选状态，经过用户确认后才能用于模型上下文；
- 企业管理员默认可以查看记忆数量、容量、来源、更新时间和治理状态；
- 查看私人记忆内容必须具备指定原因、审批、最小范围、限时授权和完整审计；
- 管理员可以执行冻结、删除、导出和保留策略，但不得绕过上述内容访问约束。

## 6. 运行时状态隔离

Worker Pod 可以承载多个用户的任务，但不得使用进程级全局变量保存用户私有 Prompt、对话历史、记忆或 Secret。所有运行时状态必须至少由 `taskId + attemptId + leaseId + ExecutionContext` 标识。

Runtime、Gateway 和 Control Plane 必须在以下边界执行校验：

- Assignment 校验 Agent、Task、Attempt 和 Lease 的对应关系；
- Execution Event 校验 Agent 身份、Attempt、Lease、期望版本和 scope；
- Sandbox workspace 校验 Task、Attempt 和 scope 的所有权；
- MCP 调用通过 Gateway 或 Connector 执行凭据、工具、域名和脱敏策略；
- Worker 消息不得携带 Provider 凭据、Secret 明文、宿主机路径或完整用户历史。

## 7. 强制验收标准

### 7.1 Worker 生命周期

- 企业注册和激活不会创建 Worker Pod；
- 用户初始化不会创建 Worker Pod；
- 显式 Worker 实例化或部署能够产生 Worker CR，并由 Operator 创建 Deployment/Pod；
- Worker 未达到 `READY` 时，任务保持 `QUEUED`，并返回可识别的 Worker 不可用原因；
- Worker 删除、断连或 drain 后，新的任务不会继续分配到该 Worker；
- Worker 重启或连接切换不会产生重复执行或跨用户上下文泄漏。

### 7.2 调度隔离

- 同一 Project 的不同用户任务可以复用 Worker，但每个 Attempt 都拥有独立的 Lease 和执行上下文；
- 不同 Project、Tenant 或 Organization 的任务不能被同一 scope 外 Worker 接收；
- Team 并发上限不会被多个 Control Plane 副本或多个 Worker 突破；
- 无 `teamId` 任务不会通过全局 READY Worker 查询跨 scope 调度。

### 7.3 Sandbox 隔离

- `NONE` 任务不创建 Sandbox；
- `ISOLATED`、`HARDENED` 和 `DEDICATED` 按策略创建对应 Sandbox；
- 同一 Attempt 不会创建多个活动 Sandbox；
- Task 重试不会接受旧 Attempt 或旧 Sandbox 的结果；
- Sandbox 回收失败可以重试，但不回滚已经持久化的业务结果。

### 7.4 记忆隔离

- 用户 A 的 `USER_PRIVATE` 记忆不能出现在用户 B 的 Context Assembly 结果中；
- Project/Team 共享记忆必须满足对应成员和 scope 权限；
- 过期、冻结、删除、撤回同意或受限的记忆不能进入普通模型上下文；
- Context Assembly 使用 Token 预算并只输出最小摘要；
- Worker 日志、事件和 Sandbox 日志不包含 Prompt、Secret、完整记忆和原始 Chain of Thought；
- 管理员治理私人记忆的每次操作都有原因、操作者、幂等键和审计记录。

## 8. 当前实现收口要求

以下内容属于本需求基线的实现完成条件：

1. 为无 `teamId` 的兼容调度路径补齐 `Organization + Tenant + Project` scope 过滤和测试；
2. 将 Context Assembly 接入真实 Task Attempt 执行链路，而不是只保留独立服务和单元测试；
3. 将 Worker 实例化结果与 Worker CR 创建建立可恢复、幂等的 Provisioning 链路；
4. 为 Worker Runtime 的并发数、Agent Lease 和 Runtime 任务状态增加一致的 admission 约束；
5. 在 Worker 复用场景增加跨用户上下文污染、跨 scope 调度和 Sandbox workspace 所有权测试。

## 9. 管理端优先与 Java SDK 冻结约束

### 9.1 Java SDK 冻结窗口

从本约束生效起，后续功能开发暂不更新 `sdk/java`。冻结范围包括：

- Java SDK 的生产代码、公开类型、方法、路径和行为；
- Java SDK 的 README、示例、生成代码和版本号；
- 为了追赶管理端功能而新增 Java SDK 公共接口；
- 将内部 Control Plane API、管理端 API 或实验性能力提前暴露到 Java SDK。

冻结期间可以修复阻塞构建或安全问题，但必须单独说明原因，不能借机扩展 SDK 能力。OpenAPI、Control Plane API、Console 和领域模型可以正常演进，但新增接口不得反向要求 Java SDK 立即同步。

### 9.2 管理端能力覆盖原则

能力集合遵循单向包含关系：

```text
SDK 已有能力 ⊆ 管理端能力
```

也就是说，SDK 里已经存在的业务能力，管理端必须逐项具备；管理端可以提供更多 SDK 尚未暴露的内部管理、治理、运营和运维能力，不要求这些管理端能力同步进入 Java SDK。SDK 与管理端的对应关系按能力语义对齐，而不是要求 URL、Java 类名或页面布局完全相同。每项已经存在于公共 SDK 的业务能力，必须同时具备：

1. 一个受 Control Plane 授权保护的管理 API；
2. 一个管理端页面、详情区或操作入口；
3. 与 SDK 相同的业务结果、状态机、错误分类、幂等和版本冲突语义；
4. 与 SDK 相同的 Organization/Tenant/Project/Team scope 和用户权限约束；
5. 能在 Console 验证中完成真实的创建、查询、变更、失败和恢复路径。

管理端不得通过“仅 SDK 可用”的隐藏接口绕过页面验收，也不得把一个页面按钮接到不具备同等授权和审计语义的临时后门。管理端新增的 SDK 未覆盖能力不需要反向扩展 SDK，但仍必须遵守统一 API、权限、审计、幂等和 scope 约束。

### 9.3 管理端必须覆盖的能力域

以下能力域纳入本阶段的管理端交付范围，均须在能力矩阵中登记 `SDK/管理 API/Console/权限/隔离/验收` 六个维度：

- AgentTeams 内部用户管理；
- Organization、Tenant、Project 和 Workspace 管理；
- 外部用户、Integration、Credential 和身份映射管理；
- 权限、角色、成员生命周期和授权策略；
- Team、Team 成员、策略、版本和部署；
- Worker、Agent、AgentSpec、Template、实例化、发布、Drain、Rollout、Rollback 和状态诊断；
- Model Provider、价格目录和模型连接状态；
- MCP Server、Connection、Route、工具发现、凭据引用和可用性；
- Skill、版本、制品、发布、绑定、下载校验和运行时状态；
- Conversation、消息、事件流、取消、重连和 Task 回链；
- Task、Attempt、Assignment、Lease、审批、暂停、重试、取消和执行记录；
- Artifact、上传、完成、校验、下载、可见性和保留策略；
- 费用监控、Token/Usage、价格、预算、预测、告警、审计和导出；
- Memory Policy、记忆治理、Context Assembly 可观测性和跨用户/跨 scope 隔离验证；
- Sandbox profile、生命周期、回收、失败原因和 workspace 所有权。

“具备管理端提供”不表示浏览器直接访问 Kubernetes、PostgreSQL、NATS、MinIO、Secret Manager 或模型供应商。Console 只调用管理 API；管理 API 通过既有 Port/Adapter 和统一授权上下文访问后端资源。

### 9.4 管理端优先的交付门禁

在最终页面功能验证通过前，不得更新 Java SDK，不得以 SDK 已更新作为管理端完成的替代证明。页面验证必须至少覆盖：

- 登录、Organization/Tenant/Project 上下文切换和权限拒绝；
- 内部用户、外部用户和角色授权的创建、变更、禁用与审计；
- Team、Template、AgentSpec、Worker 的显式实例化及 Worker `READY` 前后状态；
- MCP、Skill 和 Model 的配置引用、发布、不可用和 fail-closed 路径；
- Conversation、Task、Attempt、Sandbox、Artifact 的完整回链；
- 费用、预算、告警和审计数据的 scope 过滤；
- 用户私人记忆、Project/Team 共享记忆和跨 scope 访问拒绝；
- 无生产 Secret Manager 凭据时的配置状态、依赖不可用提示和安全失败。

### 9.5 SDK 解冻条件

只有同时满足以下条件，才允许另行制定 Java SDK 更新计划：

1. 管理端能力矩阵中所有纳入范围的能力均已具备 API、页面、权限、审计和验收证据；
2. 记忆隔离、Worker Pod 生命周期、Task/Sandbox 隔离和跨 scope 负向测试通过；
3. Secret Manager 未提供生产凭据时，L1-L4 测试仍可复现，且生产 profile 对缺少凭据 fail-closed；
4. Console 页面完成最终人工/自动化功能验证，并记录版本、环境、结果和遗留限制；
5. 依据最终 OpenAPI 生成或手工维护 Java SDK，并完成 Java/TypeScript/Console 三方能力对账；
6. SDK 更新另开独立变更，包含兼容性、错误映射、幂等、重试、鉴权和敏感信息回归测试。

在解冻前，Java SDK 的差距必须记录为“待 SDK 同步”，不能通过修改 SDK README 或示例制造能力已交付的假象。

## 10. 凭据材料不可用时的实现约束

当前没有可用的生产 Secret Manager Credential 材料，不阻塞管理端和控制面功能开发，但也不能用假凭据冒充生产打通：

- 内存 Organization/Integration/Credential 服务只能用于单元测试或明确标记的开发夹具，不得作为生产多副本权威状态；
- 所有 Model、MCP、Webhook、Worker 和外部 Integration 只持久化 `credentialRef`、版本、状态和脱敏摘要，不持久化 Secret 明文；
- L1-L4 使用 `ValidationOnly`、可控 Fake/Stub 或本地 Kubernetes Secret/ExternalSecret 夹具验证契约、状态机、fail-closed 和脱敏边界；
- 生产 profile 必须显式选择 Secret Resolver 后端；凭据缺失、引用非法、ExternalSecret 未 Ready、目标 key 不存在或读取超时都必须返回可识别的不可用状态；
- 在没有生产 Credential 时不得执行真实供应商连通性验收，不得把 Mock/Fake 结果标记为生产可用；
- 凭据接入只通过 `SecretResolver`、`McpCredentialProvider` 等 Port/Adapter 完成，拿到材料后只需补充生产适配器和 L5 验收；L6 按 10.1 的最终阶段约束单独处理，不得改变领域模型或在 Console 增加 Secret 明文输入；
- 日志、事件、错误、指标标签、配置快照、Artifact 和 SDK 响应均不得包含 Secret、JWT、完整 Prompt/Response 或私有记忆内容。

### 10.1 L6 最终阶段约束

L6 真实外部依赖、生产凭据、长期运行、故障恢复和发布演练验收统一放到项目最终阶段处理。L6 只作为最终收口门禁保留，当前不纳入执行计划、当前验收队列或自动续作任务。

除非用户明确发出“启动 L6 验收”指令，否则不得创建 L6 子计划、排入 L6 任务、申请或使用 L6 凭据，也不得以 L6 缺失作为当前 L1-L5 功能开发的阻塞条件。用户明确启动后，才根据当时的环境、凭据和验收范围创建独立 L6 计划，并单独记录结果。
