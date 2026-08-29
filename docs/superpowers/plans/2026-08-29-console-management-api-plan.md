# AgentTeams 管理端后端 API 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Console 提供 Project、Team、Task、Worker、事件和 Manager Session 所需的安全、可分页、可重连 API。

**架构：** 延续 Spring Boot、JdbcTemplate、PostgreSQL 和现有 Controller/Service/Repository 分层。列表接口统一使用 Cursor 分页，写接口保留 `Idempotency-Key` 和 `expectedVersion`；Manager Session 列表复用现有持久化事件模型，Conversation 运行时、适配器和 E2E 由独立计划负责。

**技术栈：** Java 17、Spring Boot 3.4.5、Spring MVC、Spring Security Resource Server、JdbcTemplate、PostgreSQL、Flyway、JUnit 5、MockMvc、Testcontainers。

**执行顺序：** 先执行本计划完成管理 API 和 Manager Session 列表，再执行 Console SPA 计划，最后执行 Conversation 运行时与 E2E 计划；三份计划共用的部署和测试文件按此顺序追加修改。

---

## 文件清单

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/CursorPage.java`，统一列表响应。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/CursorPageRequest.java`，统一游标参数校验。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`、`AgentRepository.java`、`TeamRepository.java`、`ProjectRepository.java`，增加受作用域约束的列表查询。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java`、`AgentService.java`、`TeamService.java`，暴露查询模型。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java`、`AgentController.java`、`TeamController.java`、`ProjectController.java`，增加列表资源。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskEventController.java`，提供事件历史和 SSE。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationRepository.java`、`InternalWorkerOperationController.java`，提供操作记录查询。
- 修改：`manager/src/main/java/io/agentteams/manager/session/ManagerSessionRepository.java`、`JdbcManagerSessionRepository.java`、`ManagerSessionServiceFacade.java`、`ManagerSessionController.java`，增加会话列表。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/api/ControlPlaneControllerTest.java`、`TaskControllerTest.java`、`TeamControllerTest.java`、`AgentSpecControllerTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/api/TaskListControllerTest.java`、`AgentListControllerTest.java`、`ProjectListControllerTest.java`、`TaskEventControllerTest.java`。
- 修改：`manager/src/test/java/io/agentteams/manager/api/ManagerSessionControllerTest.java`、`manager/src/test/java/io/agentteams/manager/session/JdbcManagerSessionRepositoryTest.java`。

### 任务 1：建立统一分页与查询参数模型

**文件：**

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/CursorPageRequest.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/CursorPage.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/api/CursorPageRequestTest.java`

- [ ] **步骤 1：编写失败测试。** 覆盖默认 page size、最大 page size、空 cursor、过长 cursor 和非法排序字段。

```java
@Test
void rejectsPageSizeAboveMaximum() {
    assertThatThrownBy(() -> new CursorPageRequest("", 201, "updatedAt", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pageSize");
}
```

- [ ] **步骤 2：运行测试验证失败。**

运行：`mvn -q -pl control-plane -Dtest=CursorPageRequestTest test`

预期：FAIL，原因是分页类型尚不存在。

- [ ] **步骤 3：实现最少类型。** `CursorPageRequest` 只允许 `pageSize` 为 1 至 200，`sort` 只允许调用方声明的稳定字段；`CursorPage<T>` 包含 `items`、`nextCursor`、`hasMore` 和 `serverTime`。

- [ ] **步骤 4：运行测试验证通过。**

运行：`mvn -q -pl control-plane -Dtest=CursorPageRequestTest test`

预期：PASS。

- [ ] **步骤 5：Commit。**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/api/CursorPage*.java control-plane/src/test/java/io/agentteams/controlplane/api/CursorPageRequestTest.java
git commit -m "feat(API): 增加统一游标分页模型"
```

### 任务 2：实现 Project、Team 和 Agent 列表

**文件：**

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/project/ProjectRepository.java`、`ProjectAuthorizationService.java`、`ProjectController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TeamRepository.java`、`service/TeamService.java`、`api/TeamController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`、`service/AgentService.java`、`api/AgentController.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/api/ProjectListControllerTest.java`、`TeamControllerTest.java`、`AgentListControllerTest.java`

- [ ] **步骤 1：编写失败测试。** 分别验证作用域过滤、名称搜索、状态过滤、稳定排序和 `nextCursor`。

```java
@Test
void listsOnlyAgentsVisibleToAuthenticatedProject() {
    mockMvc.perform(get("/api/v1/agents?projectId=project-a&pageSize=20")
                    .with(principalFor("tenant-a", "project-a", "team-a", "agent:read")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].projectId", everyItem(is("project-a"))));
}
```

- [ ] **步骤 2：运行测试验证失败。**

运行：`mvn -q -pl control-plane -Dtest=ProjectListControllerTest,AgentListControllerTest test`

预期：FAIL，原因是列表路径尚不存在。

- [ ] **步骤 3：实现 Repository 查询。** 使用 `(updated_at, id)` 作为稳定游标，查询条件先应用租户和 Project 作用域，再应用搜索与状态；禁止把完整 JSON 作用域拼入 SQL 字符串。

- [ ] **步骤 4：实现 Service 和 Controller。** 新增 `GET /api/v1/projects`、`GET /api/v1/teams`、`GET /api/v1/agents`，响应统一为 `CursorPage`；Team 和 Agent 的现有详情、创建和操作接口保持兼容。

- [ ] **步骤 5：运行测试验证通过。**

运行：`mvn -q -pl control-plane -Dtest=ProjectListControllerTest,TeamControllerTest,AgentListControllerTest test`

预期：PASS，并验证跨租户、跨 Project 请求返回 `403` 或空结果，不泄露资源存在性。

- [ ] **步骤 6：Commit。**

```bash
git add control-plane/src/main/java control-plane/src/test/java
git commit -m "feat(API): 增加 Project Team Agent 列表查询"
```

### 任务 3：实现 Task 列表、摘要和事件查询

**文件：**

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskEventController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/DomainEventRepository.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/api/TaskListControllerTest.java`、`TaskEventControllerTest.java`

- [ ] **步骤 1：编写失败测试。** 覆盖 `phase`、Team、Worker、创建人、时间范围和关键词过滤；事件接口覆盖 `after` 游标和 `Last-Event-ID`。

```java
@Test
void taskEventsResumeAfterLastEventId() {
    mockMvc.perform(get("/api/v1/tasks/{id}/events", taskId)
                    .header("Last-Event-ID", "7")
                    .with(principalFor("tenant-a", "project-a", "team-a", "task:read")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(containsString("id: 8")));
}
```

- [ ] **步骤 2：运行测试验证失败。**

运行：`mvn -q -pl control-plane -Dtest=TaskListControllerTest,TaskEventControllerTest test`

预期：FAIL，原因是 Task 列表和事件 Controller 尚不存在。

- [ ] **步骤 3：实现查询和响应。** 新增 `GET /api/v1/tasks`；列表项包含任务详情页所需的 `id`、`title`、`phase`、`priority`、作用域摘要、Team/Worker 引用、`updatedAt` 和 `version`。事件响应只返回脱敏事件，不返回凭据、完整 Secret、容器日志或未授权作用域数据。

- [ ] **步骤 4：实现 SSE。** 首次请求返回 `after` 之后的事件；事件带递增 ID、固定事件类型和 JSON data。查询不到新事件时保持短轮询策略由客户端决定，服务端不创建无界连接状态。

- [ ] **步骤 5：运行测试验证通过。**

运行：`mvn -q -pl control-plane -Dtest=TaskListControllerTest,TaskEventControllerTest test`

预期：PASS，并验证 `401`、`403`、`409` 映射不被列表和事件接口吞掉。

- [ ] **步骤 6：Commit。**

```bash
git add control-plane/src/main/java control-plane/src/test/java
git commit -m "feat(API): 增加 Task 列表与事件流"
```

### 任务 4：提供 Worker 操作记录查询

**文件：**

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/worker/InternalWorkerOperationController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/worker/WorkerOperationRepositoryTest.java`、`InternalWorkerOperationControllerTest.java`

- [ ] **步骤 1：编写失败测试。** 验证 `GET /api/v1/agents/{agentId}/operations` 返回按 `createdAt DESC, id DESC` 排序的分页记录，并且只能看到当前作用域 Agent。

- [ ] **步骤 2：运行测试验证失败。**

运行：`mvn -q -pl control-plane -Dtest=InternalWorkerOperationControllerTest test`

预期：FAIL，原因是操作列表路径不存在。

- [ ] **步骤 3：实现查询。** 复用 `WorkerOperation` 和现有观察记录，响应包含类型、状态、请求摘要、失败分类、关联版本、创建/更新时间和当前观察结论；不返回 Token、Secret 或容器日志。

- [ ] **步骤 4：运行测试验证通过。**

运行：`mvn -q -pl control-plane -Dtest=WorkerOperationRepositoryTest,InternalWorkerOperationControllerTest test`

预期：PASS。

- [ ] **步骤 5：Commit。**

```bash
git add control-plane/src/main/java control-plane/src/test/java
git commit -m "feat(API): 增加 Worker 操作记录查询"
```

### 任务 5：扩展 Manager Session 列表接口

**文件：**

- 修改：`manager/src/main/java/io/agentteams/manager/session/ManagerSessionRepository.java`
- 修改：`manager/src/main/java/io/agentteams/manager/session/JdbcManagerSessionRepository.java`
- 修改：`manager/src/main/java/io/agentteams/manager/session/ManagerSessionServiceFacade.java`
- 修改：`manager/src/main/java/io/agentteams/manager/api/ManagerSessionController.java`
- 测试：`manager/src/test/java/io/agentteams/manager/api/ManagerSessionControllerTest.java`、`manager/src/test/java/io/agentteams/manager/session/JdbcManagerSessionRepositoryTest.java`

- [ ] **步骤 1：编写失败测试。** 验证会话列表按 Project 和 actor 过滤、按更新时间倒序分页，并返回脱敏的 Team、Worker 和 Task 摘要。

```java
@Test
void listsSessionsOnlyForCurrentProjectAndActor() {
    mockMvc.perform(get("/api/v1/manager/sessions?projectId=project-a&pageSize=20")
                    .with(principalFor("tenant-a", "project-a", "team-a", "session:read")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].projectId", everyItem(is("project-a"))))
            .andExpect(jsonPath("$.items[*].actorId", everyItem(is("actor-a"))));
}
```

- [ ] **步骤 2：运行测试验证失败。**

运行：`mvn -q -pl manager -Dtest=ManagerSessionControllerTest,JdbcManagerSessionRepositoryTest test`

预期：FAIL，原因是会话列表方法尚不存在。

- [ ] **步骤 3：实现 Repository 和 Service。** 增加按租户、Project、Team、actor 和更新时间游标查询会话的方法；列表项只返回会话标识、状态、作用域摘要、最近消息摘要和更新时间。

- [ ] **步骤 4：扩展 Controller。** 保留现有 `/api/v1/manager/sessions` 的创建、详情、消息、事件和取消路径，并增加分页 `GET /api/v1/manager/sessions`；列表读取校验租户、Project、Team 和 actor 作用域。

```text
GET  /api/v1/manager/sessions
```

- 会话创建、消息发送和取消继续要求 `Idempotency-Key`；所有读取请求校验租户、Project、Team 和 actor 作用域。

- [ ] **步骤 5：运行测试验证通过。**

运行：`mvn -q -pl manager -Dtest=ManagerSessionControllerTest,JdbcManagerSessionRepositoryTest test`

预期：PASS，包含分页、详情读取、事件读取、幂等、版本冲突、取消和权限测试。

- [ ] **步骤 6：Commit。**

```bash
git add manager/src/main/java manager/src/test/java
git commit -m "feat(API): 增加 Manager Session 列表接口"
```

### 任务 6：执行跨模块验证

**文件：**

- 修改：`README.md`，记录新接口和本地开发代理约定。
- 修改：`scripts/validate-kind-manifests.py`，校验 Conversation Service 的部署配置。
- 测试：`integration-tests/src/test/java/io/agentteams/integration/ConsoleApiInfrastructureIT.java`

- [ ] **步骤 1：编写失败的集成测试。** 使用 Testcontainers PostgreSQL、NATS 和 Mock Runtime 验证列表作用域、Task 事件 cursor、Worker 操作记录和 Conversation cancel。

- [ ] **步骤 2：运行测试验证失败。**

运行：`source deploy/dev-env.sh && mvn -q -Pintegration-tests -pl integration-tests -am -Dtest=ConsoleApiInfrastructureIT verify`

预期：FAIL，原因是集成测试引用的 API 尚未全部实现。

- [ ] **步骤 3：实现测试适配。** 只通过 HTTP/JSON/SSE 和公开端口访问服务，不引用 Control Plane 或 Manager 的 persistence package。

- [ ] **步骤 4：运行测试验证通过。**

运行：`source deploy/dev-env.sh && mvn -q -Pintegration-tests -pl integration-tests -am -Dtest=ConsoleApiInfrastructureIT verify`

预期：PASS。

- [ ] **步骤 5：Commit。**

```bash
git add README.md scripts/validate-kind-manifests.py integration-tests/src/test/java/io/agentteams/integration/ConsoleApiInfrastructureIT.java
git commit -m "test(API): 验证 Console 后端接口闭环"
```

### 任务 7：全量验证

- [ ] **步骤 1：运行 Java 单元测试。** `mvn -q test`，预期全部通过。
- [ ] **步骤 2：运行 Docker 集成测试。** `source deploy/dev-env.sh && mvn -q -Pintegration-tests verify`，预期全部通过。
- [ ] **步骤 3：运行部署校验。** `python3 scripts/validate-kind-manifests.py && helm lint deploy/helm/agentteams-java`，预期输出 `KIND_MANIFESTS_OK` 且 Helm 无失败 Chart。
- [ ] **步骤 4：Commit。** `git commit --allow-empty -m "test(API): 完成 Console 后端验证"`
