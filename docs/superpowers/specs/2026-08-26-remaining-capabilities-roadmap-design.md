# AgentTeams Java 剩余能力总体路线图设计

**日期：** 2026-08-26
**状态：** 已获用户确认；批次 A/B/C 第一纵切及 Console/Conversation 管理闭环已完成，外部平台验收仍待受控环境
**代码基线：** `2a9b553`
**目标版本：** 生产可用基线后继续演进商业化能力

## 1. 文档目标

本文把当前仓库尚未完成的能力组织成可独立设计、实施和验收的工作流，作为后续实现计划的唯一入口。本文不重复已经完成的基础功能，也不以早期计划中未勾选的复选框判断实现状态；实现状态以当前代码、最新规格和可复现测试为准。

详细设计拆分为 5 份子规格：

1. 运行时生产闭环；
2. 控制面治理闭环；
3. 生产交付与可靠性闭环；
4. 可观测与规模化闭环；
5. 产品生态扩展。

每份子规格必须能够被不了解仓库历史的开发者或大模型独立理解。后续实现计划按子规格分别编写，不生成一份跨越全部子系统的巨型计划。

## 2. 当前能力基线

以下能力已经存在，不再作为新功能重新开发：

- Java 17 多模块架构、Spring Boot Control Plane、Gateway、Runtime、Worker、Manager 和 Operator；
- PostgreSQL 权威状态、Flyway、Outbox、NATS JetStream 和至少一次投递；
- Agent 注册、短期 Session、gRPC 双向通道、Lease、ACK、断线重放和 Attempt fencing；
- Task 创建、审批、暂停、取消、重试、执行事件和 Artifact 预签名上传；
- QwenPaw HTTP/SSE Runtime 与 DeepSeek/OpenAI Compatible Provider；
- AgentSpec、Model、Skill、MCP 引用校验、配置修订、Worker ACK、失败重试和回滚；
- Team CRD、成员同步、调度约束、Leader 声明和并发策略基础；
- OIDC、mTLS、项目成员、资源作用域、出站策略和审计基础；
- 项目配额、模型价格、成本审计、Dashboard 聚合和持久化告警；
- Matrix/Tuwunel、Prometheus、OpenTelemetry、Kind 故障恢复和备份验证基础；
- Task 级 `SandboxRuntimePort`、Sandbox 持久化状态、TaskSandbox CRD 和受限 Job 渲染；
- AgentScope Runtime、事件翻译、Workspace 边界、Fake Model 验收和灰度配置契约。

## 3. 优先级定义

| 优先级 | 定义 | 启动条件 | 完成条件 |
|---|---|---|---|
| P0 | 阻塞声明的生产主路径或导致生产配置不可运行 | 当前基线测试通过，依赖接口已明确 | L1-L4 通过；涉及隔离或生产平台时还需 L5/L6 |
| P1 | 核心能力已可用，但规模化、治理或可靠性不足 | 依赖的 P0 契约稳定 | 对应负载、故障和权限矩阵通过 |
| P2 | 提升产品完整度、交付效率和易用性 | P0 主路径稳定 | API、SDK 或 UI 契约稳定并具备端到端验收 |
| P3 | 云商业化或特定部署场景扩展 | 有明确客户或部署需求 | 独立适配器通过，不改变核心领域模型 |

## 4. 工作流与能力映射

| 工作流 | 优先级 | 包含能力 | 子规格 |
|---|---|---|---|
| W1 运行时生产闭环 | P0 | Kubernetes Sandbox、生命周期调度、gVisor/Kata、AgentScope Worker 路由、正式 Manager 服务 | `2026-08-26-runtime-production-closure-design.md`（L1-L5 RuntimeClass/TaskSandbox 已完成；节点故障恢复与 L6 待受控环境） |
| W2 控制面治理闭环 | P0/P1 | Team 版本化绑定、Effective Config、Worker 运维、全资源 RBAC、成员生命周期、Secret 解析 | `2026-08-26-control-plane-governance-closure-design.md` |
| W3 生产交付与可靠性 | P0/P1 | 外部依赖网络、镜像晋级、供应链、Ingress、证书、备份恢复和发布回滚 | `2026-08-26-production-delivery-reliability-design.md` |
| W4 可观测与规模化 | P1 | Skill/MCP 生效闭环、预算预测、审计对账、配额压测、Operator/HPA/SLO | `2026-08-26-observability-scale-closure-design.md` |
| W5 产品生态扩展 | P2/P3 | Worker Template、Console、SDK、Channel SPI、Sandbox Pool、Cube、多区域和账单扩展 | `2026-08-26-product-ecosystem-expansion-design.md` |

### 4.1 当前进度（2026-08-28）

- W1 的批次 A 代码已进入 `main`，包括 Sandbox Provider、Operator 生命周期保护、AgentScope 路由、Manager 服务、Team Revision/Effective Config 和 Helm 安全契约。
- 批次 A 的本地 Java/脚本/Helm 验证及 GitHub Actions `verify`、`kind-recovery`、`kind-oidc` 已通过。
- W1 的 gVisor/Kata RuntimeClass/TaskSandbox 已在独立 Ubuntu/KVM 节点完成 L5 真实验收；外部 Secret Manager、外部 IdP、生产镜像签名、节点故障恢复和预发布环境恢复演练仍待受控环境，不以 Kind 结果替代。
- W2/W3 批次 B 第一纵切已进入 `main`：Worker 运维双确认/恢复调度、统一资源授权与项目成员生命周期、External Secrets Ready/metadata 解析、签名 Release Manifest/Chart 晋级、Ingress/Gateway API、三种 egress 模式和恢复安全闸门均已落库。
- `main` 的 GitHub Actions CI `33124422786` 已通过 `verify`、`kind-oidc` 和 `kind-recovery`；本机 Colima Docker-backed Maven、脚本全量和 Helm 验证也已通过。生产 Canary、自动回滚、真实外部 Secret/IdP 和 L5/L6 恢复演练仍未完成，不以静态契约或 Kind 结果替代。
- 批次 B 后续实施计划见 `docs/superpowers/plans/2026-08-27-batch-b-security-operations-plan.md`。
- 批次 C 的 Skill 制品运行时纵切已进入 `main`：Control Plane 会为已发布且上传完成的 Skill 版本生成 15 分钟短期 `artifactRef`，并随 AgentSpec manifest 下发包大小/SHA-256；Worker 已完成归档下载校验、受限解包、`SKILL.md` 复核和 AgentScope 只读仓库注册。真实外部 Skill 运行时策略与 MCP 工具发现仍待后续批次。
- 批次 C 的 MCP 发现与运行时纵切已进入 `main`：Control Plane 按 server revision 和实例写入快照，聚合新鲜观测为 `AVAILABLE`、`UNAVAILABLE` 或 `UNKNOWN`，健康探测只持久化工具摘要和固定失败分类；AgentSpec manifest 下发非敏感 MCP 运行元数据，Worker 通过 AgentScope runtime Port 注册 HTTP/SSE 工具并按 credentialRef 动态取凭证。预算策略、线性预测、成本状态区分、项目作用域评估查询、使用量维度完整性审计、可恢复历史维度回填，以及预算周期评估/集中通知/失败重试第一纵切已进入 `main`；真实外部 MCP 长期运行和 L6 长压测仍未完成。
- 批次 C 的 HPA 仓库纵切已进入 `main`：Control Plane、Gateway 和可选 Manager 提供默认关闭的 `autoscaling/v2` CPU HPA，启用时 Helm 强制校验 `resources.requests.cpu` 和副本范围，并配套 schema、生产 values 示例与契约测试；Metrics Server/Prometheus Adapter、实际扩缩容、拓扑故障和 L6 长压测仍待受控环境。
- 批次 C 的 Operator 行为测试仓库纵切已进入 `main`：Worker/Team 覆盖首次创建、重复 reconcile、子资源篡改恢复、OwnerReference、状态投影和 generation 不变；TaskSandbox 已覆盖生命周期、删除、缺失子资源和旧 generation。Fabric8 Mock Server 已覆盖 Worker/Team status 409、429/500、持续错误和短暂 API 不可用，确认冲突交给 Java Operator SDK 默认重试链路；真实 Kind 故障注入和 Leader Election 验收仍待后续批次。
- 模型价格目录自动同步第一纵切已进入 `main`：Control Plane 通过默认关闭的受限 HTTP 客户端拉取不含作用域的价格快照，仅写入部署显式配置的租户/项目，使用数据库租约、自然键和现有幂等审计链路去重，既不接受 payload 传入作用域，也不覆盖已有人工价格；真实价格源兼容性和 L6 长压测仍需在受控环境验证。
- Console/Conversation 管理闭环已完成本地真实验收：登录入口、Project/Team/Worker/Task 管理页面、Conversation SSE 流式输出、重连、取消、幂等消息、跨 Manager 副本事件持久化和重启后的历史/幂等回放均已接入；Docker/Kind 真实 QwenPaw、Chromium E2E 和 158 项脚本回归通过。直接系统 Chrome 控制连接仍取决于当前浏览器连接器是否可用，不以 Playwright 结果冒充该连接器验收。
- W5 的 Worker Template Registry 最小纵切已进入当前开发分支：模板 scope 与名称唯一、不可变 revision、Review/Publish/Deprecated 状态机、幂等实例化、AgentSpec/Worker 创建适配边界和实例升级 API 已落库；外部 Skill/MCP/Secret 深度校验、企业审批、生产就地升级/回滚以及 SDK/Console 仍不在本批范围内。L6 真实验收继续留在主线之外。

## 5. 统一架构决策

### 5.1 权威状态

- PostgreSQL 继续保存 Task、Attempt、Lease、Sandbox、配置修订、绑定、权限、审计和使用量事实。
- Kubernetes CRD 只保存基础设施期望态和状态投影，不替代业务历史。
- AgentScope Session、Matrix Timeline、Prometheus 时序和外部 Sandbox 状态都不是 Task 状态源。
- 所有跨进程状态变更使用 Outbox 或显式持久化命令，禁止依赖进程内 Map 作为生产事实。

### 5.2 Port/Adapter 边界

- 外部系统通过项目拥有的 Port 接入：Sandbox、Secret、Channel、Model、Skill Scanner、MCP Transport、Object Storage 和 Notification。
- 领域层不得依赖 Fabric8、AgentScope、云厂商 SDK、Vault SDK 或具体 Matrix 实现。
- 新 Adapter 必须提供可判定的能力声明和稳定错误分类，不得把供应商响应原文写入领域事件或日志。

### 5.3 安全默认值

- 新功能默认关闭，显式配置后启用；安全能力启用后必须 fail-closed。
- Control Plane 不获得 Docker Socket、集群级 Pod/Job 权限或运行时宿主机权限。
- Secret 只通过引用和短期读取进入进程，不进入 Git、配置修订、事件、审计正文或指标标签。
- 所有外部 HTTP Client 禁止任意重定向，并受超时、域名、scheme、响应大小和低基数错误分类约束。

### 5.4 幂等与恢复

- 每个写命令必须有 `Idempotency-Key`、稳定业务键或数据库唯一约束。
- 外部调用不能持有数据库事务；先提交可恢复意图，再由 Worker/Scheduler 执行。
- 重放必须查询权威状态；不能因为重复消息重复创建 Sandbox、扣减配额、发布配置或发送不可撤销通知。
- 所有异步记录包含 `eventId`、`correlationId`、`causationId`、`tenantId`、`projectId` 和资源版本。

### 5.5 时间与窗口

- 验收脚本禁止用固定短暂 `sleep` 推断异步完成；使用状态轮询、稳定事件 ID、持久化游标或可观测条件。
- Dashboard 窗口指纹由持久化规则的 `windowStart/windowEnd` 决定，不以脚本启动分钟作为唯一断言。
- 超时只定义最大等待边界；失败输出必须包含最后观察状态和脱敏诊断。

## 6. 依赖顺序

```text
W3 外部依赖网络与 Secret 契约
                |
                +------> W1 Kubernetes Sandbox
                |              |
                |              +--> AgentScope Worker 路由
                |              +--> Manager 正式服务
                |
W2 Team Revision / Effective Config
                |
                +------> W4 Skill/MCP 运行时绑定与审计维度
                               |
                               +--> 预算、配额、SLO 和规模验证
                                              |
                                              +--> W5 Console/SDK/模板/渠道
```

允许并行的工作：

- W1 的 Kubernetes Sandbox Adapter 与 W3 的 NetworkPolicy schema 可以并行，但联合验收必须等待两者完成；
- W2 的 Team Revision 与 W3 的镜像发布流水线可以并行；
- W4 的 Operator 行为测试可在 W1 Adapter 开发期间先建立测试框架；
- W5 只能在对应后端 API 稳定后开始，Console 不先于 API 契约开发。

## 7. 统一验收层级

| 层级 | 环境 | 必须证明的内容 |
|---|---|---|
| L1 | Java/Python 单元测试 | 状态机、校验、错误分类、幂等键、配置解析和安全默认值 |
| L2 | Testcontainers | Flyway、唯一约束、并发、锁、事务边界、恢复和跨进程持久化 |
| L3 | Helm/静态策略 | lint、template、CRD、RBAC、NetworkPolicy、Secret 引用和生产值校验 |
| L4 | Kind | Control Plane、Gateway、Worker、Operator、NATS、PostgreSQL、MinIO、OIDC、Matrix 和 OTel 端到端 |
| L5 | Linux/KVM | gVisor、Kata、RuntimeClass、Pod 删除、节点故障和真实隔离 |
| L6 | 生产预发布环境 | 外部 IdP、Secret Manager、托管数据库/NATS/S3、证书轮换、备份恢复、镜像晋级和回滚 |

L1-L4 应进入默认 CI 或可复现的专用 CI。L5/L6 使用受控环境和人工审批，不向仓库注入生产凭据。

## 8. Definition of Done

每个功能只有同时满足以下条件才可标记完成：

1. 生产代码不存在仅返回固定成功或固定不可用的占位 Adapter；
2. 默认关闭、启用、错误配置和依赖不可用均有测试；
3. 数据库迁移可从空库执行，也能从上一版本升级；
4. API/事件/CRD 变更向后兼容，或明确提供版本迁移；
5. 重复请求、消息重放、进程重启和并发竞争不会产生重复副作用；
6. 日志、错误、诊断和 CI Artifact 不包含 Secret、JWT、完整 Prompt/Response；
7. Helm 和生产配置示例同步；
8. 对应 L1-L4 验收通过，涉及的 L5/L6 验收有真实结果；
9. README、架构地图、商业差距矩阵和实施计划状态同步；
10. 提交保持单一职责，并通过敏感信息扫描和 `git diff --check`。

## 9. 实施批次

### 批次 A：生产主路径修复

1. 修复生产 NetworkPolicy 与 Secret 配置契约；
2. 实现 Kubernetes Sandbox Adapter 和生命周期调度；
3. 完成 AgentScope Worker 路由及灰度；
4. 将 Manager 变成正式可部署服务；
5. 完成 Team Revision 和 Effective Config。

### 批次 B：安全与运维闭环

1. 完成 Worker rollout/drain/rollback；
2. 完成全资源 RBAC 和成员生命周期；
3. 接入生产 Secret Resolver；
4. 建立镜像发布、签名、晋级和回滚；
5. 建立生产备份、PITR 和恢复演练。

### 批次 C：规模化与运营

1. Skill/MCP 运行时生效；
2. 审计维度完整性和预算预测；
3. 配额、Operator 和多副本压力验证；
4. HPA、拓扑、安全上下文和 SLO；
5. Matrix、mTLS 和外部依赖长期运行验收。

### 批次 D：产品生态

1. Worker Template；
2. OpenAPI SDK；
3. Web Console；
4. Channel SPI；
5. Sandbox Pool 和可选 CubeSandbox；
6. 按明确商业需求实现多区域、云资源和账单扩展。

## 10. 非目标

- 不把 Team、Worker 或 Task 直接绑定到 Kubernetes Node；
- 不把 CubeSandbox、Vault、云数据库或阿里云专有能力写入核心领域模型；
- 不在默认 CI 中使用真实 DeepSeek Key、生产 OIDC Token、生产证书或 Secret Manager 凭据；
- 不在 API 尚未稳定前启动 Console；
- 不把估算成本称为最终账单；
- 不为尚无使用方的多区域一致性设计复杂共识协议。
