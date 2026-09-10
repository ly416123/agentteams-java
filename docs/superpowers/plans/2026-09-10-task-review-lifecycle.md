# 任务业务生命周期闭环（G02）实现计划

- 日期：2026-09-10
- 规格依据：`docs/superpowers/specs/2026-09-10-task-review-lifecycle-design.md`（决策 D1–D10）
- 执行方式：`using-git-worktrees` 建隔离工作树 + 独立分支 `task-review-lifecycle`；顺序执行，每任务红/绿验证；全部完成后 `finishing-a-development-branch`。
- 全局验证命令：`mvn -q -pl control-plane -am test`（或按任务模块收窄）；console 不涉及。

## Task 1：V67 迁移与持久层骨架

文件：
- `control-plane/src/main/resources/db/migration/V67__task_review_lifecycle.sql`：spec 第 4 节 SQL 原样落盘（tasks 加列 + task_adjustments + task_result_versions + 索引）。
- `control-plane/.../persistence/TaskAdjustmentRecord.java`、`TaskResultVersionRecord.java`（record，含 archived 归档字段的 TaskRecord 扩展）。
- `control-plane/.../task/JdbcTaskAdjustmentRepository.java`、`JdbcTaskResultVersionRepository.java`（接口 + JDBC 实现；版本提交需 `nextSeq(taskId)` 行内锁或 `INSERT ... SELECT coalesce(max(seq),0)+1` 原子递增）。
- `TaskRecord`/`TaskListRecord` 增 `archiveStatus`/`archivedAt`/`archiveActor` 映射；`FoundationPersistenceService` 查询列同步。

验证：`mvn -q -pl control-plane test -Dtest='*Migration*'`（如无迁移测试则启动上下文测试）；任务 1 无新行为测试，靠 Task 3+ 消费。

## Task 2：状态机新增 SUCCEEDED→QUEUED

文件：
- `domain/.../task/TaskTransitionService.java`（转移表加 `SUCCEEDED → QUEUED`，仅此一条）。
- `domain` 状态机单测穷举表更新（先红后绿：新增一条合法转移断言 + 保持非法转移清单不变）。

验证：`mvn -q -pl domain test`。

## Task 3：补充要求（D5）

文件：
- `control-plane/.../task/TaskAdjustmentService.java`：create（requirement 非空、content 为 JSONB、幂等键、事件 `TASK_ADJUSTED`）、listByTask、markConsumed(runId, adjustmentIds)。
- `control-plane/.../api/TaskAdjustmentController.java`：`POST /api/v1/tasks/{id}/adjustments`、`GET /api/v1/tasks/{id}/adjustments`；`PrincipalContext.requireScope` + `TASK_OPERATE`。
- 单测：非空校验 422、幂等重放同响应、事件断言。

验证：`mvn -q -pl control-plane test -Dtest='TaskAdjustment*'`。

## Task 4：结果版本与评审（D1–D4）

文件：
- `control-plane/.../task/TaskResultVersionService.java`：
  - `onManifestPublished(ExecutionContext, manifest)`：manifest=SUCCEEDED 且顶层（无 subtask 映射）时 `submit(taskId, runId, manifestId, summary)`（seq 原子递增、事件 `RESULT_SUBMITTED`；同 manifest 幂等：先查 manifest_id 已存在则跳过）。
  - `review(taskId, resultId, decision, comment, actor, idempotencyKey)`：仅 SUBMITTED 可评审；REVISION_REQUIRED 必填 comment（422）；事件 `RESULT_REVIEWED`；幂等重放。
- 挂接点：`TaskResultManifestService.publish` 成功路径调用 `onManifestPublished`（装配在 control-plane 现有 manifest 链路；注意 `TaskResultManifestService` 仅 43 行，改动小）。
- `control-plane/.../api/TaskResultController.java`：`GET /api/v1/tasks/{id}/results`、`GET .../{resultId}`、`POST .../{resultId}/review`（review 走 `TASK_APPROVE`）。
- 单测：seq 递增并发安全（两线程同 task）、非 SUBMITTED 评审 409、打回缺 comment 422、manifest 重复 publish 幂等。

验证：`mvn -q -pl control-plane test -Dtest='TaskResultVersion*'`。

## Task 5：归档 / PATCH / DELETE / stats / list 扩展（D6–D10）

文件：
- `TaskService` 增：`archive/unarchive`（terminal() 校验→409；不改 phase；事件）、`patch`（白名单 title/description/priority/spec 顶层键浅合并 + expectedVersion 乐观锁 + 事件）、`delete`（DRAFT 且无 run→204，否则 409；事件）、`stats(teamId, archiveStatus)`。
- `TaskController` 增对应端点：`POST /{id}/archive`、`/{id}/unarchive`、`PATCH /{id}`、`DELETE /{id}`、`GET /stats`（注意路由顺序：`/stats` 先于 `/{id}` 注册）。
- list 扩展：`TaskListFilter` 增 `statuses`(Collection<TaskPhase>)、`archiveStatus`（默认 ACTIVE）、`assignedTo`（映射 actor 列）；`TaskController.list` 增参数；`JdbcTaskTreeRepository`/列表查询 SQL 同步。
- 单测：archive 非终态 409、PATCH 白名单外字段拒绝、spec 浅合并回显、DELETE 双准入、statuses 多值与默认 ACTIVE 过滤、stats 计数。

验证：`mvn -q -pl control-plane test -Dtest='TaskService*,TaskController*'`。

## Task 6：响应扩展与契约同步

文件：
- `TaskResponse` 增 `archiveStatus`、`latestResultSeq`；`CancelTaskRequest` 增 `reason` → spec 顶层 `cancelReason`（可选）。
- `openapi/agentteams-public.yaml`：全部新端点 + AdjustmentResponse/TaskResultVersionResponse/TaskStatsResponse schema + TaskResponse 增量字段（对照 controller 逐个核对，防契约漂移）。
- `ManagerAuthenticationFilter` 无需放行调整（全部在既有 /api/v1/tasks 鉴权域）。

验证：`mvn -q -pl control-plane test`；openapi lint（如有）或人工 diff 核对。

## Task 7：集成测试全链路

文件：`integration-tests/src/test/java/io/agentteams/it/TaskReviewLifecycleIT.java`。
场景（spec 第 9 节第 2 条）：
1. create→queue→(mock 完成)→manifest SUCCEEDED→结果 v1 SUBMITTED；
2. review 打回（缺 comment→422；带 comment→REVISION_REQUIRED）；
3. retry（SUCCEEDED→QUEUED 新转移边）→新 run→结果 v2 SUBMITTED；
4. review 通过→ACCEPTED；重复评审同版本→幂等重放；
5. adjust 全链路：创建、列表、重试后 markConsumed 断言；
6. archive→默认 list 不含、ARCHIVED 过滤可见、unarchive；
7. PATCH 浅合并 + DELETE（DRAFT 无 run 204 / 有 run 409）。

验证：`mvn -q -pl integration-tests -am test -Dtest=TaskReviewLifecycleIT`。

## Task 8：kind 验收脚本

文件：`scripts/run-kind-task-review-lifecycle.py`（对齐 `run-kind-lease-recovery.py` 等既有模式：环境探测→API 驱动→断言→退出码）。
流程：创建任务（MCP/直连 mock）→ 完成 → 结果 v1 → 打回（带 comment）→ retry → v2 → 通过 → 归档 → list 默认过滤断言 → stats 断言。

验证：kind 集群内脚本退出码 0；随后 L5 真模型一轮（PDF 交付物→评审→重交付）。

## 完成定义

- [ ] Task 1–8 全绿；`mvn -q test`（全模块）通过
- [ ] 每任务独立 commit（中文规范），CodeReview 子代理审查后修复
- [ ] kind 验收 PASS + L5 验收 PASS
- [ ] finishing-a-development-branch：合并回 main 前验证测试
