# 多 Agent 协作真调度（G03）设计规格与方向决策

- 日期：2026-09-11
- 基线：main `9860e5f`（G02 任务评审闭环已合并）
- 关联：`2026-09-10-aliyun-replacement-gap-revision.md` 缺口基线修订（§4 方向决策前移、§5 交集风险、§6 立即行动 #1）；`2026-09-08-task-process-visibility-phase2-design.md`（子任务观测投影二期）；`2026-09-10-task-review-lifecycle-design.md`（G02 评审闭环）
- 文档性质：C 批次起点决策材料。六个方向决策（D1-D6）已由项目负责人拍板，架构落点经方案对比选定。

## 1. 背景与决策输入

缺口基线修订将「子任务是展示还是真调度」定为全部 P0 中唯一的方向性决策：它决定 C 批次体量与最终验收形态，且 G03「子任务产物汇总」与 G02「结果评审」的对象存在交集，必须在契约设计时一次定清。

现状事实（2026-09-11 代码核验）：

- 子任务现状是**观测投影**：`SubtaskService` 公理一「best-effort 观测投影，绝不改变主任务终态」；agent 在单会话内经 MCP `plan_subtasks`/`update_subtask_status` 自报，子任务 FAILED 与主任务 SUCCEEDED 隔离。
- 调度底座已有**任务级**能力：Team CRD（leader/worker membership、policy、maxConcurrentTasks）、能力匹配调度、attempt/lease/assignment、幂等键。
- G02 已交付主任务结果版本评审（SUBMITTED/REVISION_REQUIRED/ACCEPTED、run manifest publish 联动、retry、归档）。
- 已知缺陷：SubtaskService re-plan 语义未定义（G02 retry 开启同任务二次执行场景，验收脚本 fixture 绕行中）。
- L5 真模型在二期验收中未主动触发拆解（best-effort NOTE）。

## 2. 决策记录（已拍板）

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 业务场景 | **并行真分工**：复杂任务拆给多个不同能力/模型的 worker 并行或串行执行，产物汇总成主任务结果 |
| D2 | 评审对象 | **汇总后评审**：子任务只提交产物（衔接 G05 交付物模型），评审仅在主任务最终汇总结果上做一轮，复用 G02 闭环，不建第二套评审状态机 |
| D3 | 终态语义 | **硬约束**：全部子任务 SUCCEEDED 才允许汇总提交评审；子任务 FAILED → 可单独 retry，主任务保持等待或被显式 cancel |
| D4 | 编排者 | **主 agent 拆解 + 平台调度执行**：主 agent 用 `plan_subtasks` 声明拆解（复用二期工具语义），每个子任务作为独立 attempt 按能力匹配分派；汇总由主 agent 完成 |
| D5 | 调度粒度 | **依赖拓扑调度**：无依赖子任务立即并行分派，有依赖的等前置 SUCCEEDED 才分派，完整 DAG 语义一次到位 |
| D6 | 架构落点 | **方案 A：子任务升格为一等任务实体**——复用 `tasks` 表与全套底座（状态机/attempt/lease/Team 调度/幂等），不新增第二套任务状态机 |

D6 否决的替代方案：方案 B（独立 `task_subtask_executions` 表——两套状态机/调度/监控，违反二期「不新增第二套任务状态机」约束）；方案 C（Matrix 房间消息委派——平台无法实施 D3 硬约束，可靠性依赖 agent 自觉）。

## 3. 架构：子任务一等实体与两次 run 模型

### 3.1 存储（V93 迁移）

`tasks` 表新增：

- `parent_task_id UUID NULL`（自引用，NULL 即 MAIN）
- `kind TEXT NOT NULL DEFAULT 'MAIN'`（MAIN/SUBTASK）

子任务行是完整任务：自己的 `phase`（复用 `TaskPhase`）、`spec`（scope 继承主任务 + 自身 `requiredCapabilities`）、attempt/run/租约。现有调度器出队逻辑对 kind 不敏感——子任务进 QUEUED 即被自然拾取，调度器零改动。

### 3.2 两次 run 模型

主任务生命周期分两轮 run：

1. **拆解轮**：主任务正常调度执行；主 agent 在会话内调用 `plan_subtasks` 声明拆解 → 平台为每个子任务创建一等任务行（DRAFT）；拆解轮 run 正常结束。
2. **平台接管**：依赖 gate 按拓扑将子任务转 QUEUED 分派（无依赖立即并行）；子任务 run manifest publish 产生子任务交付物（G05 模型，无评审）。
3. **汇总轮**：全部子任务 SUCCEEDED → 平台自动将主任务转 QUEUED（系统触发转移，幂等键 `SYSTEM_CHILDREN_COMPLETED` 防并发重放）→ 主 agent 读取子任务产物汇总 → run manifest publish → G02 结果版本提交 → 评审 → 归档，全部原样生效。

### 3.3 数据流

```
主任务 QUEUED → 调度 → 主 agent run#1
  └─ plan_subtasks（声明式，MCP）→ 子任务行(kind=SUBTASK, DRAFT)
       ├─ 无依赖 → gate 立即 QUEUED → 能力匹配 → worker 并行执行
       └─ 有依赖 → 前置 SUCCEEDED 后 gate 转 QUEUED
  ← run#1 结束（存在非 SUCCEEDED 子任务 → publish 跳过，不产生结果版本）
子任务 run manifest → 子任务交付物（G05 模型，无评审）
全部 SUCCEEDED → 平台自动 QUEUED 主任务（幂等防重）
  → 主 agent run#2（汇总）→ publish → G02 结果版本 → 评审 → 归档
子任务 FAILED → 可单独 retry；主任务保持等待或被显式 cancel
```

## 4. 关键机制

### 4.1 re-plan 语义（清偿 retry 缺陷）

一等实体下的声明式同步规则：

| 子任务现状 | 新清单内 | 新清单外 |
|---|---|---|
| DRAFT（未出队） | 就地更新 title/sequence/dependencies | CANCELLED |
| QUEUED / RUNNING | 保留不重置 | CANCELLED（级联取消其活跃 attempt/run，同主任务 cancel 语义） |
| 终态（SUCCEEDED/FAILED/CANCELLED） | 保留，不重跑 | 保留（历史留存） |

- 不再物理删行（现二期 `deleteOthers` 替换语义废弃），执行与投影历史靠事件流留存。
- 依赖环在创建时拓扑检测，成环拒绝（400）。
- 该语义同时闭合 G02 retry 开启的「同任务二次执行 plan+update 400」缺陷——终态子任务保留、未终态按表处理，第二跑 plan+update 不再冲突。

### 4.2 终态硬约束落点

- `onManifestPublished` 对 kind=MAIN 且存在非 SUCCEEDED 子任务的任务**跳过结果版本提交**（返回 empty，WARN 日志）。D3 硬约束在平台内部实施：结果提交无手动 API，唯一入口就是 manifest publish 联动，无需对外 409。拆解轮 run 本身仍 SUCCEEDED（会话正常结束），只是不产生结果版本。
- 子任务（kind=SUBTASK）publish 不适用主任务约束（子任务无下级）、不建结果版本评审链，交付物直接挂 run/result。
- 主任务自动 QUEUED 的系统转移以幂等键防护：同轮子任务完成事件并发到达时只转移一次。

### 4.3 retry / cancel 贯通

- 失败子任务单独 retry：复用 G02 SUCCEEDED→QUEUED 边（打回必填 comment 语义同样适用）。
- 主任务 retry：不重置终态子任务（按 §4.1 规则保留），主 agent 拆解轮 re-plan 时按声明式同步处理。
- 主任务 cancel：级联 cancel 全部未终态子任务（终态子任务不动）。
- Team `maxConcurrentTasks`：子任务计入并发上限（复用现有行锁计数）；极端情况下主任务汇总轮排队可接受，优先级调度留后续。

## 5. G02 / G05 对接

- **G02 零改动**：评审对象始终是主任务结果版本；`task_result_versions`/`task_adjustments`/归档/PATCH 全部按既有语义工作于 MAIN 任务。
- **G05 衔接**：子任务产物走 run manifest → 交付物存储（G05 任务文件模型）；本批不实现二进制交付的剩余缺口，仅保证引用链（run → 交付物引用）可被汇总轮读取。
- **MCP 扩展**：`get_task_result` 增加 `subtask_id` 参数（或对等聚合端点），供汇总轮读取子任务产物清单与引用。

## 6. 批次拆分与验收

C 批次 5 个任务：

| 任务 | 内容 |
|---|---|
| C1 | V93 迁移 + 子任务一等实体创建（plan 升格）+ re-plan 语义 + 依赖环检测 |
| C2 | 依赖 gate（拓扑 QUEUED）+ 主任务自动 QUEUED 系统转移 + 硬约束 publish 校验 |
| C3 | 子任务产物回收 + MCP `get_task_result` 扩展 + retry/cancel 级联贯通 |
| C4 | console DAG 真实任务链接 + OpenAPI 补录 + kind 验收脚本（`run-kind-subtask-delegation.py`） |
| C5 | L5 真模型验收（`run-l5-subtask-delegation.py`）+ 全模块 mvn test |

验收形态（与 G02 对齐）：

- kind mock 剧本全链：拆解 → 并行分派 → 拓扑顺序 → 硬约束 409 → 汇总轮 → G02 评审 ACCEPTED → 归档 → 失败子任务 retry 恢复链。
- L5 真模型一轮完整交付；真模型自主拆解不确定性以 prompt 引导缓解、确定性层剧本兜底。
- 全模块 `mvn -q test` 0 失败。

## 7. 范围外与风险

| 项 | 处置 |
|---|---|
| G04 动态路由（多 endpoint/多模型 worker） | 不在本批；P0 用同 Team 内同 runtime 多 worker |
| Matrix 房间消息流协作 | 范围外（上游范式，P1+ 评估） |
| 子任务级评审/BLOCKED 人工决策流 | 范围外（D2 已定汇总后评审；BLOCKED 维持二期口径） |
| 子任务饿死主任务汇总轮 | P0 接受；优先级调度 P1 |
| L5 真模型不主动拆解 | prompt 引导 + 确定性层兜底（二期已有先例） |
| 二期投影 API（`/tree`、过程事件）消费方 | 语义不变，DAG 数据源可平滑切换到 tasks 表 |

## 8. 对批次计划的影响

- C 批次体量以此为准（5 任务），B 批次剩余（G05 任务文件二进制交付）可与 C1/C2 并行推进，交付物引用链（§5）为两者共享契约。
- G03 方向已定，A 批次替换契约设计中「评审对象」条目随之冻结：评审 = 主任务最终结果版本。
