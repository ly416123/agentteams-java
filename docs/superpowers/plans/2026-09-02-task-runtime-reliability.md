# 任务运行可靠性实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在不修改 Java SDK 的前提下，完成管理端任务可靠性、定时运行闭环、对话追加和断链重连。

**架构：** Control Plane 以类型化 Task、Schedule Run、Checkpoint 和 Retry 元数据为事实边界；Manager 以持久化消息状态和串行派发队列支持对话追加；Console 通过游标 API 展示定义、运行、结果和恢复状态。传输重连只推进游标，业务重试只使用持久化幂等意图。

**技术栈：** Java 17、Spring Boot、PostgreSQL/Flyway、React、TypeScript、Vitest、现有 SSE、Scheduler Lease 和资源授权模型。

---

## 文件职责

- 新增/修改 `application-contracts`：Task Type、Schedule Run、Checkpoint 和重试数据契约。
- 修改 `control-plane`：迁移、Task/Schedule Repository、运行控制 API、恢复调度和结果查询。
- 修改 `manager`：消息排队、自动派发、恢复状态和运行时事件对接。
- 修改 `console`：任务类型、定时计划、运行结果和对话状态 UI，以及游标重连测试。
- 修改 `docs`：记录已实现语义、接口和 SDK 延后约束。
- 不修改 `sdk/java`。

### 任务 1：先写失败测试并冻结契约

**文件：**
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/ConversationServiceTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/schedule/ScheduledTaskServiceTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/service/TaskAssignmentServiceTest.java`
- 测试：`console/tests/features/conversations/ConversationPage.test.tsx`
- 测试：`console/tests/streams/conversationEvents.test.ts`

- [x] 步骤 1：增加对话执行中第二条消息顺序派发、Schedule Run 查询/终止和 Task Type 的失败测试；恢复能力复用既有租约回收红测。
- [x] 步骤 2：运行定向测试，确认失败原因是契约/实现不存在，而不是测试配置错误。

### 任务 2：实现 Task Type 与 Schedule Run 基础

**文件：**
- 修改：`control-plane/src/main/resources/db/migration/V1__foundation.sql` 或新增下一版本迁移
- 修改：`control-plane/src/main/resources/db/migration/V67__scheduled_tasks.sql` 或新增下一版本迁移
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRecord.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskListRecord.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/schedule/ScheduledTaskDefinition.java`
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/schedule/ScheduledTaskRun.java`
- 新增/修改：`control-plane/src/main/java/io/agentteams/controlplane/schedule/JdbcScheduledTaskRunRepository.java`

- [x] 步骤 1：添加 `task_type`、Schedule Run 关联和运行状态字段，保证旧数据默认 `NORMAL`。
- [x] 步骤 2：为每次计划触发写入唯一 Schedule Run，并让生成 Task 的类型为 `SCHEDULED`。
- [x] 步骤 3：运行迁移、Repository 和 Task Service 定向测试。

### 任务 3：实现定时运行查询、结果展示数据与终止

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/ScheduledTaskController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/schedule/ScheduledTaskService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/schedule/ScheduledTaskScheduler.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskService.java`
- 新增/修改：相关 Schedule/Task Controller 测试
- 新增：`console/src/api/scheduledTasks.ts`
- 新增：`console/src/features/tasks/ScheduledTaskPage.tsx`
- 修改：`console/src/app/AppShell.tsx`、`console/src/app/router.tsx`

- [x] 步骤 1：实现按作用域查询计划运行历史和单次运行详情，关联 Task、Run 和结果摘要。
- [x] 步骤 2：实现“停用计划”和“终止当前运行”两个独立、幂等的操作。
- [x] 步骤 3：管理端增加计划列表、状态操作、运行历史和结果跳转。
- [x] 步骤 4：运行后端与 Console 定向测试。

### 任务 4：实现执行检查点、崩溃恢复和重试策略

**文件：**
- 新增迁移：`control-plane/src/main/resources/db/migration/V84__task_recovery_checkpoints.sql`
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskRecoveryCheckpoint.java`
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskRecoveryCheckpointRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/FoundationTransaction.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskExecutionController.java`

- [ ] 步骤 1：定义 checkpoint 的 Task/Run/Attempt/step 幂等键、状态、版本和安全 Payload 引用。
- [ ] 步骤 2：租约过期时保存恢复原因与重试次数，按持久化策略退避并重新排队；超过上限进入 FAILED/RECOVERY_REQUIRED。
- [ ] 步骤 3：暴露恢复记录和最近 checkpoint，避免管理端只能看到一个旧 Lease。
- [ ] 步骤 4：运行恢复、重复事件、重试上限和跨租户隔离测试。

### 任务 5：实现对话追加派发与断链自动恢复

**文件：**
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/ConversationService.java`
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/ConversationRuntimePort.java`
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java`
- 修改：`manager/src/main/java/io/agentteams/manager/api/ConversationController.java`
- 修改：`console/src/streams/conversationEvents.ts`
- 修改：`console/src/features/conversations/ConversationPage.tsx`

- [x] 步骤 1：消息发送改为持久化后串行派发，第二条消息在当前请求完成后自动发送。
- [x] 步骤 2：对可重试的上游连接失败实现有上限的自动重试，并保持原消息幂等键和事件边界。
- [x] 步骤 3：事件 SSE 正常结束后继续按游标轮询/重连，断链不重复渲染事件；页面保留重连状态。
- [x] 步骤 4：运行 Manager 与 Console 对话测试。

### 任务 6：补齐普通任务过程/结果管理端展示

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskExecutionController.java`
- 修改：`console/src/api/tasks.ts`
- 修改：`console/src/features/tasks/TaskDetailPage.tsx`
- 修改：`console/src/features/tasks/TaskPage.tsx`
- 修改：`console/src/api/types.ts`

- [x] 步骤 1：让任务详情能发现 Run，并展示结果摘要与关联运行入口。
- [x] 步骤 2：增加恢复次数和最近 checkpoint 展示；租约恢复沿用现有重排队语义，并把最近检查点写入恢复规格。
- [x] 步骤 3：运行 Task 页面测试和构建。

### 任务 7：回归、文档和提交

**文件：**
- 修改：`docs/superpowers/specs/2026-09-02-task-runtime-reliability-design.md`
- 修改：`docs/superpowers/plans/2026-09-02-task-runtime-reliability.md`

- [ ] 步骤 1：运行受影响 Java 模块测试、Console 全量测试、构建、Lint、格式和 Flyway 校验。
- [x] 步骤 2：运行需求逐项核对，确认 Java SDK 未发生变化，确认 L6 未执行。
- [ ] 步骤 3：请求代码审查并修复 Critical/Important 问题。
- [ ] 步骤 4：提交功能变更并准备合并回 `main`。
