# 任务过程可见性二期（子任务拆解、协作语义事件与 DAG 下钻）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** agent 可通过 MCP 工具把主任务拆解为结构化子任务并逐个推进状态；拆解与状态事件作为过程事件落库，console DAG 展示子任务节点并支持点击下钻过滤时间线。

**架构：** agent 侧新增 `plan_subtasks` / `update_subtask_status` 两个 MCP 工具，经 control-plane REST 直调（不经 gateway TASK_EVENT gRPC 链路）；control-plane 的 `SubtaskService` 双写 `task_subtasks` 树投影与 `task_process_events` 事件（subtask.* 类型，与 runtime 事件同表同序）；runtime 在 prompt 前注入含 taskId 的平台上下文块，使 agent 能定位当前任务；console 在 DAG 节点上增加状态色、title 文本（来自 subtask.planned 事件窗口）、依赖虚线边与点击下钻。

**技术栈：** Java 21 / Spring Boot（control-plane、runtime）、零依赖 Python stdio MCP server（scripts）、React + TypeScript + vite/vitest（console）、Testcontainers（control-plane 集成测试）。

**规格：** `docs/superpowers/specs/2026-09-08-task-process-visibility-phase2-design.md`（11 项决策记录 + 4 缺口 + 验收三层）。

---

## 全局约束（每个任务开始前重读）

1. **两条公理**：中间事件 best-effort 永不威胁任务终态（任何 subtask 失败都不改变主任务 phase）；双层校验不信任上游（control-plane 校验枚举/数量/归属，不信任 MCP 传参）。
2. **状态语义**：`update_subtask_status` 只接受 `RUNNING | SUCCEEDED | FAILED | CANCELLED`，不接受 `PENDING` / `BLOCKED`；不设状态迁移单向约束（agent 自治）。
3. **plan 全量声明式语义**：以请求清单为准——新 subtaskId 以 `PENDING` 插入并逐条 append `subtask.planned`；已存在的 subtaskId 保留现状（不重置状态、不重发事件）；清单中不存在的历史行从树中删除。
4. **事件归属**：subtask.* 事件 payload 携带 `subtaskId`；`tool.*` 事件的子任务归属由 console 时间窗口推断（subtask.started → 下一个 subtask 终态事件之间的 tool.* 归该子任务），best-effort。
5. commit 规范：`feat:/test:/chore:` 中文描述（chinese-commit-conventions）。

## 文件结构

| 文件 | 操作 | 职责 |
|---|---|---|
| `runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java` | 修改 | `promptText` 注入含 taskId 的平台上下文块（缺口四） |
| `runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java` | 修改 | 断言上行消息含平台上下文块 |
| `control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskService.java` | 创建 | plan 声明式同步 + 状态推进，双写树与过程事件 |
| `control-plane/src/main/java/io/agentteams/controlplane/task/TaskTreeRepository.java` + `JdbcTaskTreeRepository.java` | 修改 | 新增 `deleteOthers`（先删后 upsert 的替换语义） |
| `control-plane/src/main/java/io/agentteams/controlplane/task/TaskRunObservationRepository.java` + `JdbcTaskRunObservationRepository.java` | 修改 | 新增 `latestRunId(taskId)`（REST 侧反查 run） |
| `control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java` | 创建 | `PUT /api/v1/tasks/{taskId}/subtasks` 与 `PUT .../subtasks/{subtaskId}/status`，鉴权 + 幂等 |
| `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskServiceTest.java` | 创建 | Testcontainers：替换语义、事件落库、校验拒绝 |
| `control-plane/src/test/java/io/agentteams/controlplane/api/SubtaskControllerTest.java` | 创建 | REST 鉴权/幂等/404/400 |
| `scripts/agentteams-task-mcp.py` | 修改 | 新增 `plan_subtasks` / `update_subtask_status` 工具 |
| `scripts/test_agentteams_task_mcp.py` | 修改 | 新工具单测（mock HTTP） |
| `deploy/kind-qwenpaw-task-mcp.yaml` | 修改 | ConfigMap 内嵌脚本同步 |
| `console/src/features/tasks/TaskInfoPanel.tsx` | 修改 | subtask.* 中文标签、payload 摘要、tool.* 子任务归属（纯函数） |
| `console/src/features/tasks/TaskInfoPanel.test.ts` | 创建 | 归属与摘要纯函数测试 |
| `console/src/features/tasks/TaskDag.tsx` | 修改 | 节点 title/状态色已有、新增 onSelect、依赖虚线边、选中高亮 |
| `console/src/features/tasks/TaskDetailPage.tsx` | 修改 | 提升 `selectedSubtaskId`，下钻过滤时间线 |
| `scripts/run-kind-subtask-decomposition.py` | 创建 | kind 端到端验收：mock 剧本 → 断言树/事件/DAG |

不修改：agent-gateway（subtask 事件不经 gRPC 链路）、domain、manager、agent-worker（runtime 模块在 agent-worker 模块内——以 `ls agent-worker/src/main/java/io/agentteams/` 实际为准，任务 1 开头先确认 QwenPawHttpRuntimePort 所属 Maven 模块）。

---

### 任务 1：runtime prompt 平台上下文注入

agent 看不到 taskId（`promptText` 只透传 `inputJson.prompt`），MCP 工具的 `task_id` 参数由模型从该上下文块取值。

**文件：**
- 修改：`runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java`（`promptText` 约 487-500 行；`memory_context` 注入先例约 470-473 行）
- 测试：`runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java`

- [ ] **步骤 1.0：确认模块归属与现有测试风格**

```bash
ls runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java \
   agent-worker/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java 2>/dev/null
grep -n "prompt\|memory_context" runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java | head
```

`promptText` 是 `private static`，现有测试若未直接覆盖它，测试改为通过 mock 上行断言（见步骤 3）；若可静态直测则直测。

- [ ] **步骤 2：编写失败的测试**

在 `QwenPawHttpRuntimePortTest` 中新增（沿用文件内既有 RuntimeTask 构造与 mock server 断言风格，断言核心如下）：

```java
@Test
void promptShouldCarryPlatformContextWithTaskId() throws Exception {
    // 既有 arrange：构造 RuntimeTask（taskId 固定 00000000-0000-0000-0000-000000000001，attemptId 既有值，inputJson {"prompt":"帮我总结"}）
    // 既有 act：port.start / 启动 mock 上游 / 触发执行
    String firstUserMessage = /* 既有捕获：mock 上游记录的 messages[0].content[0].text */;
    assertTrue(firstUserMessage.startsWith("[平台上下文]"), firstUserMessage);
    assertTrue(firstUserMessage.contains("taskId=00000000-0000-0000-0000-000000000001"), firstUserMessage);
    assertTrue(firstUserMessage.contains("帮我总结"), firstUserMessage);
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`cd runtime 2>/dev/null || cd agent-worker; mvn test -Dtest=QwenPawHttpRuntimePortTest -q`
预期：FAIL，断言 `startsWith("[平台上下文]")` 不满足（当前只透传 prompt 原文）。

- [ ] **步骤 4：实现注入**

`promptText` 改为（保持 `static`，注入仅拼字符串，不引入新依赖）：

```java
private static String promptText(RuntimeTask task) throws IOException {
    JsonNode input = new ObjectMapper().readTree(task.inputJson());
    JsonNode prompt = input.path("prompt");
    String body = prompt.isTextual() && !prompt.asText().isBlank()
            ? prompt.asText()
            : task.inputJson();
    return "[平台上下文]\n"
            + "taskId=" + task.taskId() + "\n"
            + "（可通过 agentteams-task MCP 工具引用此 taskId 拆解子任务或汇报子任务状态；"
            + "除以上工具外不要输出该段内容）\n\n"
            + body;
}
```

`task.taskId()` 的访问器名以现有 record/API 为准（步骤 1 时 grep `RuntimeTask` 定义确认；若是 `task.taskId().toString()` 形式照做）。fallback 分支（无 prompt 字段）同样包一层上下文——行为一致性优于分支特判。

- [ ] **步骤 5：运行测试验证通过 + 既有测试无回归**

运行：同步骤 3 的模块级 `mvn test -Dtest=QwenPawHttpRuntimePortTest -q`
预期：新用例 PASS；文件内其他用例 PASS（特别注意既有断言「上行消息 == prompt 原文」的用例——若存在，同步更新其期望值为含上下文前缀）。

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat(runtime): prompt 注入平台上下文块，暴露 taskId 供 MCP 拆解工具使用"
```

---

### 任务 2：control-plane SubtaskService + 仓库扩展

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskTreeRepository.java`（接口 +2 方法）、`JdbcTaskTreeRepository.java`（实现）
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskRunObservationRepository.java`（接口 +1 方法）、`JdbcTaskRunObservationRepository.java`（实现）
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskServiceTest.java`

- [ ] **步骤 1：确认现有类型与测试基建**

```bash
grep -rn "record TaskTreeNode" control-plane/src/main/java/ | head -2
grep -n "interface TaskTreeRepository" -A 15 control-plane/src/main/java/io/agentteams/controlplane/task/TaskTreeRepository.java
grep -rn "class SubtaskServiceTest\|@Testcontainers\|PostgreSQLContainer" control-plane/src/test/java/ | head -5
grep -rn "TaskProcessEvent\b" control-plane/src/main/java/io/agentteams/application/api/TaskProcessEvent.java | head
```

记录：`TaskTreeNode` record 构造签名（预期 `(taskId, parentTaskId, sequence, status, dependencyIds, updatedAt)`，以 grep 实际为准）；`TaskProcessEvent` 工厂/构造方式；control-plane 现有 Testcontainers 测试的装配基类（一期 `TaskProcessEventServiceTest` / 树投影相关测试必有先例，沿用同款注解与容器）。

- [ ] **步骤 2：仓库接口扩展（先写接口与 SQL，属实现前置，测试在步骤 3）**

`TaskTreeRepository` 增加：

```java
/** 声明式同步：删除 run 下不在 keepTaskIds 中的子任务投影行，返回删除行数。 */
int deleteOthers(ExecutionContext context, UUID runId, Collection<UUID> keepTaskIds);
```

`JdbcTaskTreeRepository` 实现（`IN` 占位符按 keepTaskIds 动态拼接，参数数组长度必须与占位符数一致——见记忆库「JdbcTemplate 参数化 SQL 单测强制规范」）：

```java
@Override
public int deleteOthers(ExecutionContext context, UUID runId, Collection<UUID> keepTaskIds) {
    if (keepTaskIds.isEmpty()) {
        return jdbc.update("DELETE FROM task_subtasks WHERE run_id = ?", runId);
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(keepTaskIds.size(), "?"));
    Object[] params = new Object[keepTaskIds.size() + 1];
    params[0] = runId;
    int i = 1;
    for (UUID id : keepTaskIds) params[i++] = id;
    return jdbc.update("DELETE FROM task_subtasks WHERE run_id = ? AND task_id NOT IN (" + placeholders + ")", params);
}
```

`TaskRunObservationRepository` 增加：

```java
/** REST 侧反查：任务最近一次运行的 runId（无 run 返回 empty）。 */
Optional<UUID> latestRunId(UUID taskId);
```

`JdbcTaskRunObservationRepository` 实现（列名以该文件既有 SQL 为准，`task_runs` 表按 `task_id` 过滤取最新一条的 `id`；排序键用该表自增主键或 `created_at`，与既有查询一致）：

```java
@Override
public Optional<UUID> latestRunId(UUID taskId) {
    return jdbc.query("SELECT id FROM task_runs WHERE task_id = ? ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(), taskId);
}
```

- [ ] **步骤 3：编写失败的测试（SubtaskServiceTest，沿用步骤 1 确认的容器装配）**

测试类骨架 + 四个核心用例（`ExecutionContext`/`TaskProcessEvent` 的构造方式照抄同包既有测试）：

```java
@Test
void planInsertsPendingNodesAndEmitsPlannedEvents() {
    // arrange：插一条 RUNNING 任务 + 一条 run（沿用既有测试的插入 helper）
    // act：
    List<TaskTreeNode> nodes = service.plan(context, taskId, List.of(
            new SubtaskSpec(UUID.fromString("...a1"), "抓取邮件", 1, List.of()),
            new SubtaskSpec(UUID.fromString("...a2"), "生成摘要", 2, List.of("...a1"))));
    // assert：两个节点 PENDING、parentTaskId=taskId、sequence 正确；
    // process-events 回放出现 2 条 subtask.planned，payload 含 subtaskId 与 title；
    // 事件 sequence 单调递增（与 runtime 事件同表同序）。
}

@Test
void planIsDeclarativeSyncKeepingExistingAndRemovingStale() {
    // 首次 plan a1,a2 → 二次 plan a2,a3：
    // a2 保留原状态（先 updateStatus(a2, RUNNING) 再 plan），a1 行被删除，a3 新增 PENDING；
    // 只对 a3 发一条 planned 事件（对 a2 不重发）。
}

@Test
void updateStatusRejectsPendingAndBlocked() {
    // updateStatus(taskId, subId, "BLOCKED", null) → IllegalArgumentException，且不落任何事件。
}

@Test
void updateStatusUpsertsNodeAndEmitsTypedEvent() {
    // updateStatus(..., "RUNNING", null) → 树中该行状态 RUNNING + subtask.started 事件；
    // updateStatus(..., "FAILED", "上游超时") → 状态 FAILED + subtask.failed 事件 payload 含 note。
}
```

`SubtaskSpec` 与 `SubtaskStatusUpdate` 用 `SubtaskService` 内嵌 record（任务 3 的 REST 请求体直接映射，避免重复类型）：

```java
public record SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds) {}
public record SubtaskStatusUpdate(String status, String note) {}
```

- [ ] **步骤 4：运行测试验证失败**

运行：`cd control-plane && mvn test -Dtest=SubtaskServiceTest -q`
预期：编译失败（`SubtaskService` 不存在）。

- [ ] **步骤 5：实现 SubtaskService**

```java
package io.agentteams.controlplane.task;

/** 子任务投影与协作语义事件：声明式同步 + 状态推进，双写任务树与过程事件（同表同序）。 */
@Service
public final class SubtaskService {
    public static final int MAX_SUBTASKS = 20;
    static final Set<String> UPDATABLE_STATUSES = Set.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> TREE_STATUSES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED");

    private final TaskTreeRepository tree;
    private final TaskRunObservationRepository runs;
    private final TaskProcessEventService processEvents;
    private final Clock clock;

    public SubtaskService(TaskTreeRepository tree, TaskRunObservationRepository runs,
            TaskProcessEventService processEvents, Clock clock) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.processEvents = Objects.requireNonNull(processEvents, "processEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds) {}

    public List<TaskTreeNode> plan(ExecutionContext context, UUID taskId, List<SubtaskSpec> specs) {
        if (specs.isEmpty() || specs.size() > MAX_SUBTASKS) {
            throw new IllegalArgumentException("subtasks size must be 1.." + MAX_SUBTASKS);
        }
        UUID runId = runs.latestRunId(taskId).orElseThrow(() ->
                new IllegalArgumentException("task has no run yet; decomposition requires an active run"));
        Instant at = clock.instant();
        tree.deleteOthers(context, runId, specs.stream().map(SubtaskSpec::subtaskId).toList());
        List<TaskTreeNode> result = new ArrayList<>(specs.size());
        for (SubtaskSpec spec : specs) {
            Objects.requireNonNull(spec.subtaskId(), "subtaskId");
            validateTreeNodeStatus(spec, taskId);
            TaskTreeNode existing = findNode(context, runId, spec.subtaskId());
            TaskTreeNode node = existing != null ? existing
                    : new TaskTreeNode(spec.subtaskId(), taskId, spec.sequence(), "PENDING",
                            List.copyOf(spec.dependencyIds()), at);
            tree.upsert(context, runId, node);
            if (existing == null) {
                processEvents.append(context, TaskProcessEvent.builder()
                        .taskId(taskId).runId(runId).eventType("subtask.planned")
                        .visibility(TaskEventVisibility.REQUESTER)
                        .payload(json(Map.of("subtaskId", spec.subtaskId().toString(),
                                "title", spec.title(), "sequence", spec.sequence())))
                        .occurredAt(at).build());
            }
            result.add(node);
        }
        return List.copyOf(result);
    }

    public TaskTreeNode updateStatus(ExecutionContext context, UUID taskId, UUID subtaskId,
            String status, String note) {
        if (!UPDATABLE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + UPDATABLE_STATUSES);
        }
        UUID runId = runs.latestRunId(taskId).orElseThrow(() ->
                new IllegalArgumentException("task has no run yet"));
        TaskTreeNode existing = findNode(context, runId, subtaskId);
        if (existing == null) throw new IllegalArgumentException("subtask not found: " + subtaskId);
        TaskTreeNode node = new TaskTreeNode(subtaskId, existing.parentTaskId(), existing.sequence(),
                status, existing.dependencyIds(), clock.instant());
        tree.upsert(context, runId, node);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subtaskId", subtaskId.toString());
        if (note != null && !note.isBlank()) payload.put("note", note);
        processEvents.append(context, TaskProcessEvent.builder()
                .taskId(taskId).runId(runId).eventType("subtask." + status.toLowerCase(Locale.ROOT))
                .visibility(TaskEventVisibility.REQUESTER)
                .payload(json(payload)).occurredAt(clock.instant()).build());
        return node;
    }

    private TaskTreeNode findNode(ExecutionContext context, UUID runId, UUID subtaskId) {
        return tree.find(context, runId).stream()
                .filter(n -> n.taskId().equals(subtaskId)).findFirst().orElse(null);
    }
}
```

实现注意：
- 规格中「subtask.* 加入 ALLOWED_RUNTIME_EVENT_TYPES 同层的白名单体系」在本服务的落点是：事件类型只由本服务从受控枚举生成（plan 固定 `subtask.planned`，update 由 UPDATABLE_STATUSES 小写派生），REST 层（任务 3）再校验入参枚举——`ALLOWED_RUNTIME_EVENT_TYPES` 本身是 runtime gRPC 上报路径的集合，本路径不经它，无需修改。
- `TaskProcessEvent` 的构造/builder 与 `json(Map)` 序列化照抄 `ControlPlaneTaskExecutionObservationAdapter.recordProcess` 的既有写法（grep 该文件，复用同一 ObjectMapper 工具）。
- `tree.find(context, runId)` 方法名以接口实际为准（步骤 1 已确认）。
- 事务边界：plan 的「先删后插」包在 `@Transactional`（service 方法或调用方）——一期树投影是否已有事务包裹，以 `ControlPlaneTaskExecutionObservationAdapter` 现状为准；若该处无事务则此处保持一致（单条 SQL 幂等由 ON CONFLICT 保证，替换语义的原子性依赖步骤 3 用例二验证）。
- `Clock` bean：control-plane 已有（一期 `TaskProcessEventService` 同款注入方式）。

- [ ] **步骤 6：运行测试验证通过**

运行：`cd control-plane && mvn test -Dtest=SubtaskServiceTest -q`
预期：4 用例 PASS。

- [ ] **步骤 7：Commit**

```bash
git add -A && git commit -m "feat(control-plane): SubtaskService 声明式拆解同步与子任务状态事件"
```

---

### 任务 3：SubtaskController REST 端点

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/api/SubtaskControllerTest.java`

- [ ] **步骤 1：确认鉴权与幂等先例**

```bash
sed -n '25,60p' control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java
grep -n "requireScope\|requireIdempotencyKey" control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java | head
grep -rn "requireScope" control-plane/src/main/java/io/agentteams/controlplane/security/PrincipalContext.java
```

记录：`requireIdempotencyKey` 是 TaskController 内私有方法还是共享工具（私有则复制同款 4 行）；`PrincipalContext.requireScope(...)` 参数语义（预期是「请求声明的团队作用域 JSON」，agent 上报路径在 AgentController 如何传）；SubtaskService 的 `ExecutionContext` 如何构造（TaskController/AgentController 既有写法）。

- [ ] **步骤 2：编写失败的测试（沿用既有 controller 测试装配，无则 standalone MockMvc）**

```java
@Test
void putSubtasksRequiresIdempotencyKey() throws Exception {
    mockMvc.perform(put("/api/v1/tasks/{id}/subtasks", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subtasks\":[{\"subtaskId\":\"" + UUID.randomUUID()
                            + "\",\"title\":\"t\",\"sequence\":1,\"dependencyIds\":[]}]}"))
            .andExpect(status().isBadRequest());
}

@Test
void putSubtasksValidatesSizeAndStatus() throws Exception {
    // 21 个子任务 → 400；status 字段含 "BLOCKED" 的 update → 400。
}

@Test
void putSubtasksReturnsNodesAndUnknownTaskIs404() throws Exception {
    // 正常 plan → 200，响应 nodes 数组含 PENDING 节点；
    // taskId=随机 UUID → 404。
}

@Test
void putSubtaskStatusForwardToService() throws Exception {
    // PUT .../subtasks/{sid}/status {"status":"RUNNING"} → 200 且树状态变更（或断言 service 被调用，视装配方式）。
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`cd control-plane && mvn test -Dtest=SubtaskControllerTest -q`
预期：404（路由不存在）或编译失败。

- [ ] **步骤 4：实现 Controller**

```java
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/subtasks")
public final class SubtaskController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final SubtaskService service;

    public record PlanRequest(List<SubtaskService.SubtaskSpec> subtasks) {}
    public record PlanResponse(List<TaskTreeNode> nodes) {}

    @PutMapping
    public PlanResponse plan(@PathVariable UUID taskId, @RequestBody PlanRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        requireIdempotencyKey(idempotencyKey);
        PrincipalContext.requireScope(request.subtasks() == null ? null : "subtasks"); // ← 参数语义以步骤 1 结论为准
        ExecutionContext context = /* 步骤 1 确认的构造方式（AgentController 上报路径同款） */;
        return new PlanResponse(service.plan(context, taskId, request.subtasks()));
    }

    @PutMapping("/{subtaskId}/status")
    public TaskTreeNode updateStatus(@PathVariable UUID taskId, @PathVariable UUID subtaskId,
            @RequestBody SubtaskService.SubtaskStatusUpdate request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        requireIdempotencyKey(idempotencyKey);
        PrincipalContext.requireScope(/* 同上 */);
        ExecutionContext context = /* 同上 */;
        return service.updateStatus(context, taskId, subtaskId, request.status(), request.note());
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
    }
}
```

实现注意：
- `PrincipalContext.requireScope(...)` 的参数是既有 API 强制的「作用域声明」——务必按步骤 1 在 AgentController/TaskController 找到真实语义后填写；若 requireScope 语义是「团队作用域与 PrincipalContext 匹配校验」，则从 task 记录解析其 scope 传入。禁止为了通过编译传占位字符串上线。
- 404 语义：`IllegalArgumentException`（task 无 run / subtask 不存在）映射 `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` 或既有全局异常处理器的等价方式——看 `TaskController` 对未知任务如何返回 404，保持一致。
- 鉴权注解（若工程有 `@PreAuthorize` / 过滤器作用域要求）按 AgentController 同款补齐。

- [ ] **步骤 5：运行测试验证通过**

运行：`cd control-plane && mvn test -Dtest=SubtaskControllerTest -q`
预期：4 用例 PASS。

- [ ] **步骤 6：control-plane 模块全量回归**

运行：`cd control-plane && mvn test -q`
预期：全绿（特别关注一期 `TaskTree*` / `TaskProcess*` 既有测试未被接口扩展破坏）。

- [ ] **步骤 7：Commit**

```bash
git add -A && git commit -m "feat(control-plane): 子任务拆解与状态推进 REST 端点（鉴权/幂等/404 语义）"
```

---

### 任务 4：MCP 拆解工具（python）

**文件：**
- 修改：`scripts/agentteams-task-mcp.py`（`TOOLS` 常量 + 工具函数 + dispatch）
- 测试：`scripts/test_agentteams_task_mcp.py`

- [ ] **步骤 1：编写失败的测试**

在 `test_agentteams_task_mcp.py` 中沿用既有 mock HTTP server 模式新增：

```python
def test_plan_subtasks_posts_declared_list():
    # mock server 记录 PUT /api/v1/tasks/{tid}/subtasks，返回 {"nodes":[...]}
    result = call_tool("plan_subtasks", {
        "task_id": "00000000-...-01",
        "subtasks": [
            {"subtaskId": "...a1", "title": "抓取邮件", "sequence": 1},
            {"subtaskId": "...a2", "title": "生成摘要", "sequence": 2, "dependencyIds": ["...a1"]},
        ],
    })
    assert "已登记" in result or "subtask" in result
    req = server.requests[-1]
    assert req["method"] == "PUT"
    assert len(req["body"]["subtasks"]) == 2
    assert req["headers"]["Idempotency-Key"]

def test_update_subtask_status_rejects_blocked():
    result = call_tool("update_subtask_status", {
        "task_id": "00000000-...-01", "subtaskId": "...a1", "status": "BLOCKED"})
    assert "不支持" in result  # 工具层即拒绝，不打 API

def test_update_subtask_status_posts_typed_status():
    # status=FAILED, note="上游超时" → PUT .../subtasks/{sid}/status body {"status":"FAILED","note":"上游超时"}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd scripts && python3 -m pytest test_agentteams_task_mcp.py -q`
预期：FAIL（未知工具）。

- [ ] **步骤 3：实现两个工具**

`agentteams-task-mcp.py` 内按既有模式（TOOLS 常量登记 schema → `tool_xxx` 函数 → dispatch）新增：

```python
PLAN_SUBTASKS_SCHEMA = {
    "name": "plan_subtasks",
    "description": "把当前任务拆解为结构化子任务并登记（全量声明式：重复调用以最新清单为准，多出的子任务会被移除）。拆解后先调 update_subtask_status 把第一个子任务置为 RUNNING。",
    "inputSchema": {
        "type": "object",
        "properties": {
            "task_id": {"type": "string", "description": "当前主任务 taskId（见对话开头的平台上下文块）"},
            "subtasks": {
                "type": "array", "maxItems": 20, "minItems": 1,
                "items": {
                    "type": "object",
                    "properties": {
                        "subtaskId": {"type": "string"},
                        "title": {"type": "string"},
                        "sequence": {"type": "integer"},
                        "dependencyIds": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["subtaskId", "title", "sequence"],
                },
            },
        },
        "required": ["task_id", "subtasks"],
    },
}
UPDATE_STATUS_SCHEMA = {
    "name": "update_subtask_status",
    "description": "推进子任务状态。status 仅支持 RUNNING / SUCCEEDED / FAILED / CANCELLED。",
    "inputSchema": {
        "type": "object",
        "properties": {
            "task_id": {"type": "string"},
            "subtaskId": {"type": "string"},
            "status": {"type": "string", "enum": ["RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]},
            "note": {"type": "string"},
        },
        "required": ["task_id", "subtaskId", "status"],
    },
}

def tool_plan_subtasks(config: Config, args: dict) -> str:
    body = {"subtasks": args["subtasks"]}
    data = request_json(config, "PUT", f"/api/v1/tasks/{args['task_id']}/subtasks", body)
    return f"已登记 {len(data.get('nodes', []))} 个子任务。现在从 sequence 最小的子任务开始执行。"

def tool_update_subtask_status(config: Config, args: dict) -> str:
    if args["status"] not in {"RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"}:
        return f"不支持的状态 {args['status']}：只能 RUNNING/SUCCEEDED/FAILED/CANCELLED"
    body = {"status": args["status"]}
    if args.get("note"):
        body["note"] = args["note"]
    request_json(config, "PUT",
                 f"/api/v1/tasks/{args['task_id']}/subtasks/{args['subtaskId']}/status", body)
    return f"子任务 {args['subtaskId']} 状态已更新为 {args['status']}。"
```

实现注意：
- `request_json` / `Config` / Idempotency-Key 头注入沿用文件内既有 helper（`create_task` 同款：`Idempotency-Key: uuid4`）。
- `TOOLS` 列表追加两个 schema；`subtaskId`/`task_id` 走既有 `_clean_text` 校验（UUID 形态校验若已有 helper 则复用）。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd scripts && python3 -m pytest test_agentteams_task_mcp.py -q`
预期：新 3 用例 + 既有用例全 PASS。

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(scripts): MCP 新增 plan_subtasks 与 update_subtask_status 拆解工具"
```

---

### 任务 5：ConfigMap 同步

**文件：**
- 修改：`deploy/kind-qwenpaw-task-mcp.yaml`

- [ ] **步骤 1：同步内嵌脚本**

该 ConfigMap 内嵌 `agentteams-task-mcp.py` 全文。用脚本化同步避免手抄（ConfigMap 的 data 键名以文件实际为准）：

```bash
cd /Users/gecko/code/agentteams-java
python3 - <<'EOF'
from pathlib import Path
import re
yaml_path = Path("deploy/kind-qwenpaw-task-mcp.yaml")
text = yaml_path.read_text()
script = Path("scripts/agentteams-task-mcp.py").read_text()
# 定位 ConfigMap 中脚本字面量块（| 或 |- 缩进块），整体替换为最新脚本内容并保持缩进
# 键名通过 grep 先行确认，此处假设 data: agentteams-task-mcp.py: |
m = re.search(r"(agentteams-task-mcp\.py: \|\n)((?: {4}.*\n)+)", text)
assert m, "ConfigMap 脚本块定位失败，请人工检查键名与缩进"
new_block = "".join("    " + line if line.strip() else "\n" for line in script.splitlines(True))
yaml_path.write_text(text[:m.start(2)] + new_block + text[m.end(2):])
EOF
diff <(python3 -c "print(open('deploy/kind-qwenpaw-task-mcp.yaml').read())" | grep -c "plan_subtasks") 0 \
  && echo "SYNC_CHECK_FAILED" || echo "SYNC_OK plan_subtasks present"
```

若替换块定位失败（键名/缩进不同），人工编辑 YAML：把 `scripts/agentteams-task-mcp.py` 全文按原缩进规则粘入对应 data 键。

- [ ] **步骤 2：验证 YAML 可解析且与源脚本一致**

```bash
python3 - <<'EOF'
import yaml, pathlib
doc = yaml.safe_load(pathlib.Path("deploy/kind-qwenpaw-task-mcp.yaml").read_text())
cm = next(d for d in doc["items"] if d["kind"] == "ConfigMap") if "items" in doc else doc
key = [k for k in cm["data"] if "task-mcp" in k or k.endswith(".py")][0]
embedded = cm["data"][key]
source = pathlib.Path("scripts/agentteams-task-mcp.py").read_text()
assert "plan_subtasks" in embedded and "update_subtask_status" in embedded
print("YAML_OK", "embedded==source" if embedded.strip() == source.strip() else "embedded differs (check trailing whitespace)")
EOF
```

预期：`YAML_OK`。（embedded 与 source 允许尾随空白差异；内容行必须一致。）

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "chore(deploy): kind ConfigMap 同步 MCP 拆解工具脚本"
```

---

### 任务 6：console 事件标签、摘要与 tool.* 归属

**文件：**
- 修改：`console/src/features/tasks/TaskInfoPanel.tsx`（`PROCESS_EVENT_LABELS`、`processEventSummary`、新增 `buildSubtaskContext`/`attributeSubtask` 纯函数）
- 测试：`console/src/features/tasks/TaskInfoPanel.test.ts`（新建，纯函数测试）

- [ ] **步骤 1：编写失败的测试**

```typescript
import { describe, expect, it } from 'vitest';
import { buildSubtaskContext, processEventSummary, subtaskWindow } from './TaskInfoPanel';

const planned = (subtaskId: string, title: string, seq: number) => ({
  eventId: `e-planned-${subtaskId}`, eventType: 'subtask.planned', occurredAt: `2026-09-09T00:0${seq}:00Z`,
  payload: JSON.stringify({ subtaskId, title, sequence: seq }),
});
const started = (subtaskId: string, at: string) => ({
  eventId: `e-start-${subtaskId}-${at}`, eventType: 'subtask.started', occurredAt: at,
  payload: JSON.stringify({ subtaskId }),
});
const terminal = (type: string, subtaskId: string, at: string) => ({
  eventId: `e-${type}-${subtaskId}-${at}`, eventType: type, occurredAt: at,
  payload: JSON.stringify({ subtaskId }),
});
const tool = (name: string, at: string) => ({
  eventId: `e-tool-${name}-${at}`, eventType: 'tool.called', occurredAt: at,
  payload: JSON.stringify({ tool: name }),
});

describe('buildSubtaskContext', () => {
  it('从 planned 事件构建 subtaskId → title 映射', () => {
    const ctx = buildSubtaskContext([planned('a1', '抓取邮件', 1), planned('a2', '生成摘要', 2)]);
    expect(ctx.titles.get('a1')).toBe('抓取邮件');
    expect(ctx.titles.get('a2')).toBe('生成摘要');
  });
});

describe('subtaskWindow', () => {
  it('subtask.started 到下一终态之间的 tool.called 归该子任务', () => {
    const events = [
      started('a1', '2026-09-09T00:01:00Z'),
      tool('http.get', '2026-09-09T00:02:00Z'),
      terminal('subtask.succeeded', 'a1', '2026-09-09T00:03:00Z'),
      tool('orphan', '2026-09-09T00:04:00Z'),
    ];
    const attribution = subtaskWindow(events);
    expect(attribution.get('e-tool-http.get-2026-09-09T00:02:00Z')).toBe('a1');
    expect(attribution.get('e-tool-orphan-2026-09-09T00:04:00Z')).toBeUndefined();
  });
});

describe('processEventSummary', () => {
  it('subtask.planned 显示标题与序号', () => {
    const summary = processEventSummary({
      eventType: 'subtask.planned',
      payload: JSON.stringify({ subtaskId: 'a1', title: '抓取邮件', sequence: 1 }),
    });
    expect(summary).toContain('抓取邮件');
  });
  it('subtask.failed 显示备注', () => {
    const summary = processEventSummary({
      eventType: 'subtask.failed',
      payload: JSON.stringify({ subtaskId: 'a1', note: '上游超时' }),
    });
    expect(summary).toContain('上游超时');
  });
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd console && npx vitest run src/features/tasks/TaskInfoPanel.test.ts`
预期：FAIL（`buildSubtaskContext`/`subtaskWindow` 未导出）。若 vitest 报「找不到测试文件」，检查 `vite.config.ts` 的 `test.include` 是否覆盖 `src/**/*.test.ts`，必要时在该配置中补 include（保持既有 test.environment）。

- [ ] **步骤 3：实现纯函数与标签**

`TaskInfoPanel.tsx`：

```typescript
export type TaskProcessEventLite = {
  eventId: string; eventType: string; occurredAt: string; payload?: string | null;
};

/** subtask.* 中文标签（下钻与时间线共用）。 */
const SUBTASK_EVENT_LABELS: Record<string, string> = {
  'subtask.planned': '登记子任务',
  'subtask.started': '子任务开始',
  'subtask.succeeded': '子任务完成',
  'subtask.failed': '子任务失败',
  'subtask.cancelled': '子任务取消',
};
// PROCESS_EVENT_LABELS 中 Object.assign(SUBTASK_EVENT_LABELS) 合并，保持导出不变。

function subtaskPayload(data: Record<string, unknown>): { subtaskId?: string; title?: string; note?: string; sequence?: number } {
  return {
    subtaskId: typeof data.subtaskId === 'string' ? data.subtaskId : undefined,
    title: typeof data.title === 'string' ? data.title : undefined,
    note: typeof data.note === 'string' ? data.note : undefined,
    sequence: typeof data.sequence === 'number' ? data.sequence : undefined,
  };
}

/** 从 planned 事件窗口构建 subtaskId → title（best-effort，未登记的子任务回落短 id）。 */
export function buildSubtaskContext(events: TaskProcessEventLite[]): { titles: Map<string, string> } {
  const titles = new Map<string, string>();
  events.forEach((event) => {
    if (event.eventType !== 'subtask.planned' || !event.payload) return;
    try {
      const data = JSON.parse(event.payload) as Record<string, unknown>;
      const meta = subtaskPayload(data);
      if (meta.subtaskId && meta.title) titles.set(meta.subtaskId, meta.title);
    } catch { /* best-effort */ }
  });
  return { titles };
}

/** 时间窗口归属：subtask.started 之后、下一 subtask 终态之前的 tool.* 事件归该子任务。 */
export function subtaskWindow(events: TaskProcessEventLite[]): Map<string, string> {
  const STARTED = 'subtask.started';
  const TERMINAL = new Set(['subtask.succeeded', 'subtask.failed', 'subtask.cancelled']);
  const ordered = [...events].sort(
    (a, b) => (Date.parse(a.occurredAt) || 0) - (Date.parse(b.occurredAt) || 0));
  const attribution = new Map<string, string>();
  let current: string | undefined;
  ordered.forEach((event) => {
    if (event.eventType === STARTED) {
      let meta: ReturnType<typeof subtaskPayload> = {};
      if (event.payload) {
        try { meta = subtaskPayload(JSON.parse(event.payload) as Record<string, unknown>); } catch { /* best-effort */ }
      }
      current = meta.subtaskId;
    } else if (TERMINAL.has(event.eventType)) {
      current = undefined;
    } else if (event.eventType.startsWith('tool.') && current) {
      attribution.set(event.eventId, current);
    }
  });
  return attribution;
}
```

`processEventSummary` 增加分支（在 tool.* 分支之后）：

```typescript
if (event.eventType.startsWith('subtask.')) {
  try {
    const data = JSON.parse(event.payload) as Record<string, unknown>;
    const meta = subtaskPayload(data);
    const head = meta.title ? `#${meta.sequence ?? '—'} ${meta.title}` : (meta.subtaskId?.slice(0, 8) ?? '');
    return meta.note ? `${head} · ${meta.note}` : head;
  } catch { return event.payload; }
}
```

`mergeTaskTimelines` 的 `processItems` 增加归属字段（供任务 7 过滤）：

```typescript
const processItems = process.map((event) => ({
  id: `process:${event.eventId}`,
  title: PROCESS_EVENT_LABELS[event.eventType] || event.eventType,
  description: processEventSummary(event),
  time: event.occurredAt,
  tone: event.eventType === 'task.failed' || event.eventType.startsWith('subtask.failed') ? 'danger' : undefined,
  // 入参由 withSubtaskOwnership 预计算归属；直接透传（见任务 7）
  subtaskId: (event as { subtaskId?: string }).subtaskId,
}));
```

（本任务先把 `subtaskId` 字段加进返回类型并置 undefined，归属注入在任务 7 于 `TaskDetailPage` 完成——类型先行，避免任务 7 改签名。）

- [ ] **步骤 4：运行测试验证通过**

运行：`cd console && npx vitest run src/features/tasks/TaskInfoPanel.test.ts`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(console): 子任务事件标签/摘要与 tool.* 时间窗口归属纯函数"
```

---

### 任务 7：console DAG 下钻交互

**文件：**
- 修改：`console/src/features/tasks/TaskDag.tsx`
- 修改：`console/src/features/tasks/TaskDetailPage.tsx`（下钻状态提升 + 时间线过滤 + 归属注入）
- 修改：`console/src/features/tasks/TaskInfoPanel.tsx`（DAG props 透传）

- [ ] **步骤 1：扩展 TaskDag（onSelect + title + 依赖虚线边 + 选中高亮）**

`TaskDag.tsx` 完整改造（保持既有 layout 函数不动）：

```typescript
type TaskDagProps = {
  nodes: TaskTreeNode[];
  rootTaskId: string;
  titles?: Map<string, string>;
  selectedSubtaskId?: string | null;
  onSelectSubtask?: (subtaskId: string) => void;
};

export function TaskDag({ nodes, rootTaskId, titles, selectedSubtaskId, onSelectSubtask }: TaskDagProps) {
  // 空态两处 return 保持原文案
  const positioned = layout(nodes, rootTaskId);
  // ... width/height/byId 同现状
  // 依赖边（dependencyIds 指向的同 run 节点；不指向根）：
  const deps: Array<{ from: Positioned; to: Positioned }> = [];
  positioned.forEach(({ node, x, y }) => {
    (node.dependencyIds || []).forEach((depId) => {
      if (depId === rootTaskId) return;
      const from = byId.get(depId);
      if (from) deps.push({ from, to: { node, x, y } });
    });
  });
  return (
    <div className="task-dag" data-testid="task-dag">
      <svg width="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="任务分解图">
        {/* parent 边保持原样 */}
        {positioned.map(({ node, x, y }) => { /* 原 parent line 逻辑不动 */ })}
        {deps.map(({ from, to }) => (
          <line
            key={`dep-${to.node.taskId}-${from.node.taskId}`}
            x1={from.x + NODE_WIDTH} y1={from.y + NODE_HEIGHT / 2}
            x2={to.x} y2={to.y + NODE_HEIGHT / 2}
            className="task-dag__edge task-dag__edge--dependency"
          />
        ))}
        {positioned.map(({ node, x, y }) => {
          const label = node.taskId === rootTaskId ? '根任务' : (titles?.get(node.taskId) || node.taskId.slice(0, 8));
          const selected = node.taskId === selectedSubtaskId;
          return (
            <g
              key={node.taskId}
              transform={`translate(${x}, ${y})`}
              data-testid="task-dag-node"
              data-status={node.status}
              className={selected ? 'task-dag__node-wrapper--selected' : undefined}
              onClick={node.taskId === rootTaskId || !onSelectSubtask ? undefined : () => onSelectSubtask(node.taskId)}
              style={node.taskId === rootTaskId || !onSelectSubtask ? undefined : { cursor: 'pointer' }}
            >
              <rect
                width={NODE_WIDTH} height={NODE_HEIGHT} rx={8}
                className={`task-dag__node task-dag__node--${node.status.toLowerCase()}`}
              />
              <text x={NODE_WIDTH / 2} y={NODE_HEIGHT / 2 + 4} textAnchor="middle" className="task-dag__label">
                {label.length > 12 ? `${label.slice(0, 11)}…` : label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
```

配套 CSS（现有组件样式文件——以 `task-dag__node` 类现所在文件为准，grep `task-dag__node` 定位）：

```css
.task-dag__edge--dependency { stroke-dasharray: 4 3; opacity: 0.6; }
.task-dag__node-wrapper--selected rect { stroke-width: 2; }
.task-dag__node--pending { /* 沿用现有状态色体系，若缺 pending/succeeded/failed 类则补齐：灰/绿/红 */ }
```

- [ ] **步骤 2：TaskInfoPanel 透传 + TaskDetailPage 提升状态并过滤**

`TaskInfoPanel` props 增加并透传（组件签名与 `<TaskDag>` 调用处）：

```typescript
export function TaskInfoPanel({ projectId, taskId, runId, task, processEvents,
  selectedSubtaskId, onSelectSubtask, subtaskTitles, }: { /* 原有字段 */
  selectedSubtaskId?: string | null;
  onSelectSubtask?: (subtaskId: string) => void;
  subtaskTitles?: Map<string, string>;
}) {
  // <TaskDag nodes={...} rootTaskId={taskId} titles={subtaskTitles}
  //          selectedSubtaskId={selectedSubtaskId} onSelectSubtask={onSelectSubtask} />
}
```

`TaskDetailPage`：

```typescript
const [selectedSubtaskId, setSelectedSubtaskId] = useState<string | null>(null);
const subtaskTitles = useMemo(() => buildSubtaskContext(processEvents.data || []).titles, [processEvents.data]);
const timelineItems = useMemo(
  () => mergeTaskTimelines(
    (events.data || []).map((event) => ({ /* 原映射不动 */ })),
    withSubtaskOwnership(processEvents.data || []),
  ).filter((item) => !selectedSubtaskId || item.subtaskId === selectedSubtaskId),
  [events.data, processEvents.data, selectedSubtaskId],
);
```

实现注意：
- 在 `TaskInfoPanel.tsx` 新增导出 `withSubtaskOwnership(process: TaskProcessEventLite[])`：内部先 `subtaskWindow` 得到 tool.* 归属，再对每条事件计算 `subtaskId`（tool.* 取窗口归属；planned/started/终态事件取 payload 内 subtaskId，解析失败则 undefined），返回带 `subtaskId` 字段的事件数组；`mergeTaskTimelines` 的 `processItems` 直接透传该字段。这样下钻过滤与归属推断都是纯函数，页面内不出现 JSON 解析。
- 时间线顶部显示下钻状态提示（保持轻量）：

```tsx
{selectedSubtaskId && (
  <div className="info-box" role="status" data-testid="subtask-filter-hint">
    正在查看子任务 {subtaskTitles.get(selectedSubtaskId) || selectedSubtaskId.slice(0, 8)} 的事件
    <button className="button button--ghost" onClick={() => setSelectedSubtaskId(null)}>清除筛选</button>
  </div>
)}
```

- `<TaskInfoPanel ... selectedSubtaskId={selectedSubtaskId} onSelectSubtask={setSelectedSubtaskId} subtaskTitles={subtaskTitles} />`

- [ ] **步骤 3：运行单元测试与既有 console 检查**

运行：
```bash
cd console && npx vitest run && npm run build
```
预期：vitest 全 PASS（含任务 6 测试）；`tsc && vite build` 无类型错误（`subtaskId` 可选字段不破坏既有消费方）。

- [ ] **步骤 4：手工冒烟（dev server）**

```bash
cd console && npm run dev
```

浏览器打开任务详情页（有子任务数据的任务；可先用 curl 向本地 control-plane 造数据：一次 plan 两子任务 + 一次 updateStatus RUNNING）：
1. DAG 显示 3 节点（根 + 2 子任务），子任务节点显示 title，a2→a1 依赖虚线可见
2. 点击 a1 节点 → 主列时间线只剩 a1 相关条目 + 提示条出现
3. 「清除筛选」恢复全量
预期截图留档 `.local/phase2-dag-smoke.png`（可选）。

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(console): DAG 子任务点击下钻、title/依赖边渲染与时间线过滤"
```

---

### 任务 8：kind 端到端验收剧本 + 全量回归

**文件：**
- 创建：`scripts/run-kind-subtask-decomposition.py`
- 修改：`scripts/qwenpaw-conversation-mock.py`（剧本新增拆解动作；若该 mock 已支持指令化剧本则只加数据）

- [ ] **步骤 1：确认既有 kind 剧本模式**

```bash
sed -n '1,80p' scripts/run-kind-qwenpaw-conversation-acceptance.py
grep -n "plan_subtasks\|conversation\|replay" scripts/qwenpaw-conversation-mock.py | head -10
```

记录：脚本如何找到 kind 环境（NodePort/端口转发、鉴权 token 获取）、断言库风格、conversation mock 的剧本注入方式（文件内剧本列表 or 外部 JSON）。

- [ ] **步骤 2：扩展 conversation mock 剧本**

在 mock 剧本数据中新增「拆解剧本」条目：第一轮回复含工具调用 `plan_subtasks`（两个子任务，title/sequence/依赖与任务 2 测试一致），随后按剧本依次 `update_subtask_status`（RUNNING → SUCCEEDED / FAILED），最终给出总结性回复。实现方式完全沿用该 mock 既有的多轮剧本结构（grep 出现有剧本 dict 结构后仿写），不引入新的编排机制。

- [ ] **步骤 3：编写验收脚本**

`scripts/run-kind-subtask-decomposition.py`（沿用既有脚本骨架：参数解析、token 获取、端口转发发现、断言打印）核心断言序列：

```python
# 1. 创建任务（scope/团队 与 mock worker 配置一致）+ queue，等待 worker 领取
# 2. 等待拆解发生：轮询 GET /api/v1/tasks/{taskId}/runs/{runId}/tree（既有树查询端点，路径以 console api/tasks.ts 的 useTaskTree 为准）
#    断言：除根节点外出现 2 个子任务节点，状态最终为 SUCCEEDED 与 FAILED（agent 自治语义：子任务失败不改主任务终态——主任务仍 SUCCEEDED）
# 3. 轮询 process-events（GET .../process-events，既有一期端点）
#    断言：出现 subtask.planned ×2、subtask.started、subtask.succeeded、subtask.failed；
#    sequence 全局单调；tool.* 事件存在（窗口归属留给 console，脚本只断言事件在流中）
# 4. 反向一致性：主任务终态不受 subtask FAILED 影响（get_task phase == SUCCEEDED）
# 5. 打印 PASS 摘要（子任务 id/状态/事件计数）
```

运行方式与既有脚本一致：`python3 scripts/run-kind-subtask-decomposition.py`（kind 集群在跑的前提下）。

- [ ] **步骤 4：执行 kind 剧本验证**

```bash
bash .local/run-l5-build.sh > /dev/null 2>&1 &   # 不需要；kind 验证直接用本地镜像构建脚本 deploy/build-images.sh
bash deploy/build-images.sh && python3 scripts/run-kind-subtask-decomposition.py
```

预期：脚本打印 PASS；若 conversation mock 未按剧本拆解，先核对 ConfigMap 是否已 apply（`kubectl apply -f deploy/kind-qwenpaw-task-mcp.yaml`）与 worker 是否滚动重启。

- [ ] **步骤 5：全量回归**

```bash
mvn -q test   # 全模块
cd console && npx vitest run && npm run build
```

预期：全绿。

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "test(scripts): kind 子任务拆解端到端验收剧本（树投影+事件流+终态隔离）"
```

---

## 收尾

- [ ] **L5 镜像与部署**（验收第三层，独立于本计划任务链，待后台构建完成后执行）：镜像 tag `l5-process-visibility-20260909-v1` 已在后台构建（`.local/run-l5-build.sh`，terminal 1）。构建完成后：`docker save | gzip` → `scp ly@192.168.122.55` → `sudo k3s ctr -n k8s.io images import` → 滚动更新（禁止 helm upgrade 直改——见记忆库 L5 Operator 托管负载修改入口原则）→ 一期真模型验收（含本二期 MCP 拆解真模型演示）。
- [ ] **规格覆盖自检**（执行者完成全部任务后）：对照规格「方案」5 小节逐条勾稽——MCP 工具（任务 4）、事件与持久化（任务 2/3）、runtime 注入（任务 1）、console DAG（任务 6/7）、验收（任务 8）。
