# 任务过程可见性二期设计：子任务拆解、协作语义与阻塞恢复

前置文档：一期设计 [2026-09-08-task-process-visibility-design.md](./2026-09-08-task-process-visibility-design.md)（已交付：TASK_EVENT 事件通道、过程事件持久化、console 右栏面板/DAG/双流时间线）。二期遵循其两条公理：中间事件 best-effort 永不威胁终态；双层校验不信任上游。开发阶段不跨版本兼容。

## 目标

agent 执行任务时能把大任务**主动拆解**为子任务并**逐个推进**，平台结构化记录拆解与进度，console DAG 实时反映，任务终态前汇总。

**非目标**：子任务并行执行（独立会话/多 agent 并行）、子任务独立调度（真 task 实体）、BLOCKED 人工决策流——均为三期范围（见「范围外」）。

## 决策记录

| 决策点 | 结论 | 理由 |
|---|---|---|
| 核心目的 | 任务拆解能力（拆得动、跟得住） | 多 agent 并行协作不在本期价值主张内 |
| 执行模型 | 同会话 + 串行 | 平台级并行已存在（多任务多 worker）；同会话真并行依赖 QwenPaw 子代理能力（外部不可控）；独立会话并行 = 在 worker 内重造调度器。数据模型（dependency_ids + 事件 subtaskId 关联）预留并行演进 |
| 子任务形态 | run 内逻辑分解节点（复用 `task_subtasks`，零迁移） | `task_id` 无 FK 到 tasks 表的现状即此语义；独立实体属三期 |
| 子任务粒度上限 | 单任务最多 20 个子任务 | 与 MCP 工具现有 MAX_* 常量风格一致 |
| 失败传播 | agent 自治 | 子任务失败由 agent 重试/换路/降级后自行定论；平台不自动阻塞父任务（公理一：终态保障不受子任务状态干扰） |
| 终态收尾约束 | best-effort：`TERMINAL_SUBTASK_INCOMPLETE` 一致性检查告警，不阻断终态 | 复用已有检查器；阻断终态违反公理一 |
| 工具任务定位 | `task_id` 由 agent 显式传入；`runId` 由 control-plane 反查 | worker pod 并发处理多任务，MCP server 绑定「当前任务」会串会话 |
| agent 获知 taskId 的途径 | runtime `promptText` 注入平台上下文前缀 | 现状 agent 完全不可见 taskId（prompt 只透传 inputJson.prompt） |
| tool.* 事件归属 | console 按时间窗口推断（best-effort），不改 runtime | `subtask.started` 与下一状态事件之间的 `tool.*` 归属该子任务 |
| DAG 交互 | 下钻主导（原型方案 B） | 点击右栏 DAG 节点 → 主列时间线过滤该子任务；时间线保持全局流 + 子任务徽章 |
| 上游对标（agentscope-ai/AgentTeams） | 借鉴「拆解必须是结构化事件」，不做 Matrix 式纯消息协作、不照抄 Worker 容器指派 | 上游 v1.2.3 补 Project workflow API 恰证明消息流协作对长任务跟不住；本项目已有结构化底座 |

## 现状与约束

- 已有底座：`task_subtasks` 表（V64：parent_task_id/sequence/status/dependency_ids，status 枚举含 BLOCKED；`ON CONFLICT (run_id, task_id)` 支持声明式重放）；`TaskTreeService.upsert`（自引用拦截）；`TaskProgressService` waitingReason（blocked 计数）；`TERMINAL_SUBTASK_INCOMPLETE` 一致性检查；console TaskDag 组件与右栏标签页容器。
- 缺口一：树投影唯一数据源是 `projectManagerPlan`（manager 任务启动时投影单根节点），agent 执行中无拆解入口。
- 缺口二：MCP `create_task` 只能创建无关独立任务，无父子语义。
- 缺口三：gateway / control-plane 事件白名单不含 `subtask.*` 类型。
- 缺口四：agent 上下文不可见 taskId（`promptText` 只透传 prompt），MCP 工具无法定位当前任务。
- 约束：不新增第二套任务状态机；任务终态可靠性语义不变；worker 只连 gateway；`runId` 对 agent 不感知（control-plane 从 taskId 反查当前 run）。

## 方案

### 数据流

```
QwenPaw 会话 (agent 决策拆解)
  → MCP agentteams-tasks (plan_subtasks / update_subtask_status, REST)
  → control-plane (校验 → task_subtasks 投影 + subtask.* 过程事件落库, 同事务)
  → console (process-events 轮询/SSE → DAG 多节点 + 时间线徽章/过滤)
```

传输不经一期 TASK_EVENT gRPC 链路：subtask 工具走 MCP→REST 直调 control-plane（与 create_task 同路），因为拆解是 agent 主动动作而非 runtime 上报；落库后与 runtime 事件合流（同表同序）。

### MCP 工具设计

扩展 `scripts/agentteams-task-mcp.py`（ConfigMap 同步）：

- **`plan_subtasks(task_id, subtasks)`**：全量声明式设定清单。`subtasks` 为 `[{id: uuid, title(≤200), description(≤2000), sequence(≥0), dependency_ids: [uuid]}]`，上限 20；`dependency_ids` 必须引用清单内存在的 id；重复调用以最新清单整体替换：handler 层先删除该 run 下不在新清单中的子任务投影行，再逐条 upsert（冲突键 run_id+task_id 幂等）；被替换的历史靠事件流留存，投影只记最新。每次调用触发每子任务一条 `subtask.planned` 事件（payload ≤4KB 不允许单条携带全量清单）。
- **`update_subtask_status(task_id, subtask_id, status, note?)`**：status ∈ {RUNNING, SUCCEEDED, FAILED, CANCELLED}，**不开放 BLOCKED**（agent 自治：失败由 agent 重试/换路后自行定论；BLOCKED 留给三期人工决策流）。不设状态迁移单向约束（允许重做回退），投影记录最新状态，历史靠事件流。
- 两工具均要求显式 `task_id`；校验沿用现有模式（UUID/长度/枚举 + Idempotency-Key + Bearer）；工具描述引导「任务完成前应将全部子任务收尾」。
- REST 形态：control-plane 新增 `PUT /api/v1/tasks/{taskId}/subtasks`（声明式设定）与 `PUT /api/v1/tasks/{taskId}/subtasks/{subtaskId}/status`（状态推进），鉴权沿 `PrincipalContext.requireScope`（与 Task 写路径一致），写 API 带幂等键。

### runtime：prompt 平台上下文注入

`QwenPawHttpRuntimePort.promptText` 在用户 prompt 前注入固定格式上下文块（含 taskId；实现计划确定具体格式与是否含更多字段），使 agent 可知当前任务。注入文本不得破坏既有冒烟 marker 匹配（QWENPAW_DEEPSEEK_SMOKE_OK 等按 contains 断言，前缀注入安全）。`session_id`（attemptId）逻辑不变。

### control-plane：投影与事件白名单

- `subtask.*` 事件类型加入 `ALLOWED_RUNTIME_EVENT_TYPES` 同层的白名单体系；本路径事件由 REST handler 直接写 `task_process_events`（sequence 复用 `nextSequence`），与 runtime 事件同流合序。
- 收到 `subtask.planned` / 状态事件 → `TaskTreeService.upsert` 投影 `task_subtasks`（`subtask.id` 作 task_id 列，`parent_task_id` = 主任务 taskId）；自引用与依赖引用校验在 handler 层先行（双层校验公理）。
- 依赖环检测后置三期（串行下依赖仅展示语义）。
- `waitingReason` 逻辑不变（BLOCKED 二期不出现，分支自然不触发）。

### console：DAG 下钻（方案 B）

- 右栏 DAG 标签页多节点渲染：节点=子任务（状态色：PENDING 灰 / RUNNING 蓝 / SUCCEEDED 绿 / FAILED 红 / CANCELLED 灰删线），sequence + dependency_ids 布局成图。
- 点击节点 → 主列时间线切换为该 subtaskId 过滤视图（事件徽章匹配），再次点击节点或「全部」复位；时间线全局流不分组，`tool.*` 事件按窗口推断带子任务徽章。
- 空态兼容：run 无子任务时维持一期形态（单节点/隐藏）。

## 验收

- 单测/集成：scripts MCP/REST 契约测试、agent-gateway（白名单不受影响）、control-plane（投影/校验/一致性）、runtime（prompt 注入）、console vitest；全量回归保持全绿。
- kind 端到端：`qwenpaw-conversation-mock` 扩展拆解剧本（plan → 逐个 update → 收尾），断言 task_subtasks 投影与 process-events 事件链。
- L5 真模型：对话建任务 → agent 自主拆解 → 详情页 DAG 实时点亮 → 子任务逐个完成 → 任务终态汇总。与一期 L5 验收同规格。

## 范围外（三期预告）

子任务独立会话执行与独立调度（真 task 实体、平台真调度）；并行执行（dependency_ids 升级为调度语义、失败传播组合策略）；BLOCKED 状态与人工决策流；runtime 子代理并发（依赖 AgentScope 能力评估）。
