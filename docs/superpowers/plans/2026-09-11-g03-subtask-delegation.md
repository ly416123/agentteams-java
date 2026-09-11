# G03 多 Agent 协作真调度（子任务委派）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 子任务从观测投影升格为一等任务实体，由平台按依赖拓扑调度给 worker 并行执行，产物由主 agent 汇总后进入 G02 评审闭环。

**架构：** V93 给 `tasks` 表加 `parent_task_id`/`kind` 两列（方案 A）；`SubtaskDelegationService` 在 `plan_subtasks` 时创建一等子任务行并按 re-plan 语义做声明式同步；`SubtaskGateService` 挂在调度器 tick 上做依赖 gate（DRAFT→QUEUED）与汇总轮触发（子任务全 SUCCEEDED → 主任务系统转 QUEUED）；`onManifestPublished` 对存在非 SUCCEEDED 子任务的 MAIN 任务跳过结果版本（D3 硬约束）。

**技术栈：** Java 21 / Spring Boot（control-plane）、Flyway（V93）、Testcontainers（IT）、零依赖 Python stdio MCP server、React + TypeScript（console）、kind + L5 双环境验收。

**规格：** `docs/superpowers/specs/2026-09-11-g03-multi-agent-delegation-design.md`（D1-D6 决策 + §4 机制）。

---

## 全局约束

1. 分支：`subtask-delegation`，从 `main`（≥ e055f11）切出，用 `.worktrees/` 工作树（using-git-worktrees 技能）。
2. commit 规范：`feat:/test:/fix:/docs:` + 中文描述（chinese-commit-conventions）。
3. `TaskRecord` 已有追加列兼容构造器先例（G02 的 archive 三列，见 `TaskRecord.java:27-40`）：V93 新列追加为组件 + 新增兼容构造器委托，**既有 `new TaskRecord(...)` 调用点约 25 处零改动**。
4. `TaskTransitionService.legal`（domain）现有边已够用：DRAFT→QUEUED、QUEUED→CANCELLED、SUCCEEDED→QUEUED（G02 retry 边复用为汇总轮转移）。**不改状态机**。
5. 现有调度链 `TaskAssignmentScheduler.runOnce → TaskAssignmentService.queuedTaskIds + queueReadyTask`（行锁 + `matchingAgent` 能力匹配）对 kind 不敏感——子任务 QUEUED 即被拾取，**调度器核心零改动**。
6. 单模块跑测试必须 `-am`；全量验证用完整 reactor（memory：`-rf` 会从 ~/.m2 旧 jar 解析导致 IllegalAccessError）。
7. JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home。

## 文件结构

| 文件 | 操作 | 职责 |
|---|---|---|
| `control-plane/src/main/resources/db/migration/V93__subtask_delegation.sql` | 创建 | tasks 加 parent_task_id/kind + 索引 |
| `control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRecord.java` | 修改 | 加 parentTaskId/kind 组件 + 兼容构造器 + isSubtask() |
| `control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java` | 修改 | INSERT/SELECT/map 加两列；findByParent/countByParentNotPhase/findParentIdsWithDraftChildren/findParentIdsAllChildrenSucceeded |
| `control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskDelegationService.java` | 创建 | plan 一等实体创建 + re-plan 表语义 + 依赖环检测 + 子任务 specJson 派生 |
| `control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskService.java` | 修改 | plan 委托 delegationService 后再做投影双写；javadoc 更新公理一边界 |
| `control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskGateService.java` | 创建 | 依赖 gate（DRAFT→QUEUED）+ 汇总轮触发（子任务全 SUCCEEDED → 主任务 QUEUED） |
| `control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java` | 修改 | 加 gateSubtasks()（扫描可 gate 的 parent 并触发） |
| `control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentScheduler.java` | 修改 | runOnce 中 assign 循环前调 gateSubtasks |
| `control-plane/src/main/java/io/agentteams/controlplane/task/TaskResultVersionService.java` | 修改 | onManifestPublished 加 MAIN 硬约束跳过 |
| `control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java` | 修改 | cancel 主任务级联取消未出队子任务 |
| `control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java` | 修改 | plan 走 SubtaskDelegationService；新增 GET 列表端点 |
| `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskDelegationServiceTest.java` | 创建 | Testcontainers：一等实体/re-plan/环检测/spec 派生 |
| `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskGateServiceTest.java` | 创建 | Testcontainers：gate 触发/幂等/硬约束前置 |
| `integration-tests/src/test/java/io/agentteams/it/SubtaskDelegationIT.java` | 创建 | 全链 IT：plan→gate→分派模拟→汇总→publish 跳过→评审 |
| `scripts/agentteams-task-mcp.py` | 修改 | 新增 `list_subtasks` 工具 |
| `scripts/test_agentteams_task_mcp.py` | 修改 | list_subtasks 单测 |
| `openapi/agentteams-public.yaml` | 修改 | GET /tasks/{taskId}/subtasks 补录 |
| `console/src/features/tasks/TaskDag.tsx` | 修改 | 子任务节点「打开详情」跳转 |
| `scripts/qwenpaw-conversation-mock.py` | 修改 | 真调度两段剧本（拆解/汇总） |
| `scripts/run-kind-subtask-delegation.py` | 创建 | kind 全链验收 |
| `scripts/run-l5-subtask-delegation.py` | 创建 | L5 真模型验收 |

---

### 任务 1：V93 迁移与 TaskRecord/TaskRepository 扩展

**文件：**
- 创建：`control-plane/src/main/resources/db/migration/V93__subtask_delegation.sql`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRecord.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/persistence/FoundationRepositoryIT.java`（追加用例）

- [ ] **步骤 1.1：写 V93 迁移**

```sql
-- 多 Agent 协作真调度（G03）：子任务升格为一等任务实体（D6 方案 A）。
-- 设计规格：docs/superpowers/specs/2026-09-11-g03-multi-agent-delegation-design.md。
ALTER TABLE tasks ADD COLUMN parent_task_id UUID REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'MAIN' CHECK (kind IN ('MAIN', 'SUBTASK'));
CREATE INDEX tasks_parent_idx ON tasks (parent_task_id);
```

- [ ] **步骤 1.2：TaskRecord 追加组件与兼容构造器**

canonical 组件列表末尾（`String archiveActor` 之后）追加 `UUID parentTaskId, String kind`；compact 构造器追加校验：

```java
    public TaskRecord {
        // ……既有校验保持不动，末尾追加：
        if (kind == null || kind.isBlank()) {
            kind = "MAIN";
        }
        if (!"MAIN".equals(kind) && !"SUBTASK".equals(kind)) {
            throw new IllegalArgumentException("kind must be MAIN or SUBTASK");
        }
        if ("SUBTASK".equals(kind) && parentTaskId == null) {
            throw new IllegalArgumentException("subtask requires parentTaskId");
        }
        if ("MAIN".equals(kind) && parentTaskId != null) {
            throw new IllegalArgumentException("MAIN task must not carry parentTaskId");
        }
    }

    /** G02 时代 17 参兼容构造器：kind/parent 缺省为 MAIN/null，既有调用点零改动。 */
    public TaskRecord(UUID id, String title, String description, TaskPhase phase, int priority,
            String specJson, String actor, String source, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version,
            String taskType, String archiveStatus, Instant archivedAt, String archiveActor) {
        this(id, title, description, phase, priority, specJson, actor, source, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, taskType, archiveStatus,
                archivedAt, archiveActor, null, "MAIN");
    }

    public boolean isSubtask() {
        return "SUBTASK".equals(kind);
    }
```

注意：原 17 参显式构造器此时与 canonical 冲突（17 参现在是新兼容构造器签名）——删除原 17 参构造器体，替换为上面的兼容版；14 参与 13 参构造器链保持不变（它们调用 17 参兼容版）。

- [ ] **步骤 1.3：TaskRepository SQL 扩展**

INSERT（L22-29）改为：

```java
    private static final String TASK_COLUMNS = """
            id, title, description, phase, priority, spec::text, actor, source,
            failure_code, redacted_failure_message, created_at, updated_at, version, task_type,
            archive_status, archived_at, archive_actor, parent_task_id, kind
            """;
```

（把 `findById`/`findByIdForUpdate`/`updatePhase` 等所有 SELECT 的列清单统一替换为 `TASK_COLUMNS`，消除重复。）INSERT 增加两列两占位符：

```java
                INSERT INTO tasks
                    (id, title, description, phase, priority, spec, actor, source,
                     failure_code, redacted_failure_message, created_at, updated_at, version, task_type,
                     archive_status, archived_at, archive_actor, parent_task_id, kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, /* 原参数保持 */ ..., task.archiveStatus(), task.archivedAt(), task.archiveActor(),
                task.parentTaskId(), task.kind());
```

`map(ResultSet)` 末尾追加 `rs.getObject("parent_task_id", UUID.class), rs.getString("kind")` 两参。

新增查询方法：

```java
    /** G03：按 parent 读取子任务（创建序稳定，供 gate 与级联取消）。 */
    public List<TaskRecord> findByParent(UUID parentId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE parent_task_id = ? ORDER BY created_at, id",
                this::map, parentId);
    }

    /** G03 D3 硬约束：parent 下 phase != expected 的子任务数。 */
    public long countByParentNotPhase(UUID parentId, TaskPhase phase) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE parent_task_id = ? AND phase <> ?",
                Long.class, parentId, phase.name());
        return count == null ? 0 : count;
    }

    /** G03 gate：存在 DRAFT 子任务的 parent（调度器 tick 扫描入口）。 */
    public List<UUID> findParentIdsWithDraftChildren(int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT parent_task_id FROM tasks
                 WHERE parent_task_id IS NOT NULL AND kind = 'SUBTASK' AND phase = 'DRAFT'
                 LIMIT ?
                """, UUID.class, limit);
    }

    /** G03 汇总轮：MAIN 已 SUCCEEDED 且全部子任务 SUCCEEDED 的 parent。 */
    public List<UUID> findParentIdsAllChildrenSucceeded(int limit) {
        return jdbc.queryForList("""
                SELECT p.id FROM tasks p
                 WHERE p.kind = 'MAIN' AND p.phase = 'SUCCEEDED'
                   AND EXISTS (SELECT 1 FROM tasks c WHERE c.parent_task_id = p.id)
                   AND NOT EXISTS (SELECT 1 FROM tasks c WHERE c.parent_task_id = p.id AND c.phase <> 'SUCCEEDED')
                 LIMIT ?
                """, UUID.class, limit);
    }
```

- [ ] **步骤 1.4：FoundationRepositoryIT 追加往返用例**

仿照该文件既有任务往返用例，追加：insert 带 `parentTaskId`/`kind="SUBTASK"` 的记录 → findById 回读断言两字段；`findByParent` 排序；`countByParentNotPhase` 计数；子任务行 `phase='DRAFT'` 被 `findParentIdsWithDraftChildren` 命中。运行该 IT（需 Docker）：

```bash
JAVA_HOME=$JAVA_HOME mvn -q -pl control-plane -am test -Dtest=FoundationRepositoryIT
```

- [ ] **步骤 1.5：全模块编译验证**

```bash
JAVA_HOME=$JAVA_HOME mvn -q test -pl control-plane -am
```

预期：BUILD 通过（兼容构造器保证既有调用点零改动）。

- [ ] **步骤 1.6：Commit**

```bash
git add -A && git commit -m "feat(任务域): V93 子任务一等实体列与 TaskRecord/TaskRepository 扩展（G03 C1）"
```

---

### 任务 2：SubtaskDelegationService（一等实体创建 + re-plan 语义）

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskDelegationService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskService.java`（plan 委托）
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java`（注入新服务）
- 测试：创建 `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskDelegationServiceTest.java`（Testcontainers，模式仿 `TaskReviewLifecycleIT`：起 context、种 project/membership、真实持久层）

- [ ] **步骤 2.1：写失败的测试（re-plan 表语义 + 环检测 + spec 派生）**

测试类骨架（关键用例，复用 `TaskReviewLifecycleIT` 的容器与 seeding 模式，PRINCIPAL/CONTEXT 构造同该文件 L60-71）：

```java
    @Test
    void planCreatesFirstClassSubtaskRows() {
        UUID taskId = createQueuedTask();  // 辅助：走 persistence.createTask + updateTaskPhase(QUEUED)
        List<TaskTreeNode> nodes = delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "抓取", 1, List.of(), List.of("web")),
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "摘要", 2, List.of(), List.of())));

        assertThat(nodes).hasSize(2);
        TaskRecord child = persistence.findTask(nodes.get(0).taskId()).orElseThrow();
        assertThat(child.isSubtask()).isTrue();
        assertThat(child.parentTaskId()).isEqualTo(taskId);
        assertThat(child.phase()).isEqualTo(TaskPhase.DRAFT);
        assertThat(child.specJson()).contains("\"requiredCapabilities\":[\"web\"]")
                .contains("tenant-a");  // scope 继承主任务
    }

    @Test
    void replanKeepsTerminalAndCancelsStaleUnqueued() { /* 见下 */ }

    @Test
    void planRejectsDependencyCycle() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThatThrownBy(() -> delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of(b)),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of(a)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }
```

re-plan 用例核心断言（对应规格 §4.1 表）：首跑 plan 两个子任务 → 子任务 A 推进 QUEUED（直接 `tx.tasks().updatePhase` 模拟 gate）→ 二次 plan（A 在新清单内、B 不在）→ A 保持 QUEUED 不重置；B 变 CANCELLED（行保留，`findTask` 仍可查）；新子任务 C 以 DRAFT 创建。

- [ ] **步骤 2.2：运行验证失败**

```bash
JAVA_HOME=$JAVA_HOME mvn -q -pl control-plane -am test -Dtest=SubtaskDelegationServiceTest
```

预期：编译失败 `SubtaskDelegationService 不存在`。

- [ ] **步骤 2.3：实现 SubtaskDelegationService 与 SubtaskSpec 扩展**

`SubtaskService.SubtaskSpec`（L53-59）追加 requiredCapabilities 组件与 4 参兼容构造器（否则 SubtaskController/MCP/既有测试的 4 参调用点全部编译错）：

```java
    public record SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds,
            List<String> requiredCapabilities) {
        public SubtaskSpec {
            Objects.requireNonNull(subtaskId, "subtaskId");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
            Objects.requireNonNull(dependencyIds, "dependencyIds");
            requiredCapabilities = requiredCapabilities == null ? List.of()
                    : List.copyOf(requiredCapabilities);
        }

        /** 二期 4 参兼容：无能力要求。 */
        public SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds) {
            this(subtaskId, title, sequence, dependencyIds, List.of());
        }
    }
```

新建服务：
/**
 * G03 D6：子任务一等实体管理。plan 时在 tasks 表创建 kind=SUBTASK 的真实任务行
 * （scope/teamId 继承主任务，requiredCapabilities 供 Team 调度能力匹配），按规格 §4.1
 * 表做声明式 re-plan：清单内未终态保留、终态保留不重跑；清单外未出队取消、终态留存。
 * 投影双写（task_subtasks）与 subtask.* 过程事件仍由 {@link SubtaskService} 负责。
 */
@Service
public class SubtaskDelegationService {
    public static final int MAX_SUBTASKS = SubtaskService.MAX_SUBTASKS;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<TaskPhase> CANCELLABLE = Set.of(
            TaskPhase.DRAFT, TaskPhase.QUEUED, TaskPhase.PAUSED);

    private final FoundationPersistenceService persistence;
    private final Clock clock;

    public SubtaskDelegationService(FoundationPersistenceService persistence, Clock clock) { ... }

    public record PlannedSubtask(UUID subtaskId, String title, int sequence,
            List<UUID> dependencyIds, List<String> requiredCapabilities, TaskPhase phase) {}

    @Transactional
    public List<PlannedSubtask> plan(UUID taskId, List<SubtaskService.SubtaskSpec> specs) {
        // 1. 校验：数量 1..20、id 唯一、id != taskId、依赖引用清单内、依赖环检测（DFS，异常含 "cycle"）
        // 2. inTransaction：
        //    TaskRecord parent = tx.tasks().findByIdForUpdate(taskId)   // 行锁串行化同任务 re-plan
        //            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        //    if (parent.isSubtask()) throw new IllegalArgumentException("cannot plan under a subtask");
        //    Map<UUID, TaskRecord> existing = tx.tasks().findByParent(taskId).stream()
        //            .collect(toMap(TaskRecord::id, identity()));
        //    List<PlannedSubtask> result = new ArrayList<>();
        //    for (SubtaskSpec spec : specs) {
        //        TaskRecord old = existing.get(spec.subtaskId());
        //        TaskRecord child;
        //        if (old == null) {
        //            child = new TaskRecord(spec.subtaskId(), spec.title(), "", TaskPhase.DRAFT, 0,
        //                    childSpecJson(parent.specJson(), spec), parent.actor(), "subtask-delegation",
        //                    null, null, at, at, 0, parent.taskType(), "ACTIVE", null, null,
        //                    parent.id(), "SUBTASK");
        //            tx.tasks().insert(child);
        //            FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskCreated",
        //                    childPayload(parent, spec), at, child.version());
        //        } else {
        //            child = old;  // 保留现状（终态/未终态均不重置）；DRAFT 的 title/sequence/依赖由投影层体现
        //        }
        //        result.add(new PlannedSubtask(child.id(), spec.title(), spec.sequence(),
        //                spec.dependencyIds(), spec.requiredCapabilities(), child.phase()));
        //    }
        //    for (TaskRecord stale : existing.values()) {
        //        if (requested.contains(stale.id())) continue;
        //        if (CANCELLABLE.contains(stale.phase())) {
        //            tx.tasks().updatePhase(stale.id(), TaskPhase.CANCELLED, stale.version(), at);
        //            FoundationPersistenceService.appendEvent(tx, "task", stale.id(), "TaskPhaseChanged",
        //                    idPayload(stale.id()), at, stale.version() + 1);
        //        }
        //    }
        //    return List.copyOf(result);
    }

    /** 子任务 spec：继承主任务顶层 scope/teamId/taskType，覆盖 requiredCapabilities。 */
    private static String childSpecJson(String parentSpecJson, SubtaskSpec spec) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(parentSpecJson);
            root.remove("approvalGranted", "cancelReason");  // 生命周期键不继承
            if (spec.requiredCapabilities().isEmpty()) {
                root.remove("requiredCapabilities");
            } else {
                ArrayNode caps = root.putArray("requiredCapabilities");
                spec.requiredCapabilities().forEach(caps::add);
            }
            root.put("parentTaskId", /* 主任务 id 由调用方传入 */ "").put("kind", "SUBTASK");
            return JSON.writeValueAsString(root);
        } catch (IOException error) {
            throw new IllegalArgumentException("parent spec is not valid JSON", error);
        }
    }
}
```

（实现时 parentTaskId 实值从 plan 参数传入 childSpecJson；依赖环检测用迭代 DFS + `inStack` 集合，异常消息必须含 `cycle`。）

- [ ] **步骤 2.4：SubtaskService.plan 委托 + SubtaskController 接线**

`SubtaskService.plan` 签名不变（Controller 无感）：方法开头注入调用 `delegation.plan(taskId, specs)`（构造器加 `SubtaskDelegationService` 依赖），成功后继续既有投影双写（`tree.deleteOthers` **保留**——投影行仍按最新清单收敛，但删除范围限定为清单外且投影行存在者；真实任务行不删）。`deleteOthers` 调用前先过滤：把 `requestedIds` 之外且 tasks 行仍非终态的 id 保留在投影中（避免 CANCELLED 子任务从 DAG 消失）——实现：`keepTaskIds = requested ∪ 清单外未终态子任务 id`。javadoc 公理一更新为「真实任务行的终态由平台执行链路驱动，本服务只写观测投影与事件」。

`SubtaskController` 构造器无需变（它调 `SubtaskService`）。

- [ ] **步骤 2.5：运行测试通过 + 全模块回归**

```bash
JAVA_HOME=$JAVA_HOME mvn -q -pl control-plane -am test -Dtest='SubtaskDelegationServiceTest,SubtaskServiceTest,SubtaskControllerTest'
JAVA_HOME=$JAVA_HOME mvn -q test -pl control-plane -am
```

预期：全绿。若 `SubtaskServiceTest` 因构造器签名变化编译失败，按新构造器参数补 mock。

- [ ] **步骤 2.6：Commit**

```bash
git add -A && git commit -m "feat(任务域): plan 升格子任务为一等任务实体并定义 re-plan 语义（G03 C1）"
```

---

### 任务 3：SubtaskGateService（依赖 gate + 汇总轮触发）与 publish 硬约束

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskGateService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentScheduler.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskResultVersionService.java`
- 测试：创建 `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskGateServiceTest.java`（Testcontainers）

- [ ] **步骤 3.1：写失败的测试**

```java
    @Test
    void releasesDraftChildrenWhoseDependenciesSucceeded() {
        // plan A(无依赖) + B(依赖A)：A DRAFT→手动置 SUCCEEDED 后 tick → A/B 都 QUEUED
        delegation.plan(taskId, List.of(spec(a, 1, List.of()), spec(b, 2, List.of(a))));
        setPhase(a, TaskPhase.QUEUED); setPhase(a, TaskPhase.SUCCEEDED);  // 辅助方法直写 updatePhase
        int released = gate.releaseReadyChildren(taskId);
        assertThat(released).isEqualTo(2);  // A 无依赖立即可 gate，B 依赖已 SUCCEEDED
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
    }

    @Test
    void aggregatesParentOnlyAfterAllChildrenSucceeded() {
        // A SUCCEEDED + B RUNNING：不触发；B→SUCCEEDED 后触发：主任务 SUCCEEDED→QUEUED + TaskChildrenCompleted 事件
    }

    @Test
    void aggregateIsIdempotentWhenParentNotSucceeded() {
        // 主任务 RUNNING（第一跑还在进行）→ releaseParentForAggregation 返回 false 且不改 phase
    }
```

- [ ] **步骤 3.2：运行验证失败**（`SubtaskGateService 不存在` 编译错）

- [ ] **步骤 3.3：实现 SubtaskGateService**

```java
package io.agentteams.controlplane.task;

/**
 * G03 D5/D3：子任务依赖 gate 与汇总轮触发。由调度器 tick 驱动（1s 轮询），全部操作
 * 幂等：gate 仅作用于 DRAFT 子任务（依赖全 SUCCEEDED 才放行）；汇总触发仅作用于
 * phase=SUCCEEDED 的 MAIN 任务且要求全部子任务 SUCCEEDED（D3 硬约束），转移复用
 * G02 SUCCEEDED→QUEUED 边并追加 TaskChildrenCompleted 聚合事件。
 */
@Service
public class SubtaskGateService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FoundationPersistenceService persistence;
    private final Clock clock;
    public SubtaskGateService(FoundationPersistenceService persistence, Clock clock) { ... }

    /** 放行依赖已满足的 DRAFT 子任务，返回本 tick 放行数。 */
    public int releaseReadyChildren(UUID parentId) {
        return persistence.inTransaction(tx -> {
            List<TaskRecord> children = tx.tasks().findByParent(parentId);
            if (children.isEmpty()) return 0;
            Map<UUID, TaskPhase> phaseById = children.stream()
                    .collect(Collectors.toMap(TaskRecord::id, TaskRecord::phase));
            Instant at = clock.instant();
            int released = 0;
            for (TaskRecord child : children) {
                if (child.phase() != TaskPhase.DRAFT) continue;
                if (!dependencyIds(child.specJson()).stream()
                        .allMatch(id -> phaseById.get(id) == TaskPhase.SUCCEEDED)) continue;
                TaskRecord updated = tx.tasks().updatePhase(child.id(), TaskPhase.QUEUED,
                        child.version(), at);
                FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskPhaseChanged",
                        idPayload(child.id()), at, updated.version());
                released++;
            }
            return released;
        });
    }

    /** 子任务全部 SUCCEEDED 且主任务处于拆解后 SUCCEEDED → 系统转 QUEUED 进入汇总轮。 */
    public boolean releaseParentForAggregation(UUID parentId) {
        return persistence.inTransaction(tx -> {
            TaskRecord parent = tx.tasks().findByIdForUpdate(parentId).orElse(null);
            if (parent == null || parent.isSubtask() || parent.phase() != TaskPhase.SUCCEEDED) return false;
            if (tx.tasks().countByParentNotPhase(parentId, TaskPhase.SUCCEEDED) > 0) return false;
            if (tx.tasks().findByParent(parentId).isEmpty()) return false;
            Instant at = clock.instant();
            TaskRecord queued = tx.tasks().updatePhase(parentId, TaskPhase.QUEUED, parent.version(), at);
            FoundationPersistenceService.appendEvent(tx, "task", parentId, "TaskChildrenCompleted",
                    idPayload(parentId), at, tx.tasks().incrementVersion(parentId));
            return queued.phase() == TaskPhase.QUEUED;
        });
    }

    private static List<UUID> dependencyIds(String specJson) {
        try {
            JsonNode node = JSON.readTree(specJson).path("dependencyIds");
            List<UUID> ids = new ArrayList<>();
            node.forEach(n -> ids.add(UUID.fromString(n.asText())));
            return ids;
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String idPayload(UUID id) {
        return "{\"taskId\":\"" + id + "\"}";
    }
}
```

（子任务 `dependencyIds` 存于子任务 specJson 顶层——任务 2 的 `childSpecJson` 必须写入；若计划实现时改存投影表，此处同步调整读取来源，两处保持一致。）

- [ ] **步骤 3.4：调度器挂接**

`TaskAssignmentService` 加方法（构造器注入 `SubtaskGateService`）：

```java
    /** G03：调度 tick 前置 gate——放行就绪子任务并触发可汇总的 parent。返回动作数。 */
    public int gateSubtasks(int limit) {
        int actions = 0;
        for (UUID parentId : persistence.inTransaction(tx -> tx.tasks().findParentIdsWithDraftChildren(limit))) {
            actions += gate.releaseReadyChildren(parentId);
        }
        for (UUID parentId : persistence.inTransaction(tx -> tx.tasks().findParentIdsAllChildrenSucceeded(limit))) {
            if (gate.releaseParentForAggregation(parentId)) actions++;
        }
        return actions;
    }
```

`TaskAssignmentScheduler.runOnce`（L48-69）在 `queuedTaskIds` 循环**之前**插入：

```java
            int gated = assignments.gateSubtasks(batchSize);
```

并把 `gated` 计入 `RunResult`（新字段 `gatedSubtasks`，RunResult 紧凑构造器校验同步加 `gatedSubtasks >= 0`；既有调用/测试按新 record 分量适配）。

- [ ] **步骤 3.5：publish 硬约束（D3）**

`TaskResultVersionService.onManifestPublished` 在 `findByRun` 幂等检查之后、seq 分配之前插入：

```java
            // G03 D3 硬约束：MAIN 任务存在非 SUCCEEDED 子任务时跳过结果版本
            // （拆解轮 run 正常结束但不产生结果版本；汇总轮 publish 时子任务已全 SUCCEEDED）。
            if (tx.tasks().countByParentNotPhase(manifest.taskId(), TaskPhase.SUCCEEDED) > 0) {
                LOGGER.warn("result version skipped, subtasks not all SUCCEEDED taskId={}", manifest.taskId());
                return Optional.empty();
            }
```

类头加 `private static final Logger LOGGER = LoggerFactory.getLogger(TaskResultVersionService.class);` 与 slf4j import。注意：`countByParentNotPhase` 对无子任务的任务恒为 0，既有 G02 行为不变。

- [ ] **步骤 3.6：运行测试 + Commit**

```bash
JAVA_HOME=$JAVA_HOME mvn -q -pl control-plane -am test -Dtest='SubtaskGateServiceTest,TaskResultVersionServiceTest,TaskAssignmentSchedulerTest'
JAVA_HOME=$JAVA_HOME mvn -q test -pl control-plane -am
git add -A && git commit -m "feat(任务域): 子任务依赖 gate、汇总轮触发与 publish 硬约束（G03 C2）"
```

---

### 任务 4：cancel 级联 + GET 子任务列表 + MCP list_subtasks

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java`
- 修改：`openapi/agentteams-public.yaml`
- 修改：`scripts/agentteams-task-mcp.py`、`scripts/test_agentteams_task_mcp.py`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/service/TaskServiceLifecycleExtensionsTest.java`（追加级联用例）、`scripts/test_agentteams_task_mcp.py`

- [ ] **步骤 4.1：写失败的测试（cancel 级联）**

在 `TaskServiceLifecycleExtensionsTest` 追加（该文件已有 archive/unarchive/delete 用例模式，直接仿写）：

```java
    @Test
    void cancelCascadesToUnqueuedSubtasks() {
        // 准备：主任务 RUNNING + 子任务 A(DRAFT) B(QUEUED) C(RUNNING)（delegation.plan + 手动置 phase）
        tasks.cancel(taskId, version, "cancel-key", "alice", "rest");
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        assertThat(persistence.findTask(b).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        assertThat(persistence.findTask(c).orElseThrow().phase()).isEqualTo(TaskPhase.RUNNING); // 已出队不动
    }
```

- [ ] **步骤 4.2：实现 cancel 级联**

`TaskService.cancel`（reason 版，L211-247）在 `transition(...)` 成功返回后追加级联（无 reason 分支同样追加——抽私有方法两处调用）：

```java
    /** G03 D3：主任务取消时级联取消未出队子任务（DRAFT/QUEUED/PAUSED）；已出队的等待自然终态。 */
    private void cascadeCancelChildren(UUID parentId) {
        List<TaskRecord> children = persistence.inTransaction(tx -> tx.tasks().findByParent(parentId));
        Instant now = Clock.systemUTC().instant();
        for (TaskRecord child : children) {
            if (child.phase() != TaskPhase.DRAFT && child.phase() != TaskPhase.QUEUED
                    && child.phase() != TaskPhase.PAUSED) {
                continue;
            }
            persistence.transitionTask(child.id(), TaskPhase.CANCELLED, child.version(), now,
                    "cascade-cancel-" + child.id(), requestIdHash(parentId, child.id()),
                    CANCEL_TASK);
        }
    }
```

（`transitionTask` 幂等：级联中断后重试 cancel，主任务转移幂等重放后级联重新执行，子任务行锁 + 幂等键保证单次取消。`requestIdHash` 用 `idempotency.requestHash("cascade", parentId.toString(), child.id().toString())` 或等价确定性哈希。）

- [ ] **步骤 4.3：GET 子任务列表端点（汇总轮输入）**

`SubtaskController` 加只读端点（鉴权沿 plan 端点模式：未知任务 404，principal 校验 specJson scope）：

```java
    public record SubtaskSummary(UUID subtaskId, String title, int sequence, String status,
            String phase, List<UUID> dependencyIds) {}

    @GetMapping
    public List<SubtaskSummary> list(@PathVariable UUID taskId) {
        TaskRecord task = tasks.get(taskId);  // 未知 → 404；scope 校验同 plan
        List<TaskRecord> children = persistence.inTransaction(tx -> tx.tasks().findByParent(taskId));
        Map<UUID, TaskRecord> byId = children.stream()
                .collect(Collectors.toMap(TaskRecord::id, java.util.function.Function.identity()));
        List<SubtaskSummary> merged = new ArrayList<>();
        for (TaskTreeNode node : subtasks.listProjection(taskId)) {   // 投影序（sequence）为准
            TaskRecord child = byId.get(node.taskId());
            if (child == null) continue;
            merged.add(new SubtaskSummary(child.id(), node.title(), node.sequence(),
                    node.status(), child.phase().name(), node.dependencyIds()));
            byId.remove(child.id());
        }
        byId.values().forEach(child -> merged.add(new SubtaskSummary(child.id(), child.title(),
                0, "ORPHANED", child.phase().name(), List.of())));  // 投影缺失兑底
        return List.copyOf(merged);
    }
```

`SubtaskService` 补 `listProjection(taskId)`（`tree.find(context, runId)` 包装，无 run 时返回空表）。`SubtaskController` 需注入 `FoundationPersistenceService`（构造器追加）；`SubtaskControllerTest` 既有构造点同步补 `mock(FoundationPersistenceService.class)` 并 stub `inTransaction` 返回 `List.of()`。

- [ ] **步骤 4.4：openapi 补录**

`openapi/agentteams-public.yaml` 在子任务 plan/status 端点旁补录 `GET /api/v1/tasks/{taskId}/subtasks`：

```yaml
  /tasks/{taskId}/subtasks:
    get:
      operationId: listTaskSubtasks
      summary: 列出任务子任务（G03 真调度：phase 为平台执行真值）
      parameters: [ $ref: '#/components/parameters/TaskId' ]   # 沿文件既有参数命名
      responses:
        '200':
          description: 子任务清单（含投影 title/sequence/依赖与真实 phase）
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/SubtaskSummary' }
        '404': { $ref: '#/components/responses/NotFound' }
```

`SubtaskSummary` schema：`subtaskId/title/sequence/status/phase/dependencyIds`（status=投影状态，phase=真实任务 phase）。

- [ ] **步骤 4.5：MCP list_subtasks 工具**

`scripts/agentteams-task-mcp.py` 加工具（GET 无写语义，不带幂等键）：

```python
TOOLS.append({
    "name": "list_subtasks",
    "description": "List subtasks of a task with their real execution phase. "
                   "Use in the aggregation round to collect subtask outputs, then call "
                   "get_task_result with each subtaskId as task_id.",
    "inputSchema": {"type": "object", "required": ["task_id"], "properties": {
        "task_id": {"type": "string"}}},
})


def tool_list_subtasks(arguments: dict[str, Any]) -> dict[str, Any]:
    config = get_config()
    task_id = _safe_uuid(arguments.get("task_id"), "task_id")
    payload = _http_json(
        "GET", _api_url(config, f"/api/v1/tasks/{task_id}/subtasks"), token=_fetch_token())
    subtasks = [{"subtaskId": item.get("subtaskId"), "title": item.get("title"),
                 "sequence": item.get("sequence"), "phase": item.get("phase"),
                 "status": item.get("status")} for item in payload]
    return {"ok": True, "taskId": task_id, "subtasks": subtasks,
            "note": "Fetch each subtask's deliverables via get_task_result(task_id=subtaskId)."}
```

（`TOOLS.append` 的注册机制按该文件实际结构接入——实现时对照既有 `plan_subtasks` 的注册写法；`get_task_result` **无需改动**：子任务本身就是任务，`get_task_result(task_id=子任务id)` 直达其 run result 端点。）

- [ ] **步骤 4.6：MCP 单测 + 全量验证 + Commit**

```bash
python3 -m pytest scripts/test_agentteams_task_mcp.py -q   # 或文件实际的运行方式（unittest）
JAVA_HOME=$JAVA_HOME mvn -q test -pl control-plane -am
JAVA_HOME=$JAVA_HOME mvn -q test
git add -A && git commit -m "feat(任务域): cancel 级联、子任务列表端点与 MCP list_subtasks（G03 C3）"
```

最终 commit 前全模块 `mvn -q test` 必须 0 失败（这是 C4 kind 验收的前置）。

---

### 任务 5：console DAG 跳转 + mock 两段剧本 + kind 全链验收

**文件：**
- 修改：`console/src/features/tasks/TaskDag.tsx`、`console/src/features/tasks/TaskDetailPage.tsx`
- 修改：`scripts/qwenpaw-conversation-mock.py`
- 创建：`scripts/run-kind-subtask-delegation.py`
- 测试：`console` vitest（既有 TaskInfoPanel/TaskDag 测试文件适配）

- [ ] **步骤 5.1：TaskDag 子任务节点跳转**

`TaskDag.tsx` 节点渲染处（二期已有 onSelect 下钻）为节点加跳转：仅当 `node.taskId` 是一等子任务（`TaskDetailPage` 通过新 GET 端点得知 `phase` 来自真实任务——数据面：`TaskDetailPage` 已拉取 process-events 与 `/tree`；新增从 `GET /api/v1/tasks/{taskId}/subtasks` 拉取 `SubtaskSummary[]`，将 `phase` 传入 TaskDag，节点徽章显示真实 phase，并为每个节点渲染「打开 →」链接）：

```tsx
// TaskDag.tsx 节点 footer：
{subtask && (
  <button
    type="button"
    className="task-dag-open"
    onClick={(event) => {
      event.stopPropagation();  // 不触发既有下钻
      onOpenTask?.(subtask.subtaskId);
    }}
    aria-label={`打开子任务 ${subtask.title}`}
  >打开 →</button>
)}
```

`TaskDetailPage.tsx`：`const navigate = useNavigate()`，`onOpenTask={(id) => navigate(\`../tasks/\${id}\`)}`（相对路径按既有路由结构校正）。vitest：为 TaskDag 新 props 写渲染断言（节点有 phase 徽章与打开按钮，点击调用 onOpenTask 且不冒泡）。

```bash
cd console && npx vitest run src/features/tasks && npm run build
```

- [ ] **步骤 5.2：mock 两段剧本**

`scripts/qwenpaw-conversation-mock.py`：拆解剧本（L148-190 附近）改造为**按会话轮次区分两段**——同一 taskId 的第 1 个会话返回拆解剧本（plan 3 个子任务：A 无依赖、B 无依赖、C 依赖 A+B，其中 plan 时带 `requiredCapabilities`）；第 2 个会话（汇总轮，主任务第二次被调度时）返回汇总剧本：先 `list_subtasks` tool_pair、再对每个 SUCCEEDED 子任务 `get_task_result` tool_pair、最后 `message.completed` 输出 `SUBTASK_AGGREGATION_SUMMARY`。实现要点：mock 已有 `SESSIONS` 状态字典（L193-199 可见），按 `task_id → 会话计数` 路由剧本段；子任务的 mock 响应为普通文本产物（无工具），保证子任务 run SUCCEEDED 自动触发 manifest publish。kind 部署链更新：`deploy/kind-qwenpaw-conversation-mock.yaml`（或对应 ConfigMap 清单）内嵌脚本逐字节同步（二期既有契约，`scripts/test_console_manifests_contract.py` 类契约测试若覆盖该清单需同步）。

- [ ] **步骤 5.3：kind 验收脚本**

创建 `scripts/run-kind-subtask-delegation.py`（以 `scripts/run-kind-task-review-lifecycle.py` 为模板：require_environment、TokenSource、port-forward、ensure_project_membership/restore、poll_until、finally 清理顺序全部沿用）。脚本步骤与断言：

1. 种 membership（ADMIN），创建主任务（`requiredCapabilities: ["qwenpaw"]`，prompt 触发拆解剧本）→ queue
2. 轮询主任务出现 3 个子任务（`GET /tasks/{id}/subtasks`）：A/B `phase=DRAFT`→QUEUED（无依赖立即 gate），C 保持 DRAFT（依赖未满足）——**拓扑 gate 断言**
3. 轮询 A、B `SUCCEEDED`（各自独立 attempt：断言 `task_attempts` 两行且 lease 归属可为不同 agent）→ C 转 QUEUED（**依赖 gate 断言**）→ 人为让 C 失败：patch mock 剧本返回失败标记或 `PUT status FAILED`（fixture）→ C `FAILED` 后主任务不触发汇总（断言主任务 phase 不变）→ 对 C 调 `POST /tasks/{C}/retry`（**失败子任务 retry 恢复链**，G02 边 FAILED→QUEUED）→ C `SUCCEEDED`
4. 轮询主任务 `SUCCEEDED→QUEUED`（TaskChildrenCompleted）→ 第二跑 → `SUCCEEDED`
5. 断言 `task_result_versions`：主任务恰 1 条 SUBMITTED（拆解轮 publish 被硬约束跳过的证明：轮询期间查 count==0 的时点在步骤 4 前记录）
6. 评审 ACCEPTED（幂等重放同记录）→ 归档 → 列表/stats 可见
7. 负路径：创建第二个主任务 plan 后直接 cancel → 未出队子任务 CANCELLED、已出队的自然终态
8. finally：membership 还原、pf 清理

运行：`python3 scripts/run-kind-subtask-delegation.py`，期望输出 `PASS kind-subtask-delegation`、`EXIT=0`。

- [ ] **步骤 5.4：kind 镜像重建部署**

```bash
docker build --platform linux/arm64 -f deploy/docker/control-plane.Dockerfile \
  -t agentteams-control-plane:g03-c1-$(date +%Y%m%d) .
kind load docker-image agentteams-control-plane:g03-c1-$(date +%Y%m%d) --name agentteams
kubectl --context kind-agentteams -n agentteams set image \
  deployment/agentteams-agentteams-java-control-plane \
  control-plane=agentteams-control-plane:g03-c1-$(date +%Y%m%d)
kubectl --context kind-agentteams -n agentteams rollout status deployment/agentteams-agentteams-java-control-plane --timeout=240s
# 日志确认：Successfully applied 1 migration ... now at version v93
```

- [ ] **步骤 5.5：Commit**

```bash
git add -A && git commit -m "feat(任务域): console DAG 跳转、mock 两段剧本与 kind 真调度全链验收（G03 C4）"
```

---

### 任务 6：L5 真模型验收 + 最终回归

**文件：**
- 创建：`scripts/run-l5-subtask-delegation.py`
- 验证：全模块 `mvn -q test`

- [ ] **步骤 6.1：L5 amd64 镜像分发**

```bash
docker build --platform linux/amd64 -f deploy/docker/control-plane.Dockerfile \
  -t agentteams-control-plane:l5-g03-$(date +%Y%m%d) .
docker tag agentteams-control-plane:l5-g03-$(date +%Y%m%d) \
  localhost:5500/library/agentteams-control-plane:l5-g03-$(date +%Y%m%d)
docker push localhost:5500/library/agentteams-control-plane:l5-g03-$(date +%Y%m%d)
SSH="ssh -i $HOME/.ssh/agentteams-l5-nopass -o UserKnownHostsFile=$HOME/.ssh/l5_known_hosts ly@192.168.122.55"
$SSH "sudo /usr/local/bin/k3s kubectl -n agentteams set image \
  deployment/agentteams-agentteams-java-control-plane \
  control-plane=agentteams-control-plane:l5-g03-$(date +%Y%m%d)"
$SSH "sudo /usr/local/bin/k3s kubectl -n agentteams rollout status \
  deployment/agentteams-agentteams-java-control-plane --timeout=240s"
# V93 迁移确认：kubectl logs grep 'now at version v93'
```

（set image 用无前缀引用——k3s mirror 首位 rewrite 到 Mac registry；新 tag 无 IfNotPresent 命中问题。详见 memory「L5 镜像分发链路」。）

- [ ] **步骤 6.2：L5 验收脚本**

创建 `scripts/run-l5-subtask-delegation.py`（以 `scripts/run-l5-task-review-lifecycle.py` 为模板：主机内 ClusterIP 直连、`KUBECTL=("sudo","/usr/local/bin/k3s","kubectl")`、真模型轮询 900s、membership 还原、`/tmp` 清理）。真模型轮结构：

1. 创建主任务，prompt 显式引导拆解：「将《季度项目报告》任务拆解为 2 个子任务（收集数据、撰写报告，后者依赖前者），随后等待平台执行」
2. **确定性层兜底**（真模型未主动拆解时的先例做法，二期 L5 验收同款）：轮询 120s 未出现子任务时，脚本经 REST 以主任务身份调 `PUT /tasks/{id}/subtasks` 直接声明清单（fixture 注入，标记 NOTE）
3. 轮询子任务拓扑执行至全 SUCCEEDED → 主任务自动 QUEUED → 汇总轮真模型产出 `SUBTASK_AGGREGATION_SUMMARY` 标记 → publish → 评审 ACCEPTED → ARCHIVED
4. 错误前缀 `L5_SUBTASK_DELEGATION_FAILED`；PASS 输出 task/子任务 id/结果版本 id

- [ ] **步骤 6.3：scp 执行 + 全模块回归 + Commit**

```bash
scp -i $HOME/.ssh/agentteams-l5-nopass -o UserKnownHostsFile=$HOME/.ssh/l5_known_hosts \
  scripts/run-l5-subtask-delegation.py ly@192.168.122.55:/tmp/
ssh -i $HOME/.ssh/agentteams-l5-nopass -o UserKnownHostsFile=$HOME/.ssh/l5_known_hosts \
  ly@192.168.122.55 "python3 /tmp/run-l5-subtask-delegation.py; rm -f /tmp/run-l5-subtask-delegation.py"
JAVA_HOME=$JAVA_HOME mvn -q test   # 全模块 0 失败
git add -A && git commit -m "test(任务域): L5 真模型子任务委派验收脚本（G03 C5）"
```

- [ ] **步骤 6.4：收尾（finishing-a-development-branch）**

CodeReview 子代理审查 `main..subtask-delegation` → 修复 → 全量回归 → 双环境重验收（镜像重建 → kind/L5 重跑脚本）→ 按 finishing-a-development-branch 技能展示 4 选项。
