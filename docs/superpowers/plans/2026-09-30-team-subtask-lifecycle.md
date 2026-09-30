# Team 子任务生命周期修复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 修复 Team Leader 规划完成后父任务提前显示成功、Executor 子任务未形成可观测调度闭环的问题。

**架构：** 为 Team 任务聚合增加明确的 `WAITING_SUBTASKS` 非终态。Leader 首轮运行完成并创建子任务后，父任务进入等待态；gate 只在所有子任务成功后把父任务改为带汇总输入的 `QUEUED`，由 Leader 执行最终汇总轮。普通 DAG 子任务仍保留原有 `SUCCEEDED` 汇总入口，已经处于 `AGGREGATE` 阶段的 Team 任务不重复规划。

**技术栈：** Java 17、Spring service、PostgreSQL/JDBC、JUnit 5、Testcontainers、Flyway。

---

### 任务 1：锁定等待态和 gate 语义

**文件：**
- 修改：`domain/src/main/java/io/agentteams/domain/task/TaskPhase.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskGateService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskStateConsistencyChecker.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/JdbcTaskStateConsistencyRepository.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskGateServiceTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/task/TaskStateConsistencyCheckerTest.java`

- [x] **步骤 1：编写失败的测试**

  在 `SubtaskGateServiceTest` 中增加：父任务为 `WAITING_SUBTASKS` 且全部子任务成功时，gate 将父任务排入 `QUEUED`；在 `TaskStateConsistencyCheckerTest` 中增加：等待态与已完成 run、未完成子任务组合不产生终态不一致告警。

- [x] **步骤 2：运行测试验证失败**

  运行：`mvn -q -pl control-plane -am -Dtest=SubtaskGateServiceTest,TaskStateConsistencyCheckerTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：编译或断言失败，原因是 `WAITING_SUBTASKS` 尚未存在、gate 尚未接受该状态。

- [x] **步骤 3：编写最少实现代码**

  增加 `WAITING_SUBTASKS` 非终态；将 all-children-succeeded 查询和 gate 的父任务前置条件扩展到该状态；将一致性快照扫描和状态兼容判断纳入等待态。不要改变普通任务或取消/失败终态语义。

- [x] **步骤 4：运行测试验证通过**

  运行同一 Maven 命令，预期相关测试全部通过。

- [ ] **步骤 5：Commit**

  `git add domain control-plane && git commit -m "fix: add explicit team subtask waiting phase"`

### 任务 2：Leader 规划完成后切入等待态

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TeamOrchestrationService.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/task/TeamOrchestrationServiceTest.java`

- [x] **步骤 1：编写失败的测试**

  用 PostgreSQL 任务行和 `SubtaskService` mock 验证：合法 Team Leader 规划结果创建子任务后，主任务从 `SUCCEEDED` 转为 `WAITING_SUBTASKS`；AGGREGATE 轮和普通任务不进入等待态。当前测试合并在 `SubtaskGateServiceTest` 中。

- [x] **步骤 2：运行测试验证失败**

  运行：`mvn -q -pl control-plane -am -Dtest=TeamOrchestrationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：测试失败，因为当前 `planLeaderResult` 只规划子任务，不更新父任务阶段。

- [x] **步骤 3：编写最少实现代码**

  让 `planLeaderResult` 在成功规划后，以行锁和版本条件把父任务切换为 `WAITING_SUBTASKS`，并保持幂等；只有首次合法 PLAN 结果且至少存在一个子任务时切换。

- [x] **步骤 4：运行测试验证通过**

  运行：`mvn -q -pl control-plane -am -Dtest=TeamOrchestrationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：新增测试通过。

- [ ] **步骤 5：Commit**

  `git add control-plane/src/main/java/io/agentteams/controlplane/task/TeamOrchestrationService.java control-plane/src/test/java/io/agentteams/controlplane/task/TeamOrchestrationServiceTest.java && git commit -m "fix: hold team parent while subtasks execute"`

### 任务 3：全量回归和真实 Team 流程验证

**文件：**
- 修改：如测试暴露出状态兼容缺口，只修改对应生产文件和测试文件

- [x] **步骤 1：运行控制面目标回归**

  运行：`mvn -q -pl control-plane -am -Dtest=SubtaskGateServiceTest,TeamOrchestrationServiceTest,TaskStateConsistencyCheckerTest,TeamPlanParserTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [x] **步骤 2：运行完整控制面测试**

  运行：`mvn -q -pl control-plane -am test`

- [ ] **步骤 3：构建运行时并部署到 L5**

  使用项目现有的 amd64 构建和 L5 发布脚本，更新 control-plane 镜像后滚动重启，确认 `/health` 和调度器恢复。

- [ ] **步骤 4：创建真实短任务回归**

  通过管理后台创建包含 Team 的短任务，确认顺序为：父任务首轮 RUNNING → WAITING_SUBTASKS、Executor 子任务 QUEUED/ASSIGNED/RUNNING/SUCCEEDED、父任务重新 QUEUED（AGGREGATE）、Leader 汇总后最终 SUCCEEDED，并检查真实 DeepSeek 审计记录及成果物。

- [ ] **步骤 5：Commit**

  `git add . && git commit -m "test: verify team subtask lifecycle end to end"`
