# Team 成员选择与 Worker 类型实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Worker 增加显式 `LEADER`/`EXECUTOR` 类型，将 Team 成员添加改为当前 Project 可见 Worker 下拉选择，并在服务端与 Template 实例化链路执行 Leader 类型约束。

**架构：** 在 `domain` 定义 `WorkerType`，由 Control Plane 持久化到 Agent 和 Worker Template。Team 成员与 Revision 继续提交 UUID，但由服务端按 Project Scope、成员关系和 Worker 类型统一校验；Console 通过现有 Worker 列表查询构造下拉选项。

**技术栈：** Java 17、Spring Boot、JDBC、Flyway、PostgreSQL、React、TypeScript、TanStack Query、Vitest、JUnit 5、Mockito。

---

## 文件范围

### 创建

- `domain/src/main/java/io/agentteams/domain/agent/WorkerType.java`：Worker 类型枚举。
- `control-plane/src/main/resources/db/migration/V87__worker_type_and_template_type.sql`：Worker、Template 和 Template Revision 类型字段及约束。
- `control-plane/src/test/java/io/agentteams/controlplane/service/TeamWorkerTypeTest.java`：Team 成员和 Revision 类型校验测试。
- `console/tests/features/TeamDetailPage.test.tsx`：Team 成员下拉与 Leader 过滤测试。

### 修改

- `control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRecord.java`：持有 `workerType`，保留旧构造方法并默认 `EXECUTOR`。
- `control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`：读写 `worker_type`。
- `control-plane/src/main/java/io/agentteams/controlplane/service/AgentService.java`：创建请求接收类型，旧调用默认 `EXECUTOR`。
- `control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java`：Agent API 请求和响应增加 `workerType`。
- `control-plane/src/main/java/io/agentteams/controlplane/service/TeamService.java`：添加成员时校验 `LEADER` 角色与 Worker 类型。
- `control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionService.java`：创建 Revision 时校验 Leader Worker、有效成员和 Scope。
- `control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionRepository.java`：补充 Revision 持久化前的 Leader 类型校验查询。
- `control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplate.java`：持有模板类型。
- `control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateRevision.java`：持有从模板继承的类型。
- `control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateService.java`：创建模板和 Revision 时传播类型。
- `control-plane/src/main/java/io/agentteams/controlplane/template/AgentSpecWorkerTemplateProvisioner.java`：实例化时将模板类型传给 AgentSpec、Worker 和 Manifest。
- `control-plane/src/main/java/io/agentteams/controlplane/api/WorkerTemplateController.java`：模板类型进入请求和响应。
- `control-plane/src/main/java/io/agentteams/controlplane/template/JdbcWorkerTemplateRepository.java`：读写模板及 Revision 类型。
- `console/src/api/types.ts`、`console/src/api/workers.ts`：前端 Worker 类型和过滤参数。
- `console/src/api/managementCatalog.ts`：前端 Template 类型。
- `console/src/features/teams/TeamDetailPage.tsx`：Worker 下拉、Leader 过滤和 Revision 选择器。
- `console/src/features/workers/WorkerListPage.tsx`、`console/src/features/workers/WorkerDetailPage.tsx`：展示 Worker 类型。
- `console/src/features/management/ManagementTemplatePage.tsx`：创建 Template 时选择类型并展示类型。

### 测试命令

- Control Plane 聚焦测试：`./mvnw -pl control-plane -Dtest=TeamWorkerTypeTest,AgentListControllerTest,WorkerTemplateServiceTest test`
- Console 聚焦测试：`npm test -- --run console/tests/features/TeamDetailPage.test.tsx console/tests/features/WorkerPages.test.tsx`
- Console 构建：`npm run build --prefix console`
- 全量相关 Java 测试：`./mvnw -pl control-plane test`

## 任务 1：先建立 Worker 类型与数据库兼容层

**文件：**

- 创建 `domain/src/main/java/io/agentteams/domain/agent/WorkerType.java`。
- 创建 `control-plane/src/main/resources/db/migration/V87__worker_type_and_template_type.sql`。
- 修改 `AgentRecord.java`、`AgentRepository.java`、`AgentService.java`、`AgentController.java`。
- 测试 `AgentListControllerTest.java`、`AgentServiceScopeTest.java`。

- [ ] **步骤 1：编写失败测试**：让 Agent 列表断言 `workerType`，并增加创建请求携带 `LEADER` 后持久化记录为 `LEADER` 的服务测试；测试使用 `WorkerType.LEADER`，在枚举和字段尚未存在时必须编译失败。
- [ ] **步骤 2：运行红灯测试**：运行 `./mvnw -pl control-plane -Dtest=AgentListControllerTest,AgentServiceScopeTest test`，预期失败原因是 `workerType` 不存在或响应未返回类型，不接受测试基础设施错误作为红灯。
- [ ] **步骤 3：实现最小代码**：增加 `WorkerType`；在 `AgentRecord` 增加字段并保留旧构造器；在 `agents` 表增加 `worker_type` 默认 `EXECUTOR` 和值约束；同步 Repository SQL、创建输入、Controller 请求与响应。旧 `AgentRecord.create(...)` 与旧 `AgentInput(...)` 全部委托到 `EXECUTOR`。
- [ ] **步骤 4：运行绿灯测试**：再次运行同一命令，预期相关测试全部通过，并确认旧测试夹具无需批量改写。
- [ ] **步骤 5：Commit**：`git add domain control-plane/src/main/java/io/agentteams/controlplane/{api,persistence,service} control-plane/src/main/resources/db/migration control-plane/src/test/java/io/agentteams/controlplane/{api,service}`，提交 `feat(Worker): 增加显式 Worker 类型`。

## 任务 2：实现 Team 成员和 Revision 的服务端约束

**文件：**

- 修改 `control-plane/src/main/java/io/agentteams/controlplane/service/TeamService.java`。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionService.java`、`TeamRevisionRepository.java`。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/api/TeamController.java`（错误响应和成员展示字段）。
- 创建 `control-plane/src/test/java/io/agentteams/controlplane/service/TeamWorkerTypeTest.java`。
- 修改 `control-plane/src/test/java/io/agentteams/controlplane/api/TeamControllerTest.java`、`TeamServiceScopeTest.java`。

- [ ] **步骤 1：编写失败测试**：覆盖 `EXECUTOR + LEADER` 添加成员被拒绝、`LEADER + LEADER` 添加成功、跨 Project 仍先返回授权错误、Revision 的非 Leader Worker 不能作为 Leader、Leader 不在成员列表时被拒绝。
- [ ] **步骤 2：运行红灯测试**：运行 `./mvnw -pl control-plane -Dtest=TeamWorkerTypeTest,TeamServiceScopeTest,TeamControllerTest test`，预期新增类型校验测试失败，既有 Scope 测试不得因测试夹具兼容构造器缺失而失败。
- [ ] **步骤 3：实现最小代码**：TeamService 在确认 Team/Worker 可见后读取 AgentRecord；当角色为 `LEADER` 且类型不是 `LEADER` 时抛出带业务错误标识的异常。Revision 创建沿用当前 UUID 请求，增加 Scope、有效成员、Leader 类型和唯一 Leader 校验；不把类型错误转换成 `AuthorizationException`。
- [ ] **步骤 4：运行绿灯测试**：运行聚焦 Java 测试，确认错误消息和 HTTP 状态均符合测试断言。
- [ ] **步骤 5：Commit**：提交 `feat(团队): 增加 Leader Worker 任命校验`。

## 任务 3：让 Worker Template 继承类型并保证实例化一致

**文件：**

- 修改 `WorkerTemplate.java`、`WorkerTemplateRevision.java`、`WorkerTemplateService.java`、`JdbcWorkerTemplateRepository.java`、`WorkerTemplateController.java`。
- 修改 `AgentSpecWorkerTemplateProvisioner.java` 和 AgentSpec 配置 Manifest 生成边界。
- 修改 `WorkerTemplateServiceTest.java`、`WorkerTemplateControllerTest.java`、`AgentSpecWorkerTemplateProvisionerTest.java`。

- [ ] **步骤 1：编写失败测试**：创建 `LEADER` Template，断言查询返回 `LEADER`；创建 Revision 断言继承模板类型；实例化断言 AgentSpec、Worker 输入和 Manifest 的类型一致。
- [ ] **步骤 2：运行红灯测试**：运行 `./mvnw -pl control-plane -Dtest=WorkerTemplateServiceTest,WorkerTemplateControllerTest,AgentSpecWorkerTemplateProvisionerTest test`，预期新增断言失败。
- [ ] **步骤 3：实现最小代码**：新增模板与 Revision 类型字段及兼容构造器；V87 为现有模板和 Revision 默认 `EXECUTOR`，并为 `worker_template_revisions.worker_type` 增加值约束；实例化适配器接收 Revision 类型，调用 AgentService 时传递类型，并在配置 Manifest 增加 `workerType`。
- [ ] **步骤 4：运行绿灯测试**：再次运行模板聚焦测试，确认旧模板测试仍按 `EXECUTOR` 通过。
- [ ] **步骤 5：Commit**：提交 `feat(模板): 继承 Worker 类型并校验实例化一致性`。

## 任务 4：改造 Console 的下拉选择和类型展示

**文件：**

- 修改 `console/src/api/types.ts`、`console/src/api/workers.ts`、`console/src/api/managementCatalog.ts`。
- 修改 `console/src/features/teams/TeamDetailPage.tsx`。
- 修改 `console/src/features/workers/WorkerListPage.tsx`、`WorkerDetailPage.tsx`。
- 修改 `console/src/features/management/ManagementTemplatePage.tsx`。
- 创建 `console/tests/features/TeamDetailPage.test.tsx`。
- 修改 `console/tests/features/WorkerPages.test.tsx`、`console/tests/api/contracts.test.ts`。

- [ ] **步骤 1：编写失败测试**：渲染 Team 成员页，断言不再出现 `Worker / Agent ID` 文本输入；断言 Worker 下拉显示当前 Project 可见 Worker，切换 Leader 时只保留 `LEADER`；断言当前成员被排除；断言 API 失败显示重试入口。
- [ ] **步骤 2：运行红灯测试**：运行 `npm test -- --run console/tests/features/TeamDetailPage.test.tsx`，预期因页面仍使用文本输入而失败。
- [ ] **步骤 3：实现最小代码**：在 TeamDetailPage 使用 `useWorkers(projectId, { phase: '' })` 和 `useTeamMembers`，按有效成员 ID 过滤选项；角色为 `LEADER` 时仅保留 `workerType === 'LEADER'`；Revision 表单改为当前成员单选/多选；错误状态使用现有 `ErrorState`。类型标签复用现有状态样式，不新建组件体系。
- [ ] **步骤 4：运行绿灯测试**：运行 Team 和 Worker 页面测试，确认类型显示、下拉过滤、空态和错误态通过。
- [ ] **步骤 5：Commit**：提交 `feat(控制台): 使用 Worker 下拉并展示类型约束`。

## 任务 5：全量验证与交付检查

**文件：**

- 验证任务 1–4 涉及的全部文件。
- 如 API 契约测试发现响应字段缺失，修改对应 Controller/Console 类型文件并补测试。

- [ ] **步骤 1：运行 Java 相关全量测试**：`./mvnw -pl control-plane test`，预期退出码为 `0`。
- [ ] **步骤 2：运行 Console 测试和构建**：`npm test --prefix console` 与 `npm run build --prefix console`，预期分别无失败测试且构建退出码为 `0`。
- [ ] **步骤 3：检查迁移与差异**：运行 `git diff --check`，检查 V87 在 Flyway 当前版本之后且没有修改 `sdk/java`；运行 `git status --short`，确认既有会话历史改动仍保留。
- [ ] **步骤 4：浏览器回归**：在真实 OIDC Project 上验证 Team 成员下拉、Leader 过滤、类型错误、Template 类型展示和 Worker 类型展示；记录环境、镜像和验证时间。
- [ ] **步骤 5：Commit**：提交测试或文档修订，提交信息使用 `test(团队): 补齐 Worker 类型回归验证` 或 `docs(团队): 更新类型功能验收记录`。
