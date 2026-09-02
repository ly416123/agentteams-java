# 任务运行可靠性与交互设计

**状态：** 已确认，进入实现

**目标：** 补齐对话执行期间追加信息、定时运行控制与结果查询、可扩展任务类型、任务崩溃恢复，以及对话和任务事件断链重连能力。

## 范围与边界

- 本批次先完成 Control Plane、Manager 和管理端 Console 的业务闭环。
- Java SDK 本批次不修改；管理端接口先稳定，最终页面功能验证通过后再补充 SDK 对等能力。
- L6 验收不纳入本批次，除非用户另行明确启动。
- 不保存 Secret、完整 Prompt、原始 Chain of Thought 或模型凭据。

## 已确认语义

### 对话追加信息

同一会话中的消息按提交顺序排队。当前模型请求完成后，Manager 自动派发下一条消息；追加消息仍使用会话版本和幂等键保护。管理端显示消息已排队、执行中、完成或失败。当前执行不会被隐式打断；需要立即停止时仍使用取消操作。

### 定时运行控制

定时计划和定时运行实例分离：

- 停用/恢复计划只影响未来触发；
- 终止当前运行只取消已经创建的 Task；
- 定时计划支持查询运行历史、运行状态、关联 Task、Run、结果和产物；
- 终止操作必须幂等，并通过作用域与版本校验。

### 任务类型

任务保存稳定的 `taskType` 标识，首批提供 `NORMAL` 和 `SCHEDULED`。类型值采用字符串而非数据库枚举，未知类型由查询和展示层安全保留，类型特有参数放在已脱敏的 `spec` 中。定时触发的 Task 使用 `SCHEDULED`，并保存 `scheduleId` 与 `scheduleOccurrence` 元数据。

### 崩溃恢复

租约过期或 Worker 崩溃后，系统保留旧 Attempt 的审计记录，生成新的 Attempt 并重新排队。恢复从最近一个已持久化、幂等的执行检查点开始；没有检查点的任务从当前步骤边界重新执行。重复副作用必须由步骤幂等键去重。恢复次数、退避和最终失败状态必须可查询。

### 断链与重试

- 事件流使用稳定游标和 `Last-Event-ID` 回放，前端断链后继续订阅，不重复渲染已收到事件。
- 传输重连与执行重试分离：传输重连不重复提交业务；可重试的执行失败按持久化重试策略自动重试，不可重试或达到上限后进入人工可见的失败状态。
- 对话消息保留幂等键，模型请求发生不确定结果时不得静默重复扣费或重复写入用户消息。

## 数据与 API 设计

- 新增 Task Type、Schedule Run、Execution Checkpoint 和可恢复重试元数据的持久化字段/表。
- `GET /api/v1/scheduled-tasks/{id}/runs` 查询某个计划的运行历史。
- `GET /api/v1/scheduled-tasks/{id}/runs/{runId}` 查询单次定时运行及关联 Task。
- `POST /api/v1/scheduled-tasks/{id}/runs/{runId}/cancel` 终止当前运行。
- 任务详情返回类型、Schedule 来源、Run 列表、恢复次数和最近检查点。
- Console 提供定时计划列表、启停、当前运行终止、运行历史、结果清单和产物入口。
- Java SDK 的对应数据交互接口只在最终 Console 验证完成后实现。

当前实现落点：V84 增加 `tasks.task_type` 与 `scheduled_task_runs`，V85 增加
`task_recovery_checkpoints`；Control Plane 提供定时运行查询/终止、Task Run 和
Checkpoint 查询。运行观察契约增加内部 `checkpoint` 扩展，恢复时将最近检查点以
`recoveryCheckpoint` 安全引用注入下一次 Task 规格。Java SDK 暂不暴露这些接口。

## 失败处理

- 版本冲突返回现有 `409`，客户端刷新后重试。
- 重复幂等键且请求内容不同返回冲突，不创建第二个消息、Task 或运行实例。
- 计划停用期间已经创建的运行不被隐式删除；用户必须明确终止。
- 检查点损坏、恢复次数超过策略或执行结果不确定时，任务进入 `RECOVERY_REQUIRED`/`FAILED`，并在管理端显示处理原因。

## 验证标准

- Manager 单测覆盖追加消息排队、顺序派发、断链恢复和重复幂等键。
- Control Plane 单测/集成测试覆盖 Task Type、计划运行历史、终止幂等、检查点恢复和重试上限。
- Console 测试覆盖定时计划操作、运行历史/结果展示、消息排队状态和事件游标重连。
- 运行受影响模块测试、Console 单测、构建、Lint、格式和迁移校验。
- 涉及 Worker、Sandbox 或 RuntimeClass 的代码变更才触发 L5；本批次若只修改控制面、Manager 和 Console，不执行 L6。
