# Task 状态一致性与终态保护实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 防止晚到 Worker 观察事件回退 `task_runs` 终态，并提供可审计、可重复执行的 Task/Run/Attempt/Manifest 一致性对账。

**架构：** `tasks.phase` 保持唯一权威；`task_runs` 仅是单调运行投影，终态不可回退。对账 Job 使用数据库 Scheduler Lease，按批扫描活跃/近期终态 Run，发现漂移写入问题表、日志和指标，第一阶段不自动修改 Task 权威状态。

**技术栈：** Java 17、Spring JDBC、PostgreSQL/Flyway、JUnit 5、Mockito、AssertJ、Testcontainers、Micrometer、Spring Scheduling。

---

## 文件边界

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskRunObservationRepository.java`、`JdbcTaskRunObservationRepository.java`，增加运行投影单调更新和关联校验。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskStateConsistencySnapshot.java`、`TaskStateConsistencyIssue.java`、`TaskStateConsistencyChecker.java`、`TaskStateConsistencyRepository.java`、`JdbcTaskStateConsistencyRepository.java`、`TaskStateConsistencyService.java`、`TaskStateConsistencyJob.java`。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/observability/ControlPlaneMetrics.java`，增加漂移发现/恢复指标；`ControlPlaneConfiguration.java`、`application.yml` 和 Helm values 增加对账装配与配置。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/InternalTaskStateConsistencyController.java`，提供内部 Token 保护的只读问题查询。
- 创建：`control-plane/src/main/resources/db/migration/V71__task_state_consistency.sql`。
- 创建/修改测试：`JdbcTaskRunObservationRepositoryTest.java`、`TaskStateConsistencyCheckerTest.java`、`TaskStateConsistencyServiceTest.java`、`TaskStateConsistencyJobTest.java`、`JdbcTaskStateConsistencyRepositoryTest.java`、`InternalTaskStateConsistencyControllerTest.java`、`ControlPlaneTaskExecutionObservationAdapterTest.java`。
- 修改文档：`docs/superpowers/specs/2026-08-31-task-state-consistency-design.md`、`docs/superpowers/plans/2026-08-31-commercial-product-mainline.md`、`README.md`。

### 任务 1：Task Run 终态单调保护

**目标：** 让 `ensureRun` 在重复、乱序和晚到观察事件下不会回退或篡改已完成 Run。

- [ ] **步骤 1：编写失败的 PostgreSQL 回归测试。**

  在 `JdbcTaskRunObservationRepositoryTest` 使用 PostgreSQL Testcontainers 和 Flyway 全量迁移，插入同一 Task 的 Run 为 `SUCCEEDED`，再调用 `ensureRun(..., "RUNNING", olderTimestamp)`；断言 Run 仍为 `SUCCEEDED`、`completed_at` 不为空且 `updated_at` 不倒退。再插入不同 Task/租户的同一 `run_id` 观察，断言抛出明确的关联异常。

- [ ] **步骤 2：运行测试确认正确失败。**

  运行：`mvn -q -pl control-plane -am -Dtest=JdbcTaskRunObservationRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：测试编译成功但终态断言失败，当前实现会把 `SUCCEEDED` 更新为 `RUNNING`。

- [ ] **步骤 3：实现最小单调 upsert。**

  修改 `JdbcTaskRunObservationRepository.ensureRun`：

  ```sql
  status = CASE
      WHEN task_runs.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN task_runs.status
      WHEN task_runs.status = 'RUNNING' AND EXCLUDED.status = 'QUEUED' THEN task_runs.status
      ELSE EXCLUDED.status
  END,
  started_at = COALESCE(task_runs.started_at, EXCLUDED.started_at),
  completed_at = CASE
      WHEN task_runs.completed_at IS NOT NULL THEN task_runs.completed_at
      WHEN EXCLUDED.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN EXCLUDED.updated_at
      ELSE NULL
  END,
  updated_at = GREATEST(task_runs.updated_at, EXCLUDED.updated_at)
  ```

  在 upsert 前锁定并校验现有 `run_id` 的 `task_id`、`organization_id`、`tenant_id`；不匹配时抛出 `IllegalArgumentException`，不存在时继续插入。保持现有接口兼容，不新增第二套状态机。

- [ ] **步骤 4：增加观察适配器回归测试。**

  在 `ControlPlaneTaskExecutionObservationAdapterTest` 覆盖完成后收到进度观察时仍会记录过程事件，但不允许由观察路径将 Run 投影降级；同时确认现有正常进度、完成、失败测试保持通过。

- [ ] **步骤 5：运行定向测试并提交。**

  运行：`mvn -q -pl control-plane -am -Dtest=JdbcTaskRunObservationRepositoryTest,ControlPlaneTaskExecutionObservationAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：全部通过；提交：`git add control-plane && git commit -m "fix(任务): 防止运行状态回退"`。

### 任务 2：一致性快照、问题表与纯函数检查器

**目标：** 以单条 Run 快照表达跨表事实，识别状态、终态资源、Manifest、过程序号和子任务漂移。

- [ ] **步骤 1：编写检查器失败测试。**

  在 `TaskStateConsistencyCheckerTest` 构造快照并覆盖：

  ```java
  assertThat(checker.check(snapshot("SUCCEEDED", "RUNNING", null, false, 0, -1, 0)))
      .extracting(TaskStateConsistencyIssue::type)
      .containsExactly("TASK_RUN_STATUS_MISMATCH", "TERMINAL_ATTEMPT_ACTIVE", "RESULT_MANIFEST_MISSING");
  assertThat(checker.check(snapshot("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", false, 2, 1, 0)))
      .extracting(TaskStateConsistencyIssue::type)
      .containsExactly("PROCESS_SEQUENCE_GAP");
  ```

  同时覆盖允许的 `QUEUED/RUNNING/终态` 映射、取消无 Manifest 的合法情况和阻塞子任务问题。

- [ ] **步骤 2：运行测试确认正确失败。**

  运行：`mvn -q -pl control-plane -am -Dtest=TaskStateConsistencyCheckerTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：因快照、问题类型和检查器尚不存在而编译失败。

- [ ] **步骤 3：实现快照、问题类型和纯检查器。**

  `TaskStateConsistencySnapshot` 保存 Task/Run 标识、组织租户、Task phase、Run status、Manifest status、活动 Attempt/Lease 数、过程事件数量/最大序号、未完成子任务数和观察时间。`TaskStateConsistencyChecker` 只根据快照返回稳定排序的问题类型和脱敏详情，不访问数据库、不修改状态。

- [ ] **步骤 4：编写 V71 和 JDBC Repository 失败测试。**

  在 `JdbcTaskStateConsistencyRepositoryTest` 验证空库迁移、问题唯一键 `(task_id, run_id, issue_type)`、重复发现只递增 `occurrences`、状态恢复写入 `resolved_at`、按 limit 查询 OPEN 问题。测试数据覆盖一个成功 Task、运行投影、Manifest、Attempt/Lease、过程事件和子任务。

- [ ] **步骤 5：实现 V71 迁移与快照查询。**

  `V71__task_state_consistency.sql` 创建 `task_state_consistency_issues`，保存 `task_id`、`run_id`、组织租户、问题类型、观察状态、脱敏详情、`first_seen_at`、`last_seen_at`、`occurrences`、`status`、`resolved_at`、`created_at`、`updated_at`，并增加 OPEN 查询索引和非空/状态约束。

  `JdbcTaskStateConsistencyRepository` 通过一个批量查询返回最近活跃/近期终态 Run 的快照；用聚合查询计算活动 Lease、Manifest、过程序号和未完成子任务；写入问题时使用 PostgreSQL `ON CONFLICT`，恢复时只把当前 Run 的其他 OPEN 问题标记为 RESOLVED。

- [ ] **步骤 6：运行检查器和 JDBC 定向测试并提交。**

  运行：`mvn -q -pl control-plane -am -Dtest=TaskStateConsistencyCheckerTest,JdbcTaskStateConsistencyRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：全部通过，Flyway 验证到 V71；提交：`git add control-plane && git commit -m "feat(任务): 增加状态一致性问题投影"`。

### 任务 3：对账服务、Lease Job、指标和内部查询

**目标：** 定期、可重复、只读地发现并记录状态漂移，为运维提供受保护查询入口。

- [ ] **步骤 1：编写服务和 Job 失败测试。**

  `TaskStateConsistencyServiceTest` 使用 Mock Repository 验证：每个快照的问题被 upsert，旧 OPEN 问题被 resolve，单条快照检查异常不会阻塞其他快照；`TaskStateConsistencyJobTest` 验证非 Lease leader 不扫描，leader 使用 `task-state-consistency` lease 并返回扫描/问题/恢复计数。

- [ ] **步骤 2：实现服务和 Job。**

  `TaskStateConsistencyService.reconcile(now, lookback, batchSize)` 获取快照、调用纯检查器、持久化问题并返回 `ReconcileResult(scanned, detected, resolved, failed)`；单条异常记录日志后继续。`TaskStateConsistencyJob` 复用 `SchedulerLeaseService`，默认每 60 秒、回看 24 小时、每批 100 条、租约 30 秒。

- [ ] **步骤 3：增加指标和配置装配。**

  在 `ControlPlaneMetrics` 增加 `agentteams.task.consistency.issues`、`agentteams.task.consistency.resolved`、`agentteams.task.consistency.scan.failures`，在 `ControlPlaneConfiguration` 中按 Repository/Lease 条件装配服务和 Job；`application.yml`、Helm 默认值和生产示例提供 interval/lookback/batch/lease 配置。

- [ ] **步骤 4：增加内部只读查询。**

  `GET /internal/v1/task-state-consistency/issues?limit=100` 使用现有 `X-AgentTeams-Internal-Token` 校验，最多返回 1000 条 OPEN 问题；响应只包含 Task/Run 标识、问题类型、状态快照、次数和时间，不返回 Prompt、Payload、Secret 或完整业务内容。补充 Controller 正负向测试。

- [ ] **步骤 5：运行定向测试并提交。**

  运行：`mvn -q -pl control-plane -am -Dtest=TaskStateConsistencyServiceTest,TaskStateConsistencyJobTest,InternalTaskStateConsistencyControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

  预期：全部通过；提交：`git add control-plane && git commit -m "feat(任务): 接入状态一致性对账"`。

### 任务 4：文档、全量验证和收口

- [ ] **步骤 1：同步设计与项目计划状态。**

  在规格中记录实际落地的 V71 表、终态保护、对账 Job、内部查询和“只告警不自动修复”边界；在商业主线计划和 README 中增加状态一致性能力说明。

- [ ] **步骤 2：运行 Control Plane 全量测试。**

  运行：`mvn -q -pl control-plane -am test`

  预期：退出码 0，Surefire 报告失败数和错误数均为 0。

- [ ] **步骤 3：运行统一 Docker/API/Helm 门禁。**

  运行：`bash scripts/enterprise-execution-contract.sh`

  预期：Testcontainers 解析 `unix:///Users/gecko/.colima/default/docker.sock`，输出 `API_CONTRACT_OK`、`ENTERPRISE_EXECUTION_CONTRACT_OK`，Helm lint 和 `git diff --check` 均通过。

- [ ] **步骤 4：提交文档和最终实现。**

  运行：`git diff --check && git status --short && git show --check --oneline HEAD`

  预期：工作树干净；提交：`git add control-plane docs README.md deploy/helm/agentteams-java && git commit -m "docs(任务): 完成状态一致性收口"`。
