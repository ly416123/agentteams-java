# AgentTeams 商业版能力对齐：需求、技术方案与实施计划

**基准日期**：2026-08-23
**适用仓库**：`agentteams-java`
**对比对象**：阿里云 AgentTeams 商业版公开文档
**当前代码基线**：`5803604`（Dashboard Summary 读模型已落地；本轮并行治理改动尚未提交）

## 1. 文档目标与范围

本文件将阿里云 AgentTeams 商业版公开能力，转换为当前 Java 版本可执行的产品需求、技术方案和开发计划。

本项目的目标不是复制阿里云控制台或云厂商内部实现，而是建立与商业版相近的核心能力边界：

1. 模型、Worker、Team、Skill、MCP 和身份权限可配置、可审计、可回滚。
2. 配置能够从 Control Plane 安全、可观测地同步到 Worker。
3. 任务、模型调用、工具调用和资源使用能够统计、查询和告警。
4. 保持 OIDC、OpenTelemetry、MCP、OpenAPI、JSON Schema、Kubernetes CRD 等开放接口，避免绑定单一云厂商。

以下内容暂不作为主线目标：阿里云实例购买/续费、VPC 与云网络编排、商业计费结算、阿里云控制台像素级复刻。这些能力放在可选阶段。

## 2. 商业版能力基线

阿里云公开文档目前明确覆盖以下能力：

- Worker/Worker Team 管理，包括模型、文件、Skills、MCP Server 和团队成员配置：
  [Worker 管理](https://help.aliyun.com/zh/agentteams/manage-worker)。
- Model Provider/Model 配置及模型与 Provider 绑定、幂等创建、资源绑定约束：
  [CreateModel API](https://help.aliyun.com/zh/agentteams/api-agentteams-2026-06-05-createmodel)。
- Dashboard 对 Worker、Task、Team、Model、Tool 的调用量、Token、状态、时延和趋势进行统计：
  [Dashboard 与监控](https://help.aliyun.com/zh/agentteams/view-dashboards-and-monitoring)。
- MCP Server 的创建、编辑、删除、认证、HTTP/SSE/Streamable HTTP 等连接配置：
  [MCP 管理](https://help.aliyun.com/zh/document_detail/3040380.html)。
- Skill 的导入、版本、审核、发布、启停、可见性和权限控制：
  [Skill 管理](https://help.aliyun.com/zh/document_detail/3040382.html)。
- 从模型、用户、Worker Team、Worker 到 MCP/Skill 的基础配置流程：
  [快速开始](https://help.aliyun.com/zh/agentteams/magic-quick-start)。

## 3. 当前项目状态与差距矩阵

状态定义：

- **已有**：已有可复用实现，补齐测试或接口即可。
- **部分具备**：已有底层能力，但缺少统一模型、生命周期或商业化管理面。
- **缺失**：当前代码中未形成完整能力。
- **可选**：对核心开源 AgentTeams 不构成阻塞，可在主线稳定后实现。

## 3.1 当前执行进度（2026-08-23）

已完成并通过 Control Plane 全量测试的基础能力：

- Model Provider/Model Catalog 基础目录（V16）。
- AgentSpec v1 草案资源、幂等创建、查询和权限入口（V20）。
- AgentSpec 到现有 ConfigSnapshot/Outbox/Worker ACK 管道的部署适配，可通过
  `POST /api/v1/agent-specs/{specId}/deployments/{agentId}` 发布配置。
- Skill Registry、MCP Server Registry、Usage Summary、Operation Audit 基础 API（V17–V21）。
- Keycloak/OIDC 身份验证、权限策略、敏感字段脱敏和 Flyway/Testcontainers 验证。

本轮已选择并行推进的下一组任务：

| 优先级 | 任务 | 依赖 | 当前状态 |
|---|---|---|---|
| P0 | AgentSpec 引用 Model Catalog、发布前校验和生命周期 | Model Catalog、AgentSpec | 已完成：校验、幂等发布/停用、版本冲突 |
| P0 | Skill manifest/digest/版本发布校验 | Skill Registry | 已完成：manifest、SemVer、digest、入口和大小校验 |
| P1 | Model/MCP 写操作接入持久化审计 | Audit、Model/MCP Registry | 已完成：成功/失败事件、脱敏、REQUIRES_NEW |
| P1 | Usage 增加 Dashboard/Prometheus 稳定分组契约 | model_call_audits、Usage API | 已完成：provider/model/status 分组和 limit |
| P1 | AgentSpec → Worker 配置发布、ACK、旧版本拒绝和回滚 | ConfigSnapshot、Outbox、Worker ACK | 已完成第二段：Snapshot/Outbox/ACK、旧版本保护、绑定状态、失败重试；Worker 实际回滚待收口 |

本轮第二波已在工作区完成并通过干净全量测试（190 个测试，0 失败）：

- **Worker 配置治理**：增加绑定状态查询、最新 revision 保护、失败配置重试，以及 ACK 幂等处理。
- **Model Provider/Model 治理**：增加启停、删除依赖检查、连接测试结果分类和模型/Provider 依赖索引；默认连接探针只做配置校验，不会误发真实模型请求。
- **项目级 IAM 基线**：Keycloak/OIDC 提供身份和租户上下文，Control Plane 持久化项目、成员和 OWNER/ADMIN/OPERATOR/DEVELOPER/VIEWER 角色，并提供幂等成员管理。
- **MCP/Skill 安全基线**：MCP 出站 scheme/domain/tool/timeout 策略、失败分类，以及 Skill 版本安全扫描和审核字段。
- **Dashboard**：提供 `GET /api/v1/dashboard/summary`，复用 Usage 查询口径输出模型调用、Token、成功率和延迟摘要。

尚未宣称为完整商业版能力的部分：真实 Provider 网络探针和 Secret Manager 接入、Worker 实际回滚执行、项目角色覆盖全部资源 API、MCP 真正连接器/工具发现、Skill 包安全扫描器、Prometheus 告警规则、成本/配额、Worker 模板和 Console。

推荐的依赖关键路径为：

```text
Model Catalog 校验
        ↓
AgentSpec 引用与版本发布
        ↓
ConfigSnapshot → Outbox → Worker ACK
        ↓
任务/模型/工具使用统计与告警
```

Skill、MCP、Audit 可以在关键路径上并行建设，但 Skill/MCP 的最终绑定必须复用 AgentSpec 引用协议；Dashboard 先稳定 API 和数据口径，再实现 Console，避免前端先行导致统计模型反复变更。

| 能力域 | 当前项目 | 差距 | 优先级 |
|---|---|---|---|
| Model Provider/Model | **部分具备**。已有目录、启停/删除依赖检查、配置型连接测试、模型调用审计和 AgentSpec 引用校验 | 缺少真实协议探针、Secret Manager、能力/价格目录和 Worker 侧生效确认 | P0 |
| Worker 生命周期 | **部分具备**。有 Agent 注册 API、Worker CRD/Operator、Gateway 注册/租约/重放和 mTLS 基础；配置绑定支持状态、ACK 幂等和失败重试 | 缺少 Worker 实际回滚执行、优雅下线和完整 Worker/Team 管理 API | P0 |
| Worker Team | **部分具备**。已有 Team CRD、同步、调度和策略基础 | 缺少成员/管理员模型、Leader 配置、Team 级模型/文件/Skill/MCP 绑定及版本化发布 | P0 |
| AgentSpec | **缺失**。当前 WorkerSpec、AgentRecord 和运行时配置分散 | 缺少统一的 AgentSpec v1、Schema 校验、引用关系、配置修订和 Worker ACK | P0 |
| 用户/租户/项目/RBAC | **部分具备**。已有 OIDC/Keycloak、JWT scope、项目成员/角色表和租户隔离基线 | 缺少角色覆盖全部资源、成员禁用/邀请、跨资源授权过滤和完整成员审计 API | P0 |
| Skill 管理 | **部分具备**。已有 Registry、digest/manifest/版本校验和发布生命周期，并增加扫描/审核状态 | 缺少真实安全扫描器、包存储/下载策略、可见性和 Worker/Team 绑定 | P1 |
| MCP Server 管理 | **部分具备**。已有 Registry、认证引用、传输配置、健康状态和出站策略模型 | 缺少真实连接器、工具发现、健康探针、限流/熔断和调用审计闭环 | P1 |
| Dashboard/使用分析 | **部分具备**。已有 Micrometer、OTel、Prometheus/Grafana、Usage API 和 Dashboard Summary | 缺少 Worker/Task/Team/Tool 全维度聚合、成本/配额、告警规则和统一大盘 | P1 |
| 审计与安全治理 | **部分具备**。已有模型调用审计、敏感信息脱敏、OIDC/mTLS/RBAC 基础 | 缺少配置变更审计、Skill/MCP 操作审计、Secret 轮换、审批、出站策略和完整合规事件 | P1 |
| Worker 模板 | **缺失** | 缺少可复用模板、版本、审批、实例化和升级策略 | P1 |
| 渠道接入 | **部分具备**。当前已有 Matrix/Tuwunel 方向 | DingTalk 等商业渠道未接入；需要统一 Channel SPI 和异步投递语义 | P2 |
| 配额、成本、计费 | **缺失** | 缺少 Token/调用配额、成本估算、限流、预算告警；真实账单属于云厂商扩展 | P2 |
| 云实例/网络生命周期 | **缺失** | 本项目当前以 Helm/Kind/Kubernetes 部署为主，不负责云资源购买、VPC 和实例生命周期 | P2（可选） |
| 控制台 UI/开放 SDK | **部分具备**。后端 API 和 Helm 运维入口已有 | 缺少统一 Web Console、前端权限模型和 Java/TypeScript SDK | P2 |

## 4. 产品需求清单

### 4.1 P0：核心控制面闭环

#### MP-01 Model Provider/Model 目录

**需求**

- 支持 Provider 和 Model 的创建、查询、更新、启用/禁用、删除。
- Model 必须引用一个 Provider；Provider 名称、Model 名称在作用域内唯一。
- 支持 OpenAI Compatible、DashScope/兼容协议、DeepSeek 等协议类型扩展。
- 保存 endpoint、协议、模型能力、上下文长度、版本和启用状态。
- 写操作支持 `Idempotency-Key`/`ClientToken`，重复请求不得产生重复资源。

**验收**

- Provider/Model 重复创建、无效协议、无效 endpoint 能得到稳定错误码。
- 已被 AgentSpec/Worker 引用的 Model 不允许直接删除，必须先解除绑定或进入停用状态。
- API 响应和日志中不得返回 API Key；只返回 `credentialConfigured` 或 Secret 引用状态。

#### MP-02 Provider 凭证与连通性测试

**需求**

- 凭证仅保存为 `credentialRef`，实际 Secret 由 Kubernetes Secret、External Secrets 或企业 Secret Manager 管理。
- 提供连接测试 API，支持超时、脱敏错误、协议校验和最小模型请求。
- 连接测试结果保存状态、时间、错误分类和延迟，不保存完整 Prompt/Response。

**验收**

- 凭证不存在、权限错误、endpoint 不可达、模型不存在分别返回可诊断的错误类型。
- Secret 轮换后无需修改 AgentSpec，只需重新连接测试并发布新配置。

#### AG-01 AgentSpec v1

建立 Worker、运行时配置和模型/工具/技能引用的唯一规范模型。建议初版结构：

```yaml
apiVersion: agentteams.io/v1alpha1
kind: AgentSpec
metadata:
  name: analyst-worker
  version: 3
spec:
  runtime: qwenpaw
  modelRef:
    provider: deepseek
    model: deepseek-chat
  teamRef: research-team
  desiredState: RUNNING
  files:
    soulMdRef: artifact://skills/analyst/soul.md
    agentMdRef: artifact://skills/analyst/agent.md
  skillRefs: [web-search-v1]
  mcpServerRefs: [search-mcp]
  credentialRefs: [deepseek-default]
  capabilities: [task.execute, artifact.read]
  resources:
    requests: { cpu: "250m", memory: "512Mi" }
    limits: { cpu: "2", memory: "2Gi" }
```

**要求**

- AgentSpec 使用 JSON Schema 校验，字段语义与现有 WorkerSpec/AgentRecord 做兼容映射。
- 引用资源必须存在、启用且对当前租户/项目可见。
- 发布后产生不可变 revision；修改配置生成新 revision，不原地覆盖已发布配置。
- Worker 必须回传 `observedRevision`、状态、能力和最后错误。

#### AG-02 Worker/Team 配置同步

- Control Plane 是期望态唯一来源，Operator/Gateway/Worker 负责实际下发和执行。
- 使用 Outbox + NATS/JetStream 传递配置变更，消息携带 `resourceId`、`revision`、`traceparent`、`idempotencyKey`。
- Worker 支持幂等应用、断线重放、ACK、失败重试和回滚到上一稳定 revision。
- 删除采用 `DRAINING -> TERMINATED` 两阶段流程，避免任务和租约被直接截断。
- Team 配置变更必须能解释影响范围：Leader、成员、模型、Skill、MCP 和并发策略。

#### IAM-01 用户、项目与权限

- Keycloak 继续作为 IdP；Control Plane 保存本地资源归属、项目成员和角色绑定。
- 最小角色：`OWNER`、`ADMIN`、`OPERATOR`、`DEVELOPER`、`VIEWER`。
- 权限维度覆盖 model、worker、team、skill、mcp、task、usage、audit。
- 每个资源必须带 `tenantId/projectId/createdBy`，查询和事件消费都执行租户隔离。
- 支持成员加入、禁用、角色变更和资源转移；所有操作产生审计事件。

### 4.2 P1：商业版管理与运维能力

#### SK-01 Skill Registry

- 支持 ZIP/目录导入，解析并校验 `SKILL.md` 的名称、描述和正文。
- Skill 包计算 digest；版本发布后不可变，修改必须创建新版本。
- 生命周期：`DRAFT -> REVIEWING -> PUBLISHED -> DISABLED/DEPRECATED`。
- 支持私有/项目/租户/公共可见性、启停、绑定 Worker/Team 和版本回滚。
- 发布前执行压缩包路径穿越、危险脚本、依赖和大小检查；生产环境可挂接安全扫描服务。

#### MCP-01 MCP Server Registry 与安全连接

- 支持 HTTP-to-MCP、直接代理两类模式，以及 SSE、Streamable HTTP 传输。
- 保存 endpoint、transport、TLS、认证方式、`credentialRef`、超时和重试策略。
- 提供健康检查和连接测试；支持工具发现结果缓存和版本校验。
- 支持 host allowlist、工具 allowlist、租户级出站策略、限流、熔断和调用审计。
- Worker 只接收经过授权的 MCP 配置，不直接读取控制面 Secret。

#### OBS-01 使用量与 Dashboard 数据 API

统一记录以下维度：`tenant/project/team/worker/task/model/provider/tool/channel`。

- Task：总量、成功/失败、平均时延、状态分布、重试和超时。
- Worker：在线数、注册数、任务数、最后心跳、配置 revision、错误率。
- Model：调用数、输入/输出 Token、延迟、错误率、估算成本。
- Tool/MCP：调用数、成功率、耗时、拒绝数和出站错误。
- 支持时间范围、分页、排序、按日/小时聚合和数据刷新时间。

Prometheus 继续负责实时运行指标，PostgreSQL/分析存储负责可查询历史数据；初期先使用 PostgreSQL 聚合表和 Grafana，规模增长后再评估 ClickHouse。

#### GOV-01 审计、告警与配额基础

- 审计 Provider、Model、Worker、Team、Skill、MCP、权限和配置发布操作。
- 审计事件包含操作者、资源、前后版本、结果、来源 IP、traceId 和 requestId，不包含 Secret 和完整模型内容。
- 告警覆盖 Worker 大面积离线、模型错误率、Outbox backlog、配置发布失败、MCP 出站失败和 Token/配额超限。
- 配额初期实现项目级并发、日调用数和 Token 上限；超过阈值可拒绝、降级或只告警。

#### TMP-01 Worker 模板

- 模板包含 AgentSpec、默认模型、文件、Skill/MCP 引用、资源和权限策略。
- 模板版本不可变，支持审批、实例化、升级和回滚。
- 模板不能绕过租户权限和 Secret 访问控制。

### 4.3 P2：可选扩展

- 统一 Channel SPI，并在 Matrix 基础上接入 DingTalk、Webhook 等渠道。
- Web Console、TypeScript/Java SDK 和 OpenAPI 代码生成。
- 云厂商实例、VPC、域名、证书和商业账单生命周期。
- 多区域部署、跨区域数据同步、归档和长期成本分析。

## 5. 技术方案

### 5.1 分层架构

```text
                    +-----------------------------+
                    | Console / OpenAPI / SDK     |
                    +--------------+--------------+
                                   |
                    +--------------v--------------+
                    | Control Plane                |
                    | Catalog / IAM / AgentSpec    |
                    | Revision / Audit / Usage API |
                    +----+-------------+------------+
                         |             |
                    PostgreSQL      Outbox + NATS/JetStream
                         |             |
              +----------v-------------v----------+
              | Operator / Gateway / Config Sync   |
              +----------+-------------+------------+
                         |             |
                     Worker        Analytics/OTel
```

职责边界：

- **Control Plane**：资源目录、权限、期望态、revision、审计和查询 API。
- **Operator**：将 AgentSpec/Worker desired state 转换为 Kubernetes 资源。
- **Gateway**：注册、租约、配置通道、断线重放和 mTLS。
- **Worker**：运行时执行、能力上报、配置 ACK 和 OTel 子 Span。
- **Manager**：通过 Provider SPI 执行模型调用、重试、结构化输出和调用审计。
- **Analytics**：从领域事件、模型审计和 OTel/Micrometer 数据生成聚合指标。

### 5.2 持久化模型

沿用当前 PostgreSQL + Flyway + Repository + Outbox 方式，新增或扩展以下表：

| 表/聚合 | 关键字段 |
|---|---|
| `model_providers` / `models` | provider、protocol、endpoint、enabled、settings、version |
| `provider_credentials` | credential_ref、secret_type、status、last_tested_at；不存 Secret 明文 |
| `model_bindings` | model_id、resource_type、resource_id、revision、status |
| `agent_specs` / `agent_spec_revisions` | tenant/project、desired_state、spec_json、revision、status |
| `worker_config_revisions` | worker_id、revision、payload_digest、apply_status、observed_at |
| `skills` / `skill_versions` | digest、manifest、visibility、lifecycle、reviewer |
| `mcp_servers` / `mcp_bindings` | transport、endpoint、credential_ref、allowlist、health_status |
| `users` / `memberships` / `roles` | subject、tenant/project、role、status |
| `operation_audit_events` | actor、resource、action、before/after revision、trace_id、result |
| `usage_events` / `usage_hourly` | 维度字段、tokens、latency、status、estimated_cost |

所有可变资源使用 `version` 或 `revision` 做乐观锁；所有异步事件带幂等键。配置和审计事件中禁止写入 API Key、JWT、完整 Prompt/Response。

### 5.3 API 设计

沿用现有 `/api/v1` 风格，新增或完善：

```text
/api/v1/model-providers
/api/v1/model-providers/{id}/test
/api/v1/model-providers/{id}/models
/api/v1/models/{id}/bindings
/api/v1/agent-specs
/api/v1/workers
/api/v1/teams
/api/v1/skills
/api/v1/mcp-servers
/api/v1/users /api/v1/projects /api/v1/memberships
/api/v1/usage/query
/api/v1/dashboard/summary
/api/v1/audit-events
```

约定：

- 写 API 接受 `Idempotency-Key`，响应携带资源 `version`/`revision`。
- 更新使用 `If-Match` 或显式版本号，冲突返回 `409`。
- 删除默认是软删除或停用；有依赖时返回依赖资源列表。
- OpenAPI 和 JSON Schema 纳入 CI，契约测试覆盖鉴权、错误码、脱敏和幂等。

### 5.4 事件与配置发布

建议事件主题：

```text
agentteams.model.events
agentteams.agent-spec.events
agentteams.worker.events
agentteams.skill.events
agentteams.mcp.events
agentteams.usage.events
agentteams.audit.events
```

事件最小公共字段：`eventId`、`eventType`、`resourceId`、`revision`、`occurredAt`、`tenantId`、`projectId`、`traceparent`、`idempotencyKey`。

发布流程：

1. API 创建 DRAFT revision。
2. Schema、引用、权限、Secret 状态和资源配额校验。
3. 事务内写入 revision 与 Outbox。
4. Gateway/Operator 投递到 Worker，Worker 幂等应用并 ACK。
5. Control Plane 更新 `observedRevision`；失败进入 `FAILED` 并保留上一稳定版本。
6. 支持按 revision 回滚；回滚也必须产生新的事件和审计记录。

### 5.5 观测与成本

统一 OTel 属性和指标名称，至少包含：

- `agentteams.task.started/completed/failed`
- `agentteams.worker.online/offline`
- `agentteams.model.calls/tokens/errors/latency`
- `agentteams.mcp.tool.calls/errors/latency`
- `agentteams.config.publish/ack/failure`
- `agentteams.outbox.pending/retry/dead_letter`

模型调用 span 必须保留 `traceparent`，异步消费者使用 Span Link/Propagator 桥接父上下文；这与当前已有的异步 OTel 修复保持一致。完整 Prompt/Response 默认不采集，仅保留脱敏摘要、哈希、Token 和错误分类。

成本采用 Provider/Model 价格配置计算估算值，字段命名为 `estimatedCost`，不得伪装为云厂商最终账单。

## 6. 并行实施计划

### 阶段 P0：控制面基础闭环（基础段已完成，进入收口）

**目标**：把当前基础 Model Catalog 从“目录”升级为“可安全绑定和使用的配置中心”。

- MP-01：补齐 Provider/Model 更新、停用、删除依赖检查和协议校验。
- MP-02：引入 `credentialRef`、连接测试、Secret 脱敏和失败分类。
- AG-01：落地 AgentSpec v1 JSON Schema、Java DTO、校验器和 modelRef。
- 增加 Model 与 AgentSpec/Worker 的绑定表和绑定查询。
- 补齐 OpenAPI、权限矩阵、契约测试和迁移回滚说明。

当前状态：目录、AgentSpec 基础、配置 ACK/重试、项目 IAM 基线和 Dashboard Summary 已落地；真实 Worker 回滚、Secret Manager 和全资源授权仍需继续。

**出口条件**：创建 Provider → 创建 Model → 连接测试 → 创建 AgentSpec → 发布 revision 的链路可通过自动化测试。

### 阶段 P1：Worker/Team 配置发布（当前主线）

- 将 AgentSpec 映射到现有 Worker CRD 和 Operator。
- 增加 revision、ACK、失败重试、回滚和优雅下线。
- 完善 Worker/Team 管理 API、成员、Leader、文件和配置引用。
- 增加 Worker 重启、Gateway 断线、NATS 重放、重复事件和旧 revision 拒绝测试。
- 将当前 Control Plane 的绑定状态/重试 API 接到 Worker 实际回滚执行，并补充 Prometheus 指标和告警。

**出口条件**：一个新 Worker 可通过 AgentSpec 完成注册、模型配置同步、任务执行、升级和回滚。

### 阶段 P2：IAM、租户隔离与审计（基线已落地，继续扩展）

- Keycloak subject 与本地用户/项目成员映射。
- OWNER/ADMIN/OPERATOR/DEVELOPER/VIEWER 角色和资源级鉴权。
- 变更前后 revision 审计、Secret 操作审计和查询 API。
- 租户隔离集成测试，覆盖越权读取、越权发布和跨项目事件。
- 将项目角色校验接入 Model、Worker、Team、Skill、MCP、Task 和 Usage 全部资源端点。

### 阶段 P3：Skill Registry（注册基线已落地，继续完善）

- Skill 导入、digest、manifest、版本和生命周期。
- 安全校验、审核/发布、可见性、启停和绑定。
- Worker 下载/挂载 Skill 的受控流程和失败回滚。
- 接入真实包存储、恶意内容扫描、审核人/审核时间和 Worker/Team 绑定。

### 阶段 P4：MCP Registry 与连接安全（策略基线已落地，继续完善）

- MCP Server CRUD、认证引用、传输适配和健康检查。
- 工具发现、allowlist、出站域名策略、超时/限流/熔断。
- MCP 工具调用审计、OTel span 和失败重试测试。
- 接入真实连接器、工具发现缓存、探针分类、限流/熔断和策略拒绝指标。

### 阶段 P5：使用分析与告警（Summary API 已落地，继续扩展）

- 统一 usage event 和 model call usage 记录。
- PostgreSQL 小规模聚合表、查询 API、Grafana Dashboard。
- Worker/Task/Team/Model/Tool 维度、时间范围和 Token/估算成本。
- Prometheus 告警与审计事件关联。
- 增加 Worker/Task/Team/Tool 聚合、估算成本/配额和可直接导入的告警规则。

### 阶段 P6：模板、渠道和商业扩展

- Worker Template Registry。
- Channel SPI、DingTalk/Webhook 等接入。
- 配额、成本预算、跨区域、云实例和账单扩展。
- Web Console 和 SDK。

## 7. 并行开发拆分

| 轨道 | 任务 | 依赖 | 交付物 |
|---|---|---|---|
| A | Model Catalog + AgentSpec | 当前 `7244582` | Schema、绑定、连接测试、发布 API |
| B | Worker/Team Revision | A 的 AgentSpec 引用 | CRD/Operator/Gateway/Worker ACK |
| C | IAM + Audit | OIDC/Keycloak 基础 | 成员、角色、租户隔离、审计 API |
| D | Skill Registry | A 的 `skillRefs` | 包、版本、审核、发布和绑定 |
| E | MCP Registry | A 的 `mcpServerRefs` | 连接器、Secret 引用、策略、审计 |
| F | Usage/Dashboard | 现有 OTel/Prometheus | 事件、聚合 API、Grafana、告警 |
| G | Template/Channel/SDK | A/B/C 稳定后 | 可选商业扩展 |

关键路径为 **A → B → Worker 配置发布 → 使用分析闭环**。C、D、E、F 可以并行，但 D/E 的最终绑定协议必须遵循 AgentSpec；F 不应等待前端控制台完成。

## 8. 验收与质量门禁

### 功能验收

- Provider/Model 创建、连接测试、启停、更新、删除依赖检查和幂等。
- AgentSpec 引用校验、revision 发布、Worker ACK、失败重试和回滚。
- Worker/Team 成员和角色隔离；越权请求返回稳定的 `403`。
- Skill 安全导入、版本发布、停用、绑定和回滚。
- MCP 健康检查、认证引用、工具白名单、出站拒绝和调用审计。
- Dashboard 数据能与模型调用审计、任务记录和 OTel 指标对账。

### 稳定性验收

- NATS 断线重连后 durable consumer 可恢复，不重复执行已成功任务。
- Worker 重启、Gateway 断线、Outbox 重试、重复配置事件和旧 revision 都有自动化测试。
- Provider 超时、限流、5xx、无效响应和凭证轮换可诊断、可重试或可降级。
- CI 至少包含 API 契约、数据库迁移、Operator/Kind、OIDC、NATS、OTel 和敏感信息扫描。

### 安全验收

- Secret、JWT、API Key 不进入 API 响应、事件、日志、Span 属性和诊断 artifact。
- Prompt/Response 默认不持久化；日志只保留脱敏摘要或哈希。
- 所有资源读写执行 tenant/project/role 校验。
- Skill/MCP 作为不可信输入处理，具备包校验、域名限制、超时、限流和审计。

## 9. 关键决策与风险

1. **先补管理面，再扩渠道。** 当前任务执行、Gateway、Outbox、Operator 和 OTel 基础已较完整，最大的商业版差距是资源管理、配置治理和使用分析。
2. **以 AgentSpec 作为统一配置协议。** 不直接把 Provider、Skill、MCP 字段继续散落到多个 CRD 和服务配置中。
3. **Keycloak 只负责身份认证，本地 Control Plane 负责资源授权。** 这样可以保留现有本地 Keycloak，同时支持项目级资源隔离。
4. **Secret 使用引用，不复制明文。** 通过 Secret Provider 抽象兼容 Kubernetes Secret、External Secrets 和企业密钥服务。
5. **Dashboard 先 API + Grafana，后做 Console。** 先验证数据口径和指标闭环，避免先投入前端而反复修改统计模型。
6. **成本先做 estimated cost。** 不把本地模型或第三方 Provider 的估算值混同于阿里云最终账单。
7. **云厂商专属能力不阻塞核心路线。** DingTalk、实例生命周期、VPC 和计费放在 P2，除非产品明确要求阿里云托管环境兼容。

## 10. 推荐的下一步

下一项应执行 **P1-B：Worker 实际回滚与全资源授权收口**，并行推进 MCP/Skill 真实运行时安全：

1. 将配置绑定重试从“重发 Outbox 事件”接到 Worker 实际回滚/稳定版本选择，增加 observed revision 和失败原因。
2. 把项目角色校验覆盖到 Model、AgentSpec、Worker、Team、Skill、MCP、Task、Usage 和 Audit API。
3. 为 Provider 接入可插拔真实连接探针和 Secret Resolver，保留当前默认的 validation-only 安全模式。
4. 为 MCP 接入真实工具发现/健康探针、限流熔断和调用审计；为 Skill 接入包存储与安全扫描器。
5. 扩展 Dashboard 到 Worker/Task/Team/Tool、成本/配额与 Prometheus 告警规则。

这样可以优先把已具备的管理面能力变成可运行、可回滚、可审计的商业版闭环，并避免在真实 Secret/外部连接尚未具备时误发网络请求。
