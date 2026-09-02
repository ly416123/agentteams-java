# 管理端优先、记忆隔离与 Java SDK 冻结实施方案

**日期：** 2026-09-01
**状态：** 阶段 5 页面最终验证完成（管理端 19/19 真实 OIDC 浏览器回归已通过；能力矩阵中 Integration、Model、MCP 的 partial 仅表示需生产/外部凭据的 L6 连通性；Java SDK 保持冻结，L6 按约束保留至项目最终阶段，当前不纳入执行计划）
**适用范围：** Control Plane、Console、Agent Gateway、Worker、Runtime、Operator、数据库迁移、OpenAPI 和验收脚本
**明确不做：** 管理端最终页面功能验证通过前，不更新 `sdk/java`

## 1. 目标与交付原则

本方案的目标是先完成一个可运营、可授权、可验证的 AgentTeams 管理端，再根据最终页面和 API 结果补齐 Java SDK。能力集合遵循 `SDK 已有能力 ⊆ 管理端能力`：SDK 里的能力管理端必须逐项具备，管理端可以有更多 SDK 尚未暴露的管理、治理、运营和运维能力。管理端不是 SDK 的展示层，而是所有平台能力的统一业务入口；浏览器只访问 Console 和管理 API，不直接访问 Kubernetes、数据库、消息系统、对象存储、Secret Manager 或模型供应商。

本方案遵循以下原则：

1. **管理端优先。** 功能先完成 Control Plane 管理 API、Console 页面和端到端验收；Java SDK 保持冻结。
2. **SDK 能力逐项覆盖。** 每项 SDK 已有能力必须有对应管理 API、页面/操作、权限、审计、幂等、错误和 scope 语义；管理端新增能力不要求反向进入 SDK。URL、类名和布局不要求相同。
3. **业务状态唯一。** 复用现有 Organization/Tenant/Project/Team/Agent/Worker/Task/Attempt/Lease/Sandbox/Artifact/Usage/Memory 模型，不为 Console 建第二套状态。
4. **默认安全、缺凭据可开发。** 没有生产 Secret Manager Credential 时，继续完成 L1-L4；生产连通性只在凭据具备且受控环境可用时验收。L6 验收统一保留至项目最终阶段；除非用户明确说“启动 L6 验收”，否则不得创建 L6 子计划、将 L6 加入当前任务队列或执行 L6 验收。
5. **Worker 与记忆解耦。** Worker Pod 可以复用，但不持有用户私有记忆；调度、执行上下文、Context Assembly 和 Sandbox 必须按 scope 与 Attempt 隔离。
6. **L5 环境基线固定。** L5 主机、`ly` 权限边界、gVisor/Kata handler 映射和已知镜像拉取故障统一记录在 [`docs/acceptance/l5-linux-kvm-environment-baseline.md`](../../acceptance/l5-linux-kvm-environment-baseline.md)；再次验收必须先按该文档排查镜像拉取，再判断运行时 handler。

## 2. 现状盘点与完成定义

### 2.1 当前仓库已有基础

- Console 已有 OIDC 登录、Project 上下文、概览、Team、Task、Worker 和 Conversation 页面及对应 API 客户端；
- Control Plane 已有 Agent、AgentSpec、Team、Worker Template、Task、Artifact、MCP、Model Provider、价格目录、Dashboard、对话和调度相关 API；
- Java SDK 当前覆盖 Project 创建、Task 查询/取消/进度/结果/过程事件，以及外部用户 Provisioning；TypeScript SDK 覆盖 Project/Task 核心方法；
- 已有 OIDC、资源授权、外部签名身份、Project Membership、Permission/Role 基础和审计链路；
- 已有 Skill 制品加载、MCP 运行时绑定、模型价格与 Usage/预算能力的后端纵切；
- 已有 Memory Policy/Governance 服务和测试，以及 Worker Pod、Task Sandbox、Attempt 隔离约束；
- 已有 ValidationOnly、Kubernetes Secret、ExternalSecret 解析边界，但没有可用于生产联调的 Secret Manager Credential 材料。

### 2.2 当前主要缺口

| 能力域 | 当前状态 | 剩余闭环 |
| --- | --- | --- |
| 内部用户、Organization、Tenant | Organization/Tenant 创建幂等、状态管理和 Console 首个切片已完成 | 内部用户与成员完整生命周期、角色/权限授权管理、审计和跨 scope 负向验收 |
| 外部用户、Integration、Credential | Integration/Credential 管理、历史映射和外部用户生命周期管理端入口已实现；Provisioning 初始化/更新/停用/Membership 查询以及 Credential Ref 登记/轮换/撤销已具备持久化幂等、精确外部组织定位、脱敏审计、Console 操作面板和真实 OIDC 浏览器验收 | 生产 Secret provider 和外部真实连通性仍按凭据条件处理 |
| 权限、角色、授权策略 | Project 角色矩阵、角色变更、幂等、版本保护和 Console 页面已完成 | 组织/租户级角色策略、更多授权变更负向测试和最终审计证据 |
| Project/Workspace | Project 列表、创建、幂等、Project 作用域和管理页面已完成 | 成员完整生命周期、状态管理和最终跨 scope 负向验收 |
| Team | API、成员、策略、版本、部署、页面、Project UUID/外部名 scope 校验和多副本 Worker 运行时故障与回滚证据已完成并通过真实 OIDC/Kind 验收 | 无；随 Sandbox L5 一并做最终环境复核 |
| Template/AgentSpec/Worker | 模板页面、Project scope 传播、AgentSpec 独立生命周期、显式实例化到 Worker CR、Operator Deployment、Ready 门禁、Worker 详情诊断、单/双副本 lease 过期自动回滚及受控 Ubuntu/K3s Sandbox L5 已通过验收 | 无；L6 仍按最终阶段约束处理 |
| Model Provider/价格 | 管理 API、Model 页面、凭据引用状态、连接测试和价格目录已接入 | 真实供应商联通属于 L6，最终阶段处理 |
| MCP | 管理 API、连接/路由/Discovery、Console 页面、scope 和 fail-closed 已完成 | 真实外部 MCP 联通属于 L6，最终阶段处理 |
| Skill | Skill 目录、版本、上传/发布、绑定和 package complete 页面已完成，并通过真实 MinIO 预签名直传验收 | 真实运行时外部依赖按 L6 最终阶段处理 |
| Conversation | API、SSE、重连、取消和 Console 基础已有 | 会话管理、权限/scope、Task 回链和跨用户记忆验证 |
| Task/Attempt/Assignment/Lease | 任务状态机、事件、操作和列表已有 | Attempt/Lease 可视化、调度原因、审批和跨 scope 负向验收 |
| Artifact | 上传/完成/结果清单后端已有 | Artifact 列表、下载/可见性/校验/保留策略页面 |
| Usage/费用/预算/告警 | Token ledger、价格、预算和 Dashboard 基础已有；当前认证 scope 内 Organization/Tenant/Project/Team/User 聚合、Task/Provider/Model 筛选、受限分页、CSV 导出、独立导出权限与审计、预算阈值写入、告警规则配置、告警重试详情、人工重试幂等和审计筛选/游标分页已接入 | 跨租户/跨组织查询需具备相应后台 scope 后再验收 |
| Memory/Sandbox | Memory 治理 API/页面、Context Assembly 约束、Sandbox 元数据页面、多 subject/Project/Team/Tenant 隔离及受控 Ubuntu/K3s gVisor/Kata L5 运行时验收已完成 | 无；外部真实依赖仍按 L6 最终阶段处理 |

完成定义不是“页面可以打开”，而是能力矩阵中 SDK 已有能力的管理端映射，以及管理端新增能力自身的 API、页面操作、授权、状态、错误、幂等、审计、scope 和验证证据全部齐备。

## 3. 统一领域和运行时约束

### 3.1 Worker Pod 生命周期

Organization 注册、激活、用户初始化、Tenant/Project/Team 创建、AgentSpec 发布均不得创建长驻 Worker Pod。只有显式的 Worker/Agent 实例化或部署命令才进入：

```text
管理端显式实例化
  → 逻辑 Agent/Worker 记录
  → Worker CR
  → Operator Deployment/Service/Pod
  → Gateway Hello
  → READY
  → Scheduler 接收任务
```

`READY` 之前任务保持 `QUEUED`，并展示可识别的依赖原因。默认一个 Worker Runtime 的并发为 1；需要扩容时增加 Worker/Agent，而不是在进程全局状态中混跑多个用户上下文。

### 3.2 调度和作用域

- Worker 默认归属 `Organization + Tenant + Project + Team`；不同 Organization、Tenant 或 Project 不共享 Worker；
- 同一 Project 的不同用户可以复用 Worker，但用户 `subjectId` 只作为授权和记忆维度，不作为固定 Worker 绑定键；
- Task 执行最小单位是 `Task + Attempt + Assignment + Lease + ExecutionContext`；
- 无 `teamId` 的任务只能在当前 `Organization + Tenant + Project` 内匹配 `READY` Worker，禁止全局 READY 兜底；
- Assignment、事件、Sandbox workspace 和 Artifact 可见性均重新校验 scope，不能只依赖资源 UUID。

### 3.3 记忆和 Sandbox

- 记忆使用 `USER_PRIVATE`、`ORGANIZATION_SHARED`、`PROJECT_SHARED`、`TEAM_SHARED`、`TASK` 五种 scope；
- 每次 Attempt 执行前强制调用 Context Assembly，校验 Organization/Tenant/Project/Team/subject、治理状态、同意状态、敏感级别、企业策略和 Token 预算；
- Worker/Sandbox 只接收最小摘要和必要引用，不接收全量历史、原始审计、Secret、其他用户记忆或完整 Chain of Thought；
- `USER_PRIVATE` 必须按 `subjectId` 隔离；共享记忆必须匹配 Project/Team 成员关系；过期、冻结、撤回同意或受限投影不得进入普通上下文；
- `NONE` 不创建 Sandbox；`ISOLATED/HARDENED/DEDICATED` 按 Attempt 一对一创建和回收，重试必须使用新 Attempt 和新 Sandbox。

## 4. 能力矩阵和 API/页面对齐

实施开始前建立机器可读的能力矩阵，建议放置在 `docs/acceptance/management-capability-matrix.yaml`，每行至少包含：

```yaml
id: task.cancel
domain: task
sdk_status: existing|not_yet_exposed|not_applicable
management_api: method_and_path
console_route: route_or_component
permission: permission_name
scope: organization|tenant|project|team|user|task
idempotency: required|not_required
version_guard: required|not_required
memory_impact: none|read|write|governance
acceptance: unit|integration|kind|browser|controlled
status: planned|implemented|verified
```

矩阵至少覆盖以下管理端入口：

- `/settings/organizations`、`/settings/tenants`、`/settings/users`、`/settings/external-users`、`/settings/integrations`、`/settings/roles`、`/settings/permissions`；
- `/:projectId/overview`、`/:projectId/projects`、`/:projectId/teams`、`/:projectId/templates`、`/:projectId/agentspecs`、`/:projectId/workers`；
- `/:projectId/models`、`/:projectId/mcp`、`/:projectId/skills`、`/:projectId/conversations`、`/:projectId/tasks`、`/:projectId/artifacts`；
- `/:projectId/usage`、`/:projectId/budgets`、`/:projectId/alerts`、`/:projectId/audit`、`/:projectId/memory`、`/:projectId/sandboxes`。

如果某项能力只应由平台管理员操作，页面仍必须提供受权限控制的管理入口；如果某项能力只适用于外部 Integration，则页面必须展示外部身份和内部授权映射，而不是让外部用户直接选择内部角色。

## 5. 分阶段实施顺序

### 阶段 0：冻结基线和建立对账矩阵

**目标：** 锁定 SDK、不再扩展 Java 公共接口，确定所有后续功能的 API/页面/权限/隔离验收清单。

**工作内容：**

1. 把现有 Java/TypeScript SDK 方法和公共 OpenAPI 资源登记到能力矩阵；
2. 把内部用户、组织、外部用户、角色、Team、Template、Worker、MCP、Skill、Conversation、Task、Artifact、Usage、Memory、Sandbox 登记为产品能力域；
3. 为每一项补齐 owner、依赖、数据 scope、页面路由、验收层级和当前状态；
4. 将 Java SDK 的变更检查改为“冻结期间禁止功能性修改”，仅保留构建/安全修复例外；
5. 将旧 Console 设计中“Skill/MCP/配额延后”的内容标记为被本约束覆盖，统一以本方案为准。

**主要文件：** `docs/superpowers/specs/2026-09-01-work-pod-and-memory-isolation-requirements.md`、`docs/acceptance/management-capability-matrix.yaml`、`README.md`、`openapi/agentteams-public.yaml`（只做盘点和契约说明，不同步 Java SDK）。

### 阶段 1：组织、用户、外部身份和授权管理

当前已完成首个可验收切片：Organization/Tenant 创建幂等、状态变更和版本条件更新，Organization/Integration/Credential 元数据持久化、Secret 引用边界，以及 Console `/settings/identity` 的内部用户、组织角色、外部用户映射操作页；并补充用户、成员、Integration、Credential 和外部身份的查询入口及内部用户状态变更。外部用户 Provisioning 生命周期已补齐管理端初始化、更新、停用和 Membership 查询入口：写操作使用持久化幂等键，更新/停用/Membership 按 `integrationId + externalOrganizationId + externalUserId` 精确定位，成功操作写入脱敏审计；Java SDK 未修改。Credential 的生产 Secret provider 仍受生产凭据缺失约束。

**目标：** 管理端可以安全地建立和治理内部身份与外部调用身份。

**工作内容：**

1. 补齐 Organization/Tenant、内部 User、Membership、Role、Permission、授权策略的查询和变更 API；当前已完成 Project 成员角色查询、后端权威角色权限矩阵、`Idempotency-Key`、版本保护和真实 OIDC Console 首个切片；
2. 补齐 Integration、Credential、ExternalIdentity 和外部用户的生命周期 API；
3. 支持 Credential 轮换、撤销、过期、允许 scope、IP 限制和审计；
4. Console 提供用户、外部用户、Integration、角色和权限页面，页面不显示任何 Secret 明文；
5. 统一 OIDC 人类用户、外部签名应用和外部用户映射的 `ExecutionContext`；
6. 增加跨 Organization/Tenant/Project、禁用用户、无权限、角色降级和重放请求负向测试。

**主要文件范围：** `control-plane/src/main/java/io/agentteams/controlplane/security/`、`control-plane/src/main/java/io/agentteams/controlplane/api/`、相关 Flyway migration、`console/src/api/`、`console/src/features/settings/`、授权和集成测试。

### 阶段 2：资源配置和显式 Worker 供给

**目标：** 管理员可以通过页面从 Template/AgentSpec 显式创建 Worker，并看到真实 Ready 状态。

**工作内容：**

1. 完成 Project/Workspace、Team、成员、策略、版本、部署和回滚页面；
2. 完成 Worker Template、revision、审核、发布、实例化和升级页面；
3. 打通实例化幂等链路：Template → AgentSpec → Agent/Worker → Worker CR → Operator；
4. Worker 列表和详情展示配置版本、镜像摘要、Secret generation、连接、心跳、Lease 和失败原因；
5. 接入 Model Provider、价格目录、Skill 和 MCP 管理入口，所有引用只显示 metadata/status/credentialRef 脱敏结果；模型管理页已展示 Provider 下属 Model，并补齐 Provider/Model 专用启停接口、删除确认和结果刷新，避免用脱敏响应回写或覆盖真实凭据；价格目录展示当前作用域价格、有效期、生命周期和版本，价格写入继续由同步权威链路负责；
6. 为 MCP/Skill 绑定增加资源可见性、版本、摘要、下载/发现失败和 fail-closed 状态；MCP 管理页已补齐删除确认、编辑、真实连接测试和 Discovery 摘要，连接测试使用 `expectedVersion`/凭据安全结果；Skill 管理页已补齐已审核且制品完成后的版本发布/停用入口，Kind MinIO 的 package upload/complete 已通过真实页面验收，真实 MCP Discovery 仍受凭据边界约束；
7. 验证注册、激活、用户初始化、Team 创建和 AgentSpec publish 均不会隐式创建 Pod。

**主要文件范围：** `control-plane/src/main/java/io/agentteams/controlplane/api/`、`template/`、`agentspec/`、`mcp/`、`service/`、`operator/`、`console/src/features/{teams,workers,templates,models,mcp,skills}/`、Helm/RBAC 和 Kind 验收脚本。

### 阶段 3：对话、任务、Sandbox、Artifact 和记忆治理

**目标：** 页面可以完成一次可追踪、可隔离、可回收的执行闭环。

**工作内容：**

1. Conversation 创建、消息、SSE、重连、取消、Task 回链和历史查询使用统一 `ExecutionContext`；
2. Task 页面已接入 Attempt、Assignment、Lease 元数据查询；继续补齐审批、暂停、重试、取消、调度原因和事件树；
3. Context Assembly 已接入 Task Assignment/Sandbox READY 的真实派发链路，摘要通过 Agent channel 的受控字段传递，禁止只保留独立服务和单元测试；
4. 无 Team 调度路径已按任务 resource scope 做 Organization/Tenant/Project（当前持久化兼容模型为 tenant/project）过滤；Team 管理页已补齐成员添加/移除、调度策略编辑、Revision 草稿/审核/发布、Deployment/Retry 和回滚草稿，写操作复用幂等和版本保护；
5. Memory 管理页面已结构化展示策略、治理状态、来源、过期时间和审计所需元数据，并提供带原因的确认、撤回、冻结、导出元数据和删除操作；私人记忆内容访问必须有独立权限、原因、审批和限时授权；
6. Sandbox 页面已结构化展示 profile、Attempt 绑定、状态、回收时间、Endpoint Ref 和脱敏失败原因；workspace 内容不通过管理页暴露；
7. Artifact 页面已结构化展示上传状态、完成校验、SHA-256、Task/Attempt 归属，并支持项目级保留策略查看和带 `expectedVersion` 的更新；下载继续通过原有授权边界；
8. Usage 页面已接入当前 Project 的调用汇总、Provider/Model 明细、Organization/Tenant/Project/Team/User 聚合、Task/Provider/Model 筛选、受限分页和 CSV 导出，并展示预算策略状态；预算页面已展示评估结果并可按当前 version 写入软/硬阈值，告警页面已展示实时评估、Project 作用域规则配置、版本保护、投递状态、失败原因、下一次重试时间和人工立即重试，人工重试通过 V76 持久化请求键并记录审计，审计页面已展示脱敏操作事件并支持筛选和游标分页，导出已具备独立 `usage:export` 权限和审计记录；User 聚合通过 V78 `actor_subject` 完成；
9. 已补充 Memory 管理列表的数据库/接口双层 Project 收敛、跨 Project 治理拒绝、Task 执行越权不读取执行元数据的测试；Kind 真实 OIDC 验收已覆盖同一 Project 不同 subject 私有记忆隔离、Project/Team/Organization shared 可见性、跨 Project/跨 Tenant 不可见和 metadata-only 返回；受控 Ubuntu/K3s 已补充 gVisor/Kata 运行时级 Sandbox 验证。

**主要文件范围：** `control-plane/src/main/java/io/agentteams/controlplane/{task,memory,sandbox,artifact,service}/`、`agent-worker/`、`runtime/`、`console/src/features/{conversations,tasks,memory,sandboxes,artifacts}/`、集成和浏览器测试。

### 阶段 4：Usage、费用、预算、告警和审计运营

**目标：** 管理端能够解释“谁在什么 scope 使用了什么资源、产生了多少估算费用，以及预算是否触发”。

**工作内容：**

1. 提供按 Organization/Tenant/Project/Team/User/Task/Provider/Model 的 Usage 查询和分页；当前切片已完成认证 scope 内全部上述维度、Task/Provider/Model 筛选和受限分页，User 维度读取调用审计可靠的 `actor_subject`，不得从不具备该语义的字段推断用户；
2. 展示 Prompt/Completion Token、调用数、失败数、延迟、估算成本、价格版本和数据时间窗；
3. 补齐预算、预测、状态、阈值、通知、失败重试和告警历史页面；当前已完成 Project 作用域告警规则配置、版本保护、失败重试状态展示以及带持久化请求键的人工重试；
4. 费用统计与 Task/Attempt/Artifact/Conversation 回链，但不将估算成本伪装成最终账单；
5. 所有运营查询执行 scope 过滤和权限校验，导出需要独立权限并脱敏；当前 CSV 导出复用同一 Project 作用域和筛选条件，API 已拆出 `usage:export` 权限并记录导出审计，Console 已对 403 展示明确权限状态；
6. 增加重复事件、历史回填、价格变更、跨副本聚合和窗口边界测试。

**主要文件范围：** `control-plane/src/main/java/io/agentteams/controlplane/{usage,quota,api}/`、数据库迁移、`console/src/features/{usage,budgets,alerts,audit}/`、Dashboard/Usage 集成测试。

### 阶段 5：页面最终验证与 Java SDK 解冻评审

**目标：** 证明管理端能力完整后，再决定 Java SDK 的最终形态。

**工作内容：**

1. 用能力矩阵逐项执行 API 合约、权限、scope、幂等、版本冲突、失败恢复和页面验证；
2. 运行 Console 浏览器 E2E：登录 → 组织/用户 → Project → Team/Template → Worker Ready → MCP/Skill → Conversation/Task → Sandbox/Artifact → Usage/Audit；
3. 运行跨用户、跨 Project、跨 Tenant、跨 Organization 和旧 Attempt 回放的负向测试；
4. 记录无生产 Secret Manager Credential 时的验证边界，不把 Fake/ValidationOnly/Kind 结果标为生产可用；
5. 只有所有 P0 能力通过且没有未登记的管理端缺口，才单独创建 Java SDK 更新计划；
6. SDK 更新必须基于最终 OpenAPI 和能力矩阵，一次性补齐必要方法、模型、鉴权、错误、幂等、重试和安全回归。

当前页面验证证据记录在 `docs/acceptance/2026-09-01-management-verification-report.md`：19 个 Console E2E 用例已使用 Kind 开发 Realm 的真实 OIDC 登录通过，其中 Template 显式实例化已继续由脚本核验 Worker CR、Operator Deployment 和 Ready Pod，Worker lease 过期恢复由独立脚本核验稳定快照、事件和回滚状态；另有 Memory scope acceptance 脚本和受控 Ubuntu/K3s gVisor/Kata Sandbox L5 通过；这不构成生产认证或生产外部依赖的通过证明。

### L6 最终阶段约束（不属于当前执行计划）

L6 真实外部依赖、生产凭据、长期运行、故障恢复和发布演练验收统一放到项目最后处理。该部分只作为最终收口门禁保留，不作为当前阶段的待办项、里程碑或自动续作任务。只有用户明确发出“启动 L6 验收”指令后，才根据当时的凭据、环境和范围创建独立计划。

## 6. 没有生产 Secret Manager Credential 时的方案

### 6.1 可以继续完成的工作

- 管理 API、Console 页面、权限、scope、状态机、审计、幂等和错误分类；
- Model/MCP/Webhook/Integration 的 `credentialRef` 生命周期、引用校验和状态展示；
- `ValidationOnlySecretResolver`、可控 Fake/Stub、Kubernetes Secret 和 ExternalSecret 夹具下的 L1-L4；
- Secret 缺失、引用非法、ExternalSecret 未 Ready、目标 key 缺失、读取超时和依赖不可用的 fail-closed 行为；
- 不包含 Secret 明文的配置快照、manifest、日志、事件、指标和页面脱敏验证。

### 6.2 必须延期的工作

- 真实生产 Secret Manager 的身份绑定、网络连通性、权限策略和凭据轮换；
- 真实模型供应商、第三方 MCP、外部 Webhook 和生产 Integration 的连通性；
- 任何需要生产 Credential 才能证明的 L5/L6 结果。

### 6.3 适配边界

领域层只依赖 `SecretResolver`、`McpCredentialProvider` 等 Port。拿到凭据后，新增或启用对应 Adapter，并执行受控 L5 验收；L6 按“L6 最终阶段约束”单独处理。不得修改领域 scope、Worker 生命周期、Memory Policy 或 Console 的 Secret 交互方式。当前 `OrganizationManagementService` 的内存实现只能作为开发/测试夹具，下一切片必须迁移为持久化元数据和 Secret 引用，不能作为生产多副本权威状态。生产凭据缺失时，生产 profile 必须显示 `UNAVAILABLE`/`NOT_CONFIGURED` 等明确状态并拒绝执行，不得回退到假值或明文配置。

## 7. 验收门禁

| 门禁 | 必须通过的内容 |
| --- | --- |
| L1 | Java/TypeScript/前端单测、状态机、授权、记忆策略、错误分类、敏感信息扫描 |
| L2 | 数据库迁移、唯一约束、并发幂等、重启恢复、Usage 聚合和跨副本状态 |
| L3 | OpenAPI、Helm、CRD、RBAC、NetworkPolicy、Secret 引用和 Console 构建 |
| L4 | Kind 全链路：OIDC、控制面、Gateway、Worker、Operator、PostgreSQL、NATS、MinIO、对话、Task、Sandbox、MCP/Skill 状态 |
| Browser | 页面逐项完成能力矩阵，验证权限、空态、失败、重试、版本冲突、SSE 重连和危险操作确认 |
| L5 | 仅在涉及运行时隔离或其他必须真实环境证明的能力时，在受控环境执行，不向仓库注入 Secret |
| L6 | 项目最终阶段的独立门禁；当前不排期、不建任务、不自动执行，须用户明确说“启动 L6 验收”后再创建独立计划 |

Java SDK 解冻前的硬门禁：能力矩阵全部为 `verified` 或明确标记为不适用；Memory/Worker/Sandbox 跨 scope 负向测试通过；管理端最终页面验证有环境和版本证据；生产凭据缺失的限制已被明确记录。

## 8. 产出物和变更纪律

- 约束基线：`docs/superpowers/specs/2026-09-01-work-pod-and-memory-isolation-requirements.md`；
- 能力矩阵：`docs/acceptance/management-capability-matrix.yaml`；
- 本总体方案：本文件；各阶段实施前再拆分独立实现计划；
- Java SDK 冻结期间，不修改 `sdk/java` 的功能、模型、README 和版本；
- 不覆盖当前工作区已有的身份安全未跟踪文件，所有新变更保持单一职责；
- 每个阶段完成后同步 README、API 契约、能力矩阵、验证命令和遗留限制。

## 9. 口径确认

已确认的能力关系为 `SDK 已有能力 ⊆ 管理端能力`：SDK 能力必须被管理端逐项覆盖；管理端可以拥有 SDK 未提供的能力，不要求这些能力反向进入 SDK。对应关系按业务语义、权限、scope、错误、幂等和验收证据对齐，不要求接口路径、Java 类名或页面布局完全相同。
