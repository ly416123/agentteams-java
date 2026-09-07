# Project Scope 名称/UUID 统一实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 API、Manager、权限查询和历史数据都把项目名称与项目 UUID 解析为同一个规范 ProjectScope，并让新数据统一持久化项目 UUID。

**架构：** Control Plane 增加唯一的 ProjectScope 值对象与解析器，在 OIDC/API 边界把认证主体的项目名称或 UUID 规范化为项目 UUID，并在显式 projectId 路由上校验解析后的 UUID 相同。资源 scope 写入和读取保留受约束的名称兼容查询，迁移将可安全映射的历史文本 scope 回填为 UUID；Manager 在自己的认证边界也规范化 projectId，Conversation 的旧名称数据通过迁移和兼容查询可读。

**技术栈：** Java 17、Spring Boot Servlet Filter、Spring JDBC、PostgreSQL/Flyway、JUnit 5、AssertJ、Mockito。

---

### 任务 1：建立统一 ProjectScope 解析边界

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ProjectScope.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ProjectScopeResolver.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthenticationFilter.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/security/ProjectScopeResolverTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/security/ApiAuthenticationFilterTest.java`

- [ ] **步骤 1：编写失败的解析与规范化测试**

  覆盖：名称解析为 UUID、UUID 解析为同一项目、非当前租户/非 active membership 拒绝、显式路由名称与认证 UUID交叉通过、跨项目交叉请求拒绝；断言写入 `PrincipalContext` 的 project 永远是 UUID 字符串。

- [ ] **步骤 2：运行定向测试确认失败**

  运行：`mvn -q -pl control-plane -Dtest=ProjectScopeResolverTest,ApiAuthenticationFilterTest test`

  预期：新测试因解析器不存在或规范化行为不存在而失败，既有认证测试保持可诊断的失败信息。

- [ ] **步骤 3：实现最小 ProjectScope 与解析器**

  `ProjectScope` 保存 `tenantId`、`projectId(UUID)`、`projectName`、`teamId`；`ProjectScopeResolver` 只通过 `ProjectRepository` 查项目和 active membership，提供 `resolve(Principal)`、`resolve(Principal, requestedProject)` 和 `canonicalize(Principal, requestedProject)`，名称与 UUID 分支最终返回同一 UUID。

- [ ] **步骤 4：把 OIDC API Filter 接入解析器**

  保留无解析器构造函数以兼容纯单元测试；生产 Bean 注入 `ProjectRepository`。认证成功后先规范化 principal，再交给控制器；项目不存在、非 active 或 scope 不一致按 403 fail-closed。

- [ ] **步骤 5：运行定向测试确认通过**

  运行：`mvn -q -pl control-plane -Dtest=ProjectScopeResolverTest,ApiAuthenticationFilterTest test`

  预期：新测试和既有认证测试全部通过。

---

### 任务 2：统一资源 scope 的写入、可见性与路由校验

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/ResourceScopeRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/AgentService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TeamService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TeamController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/ScheduledTaskController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/WebhookController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardProjectScope.java`
- 修改：相关 `*ScopeTest.java` 与 `ResourceScopeRepositoryTest.java`

- [ ] **步骤 1：编写名称/UUID交叉回归测试**

  对 Agent、Team、Worker Template、AgentSpec、ScheduledTask、Webhook、Dashboard scope 分别验证：认证主体为名称/UUID、请求参数为 UUID/名称的四种组合均只命中同一项目；跨租户、跨项目和无 membership 均拒绝；resource scope 新绑定值为 UUID。

- [ ] **步骤 2：运行测试确认当前实现暴露缺口**

  运行：`mvn -q -pl control-plane -Dtest='*ScopeTest,ResourceScopeRepositoryTest,Dashboard*Test' test`

  预期：至少出现名称/UUID交叉组合失败，记录每个失败查询或边界。

- [ ] **步骤 3：让 ResourceScopeRepository 统一解析写入和 visible 查询**

  绑定时通过 `projects(tenant_id, id/name)` 将 project 写为 UUID；可见性查询通过同一 project 映射同时兼容旧名称和 UUID；移除各 Service 里将请求值直接写回 PrincipalContext 的分叉逻辑，统一调用解析器。

- [ ] **步骤 4：替换控制器中的 ad-hoc 字符串比较**

  Team、Agent、AgentSpec、Template、Dashboard、Schedule、Webhook 的 projectId 参数统一调用 ProjectScopeResolver；请求体中的 project scope 先解析为 UUID，再传递给持久化服务。不得用“是否 UUID”决定授权结果。

- [ ] **步骤 5：运行定向测试确认通过**

  运行：`mvn -q -pl control-plane -Dtest='*ScopeTest,ResourceScopeRepositoryTest,Dashboard*Test' test`

  预期：交叉 scope 测试全部通过，跨 scope 仍 fail-closed。

---

### 任务 3：覆盖 Team、Worker、Task、Template、AgentSpec、Model、MCP、Skill 的列表/详情权限查询

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TeamRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/DomainEventRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/template/JdbcWorkerTemplateRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/ResourceScopeRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardResourcesService.java`
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/JdbcConversationRepository.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/persistence/JdbcScopedResourceListRepositoryTest.java`
- 测试：各资源已有 Service/Repository scope 测试与 `manager/src/test/java/io/agentteams/manager/api/ConversationControllerTest.java`

- [ ] **步骤 1：为每类资源补齐失败交叉测试**

  使用同一 tenant 下的项目名称和 UUID分别作为 caller/requested scope，断言列表、详情、操作、引用校验只返回同一项目资源；覆盖 Model Provider/Model、MCP Server、Skill 的 resource scope，以及 Conversation 历史列表。

- [ ] **步骤 2：运行测试确认当前查询缺口**

  运行：`mvn -q -pl control-plane,manager -Dtest=JdbcScopedResourceListRepositoryTest,*ScopeTest,ConversationControllerTest test`

- [ ] **步骤 3：统一查询条件**

  所有资源 scope 查询使用规范化 project UUID作为主条件，同时通过 `projects` 映射兼容历史名称；禁止只比较 `s.project_id = principal.scope().project()` 的孤立查询。模板/AgentSpec 详情还要统一使用 UUID比较，而不是直接比较字符串。

- [ ] **步骤 4：统一 Manager Conversation scope**

  Manager 认证主体和 Conversation 创建/列表/详情/历史都使用规范化 UUID；旧会话名称数据在迁移或兼容查询下可读，跨 project/team 继续拒绝。

- [ ] **步骤 5：运行定向测试确认通过**

  运行：`mvn -q -pl control-plane,manager -Dtest=JdbcScopedResourceListRepositoryTest,*ScopeTest,ConversationControllerTest test`

---

### 任务 4：增加受约束历史数据回填迁移

**文件：**
- 创建：`control-plane/src/main/resources/db/migration/V89__canonicalize_project_scope_values.sql`
- 创建：`manager/src/main/resources/db/manager-migration/V9__canonicalize_conversation_project_scope.sql`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/project/ProjectScopeCanonicalizationMigrationTest.java`
- 测试：`scripts/test_migration_history_contract.py`

- [ ] **步骤 1：编写迁移约束测试**

  验证迁移只将 `(tenant_id, project_id)` 能唯一匹配 `projects.name` 的值改为 `projects.id::text`；未知项目、全局 scope、不同 tenant、已是 UUID 的值不变；更新前后唯一键不产生冲突。

- [ ] **步骤 2：运行迁移测试确认缺少 V89**

  运行：`mvn -q -pl control-plane -Dtest=ProjectScopeCanonicalizationMigrationTest test`

- [ ] **步骤 3：实现显式表清单回填**

  对 `resource_scopes`、`agent_specs`、`worker_templates`、模型价格/配额、usage/audit、scheduled/webhook/matrix、memory/token scope 及 Manager conversation/session 表逐表执行带 tenant 条件的 `UPDATE ... FROM projects`；不使用动态全库更新，不删除数据，不合并无法判定的冲突行。

- [ ] **步骤 4：加入重复/冲突保护和迁移契约**

  对带 `(tenant_id, project_id, natural_key)` 唯一约束的表，先检测名称/UUID双写冲突并使迁移失败而不是静默覆盖；补充 Flyway 顺序、checksum 和 SQL 语法检查。

- [ ] **步骤 5：运行迁移测试确认通过**

  运行：`mvn -q -pl control-plane -Dtest=ProjectScopeCanonicalizationMigrationTest test && python3 scripts/test_migration_history_contract.py`

---

### 任务 5：全量验证、部署与 L5 受约束回填

**文件：**
- 修改：`deploy/production/l5-linux-kvm-acceptance.md`（记录回填前后审计命令与回滚说明）
- 运行：Maven、Console 测试与 L5 Flyway/健康检查

- [ ] **步骤 1：运行 Control Plane、Manager 全量测试**

  运行：`mvn -q -pl control-plane,manager -am test`

- [ ] **步骤 2：运行前端类型检查与测试**

  运行：`npm --prefix console test -- --runInBand && npm --prefix console run build`

- [ ] **步骤 3：构建并部署包含新迁移的 Control Plane/Manager 镜像**

  记录镜像指纹，滚动更新 L5；先检查 Pod Ready、Flyway 成功和 API 健康，再执行数据回填。

- [ ] **步骤 4：在 L5 回填前执行只读审计**

  统计每张表的名称值、UUID值、未知值和冲突候选；只有未知值为 0 且冲突候选为 0 的表执行回填，其他表保留并报告。

- [ ] **步骤 5：回填后执行交叉 API 验证**

  使用同一 OIDC 用户分别以项目名称和 UUID访问 Worker、Team、Task、Conversation、Template、AgentSpec、Model、MCP、Skill 页面/接口，确认响应集合一致；验证跨项目请求为 403、组织/租户边界不扩大。

- [ ] **步骤 6：记录最终证据**

  保存测试结果、迁移版本、回填行数、未知/冲突行数、API 交叉验证结果；仅在全部证据通过后报告完成。
