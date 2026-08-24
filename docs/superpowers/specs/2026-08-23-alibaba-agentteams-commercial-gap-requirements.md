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

前一轮第二波已提交；本轮第三波正在收口，目标是把基础治理接入实际生命周期：

- **Worker 配置治理**：增加绑定状态查询、最新 revision 保护、失败配置重试，以及 ACK 幂等处理。
- **Model Provider/Model 治理**：增加启停、删除依赖检查、连接测试结果分类和模型/Provider 依赖索引；默认连接探针只做配置校验，不会误发真实模型请求。
- **项目级 IAM 基线**：Keycloak/OIDC 提供身份和租户上下文，Control Plane 持久化项目、成员和 OWNER/ADMIN/OPERATOR/DEVELOPER/VIEWER 角色，并提供幂等成员管理。
- **MCP/Skill 安全基线**：MCP 出站 scheme/domain/tool/timeout 策略、失败分类，以及 Skill 版本安全扫描和审核字段。
- **Dashboard**：提供 `GET /api/v1/dashboard/summary`，复用 Usage 查询口径输出模型调用、Token、成功率和延迟摘要。

本轮第三波已实现：

- **配置回滚**：`POST /api/v1/config/bindings/{bindingId}/rollback` 选择最近一次稳定的 `APPLIED` revision，更新期望态并发出幂等配置事件。
- **Skill 安全门禁**：版本持久化 `securityScanStatus/reviewStatus`，发布前执行可插拔扫描器并要求 `APPROVED`；增加审核 API 和安全状态返回。
- **MCP 运行时策略入口**：连接器执行前统一检查启用状态、健康状态、endpoint、超时和工具 allowlist。

前一轮加本轮改动已通过干净全量测试；当前回归口径以本文第 10 节最新结果为准。

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
| Model Provider/Model | **部分具备**。已有目录、启停/删除依赖检查、配置型/真实 HTTP 连接测试、项目级价格目录和 AgentSpec 引用校验 | 缺少 Secret Manager、能力目录和 Worker 侧生效确认 | P0 |
| Worker 生命周期 | **部分具备**。有 Agent 注册 API、Worker CRD/Operator、Gateway 注册/租约/重放和 mTLS 基础；配置绑定支持状态、ACK 幂等和失败重试 | 缺少 Worker 实际回滚执行、优雅下线和完整 Worker/Team 管理 API | P0 |
| Worker Team | **部分具备**。已有 Team CRD、同步、调度和策略基础 | 缺少成员/管理员模型、Leader 配置、Team 级模型/文件/Skill/MCP 绑定及版本化发布 | P0 |
| AgentSpec | **部分具备**。已有 AgentSpec v1、Schema 校验、Model/Skill/MCP 真实目录适配、配置修订和 Worker ACK | 缺少 Worker 实际回滚执行、Team 级绑定和完整模板化生命周期 | P0 |
| 用户/租户/项目/RBAC | **部分具备**。已有 OIDC/Keycloak、JWT scope、项目成员/角色表和租户隔离基线 | 缺少角色覆盖全部资源、成员禁用/邀请、跨资源授权过滤和完整成员审计 API | P0 |
| Skill 管理 | **部分具备**。已有 Registry、digest/manifest/版本校验、包存储、ZIP/tar 内容扫描、审核门禁和状态 API | 缺少外部沙箱扫描、可见性管理和 Worker/Team 绑定 | P1 |
| MCP Server 管理 | **部分具备**。已有 Registry、认证引用、HTTP/SSE/Streamable HTTP、工具发现缓存、健康探针、限流/熔断、出站策略和低基数审计指标 | 缺少 Worker/Team 绑定、集中告警规则和跨实例状态汇总 | P1 |
| Dashboard/使用分析 | **部分具备**。已有 Micrometer、OTel、Prometheus/Grafana、Usage API、Dashboard Summary、成本和 MCP/配额运行指标 | 缺少 Worker/Task/Team/Tool 全维度历史聚合、预算告警和统一大盘 | P1 |
| 审计与安全治理 | **部分具备**。已有模型调用审计、敏感信息脱敏、OIDC/mTLS/RBAC 基础 | 缺少配置变更审计、Skill/MCP 操作审计、Secret 轮换、审批、出站策略和完整合规事件 | P1 |
| Worker 模板 | **缺失** | 缺少可复用模板、版本、审批、实例化和升级策略 | P1 |
| 渠道接入 | **部分具备**。当前已有 Matrix/Tuwunel 方向 | DingTalk 等商业渠道未接入；需要统一 Channel SPI 和异步投递语义 | P2 |
| 配额、成本、计费 | **部分具备**。已有项目配额持久化、Manager/Runtime admission、成本估算和 quota protobuf/gRPC 传输边界 | 缺少 Worker 客户端、跨进程 Control Plane 适配、日调用/Token 端到端验收、预算告警；真实账单属于云厂商扩展 | P1 |
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

当前状态：目录、AgentSpec 基础、配置 ACK/重试、项目 IAM 基线和 Dashboard Summary 已落地；本轮补齐了配置 observed revision/失败分类、AgentSpec tenant/project 归属、Model/MCP/Skill 资源归属、Provider credentialRef 校验和 Kind 回滚验收接入。Secret Manager、真实 admission 组装和全资源跨项目集成验证仍需继续。

**出口条件**：创建 Provider → 创建 Model → 连接测试 → 创建 AgentSpec → 发布 revision 的链路可通过自动化测试。

### 阶段 P1：Worker/Team 配置发布（当前主线）

- 将 AgentSpec 映射到现有 Worker CRD 和 Operator。
- 增加 revision、ACK、失败重试、回滚和优雅下线。
- 完善 Worker/Team 管理 API、成员、Leader、文件和配置引用。
- 增加 Worker 重启、Gateway 断线、NATS 重放、重复事件和旧 revision 拒绝测试。
- 将当前 Control Plane 的绑定状态/重试 API 接到 Worker 实际回滚执行，并补充 Prometheus 指标和告警。
- 已增加稳定 revision 选择和回滚事件，持久化 Worker ACK 的 observed revision、失败分类和 `rollback` 标记，并增加回滚完成/失败指标；已接入 Kind 中真实 Worker 的双 revision 回滚验收脚本，待新鲜 CI 集群运行确认。

**出口条件**：一个新 Worker 可通过 AgentSpec 完成注册、模型配置同步、任务执行、升级和回滚。

### 阶段 P2：IAM、租户隔离与审计（基线已落地，继续扩展）

- Keycloak subject 与本地用户/项目成员映射。
- OWNER/ADMIN/OPERATOR/DEVELOPER/VIEWER 角色和资源级鉴权。
- 变更前后 revision 审计、Secret 操作审计和查询 API。
- 租户隔离集成测试，覆盖越权读取、越权发布和跨项目事件。
- AgentSpec 已按认证主体写入并校验 tenant/project 归属；Model Provider/Model、MCP Server、Skill、Worker、Team、Task、Artifact 已通过统一 `resource_scopes` 表实现项目可见性；Usage/Audit 查询按 `tenant_id/project_id` 过滤，仍需补齐跨项目集成矩阵和历史数据迁移策略。

### 阶段 P3：Skill Registry（注册基线已落地，继续完善）

- Skill 导入、digest、manifest、版本和生命周期。
- 安全校验、审核/发布、可见性、启停和绑定。
- Worker 下载/挂载 Skill 的受控流程和失败回滚。
- 已接入 Skill 版本包的对象存储元数据、预签名上传/下载、服务端 SHA-256/大小校验和发布前完成状态约束；仍需接入真实恶意内容扫描和 Worker/Team 绑定。

### 阶段 P4：MCP Registry 与连接安全（策略基线已落地，继续完善）

- MCP Server CRUD、认证引用、传输适配和健康检查。
- 工具发现、allowlist、出站域名策略、超时/限流/熔断。
- MCP 工具调用审计、OTel span 和失败重试测试。
- 已接入 MCP 策略放行/拒绝指标和 opt-in 的 HTTP/SSE/Streamable HTTP 连接器基础，覆盖 allowlist、超时、重定向拒绝、tools/list schema 和错误分类；仍需接入连接器注册、发现缓存、限流/熔断和调用审计。

### 阶段 P5：使用分析与告警（Summary API 已落地，继续扩展）

- 统一 usage event 和 model call usage 记录。
- PostgreSQL 小规模聚合表、查询 API、Grafana Dashboard。
- Worker/Task/Team/Model/Tool 维度、时间范围和 Token/估算成本。
- Prometheus 告警与审计事件关联。
- 已补齐 MCP/Skill 基础治理指标、配置 apply/rollback 告警、项目级并发/日调用/Token 配额基础，以及 Usage/Dashboard 成本字段；Manager 增加可插拔 model-call admission port、项目范围审计字段和独立价格/估算成本计算契约，Control Plane 已增加项目级价格目录持久化；仍需接入真实 Worker 组装链、Manager 成本审计和 Worker/Task/Team/Tool 聚合。

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

## 10. 本轮进展与推荐的下一步

本轮按 A/B 两条独立轨道完成并集成：

- 配置 ACK 持久化 `observed_version` 和 `failure_code`，绑定状态 API 直接返回观测 revision 与失败分类；新增 V27 迁移。
- AgentSpec 增加 tenant/project 归属，认证请求按项目过滤资源，部署 manifest 统一输出 `scope`；新增 V28 迁移。
- 新增通用 `resource_scopes` 归属表（V29），Model Provider/Model、MCP Server、Skill 创建时绑定认证主体的 tenant/project/team，读取、更新和删除执行可见性校验。
- Provider 的 `credentialRef` 仅允许 Secret 引用格式，拒绝 inline API Key/密码；新增 `SecretResolver` SPI，默认 validation-only，不读取明文 Secret；配置 apply ACK、失败和 rollback 请求增加 Prometheus 计数器，并新增 apply/rollback 告警。
- Worker/Team 创建与操作复用 `resource_scopes` 做项目隔离；新增项目配额策略、原子 acquire/release、429 错误和配额指标，配额迁移为 V31；V32 增加 Usage/Audit 项目范围和模型调用成本字段。
- MCP 策略放行/拒绝、Skill 安全扫描和审核结果接入 Control Plane Micrometer 指标。
- Task/Artifact 创建、读取和下载 URL 已接入项目隔离，跨项目访问返回稳定 `403`；Usage/Audit SQL 按项目范围过滤，model-call audit 增加 `tenant_id/project_id/cost_usd`，Dashboard/Usage 返回成本字段；新增 V32 迁移。
- Manager 在真实 Provider 调用前接入可插拔 `ModelCallAdmission`，配额拒绝短路 Provider，所有退出路径 finally 释放幂等 lease；ToolContext 和 audit 支持项目范围。
- Kind recovery 已加入双 revision 配置回滚验收（成功/失败轮询与 `rollback=true` 检查），并纳入 manifest validator/CI。
- 从空库执行 34 个 Flyway migration；本轮 Control Plane/Manager/Worker 及依赖模块全量回归通过，当前报告共 **393** 个测试，失败 0、错误 0；Helm lint 通过。Kind Python 脚本仍需由 CI/Kind 集群执行确认。

本轮实现仍保持旧构造器和未认证内部调用兼容；认证请求对无归属的历史资源默认不可见，避免把遗留全局数据误暴露给项目用户。并行测试期间出现过一次 target 目录竞争，已在所有 agent 停止写入后通过干净全量回归排除。

上一轮并行实现已完成：

- AgentSpec 增加 JSON 结构校验：限制大小、校验 `skillRefs/mcpRefs` 为唯一字符串引用，并校验 `permissions/resources` 对象结构。
- Worker/Runtime 增加 `RuntimeModelCallAdmission` 适配端口和可关闭的本地并发 admission；QwenPaw 真实提交前 admission，拒绝短路模型调用，完成/取消/停止均释放幂等 lease。Manager 的 `ModelCallAdmission` 仍可由上层远程适配器接入。
- SecretResolver 增加显式选择的 Kubernetes Secret 和 External Secrets 边界：Kubernetes 只返回状态、不返回 Secret 明文，支持 allowlist、超时和异常分类；默认仍为 validation-only。
- MCP 增加不携带 `credentialRef` 的 connector SPI、工具发现/调用门面、策略前置、超时取消、结果分类和脱敏审计；默认 connector 不建立假连接。
- Usage/Audit 增加认证项目范围测试；结构化 Kubernetes 引用 `k8s://namespace/name#key` 已纳入凭证引用校验。
- 统一回归：`control-plane`、`manager`、`agent-worker` 及依赖模块全量测试通过；本轮新增能力定向测试通过；空库 Flyway 33 个迁移通过；Helm lint 和 `git diff --check` 通过。OTel 测试中的本地无 Collector 503 仅为非阻断日志。

本轮并行实现已完成：

- Manager `QuotaPort` 和 Control Plane `ProjectQuotaPortAdapter` 已把 tenant/project 作用域透传到模型调用 admission；配额拒绝转换为稳定的 admission 拒绝，lease 幂等释放。
- Skill 版本包已具备 V33 元数据、固定对象存储路径、预签名上传/下载、服务端完成上传时的大小与 SHA-256 校验，以及发布前 `COMPLETED` 约束。
- Provider 连接测试增加显式启用的真实 HTTP 探针；默认仍 validation-only，具备 scheme/host allowlist、超时、禁止重定向、401/403/429/5xx/网络错误分类，且不发送 credential 明文。
- AgentSpec 部署到 Worker 前增加调用方项目可见性校验；AgentSpec JSON 结构校验已覆盖 `skillRefs/mcpRefs` 和权限/资源对象。
- 回归补充了配额适配、Skill 包存储、Provider 探针和跨项目部署拒绝测试；空库 Flyway 已验证到 V33。
- AgentSpec 发布阶段增加 `modelRef/skillRefs/mcpRefs` 的可插拔解析与校验端口，稳定返回引用不存在、项目不可见和生命周期不可用分类；无目录适配时保持默认兼容。
- MCP 增加 opt-in HTTP/SSE/Streamable HTTP connector 基础，严格 endpoint/host allowlist，禁止自动重定向和 credential 明文，`tools/list` 采用 fail-closed schema 校验。
- Manager 增加 provider/model/currency 价格目录、BigDecimal token 成本估算和 `UNPRICED` 结果，明确估算值不等同于最终账单。
- 修复 MCP tools/list 传输层 envelope 与 result 校验边界；该阶段最终全量回归 **355/355** 通过。
- Runtime/Worker 增加可选 tenant/project admission scope、可注入 `RuntimeQuotaPort` 和项目配额 lease；无远程适配器时继续使用本地并发 admission，拒绝与释放路径有测试覆盖。
- MCP 增加确定性的 `McpTransportConnectorRegistry`，按 transport 选择 connector、拒绝重复注册，HTTP connector 启用后不依赖 Spring 注入顺序；MCP 定向测试 33 个通过。
- Control Plane 增加 V34 项目级 Model Provider/Model 价格目录，支持输入/输出 token 价格、effective 时间、DRAFT/ACTIVE/RETIRED 生命周期、乐观版本、幂等写入和查询端口；价格目录定向测试通过，空库 Flyway 已验证到 V34。
- 增加 `ScopeIsolationMatrixTest`，覆盖 Worker、Team、Model、MCP、Skill、Task、Artifact、Usage、Audit 等资源的 tenant/project/team 三轴漂移拒绝契约。
- 当前整合回归为 **407/407** 通过（失败 0、错误 0），Helm lint 和 `git diff --check` 通过；Docker 已恢复并完成 Testcontainers 迁移验证。

推荐下一轮按以下依赖并行推进：

1. **P0 远程配额端到端接线**：当前已有 Manager port、Control Plane adapter 和 Worker 本地 admission；下一步接入真实 Manager/Worker 组装链，透传 tenant/project，并增加项目级日调用/Token 配额的 Kind 验收。
2. **P1 真实模型治理闭环**：连接探针和项目级价格目录已完成；下一步把 `ModelPriceCatalogPort` 接入 Manager 成本估算/调用审计，并补 Secret 轮换后的重新连接测试。
3. **P1 MCP 生产化接线**：HTTP/SSE/Streamable HTTP connector 和 registry/selector 已完成；下一步接入健康探针、发现缓存、限流/熔断，并把 connector 调用纳入 Worker/AgentSpec 绑定闭环。
4. **P1 Skill/AgentSpec 绑定闭环**：Skill 包存储和 AgentSpec 引用校验基础已完成；下一步接入真实 Skill/MCP/Model 目录适配、恶意内容扫描器和 Worker/Team 绑定。
5. **P1 跨项目集成矩阵**：覆盖 Worker/Team/Model/MCP/Skill/Task/Artifact/Usage/Audit，明确历史无归属数据的迁移、隔离和清理策略。
6. **P2 控制台与扩展能力**：Dashboard/Console、模板、渠道、SDK、云厂商专属实例与账单能力。

### 当前并行执行批次（2026-08-23）

本批次按不重叠写入范围拆分，目标是把已经存在的 SPI/契约推进到可运行的治理边界：

| 轨道 | 交付目标 | 依赖 | 出口条件 |
|---|---|---|---|
| A | Runtime/Worker 项目配额上下文和可注入 admission 边界 | Runtime admission、Worker 配置同步 | tenant/project 可透传；无远程适配器时保持本地并发兼容；拒绝和释放有单测 |
| B | Model Provider/Model 价格目录持久化 | Model Catalog、resource scope、Flyway V34 | 价格按项目隔离、可版本化查询；空库迁移和 API/服务测试通过 |
| C | MCP connector registry/selector | MCP transport SPI、HTTP connector | 传输类型唯一选择、拒绝重复注册、HTTP opt-in 不依赖注入顺序 |
| D | 跨项目资源隔离契约矩阵 | 统一 `resource_scopes` 和 `AuthorizationService` | Worker/Team/Model/MCP/Skill/Task/Artifact/Usage/Audit 的三轴 scope 漂移均被拒绝 |

本批次不把本地 admission 冒充成远程配额服务，也不把估算成本冒充成最终账单。完成后下一关键路径是：

```text
价格目录持久化 ─┐
                 ├─> Manager 成本/审计接线 ─> Usage/Dashboard 成本闭环
运行时配额边界 ──┘

MCP registry ─> connector 健康/缓存/限流 ─> Worker/AgentSpec 工具绑定
```

### 当前并行执行批次（2026-08-23）

本批次已完成，结果如下：

- Manager 已将可选价格目录接入成功模型调用的 `estimated cost` 审计；无价格不阻断调用，失败调用标记 `NOT_APPLICABLE`，旧构造器保持兼容。
- MCP 已增加 5 分钟/256 条默认配置的有界 LRU tools/list 缓存和健康探针；缓存失败不污染，探针只返回状态、分类和延迟。
- MCP 运行时已增加按 server 的并发租约、限流、熔断和半开恢复；tools/list 缓存键升级为 `serverId + version`，服务更新或停用后不会复用旧结果。
- Skill 已增加默认关闭、可显式启用的确定性安全扫描器，覆盖危险执行字段、明文凭证、路径穿越、外部脚本/不可信 URL 和 malformed JSON，并输出低基数脱敏结果。
- AgentSpec 已增加带 tenant/project/team scope 的 Model/Skill/MCP 目录适配器组合器；未配置适配器明确返回可诊断的引用不存在结果，不读取 Secret。
- contracts 已新增 `quota.proto` v1，Gateway 已提供独立 Quota gRPC 服务和应用层 `QuotaReservationPort`；当前仍缺 Worker 客户端和 Control Plane 跨进程后端适配。
- 本批次整合回归共 **554/554** 通过（contracts 15、application-contracts 2、control-plane 340、agent-gateway 65、runtime 48、agent-worker 17、manager 41、domain 26），失败 0、错误 0、跳过 0；Flyway V34、Helm lint 和 `git diff --check` 通过。

下一批次按依赖和优先级调整为：

| 轨道 | 任务 | 依赖 | 交付出口 |
|---|---|---|---|
| P0-A | Worker/Manager 远程配额客户端与 Control Plane 后端接线 | `quota.proto`、Quota gRPC、RuntimeQuotaPort、Manager QuotaPort | 基础客户端/后端已完成；真实 Worker/Manager 组装和跨实例 durable reservation 仍待补齐 |
| P1-B | 配额幂等持久化与 Kind 端到端验收 | project quota policy、Outbox/Gateway/Worker、Kind | 已新增 `scripts/run-kind-quota-recovery.py`，覆盖 acquire/release 重试、超时/拒绝码及 tenant/project 隔离；Java 持久化实现和 CI 接入另行推进 |
| P1-C | Skill/MCP Worker/Team 绑定与版本发布 | AgentSpec、Team、Worker 配置同步 | AgentSpec 发布绑定已完成；Worker 运行时加载与 ACK 验收仍待补齐 |
| P1-D | 外部 Skill 沙箱扫描与审批集成 | 确定性扫描器、扫描服务 SPI、审核状态 | SPI、超时/失败分类和默认关闭已完成；具体企业沙箱/审批回调仍待接入 |
| P1-E | Dashboard 历史聚合与告警规则 | Usage/Audit、Prometheus、MCP/配额指标 | hour/day 历史聚合和只读告警评估已完成；多维审计数据源与持久化规则仍待补齐 |

其中 P0-A 的基础接线已完成，下一条关键路径是生产组装与跨实例持久化；P1-B 至 P1-E 的基础能力已并行落地，后续转入真实部署验收和数据源闭环。远程配额继续使用已落地的 protobuf/gRPC 契约，不再另起临时 HTTP API。

### P1-B Kind 配额验收脚本（2026-08-23）

已新增 `scripts/run-kind-quota-recovery.py` 和共享辅助文件
`scripts/kind_test_support.py`。脚本只依赖 Python 标准库、`kubectl` 和本地
`grpcurl`，不修改 Java、Maven 或 CI workflow；运行时通过两个独立的
`kubectl port-forward` 连接 Control Plane HTTP 与 Gateway gRPC。

验收使用每次运行唯一的 project 后缀，覆盖以下稳定摘要：

- `KIND_QUOTA_ACQUIRE_IDEMPOTENCY_OK`：同一 acquire 幂等键重试返回相同 reservation，current/daily/token 只增加一次。
- `KIND_QUOTA_RELEASE_IDEMPOTENCY_OK` 与 `KIND_QUOTA_RELEASE_RETRY_OK`：release 同键重试以及新传输键重试均不重复扣减并发计数。
- `KIND_QUOTA_TIMEOUT_OK`：acquire/release 过期 deadline 返回 `DEADLINE_EXCEEDED`，计数不变化。
- `KIND_QUOTA_REJECTION_OK`：并发超限返回稳定 `CONCURRENT_CALLS` 拒绝维度，计数不变化。
- `KIND_QUOTA_SCOPE_ISOLATION_OK`：跨 project、跨 tenant 释放均返回 `RESERVATION_NOT_FOUND`，各 scope 计数互不影响。
- `KIND_QUOTA_OK`：整体验收摘要。

示例：

```bash
python3 scripts/run-kind-quota-recovery.py
```

脚本默认使用 `grpcurl -plaintext` 访问
`agentteams-agentteams-java-gateway:9090`；可通过 `GRPCURL_BIN`、
`--grpcurl`、`--gateway-tls` 和 `--restart-control-plane` 调整。开启
`--restart-control-plane` 后，脚本会在第一次 acquire 后重启 Control Plane，
再重试同一幂等键，用于验收 reservation/幂等状态已经落在持久化边界，而不是
只存在进程内存中。当前脚本不加入 CI，待 P0-A 的 Gateway/Worker/Control Plane
适配和持久化 reservation 实现稳定后，再作为独立 Kind recovery 步骤接入。

这样下一步优先解决“项目配额真正约束实际模型调用”这一闭环，再推进真实目录接线和安全/监控闭环；validation-only、Skill scanner 默认关闭和 MCP connector opt-in 策略继续避免在未配置外部依赖时误发网络请求。

### 当前批次收口状态（2026-08-23）

上一节的“下一批次”已开始实施，状态更新如下：

| 轨道 | 当前状态 | 已交付 | 仍需补齐 |
|---|---|---|---|
| P0-A 远程配额 | 基础接线完成 | Runtime/Manager gRPC quota client、Gateway QuotaService、Control Plane reservation adapter、超时/拒绝/trace/tenant-project 透传 | 将真实 Worker/Manager 生产组装链绑定到远程 port；reservation/idempotency 做跨实例持久化 |
| P1-B Kind 配额验收 | 脚本完成 | `run-kind-quota-recovery.py` 覆盖 acquire/release 重试、deadline、并发拒绝、跨 project/tenant 隔离及可选 Control Plane 重启 | 在稳定的跨进程持久化实现后接入 CI recovery job；当前需要 Kind、grpcurl 环境执行 |
| P1-C Skill/MCP/Model 绑定 | 已完成基础闭环 | AgentSpec 发布 manifest 生成稳定排序的 `resourceBindings`，包含 worker/team/scope/revision/digest；跨项目资源拒绝 | Worker 真实运行时加载与 ACK 仍需 Kind 验收 |
| P1-D 外部 Skill 扫描 | SPI 完成，默认关闭 | 本地扫描先行、外部扫描可插拔、超时/不可用/非法响应/归档大小分类，供应商详情不外泄 | 接入具体企业沙箱客户端和审批回调；不在默认配置中开启 |
| P1-E Dashboard | API 基础完成 | 历史 hour/day 时间桶、项目范围复用、错误率/延迟/成本只读告警评估端点 | Worker/Task/Team/Tool/配额维度的审计数据源和持久化告警规则 |

本批次串行收口验证：`control-plane` 全量测试通过；contracts、application-contracts、agent-gateway、runtime、agent-worker、manager、domain 合计 **559** 个测试通过，失败 0、错误 0、跳过 0。Docker/Testcontainers 可用；本机未安装 Python，因此 Kind Python 脚本仅保留已完成的静态检查记录，未在本机执行真实 Kind 验收。

下一条推荐关键路径是：

1. **P0**：把 `GrpcRuntimeQuotaPort`/`GrpcQuotaPort` 接入真实 Worker/Manager 进程配置，并把 reservation/idempotency 从进程内 map 迁移到数据库表或带唯一约束的 durable repository。

### 2026-08-24 远程配额生产组装收口

P0-A 的“真实 Worker/Manager 组装”已完成本轮接线：Manager smoke 的 DeepSeek
Provider 调用现在经过项目级 admission；Kind recovery 创建的真实 QwenPaw Worker
默认开启远程 quota，并通过 `scripts/run-kind-worker-quota-admission.py` 验证
Control Plane 持久化的日调用数、日 Token 数和并发回收。

Kind 验收任务会创建只包含目标 Agent 的临时 Team，确保结果归属于本轮真实 Worker；
本地验证结果为 `daily_calls_delta=1`、`daily_tokens_delta=1024`、
`current_concurrent_calls=0`。因此 P0-A/P1-B 的基础生产组装和 Kind 端到端出口已
达到本轮目标；真实 Provider 凭据、Secret Manager、预算告警、最终账单和更完整的
Worker/Task/Team/Tool 历史聚合仍属于后续需求。
2. **P1**：在同一条真实部署链上执行 `scripts/run-kind-quota-recovery.py`，再把它作为 Kind recovery 的非并行长耗时步骤接入 CI。
3. **P1**：让 Worker 根据 `resourceBindings` 加载固定 revision/digest，并回传 ACK/失败分类；随后补 Dashboard 的 Worker/Task/Team/Tool 维度审计聚合。
4. **P1**：实现一个具体的企业沙箱 client adapter 和审批回调；保持扫描 SPI 与默认关闭策略不变。

### 本轮并行开发收口（2026-08-23）

本轮按依赖拆分并完成了四条轨道：

| 优先级 | 交付 | 实现要点 | 验证 |
|---|---|---|---|
| P0 | 配额 reservation/idempotency 持久化 | 新增 V35 `quota_reservations` 与 `quota_reservation_releases`；数据库行锁保护幂等 claim，状态为 PENDING/ACQUIRED/RELEASED；Control Plane 生产构造器使用 JDBC，旧单测构造器保留内存兼容 | Flyway 35 migrations；Control Plane 全量通过 |
| P1 | Worker 绑定加载与 ACK | `resourceBindings` 逐项校验 type/reference/revision/digest；失败在 staging 前阻断，复用 `ConfigApplied(applied=false,errorMessage)` 回传稳定错误码；旧 manifest 兼容 | Worker 22/22 通过 |
| P1 | Dashboard 多维聚合与告警边界 | 支持 worker/task/team/tool/quota 分组、unknown 回退、成本与 hour/day 时间序列；告警规则抽象为可替换 repository，默认线程安全内存实现，项目/team scope 查询安全 | Control Plane 全量通过 |
| P1 | Skill 审批回调边界 | `SkillScanApprovalPort` 只传安全元数据；默认实现 fail-closed 返回 PENDING；REVIEW_REQUIRED 不会绕过审批，外部扫描仍默认关闭 | Skill 相关 36 个通过 |

本轮关联模块回归共 **575** 个测试通过，失败 0、错误 0、跳过 0；Control Plane Flyway、Helm lint、`git diff --check` 均通过。

剩余关键路径已收敛为：

1. 将远程配额 port 接入真实 Worker/Manager 部署配置，并在 Kind 中执行 quota recovery；
2. 为 Dashboard 告警规则增加数据库 repository/migration，并补齐 Worker/Task/Team/Tool/Quota 的真实审计字段（当前缺失字段安全回退 unknown）；
3. 接入具体企业沙箱 client 与审批系统回调；
4. 完成 AgentSpec 绑定在真实 Worker 集群中的 revision/digest ACK 验收后，再将长耗时 Kind recovery 步骤纳入 CI。

### 下一轮并行开发收口（2026-08-23）

本轮按依赖完成了生产接线、持久化告警和 CI 验收增强：

| 优先级 | 交付 | 实现要点 | 状态 |
|---|---|---|---|
| P0 | Worker/Manager 远程配额生产组装 | Worker 支持 `AGENTTEAMS_QUOTA_REMOTE_ENABLED`、tenant/project scope 和 `AGENTTEAMS_QUOTA_TIMEOUT_SECONDS`；Manager 新增 `ManagerQuotaPortFactory`，默认保持 noop，显式开启后使用 gRPC quota port | 已完成，待 Kind 真环境验收 |
| P1 | Dashboard 告警规则持久化 | 新增 V36 `dashboard_alert_rules`，提供 JDBC repository、默认 FAILURE_RATE/AVERAGE_LATENCY/COST 规则；Spring Controller/Service 默认注入 JDBC，单测仍可使用内存实现 | 已完成 |
| P1 | Skill 企业沙箱适配边界 | 新增可选 HTTP sandbox client；默认关闭，禁止重定向、凭据、URL fragment，超时/不可用/非法结果归一为稳定错误分类并去除供应商详情 | 已完成，待接入具体供应商和审批回调 |
| P1 | Kind 配额 CI 验收 | CI 缓存并安装 grpcurl 1.9.3，执行 `run-kind-quota-recovery.py`，失败时输出 quota policy/reservation/release 诊断；沿用现有 PR/default/manual/weekly 触发条件 | 已接入，待 GitHub Actions 新鲜运行确认 |

本轮串行回归共 **590** 个测试通过，失败 0、错误 0、跳过 0；Flyway 已验证并执行到 V36，Helm lint 与 `git diff --check` 通过。工作区未执行提交或远程同步，保留给后续统一提交。

剩余任务按依赖和优先级收敛为：

1. **P0：真实 Kind 配额验收。** 在 GitHub Actions 或具备 Kind、Python、grpcurl 的环境执行 quota recovery，重点确认跨进程重启后 reservation/release 幂等状态仍由数据库保证。
2. **P1：Worker 绑定真实 ACK 验收。** 用 AgentSpec 发布的 revision/digest manifest 部署多 Worker，验证成功 ACK、缺失资源、digest/revision 不匹配和配置回滚路径。
3. **P1：Dashboard 真实维度数据闭环。** 将 Worker/Task/Team/Tool/Quota 维度写入 usage/audit 事件，消除当前 `unknown` 回退，再接入告警通知通道。
4. **P1：Skill 企业审批闭环。** 对接具体沙箱 API 与审批回调，保留 fail-closed、超时分类和供应商错误脱敏策略。
5. **P2：CI 运行成本治理。** 将长耗时 Kind recovery 保持为合并前/手动/定时验收，普通开发分支只运行快速 verify，减少配额和分钟数消耗。

### 本轮剩余功能并行收口（2026-08-23）

按依赖拆分后，本轮完成了以下三条互不冲突的开发轨道：

| 优先级 | 功能 | 实现 | 验收状态 |
|---|---|---|---|
| P1 | Worker resourceBindings ACK | 保持现有 `ConfigApplied` 协议，合法绑定正常应用，非法 type/reference/revision/digest 在 staging 前阻断并返回稳定 `RESOURCE_BINDING_INVALID`；新增 Kind 验收脚本覆盖 legacy、合法三类绑定和非法 revision | Java 测试通过；需在 Kind 实际运行脚本 |
| P1 | Dashboard 真实维度 | V37 为 model call audit 增加 worker/task/team/tool/quota 维度列；`ModelCallAudit` 和 JDBC auditor 写入安全运营标识，Usage 查询直接分组，缺失值回退 `unknown` | Flyway 与关联测试通过 |
| P1 | Skill 审批回调 | 新增默认关闭的 HTTP approval callback；仅发送 skill/version/classification/digest 安全元数据，超时、网络错误、非 2xx、非法响应均保持 `PENDING` | Skill 与回调边界测试通过；需接入具体审批系统 |
| P2 | CI 成本治理 | `kind-recovery`/`kind-oidc` 明确限制为 PR、默认分支 push、手动和 weekly schedule；普通分支 push 只运行 verify，保留长耗时验收步骤 | 条件静态校验通过 |

本轮串行回归共 **601** 个测试通过，失败 0、错误 0、跳过 0；Flyway 已验证至 V37，Helm lint 与 `git diff --check` 通过。当前工作区仍未提交或推送。

下一步按依赖排序：

1. **P0：执行真实 Kind 验收。** 先执行 quota recovery，再执行 resource-binding ACK，确认 Worker 重启、数据库持久化和失败状态都能被外部 API 观察到。
2. **P1：接入实际审计调用方。** 将 Worker/Task/Team/Tool/Quota 标识从 Manager/Runtime 的真实调用上下文传入 `ModelCallAudit.Dimensions`，避免只依赖 `unknown` 回退。
3. **当前版本不启用企业 Skill 审批/沙箱。** 保留已有 SPI、默认关闭和 fail-closed 边界，不接入具体供应商，也不纳入本轮验收。
4. **P2：根据新鲜 GitHub Actions 结果调优。** 只在 Kind recovery 实际稳定后调整超时、缓存和诊断，避免为节省分钟数而削弱验收覆盖。

### 本轮剩余任务继续推进（2026-08-23）

本轮继续完成了两个可直接落地的依赖项：

| 优先级 | 功能 | 实现 | 状态 |
|---|---|---|---|
| P1 | Manager 审计维度调用链 | `ToolContext` 增加 worker/task/team/tool/quota 维度；`ManagerSessionService` 在成功和失败审计中写入 `ModelCallAudit.Dimensions`，旧构造器保持兼容，`create_task` 作为工具维度安全回退 | 已完成，Manager 测试通过 |
| P0/P1 | Kind 验收编排 | CI 在 quota recovery 后继续执行 config rollback 与 resource binding ACK；ACK 脚本增加 URL/UUID 校验和规范 SHA-256 测试数据；Kind manifest validator 校验默认安全配置和步骤顺序 | 已接线，待 GitHub Actions 实际运行 |

当前仍需要外部环境完成的事项只有：

1. GitHub Actions 中执行 quota recovery、config rollback、resource binding ACK，确认真实 Worker 与数据库状态。
2. 使用真实任务上下文填充 task/team/quota 维度；当前 Manager 已支持透传，Control Plane/Worker 业务编排仍需提供这些标识。
3. 企业 Skill 审批/沙箱不属于当前版本范围，暂不配置、不接入、不纳入 CI 验收。

### 范围决策：企业 Skill 审批与沙箱（2026-08-23）

经项目范围确认，当前版本不需要启用企业级 Skill 审批或沙箱能力：

- 不接入具体企业审批系统、沙箱供应商或外部回调服务；
- 现有扫描 SPI、HTTP adapter 和 approval callback 保持可选实现，但默认关闭；
- 保留 fail-closed、超时分类和供应商错误脱敏等安全边界；
- 不为该能力新增部署配置、Kind 验收步骤或 CI 资源消耗；
- 后续若确有企业接入需求，再单独立项完成供应商适配和端到端验收。

### 本轮任务上下文闭环（2026-08-23）

已完成 Worker 侧真实运营维度的传递链路：

- `TaskAssigned` 协议以兼容的新增字段携带 tenant/project/team/tool/quota 维度；
- Gateway 从任务 `spec` 及 `scope` 推导维度，兼容旧 `KnownTaskFields` 调用；
- Worker `GatewayRuntimeAdapter` 将维度写入 `RuntimeTask.metadata`；
- Runtime 将真实 `taskId`、Worker 标识及任务维度写入 `RuntimeModelCallAdmissionRequest.dimensions`，供配额和审计适配器使用；
- 未提供的维度仍保持为空或安全回退，不把任务内容写入审计字段。

Gateway、Runtime、Worker 相关回归测试已通过。当前剩余事项收敛为真实 GitHub Actions Kind 结果确认，以及后续将 Runtime admission dimensions 接入具体生产审计持久化实现；企业 Skill 审批/沙箱仍不在当前范围内。

### Kind 验收收口状态（2026-08-24）

本轮已根据新鲜 GitHub Actions `kind-recovery` 结果完成真实集群确认：

- 配额 recovery 通过，覆盖 acquire/release 幂等重试、超时、并发拒绝、跨 tenant/project 隔离和 Control Plane 重启后的持久化状态。
- Worker `resourceBindings` ACK 通过，覆盖 legacy manifest、合法 MODEL/SKILL/MCP 绑定和非法 revision/digest 的稳定失败分类。
- 配置 revision 发布与 rollback 通过，真实 Worker 观察到稳定 revision 恢复并返回 `rollback=true`。
- Task/Artifact API smoke 通过，生命周期操作、拒绝路径和 Artifact 查询均输出 `KIND_TASK_API_OK`。
- OTLP trace continuity 与多副本 Prometheus scraping 通过；CI 失败诊断已收窄为 Pod/Deployment 状态、Task phase、revision/digest、错误分类和最近事件元数据，不采集原始组件日志、完整 Prompt/Response、`domain_events.payload`、`outbox.last_error` 或完整 Secret。

本机复核结果：Kind 集群的 Control Plane、Gateway、Operator 均为 2/2，PostgreSQL、NATS、MinIO 和 2 个 Worker Ready；执行 `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 clean test` 退出码为 0，Kind 清单、CI 工作流、诊断契约和 Shell 语法检查均通过。

因此，当前主线已从“Kind 验收收口”转入产品能力闭环：优先补齐真实 Worker/Manager 配额组装与跨实例持久化验证、审计维度持久化对账，以及后续按需接入企业 Skill 审批/沙箱；企业审批/沙箱当前仍保持默认关闭，不纳入 CI。
