# 企业级执行平面基础能力实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在现有 TaskSandbox、MCP、权限和异步任务基础上，建立统一的企业/租户执行上下文、部署模式、Sandbox Policy 和 MCP Connectivity 契约，为 SaaS、客户 Connector 和私有化执行面提供同一套业务边界。

**架构：** Control Plane 继续作为权威状态中心；新增的执行上下文和策略只描述业务约束，不绑定 Kubernetes 或某个 Sandbox 厂商。Sandbox Provider、MCP Gateway 和客户 Connector 通过稳定的 Provider-neutral 契约接入，旧 API 通过兼容映射逐步迁移。

**技术栈：** Java 17、Spring Boot、Spring JDBC、PostgreSQL/Flyway、JUnit 5、AssertJ、Testcontainers、NATS JetStream、Fabric8 Kubernetes Client、Helm、Python 3、Bash。

**执行边界：** 本计划只完成架构基础契约和最小可验证闭环；不实现完整客户 Connector、不接入 CubeSandbox/Firecracker、不实现计费支付、不进行 L6 生产验收。

---

## 文件边界

### 架构和规格

- 创建：`docs/superpowers/specs/2026-08-31-enterprise-execution-plane-design.md`，记录已确认的部署和隔离基线。
- 修改：`README.md`，增加 SaaS、企业 Connector、私有化部署和 L5 验收约束。
- 修改：`docs/superpowers/specs/2026-08-26-product-ecosystem-expansion-design.md`，将执行平面和 MCP 连接模式接入现有生态设计。
- 修改：`docs/development/git-workflow.md`，明确执行平面变更的本地 Docker + Ubuntu/KVM L5 门禁。

### 应用契约

- 创建：`application-contracts/src/main/java/io/agentteams/application/api/ExecutionPlacement.java`，定义 `PLATFORM_SHARED`、`CUSTOMER_CONNECTOR`、`PRIVATE_DEPLOYMENT`。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/McpConnectivityMode.java`，定义 `PLATFORM_PUBLIC`、`CUSTOMER_CONNECTOR`、`PRIVATE_DEPLOYMENT`。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/SandboxPolicy.java`，定义 Profile、Provider、网络、资源和生命周期限制。
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/SandboxRequest.java`，增加组织、租户、执行位置和策略摘要，并保持旧工厂方法兼容。
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/SandboxHandle.java`，增加 Provider-neutral execution placement 和脱敏连接引用。

### Control Plane 作用域和持久化

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ExecutionContext.java`，统一保存 Organization、Tenant、Project、Team、Subject。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ExecutionContextResolver.java`，将认证主体和旧 `tenant_id` 映射为统一上下文。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/PrincipalContext.java`，暴露统一上下文并保留旧 Scope 兼容入口。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxPolicyService.java`，按平台、企业、租户、项目和 Team 策略计算最终策略。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxPolicyRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/JdbcSandboxPolicyRepository.java`。
- 创建：`control-plane/src/main/resources/db/migration/V60__organization_tenant_execution_policy.sql`，建立组织、租户、成员映射和策略表，并为现有字符串租户建立兼容映射。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxLifecycleService.java`，在申请 Provider 前执行最终策略、资源和部署位置校验。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/KubernetesSandboxRuntime.java`，只接收经过策略解析的 Provider-neutral 请求。

### MCP 连接模型

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpConnection.java`，表达企业/租户实际连接和连接模式。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpConnectionRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/JdbcMcpConnectionRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpGatewayRoute.java`，表达平台公网、客户 Connector 和私有部署路由。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpServerService.java`，保持公共 MCP 兼容，同时禁止把企业私有连接误当成全局公共资源。
- 创建：`control-plane/src/main/resources/db/migration/V61__mcp_connections_and_routes.sql`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/mcp/McpConnectionServiceTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/mcp/JdbcMcpConnectionRepositoryTest.java`。

### Skill 设置管理

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillRecord.java`，增加 Organization/Tenant 归属和资源可见性摘要。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillVersionRecord.java`，保持版本、Manifest、digest、扫描和审核状态不可变语义。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillService.java`，增加企业/租户 scope、发布撤销、版本绑定和运行能力校验。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillController.java`，提供设置、上传、扫描、审核、发布、禁用、版本查询和绑定查询接口。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillCapabilityPolicy.java`，限制 Skill 对 Sandbox、MCP、Secret、网络和资源的声明。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/skill/SkillBindingRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/skill/JdbcSkillBindingRepository.java`。
- 创建：`control-plane/src/main/resources/db/migration/V62__skill_scope_and_bindings.sql`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/skill/SkillManagementServiceTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/skill/JdbcSkillBindingRepositoryTest.java`。

### 用户上下文、记忆和行为

- 创建：`application-contracts/src/main/java/io/agentteams/application/api/MemoryScope.java`，定义 `USER_PRIVATE`、`TENANT_SHARED`、`PROJECT_SHARED`、`TEAM_SHARED`、`CONVERSATION`、`TASK`。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/PersonalizationContext.java`，只表达经过授权的非安全个性化摘要。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/memory/MemoryRecord.java`，保存 scope、归属、来源、敏感等级、确认状态、过期时间和版本。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/memory/MemoryRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/memory/JdbcMemoryRepository.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/memory/MemoryPolicyService.java`，执行用户、企业、项目和 Team 的读取/写入策略。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/memory/ContextAssemblyService.java`，按执行上下文和 Token 预算组装最小脱敏上下文。
- 创建：`control-plane/src/main/resources/db/migration/V63__memory_scopes_and_governance.sql`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/memory/MemoryPolicyServiceTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/memory/JdbcMemoryRepositoryTest.java`。
- 创建：`manager/src/test/java/io/agentteams/manager/context/ContextAssemblyContractTest.java`。

### 任务结果和执行过程

- 创建：`application-contracts/src/main/java/io/agentteams/application/api/TaskEventVisibility.java`，定义事件和产物的可见性级别。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/TaskProcessEvent.java`，定义可回放的任务过程事件信封、sequence、事件类型、脱敏 Payload 引用和 Trace 信息。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/TaskResultManifest.java`，定义最终结果摘要、Task Run、状态和 Artifact 引用。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/TaskProgressSnapshot.java`，定义阶段、完成数、总数、进度和等待原因。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskProcessEventService.java`，将领域事件转换为企业可见过程事件，不新增第二套任务状态机。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskTreeService.java`，管理父子任务、依赖和任务树查询。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskDecisionRecordService.java`，保存脱敏决策摘要和审批关联。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskResultManifestService.java`，聚合最终状态、产物元数据和版本血缘。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskEventController.java`，支持统一事件信封、可见性过滤、稳定 sequence 和回放。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/ArtifactController.java`，增加中间/最终产物阶段、血缘和结果清单引用。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java`，提供任务树、过程事件、进度快照、决策记录和结果清单查询。
- 创建：`control-plane/src/main/resources/db/migration/V64__task_process_and_result_manifest.sql`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/task/TaskProcessEventServiceTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/task/TaskResultManifestServiceTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/api/TaskProcessControllerTest.java`。
- 修改：`integration-tests` 中的任务链路测试，覆盖任务树、过程事件回放、决策摘要、中间产物和最终结果清单。

### 测试和验证

- 创建：`application-contracts/src/test/java/io/agentteams/application/api/SandboxPolicyTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/security/ExecutionContextResolverTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxPolicyServiceTest.java`。
- 修改：现有 Sandbox、MCP、Task 和权限测试，覆盖旧调用兼容和跨租户拒绝。
- 修改：`integration-tests` 中的 Sandbox E2E，覆盖策略解析后的 Provider 请求和租户归属。
- 创建：`scripts/enterprise-execution-contract.sh`，执行迁移、契约测试、Helm 静态校验和本地门禁。

---

## 任务 1：冻结架构文档和兼容边界

**目标：** 先把部署模式、资源层级、隔离等级、连接模式和验收约束固化，避免代码任务各自解释。

- [ ] **步骤 1：检查当前公共契约和文档引用。**

  运行：`rg -n "tenant_id|resource_scopes|TaskSandbox|MCP|RuntimeClass|L5|L6" README.md docs application-contracts control-plane | head -240`

  预期：列出当前租户 scope、Sandbox、MCP 和验收约束的所有主要入口。

- [ ] **步骤 2：写入架构设计文档。**

  文件：`docs/superpowers/specs/2026-08-31-enterprise-execution-plane-design.md`。

  内容必须包含：SaaS、客户 Connector、私有化三种部署档位；Organization 到 TaskSandbox 的资源层级；`NONE/ISOLATED/HARDENED/DEDICATED` 语义；MCP 三种连接模式；Sandbox 与 Durable Workspace 分离；安全、成本、稳定性和 L5 门禁。

- [ ] **步骤 3：同步 README、生态设计和 Git 工作流约束。**

  修改范围只包含执行平面架构说明、部署档位、Connector 责任边界和 L5 强制验收，不把 L6 描述为普通批次完成条件。

- [ ] **步骤 4：运行文档和格式检查。**

  运行：`git diff --check && rg -n "待定|TODO|后续实现" docs/superpowers/specs/2026-08-31-enterprise-execution-plane-design.md docs/superpowers/plans/2026-08-31-enterprise-execution-plane-foundation.md`

  预期：`git diff --check` 退出 0，计划和设计中不存在占位词。

- [ ] **步骤 5：提交架构基线。**

  运行：`git add docs README.md && git commit -m "docs(架构): 固化企业级执行平面基线"`

## 任务 2：增加统一执行上下文

**目标：** 在不破坏现有 `tenant/project/team` API 的前提下，增加 Organization、Tenant、Project、Team、Subject 的统一上下文。

- [ ] **步骤 1：编写失败测试。**

  测试文件：`control-plane/src/test/java/io/agentteams/controlplane/security/ExecutionContextResolverTest.java`。

  测试必须覆盖：完整上下文可解析；缺少 Tenant 时拒绝；旧 `tenant_id` 可以通过兼容映射解析；主体不属于租户时拒绝；跨 Organization 的 Project 访问拒绝。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl control-plane -Dtest=ExecutionContextResolverTest test`

  预期：因 `ExecutionContext` 和 Resolver 不存在而失败。

- [ ] **步骤 3：实现上下文和值对象。**

  `ExecutionContext` 使用 UUID 表示 Organization、Tenant、Project 和 Team，使用字符串表示外部 Subject；构造函数拒绝空值和不一致的层级组合。

- [ ] **步骤 4：增加兼容映射表和 Repository。**

  在 `V60__organization_tenant_execution_policy.sql` 中建立组织、租户、组织成员、租户成员和旧租户键映射。旧项目数据通过稳定的外部租户键映射到 Tenant，不在迁移中删除现有列。

- [ ] **步骤 5：接入 `PrincipalContext`。**

  认证请求设置 `ExecutionContext`；未认证内部调用继续走现有兼容路径；认证请求缺少 Organization/Tenant 映射时 fail-closed。

- [ ] **步骤 6：运行定向和回归测试。**

  运行：`mvn -q -pl control-plane -Dtest=ExecutionContextResolverTest,AuthorizationServiceTest,ResourceAuthorizationServiceTest test`

  预期：新增测试和既有授权测试全部通过。

- [ ] **步骤 7：提交上下文任务。**

  运行：`git add application-contracts control-plane && git commit -m "feat(租户): 增加统一执行上下文"`

## 任务 3：引入 Sandbox Policy 和执行位置

**目标：** 把 Sandbox Profile、资源限制、网络策略、执行位置和 Provider 选择统一为策略结果。

- [ ] **步骤 1：编写策略失败测试。**

  测试文件：`application-contracts/src/test/java/io/agentteams/application/api/SandboxPolicyTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxPolicyServiceTest.java`。

  测试必须覆盖：默认可信任务得到 `NONE + PLATFORM_SHARED`；企业策略可以强制 `ISOLATED`；任务不能降低企业强制等级；客户 Connector 模式必须有 Connector ID；`HARDENED` 的资源和 TTL 不得超过平台上限；策略合并顺序稳定。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl application-contracts,control-plane -Dtest=SandboxPolicyTest,SandboxPolicyServiceTest test`

  预期：因 `SandboxPolicy`、执行位置和策略服务不存在而失败。

- [ ] **步骤 3：实现应用契约。**

  `SandboxPolicy` 必须包含 `SandboxProfile`、Provider、执行位置、CPU、内存、临时存储、TTL、网络策略、允许的 MCP 和脱敏策略；所有资源限制使用正数和明确上限。

- [ ] **步骤 4：实现策略合并服务。**

  合并顺序固定为平台 → Organization → Tenant → Project/Team → Task 请求；策略只允许收紧安全限制；非法放宽、未知 Provider 和缺少 Connector 身份直接拒绝。

- [ ] **步骤 5：接入 Sandbox 生命周期。**

  `SandboxLifecycleService` 在写入 Provision Outbox 前解析策略；Outbox 和 Worker 消息只携带策略摘要、Sandbox ID、Attempt ID 和脱敏 Endpoint 引用，不携带 Secret。

- [ ] **步骤 6：运行 Sandbox 回归。**

  运行：`mvn -q -pl application-contracts,control-plane,integration-tests test`

  预期：默认 `NONE` 路径、Fake Provider 和既有 Sandbox 生命周期测试全部通过。

- [ ] **步骤 7：提交策略任务。**

  运行：`git add application-contracts control-plane integration-tests && git commit -m "feat(沙箱): 增加策略和执行位置"`

## 任务 4：增加 MCP Connection 和 Gateway Route 契约

**目标：** 将现有全局 MCP Server 注册表扩展为公共目录、企业连接和客户 Connector 路由，同时保持现有公共 MCP API 兼容。

- [ ] **步骤 1：编写失败测试。**

  测试必须覆盖：公共 MCP 可以使用 `PLATFORM_PUBLIC`；企业私有 MCP 必须绑定 Organization/Tenant；`CUSTOMER_CONNECTOR` 缺少 Connector ID 时拒绝；不同租户无法读取连接；公共目录不能返回 credential 明文；同一幂等键复用不同请求时拒绝。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl control-plane -Dtest=McpConnectionServiceTest,JdbcMcpConnectionRepositoryTest test`

  预期：因 MCP Connection 类型、迁移和 Repository 不存在而失败。

- [ ] **步骤 3：实现 Connection 和 Route 值对象。**

  Connection 使用 `McpConnectivityMode`、Organization/Tenant 归属、Endpoint 引用、Credential Reference、工具白名单和状态；Route 只保存 Connector ID、版本、心跳和脱敏健康信息。

- [ ] **步骤 4：实现迁移和 JDBC Repository。**

  `V61__mcp_connections_and_routes.sql` 使用 Organization/Tenant 复合索引、连接模式约束、Connector 唯一键和幂等键；旧 `mcp_servers` 继续作为平台公共目录，不把既有数据自动变成企业私有连接。

- [ ] **步骤 5：接入 MCP Server Service 的可见性校验。**

  公共资源按目录策略可见，私有连接必须通过统一 `ExecutionContext` 校验；响应只返回 `credentialRef` 的稳定摘要，不返回 Secret 内容。

- [ ] **步骤 6：运行 MCP、权限和数据库测试。**

  运行：`mvn -q -pl control-plane -Dtest=McpConnectionServiceTest,JdbcMcpConnectionRepositoryTest,ResourceAuthorizationServiceTest test`

  预期：全部通过；Docker 不可用时 Testcontainers 测试必须非零失败并输出诊断。

- [ ] **步骤 7：提交 MCP 连接任务。**

  运行：`git add control-plane application-contracts && git commit -m "feat(mcp): 增加企业连接和执行路由"`

## 任务 5：补齐 Skill 设置管理边界

**目标：** 将已有 Skill Registry、版本、包存储、扫描和审核能力提升为企业级可管理资源，并让 Skill 的运行能力受 Sandbox Policy 和 MCP Gateway 约束。

- [ ] **步骤 1：编写失败测试。**

  测试文件：`control-plane/src/test/java/io/agentteams/controlplane/skill/SkillManagementServiceTest.java`。

  测试必须覆盖：公共 Skill 可被允许的租户发现；企业私有 Skill 只能被归属租户发现；未审核或扫描失败的版本不能发布；已发布版本不可修改；绑定必须固定 `skillId + version + digest`；企业策略禁止的 MCP、Secret、网络或 Sandbox 能力被拒绝；禁用版本不能被新的 AgentSpec 或 Team Revision 引用。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl control-plane -Dtest=SkillManagementServiceTest test`

  预期：因企业 Skill scope、能力策略和绑定服务尚不存在而失败。

- [ ] **步骤 3：实现 Skill 能力声明和绑定值对象。**

  `SkillCapabilityPolicy` 只允许声明受限的 Sandbox Profile、MCP Server ID、工具白名单、Secret Reference、域名白名单和 CPU/内存/TTL 上限；能力只能由企业策略收紧，不能由 Skill 自身放宽。

- [ ] **步骤 4：增加 Skill scope 和绑定迁移。**

  `V62__skill_scope_and_bindings.sql` 为 Skill 和 Skill Version 增加 Organization/Tenant 兼容归属，建立绑定表和 `(scope, skill_id, version, digest)` 唯一约束；保留既有公共 Skill 数据并将旧私有 Skill 绑定到兼容租户映射。

- [ ] **步骤 5：接入 Skill 发布和运行时校验。**

  `SkillService` 在创建、上传、扫描、审核、发布和禁用时执行 scope 校验；AgentSpec、Team Revision、Worker Template 和 Sandbox Provision 前都重新验证 Skill 的生命周期、digest、能力策略和当前租户可见性。

- [ ] **步骤 6：补充 Skill 管理 API 和安全响应。**

  管理接口使用现有 `Idempotency-Key` 和 `expectedVersion`；响应只返回 Manifest 摘要、digest、扫描/审核状态和能力摘要，不返回包内 Secret、完整环境变量或外部凭据。

- [ ] **步骤 7：运行 Skill 和依赖回归。**

  运行：`mvn -q -pl control-plane -Dtest=SkillManagementServiceTest,AgentSpecReferenceValidatorTest,TeamRevisionServiceTest,WorkerTemplateServiceTest test`

  预期：Skill 管理、AgentSpec 引用、Team 绑定和 Template 实例化测试全部通过。

- [ ] **步骤 8：提交 Skill 管理任务。**

  运行：`git add control-plane application-contracts && git commit -m "feat(skill): 增加企业级设置管理和运行策略"`

## 任务 6：建立用户上下文、记忆和行为治理边界

**目标：** 将用户私人记忆、企业共享记忆、项目/Team 记忆、对话/任务记忆、审计数据和行为统计分层，防止 Manager 会话表承担长期记忆职责。

- [ ] **步骤 1：编写失败测试。**

  测试必须覆盖：用户只能读取自己的私人记忆；企业管理员可以管理策略但默认不能读取私人记忆内容；企业共享记忆可被授权成员读取；项目/Team 记忆按成员关系隔离；不同 Tenant 之间不可见；未确认的敏感候选记忆不能进入模型上下文；行为事件不直接进入 Prompt。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl application-contracts,control-plane,manager -Dtest=MemoryPolicyServiceTest,JdbcMemoryRepositoryTest,ContextAssemblyContractTest test`

  预期：因 Memory Scope、Memory Policy 和 Context Assembly 不存在而失败。

- [ ] **步骤 3：实现记忆作用域和值对象。**

  `MemoryRecord` 必须包含 Organization/Tenant/Project/Team/Subject 归属、scope、content type、sensitivity、source、consent state、retention policy、expiresAt 和 version；私人记忆必须带 `subjectId`，共享记忆必须带对应资源归属。

- [ ] **步骤 4：实现记忆策略和管理员治理权限。**

  默认策略为：用户私人记忆用户本人读写、管理员仅治理；企业共享记忆由企业授权角色维护；特殊内容访问必须携带原因、审批、过期时间和审计 ID；删除、冻结、导出和保留操作必须幂等。

- [ ] **步骤 5：增加迁移和 Repository。**

  `V63__memory_scopes_and_governance.sql` 建立记忆主表、候选记忆确认表、治理操作表和 scope 查询索引；正文、摘要和向量引用分开保存，所有查询强制带 Organization/Tenant 条件。

- [ ] **步骤 6：实现 Context Assembly。**

  Context Assembly 只接收统一 `ExecutionContext`，依次执行授权过滤、敏感级别过滤、确认状态过滤、过期过滤、Token 预算裁剪和来源摘要；输出不得包含 Secret、原始审计日志或未经授权的行为数据。

- [ ] **步骤 7：接入 Manager、Skill 和 MCP 边界。**

  Manager 继续负责会话历史，不直接持久化长期记忆；Skill 只能读取已绑定 scope 的记忆；MCP 结果先经过 Gateway 脱敏；Sandbox 只接收 Context Assembly 输出的任务级最小上下文。

- [ ] **步骤 8：运行记忆和跨模块回归。**

  运行：`mvn -q -pl application-contracts,control-plane,manager,integration-tests test`

  预期：记忆策略、数据库、Context Assembly、Manager 会话、Skill 引用、MCP 和 Sandbox 测试全部通过。

- [ ] **步骤 9：提交记忆治理任务。**

  运行：`git add application-contracts control-plane manager integration-tests && git commit -m "feat(记忆): 增加用户上下文和企业治理边界"`

## 任务 7：建立任务结果和执行过程协议

**目标：** 让企业可以通过统一 API、事件流和产物清单获取任务结果、任务拆分、进度、中间产物、工具调用和安全决策摘要。

- [ ] **步骤 1：编写失败测试。**

  测试必须覆盖：任务结果清单只返回终态产物；中间产物带有阶段和血缘；过程事件按 sequence 有序；重复事件不重复投影；`Last-Event-ID` 可以回放；跨租户事件和产物被拒绝；大 Payload 使用 `payloadRef`；事件中禁止 Secret、完整 Prompt 和原始思维链。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl application-contracts,control-plane -Dtest=TaskProcessEventServiceTest,TaskResultManifestServiceTest,TaskProcessControllerTest test`

  预期：因任务过程事件、任务树、决策记录和结果清单服务不存在而失败。

- [ ] **步骤 3：实现任务过程契约。**

  `TaskProcessEvent` 必须包含 `eventId`、`taskId`、`runId`、`sequence`、`eventType`、`visibility`、`occurredAt`、`correlationId` 和 Payload；事件类型覆盖生命周期、拆分、阶段、进度、工具、Sandbox、审批、重试、产物和结果。

- [ ] **步骤 4：增加任务树、决策记录和结果清单。**

  `TaskTreeService` 保存父子 Task 和依赖；`TaskDecisionRecordService` 只保存脱敏的目标、选择、依据、约束和置信度；`TaskResultManifestService` 从权威 Task 状态和 Artifact 元数据聚合最终结果，不复制 Task 状态机。

- [ ] **步骤 5：实现迁移和投影。**

  `V64__task_process_and_result_manifest.sql` 建立 Task Run、Task Subtask、Decision Record 和 Result Manifest 关联表，并为 `(tenant_id, task_id, sequence)`、可见性、状态和 Artifact 血缘建立索引。领域事件仍是事实来源，投影可重建。

- [ ] **步骤 6：改造事件和产物 API。**

  `TaskEventController` 使用统一事件信封、稳定 sequence、`Last-Event-ID` 和可见性过滤；`TaskProcessController` 提供任务树、进度、决策和结果清单；`ArtifactController` 返回阶段、血缘和短期签名下载地址，不在事件中返回大文件正文。

- [ ] **步骤 7：增加安全思考摘要。**

  只允许受控的 `Reasoning Summary` 和 `Decision Record` 进入企业事件流；过滤系统提示词、凭据、完整 Prompt、其他用户数据和未经脱敏的 MCP 响应；原始 Chain of Thought 只允许内部诊断链路，并且不进入公共 API、审计导出或长期产物。

- [ ] **步骤 8：接入 Manager、Worker 和 SDK 事件边界。**

  Manager Conversation 事件通过 `taskId`、`runId` 和 `correlationId` 关联到 Task 过程；Worker 只上报结构化阶段、工具、进度和 Artifact 引用；公共 Java/TypeScript SDK 消费 REST、SSE 和 Webhook 契约，不暴露 NATS 主题。

- [ ] **步骤 9：运行任务过程和端到端回归。**

  运行：`mvn -q -pl application-contracts,control-plane,manager,integration-tests test`

  预期：任务结果、SSE 回放、任务树、决策摘要、Artifact 下载、Manager Conversation 和跨租户授权测试全部通过。

- [ ] **步骤 10：提交任务过程协议。**

  运行：`git add application-contracts control-plane manager integration-tests && git commit -m "feat(任务): 增加结果清单和执行过程协议"`

## 任务 8：建立验证门禁和并行开发边界

**目标：** 确保后续 Connector、Provider、Token 和实时事件任务可以并行开发，但不会共享修改同一套未冻结模型。

- [ ] **步骤 1：实现本地契约脚本。**

  `scripts/enterprise-execution-contract.sh` 依次执行 Maven 模块测试、迁移校验、API 契约、Helm lint/template 和 `git diff --check`；任何命令失败立即退出非零。

- [ ] **步骤 2：增加并行任务边界文档。**

  允许独立并行：MCP Connector 协议、Token Ledger、对话到任务和实时事件；禁止并行修改 Organization/Tenant 主键、SandboxPolicy 字段和 MCP Connection 唯一键。

- [ ] **步骤 3：本地 Docker 验证。**

  运行：`bash scripts/enterprise-execution-contract.sh`

  预期：本地 PostgreSQL、NATS、MinIO 和 Testcontainers 相关测试通过。

- [ ] **步骤 4：L5 Ubuntu/KVM 验证。**

  对涉及 Operator、TaskSandbox、RuntimeClass 或 Worker 的变更，在 `192.168.122.55` 执行对应真实验收脚本，保留成功标记、运行时证据和清理结果；没有 L5 证据的批次不能标记完成。

- [ ] **步骤 5：提交门禁任务。**

  运行：`git add scripts docs && git commit -m "ci(执行平面): 增加本地和 L5 验收门禁"`

## 集成检查点

- [ ] 任务 1 完成后，先审查架构文档，再进入代码任务。
- [ ] 任务 2 完成后，所有认证资源都能得到统一 Organization/Tenant 上下文，旧 API 保持兼容。
- [ ] 任务 3 完成后，默认 `NONE` 行为不回归，策略拒绝路径有数据库和事件证据。
- [ ] 任务 4 完成后，公共 MCP 与企业私有 MCP 的数据和凭据边界明确。
- [ ] 任务 5 完成后，才能并行展开 Connector、Token Ledger、任务调度和实时事件的独立计划。
- [ ] 任务 6 完成后，Context Assembly、Memory Policy 和行为事件边界冻结，才能把个性化能力接入 Console 或 Agent Runtime。
- [ ] 任务 7 完成后，Task Snapshot、Task Event Stream、Task Tree、Decision Summary 和 Result Manifest 契约冻结，才能接入企业 Webhook、Console 高级进度视图和外部业务系统。
