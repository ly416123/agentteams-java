# 任务业务生命周期闭环（G02）设计规格

- 日期：2026-09-10
- 基线：main `78d414f`
- 关联：`2026-09-10-aliyun-replacement-gap-revision.md` 缺口基线修订（立即行动 #2）；`2026-08-26-remaining-capabilities-roadmap-design.md`
- 语义规格来源：corp-agent `AgentTeamsTaskGateway`（对接官方 AgentTeams Java SDK 0.2.0 的接口契约）与 `TaskAppService`（2164 行）实际调用面。上游 GitHub 文档因网络受限未能抓取，以真实消费者的调用面为准。

## 1. 背景与目标

缺口分析证实自研任务域只有执行控制闭环（cancel/queue/retry/pause/approve/reject），缺少业务闭环：补充要求、结果多版本、结果评审、修改后重新交付、归档、元数据更新、受限删除。corp-agent 作为官方 AgentTeams 的真实消费者，其 Gateway 每个方法都是一条业务语义需求。

本设计目标：**把上游任务 API 的业务语义补齐到自研 control-plane，让 corp-agent 的 `AgentTeamsTaskGateway` 每个方法在本平台都有等价实现**，以 B 批次起点交付。

## 2. 现状与差距

| 上游语义（corp-agent 调用面） | 自研现状 | 差距 |
|---|---|---|
| `requestAdjustment`（content-only 非破坏性调整，body `{content:{requirement}}`） | 无 | 缺 API 与存储 |
| `listTaskResults`（结果版本列表，条目含 `resultId/version/status=submitted/subTaskId(空=顶层)`） | `task_result_manifests`（`UNIQUE(run_id)`，SUCCEEDED/FAILED/CANCELLED，执行观测性质） | 缺业务结果版本对象（版本号/评审状态/评审人） |
| `reviewTaskResult`（body `{status: accepted\|revision_required, comment}`；打回必填原因；必须以用户身份） | approve/reject 仅是 DRAFT/QUEUED/PAUSED 准入审批（写 `spec.approvalGranted`），与结果验收无关 | 缺结果评审闭环 |
| `taskAction archive/unarchive` | 无 | 缺归档属性与动作 |
| `updateTask`（PATCH 元数据，visibleMembers 整体覆盖须先读后写） | 无 PATCH | 缺元数据更新 |
| `deleteTask`（仅限未进入执行） | 无 | 缺受限删除 |
| `listTasks(statuses, teamIds, assignedTo, archiveStatus, search, cursor, limit)` | list 仅 phase/teamId/workerId/actor/from/to/q 单值过滤 | 缺 statuses 多值与 archiveStatus 过滤 |
| `getTaskStats(teamId, archiveStatus)` | 无 | 缺统计端点 |
| `cancelTask`（reason 必填写 TaskMeta.cancel_reason） | cancel 已有（body 可选） | reason 必填化对齐（兼容保留可选） |

不变量（沿用既有结论）：
- approve/reject 保持准入审批语义不变；结果验收是新增独立闭环。
- 归档是任务独立属性，**不进入 TaskPhase 状态机**（phase 不因归档改变）。
- 中间过程一律 best-effort，不威胁终态交付（任务过程可见性公理）。

## 3. 关键设计决策

| # | 决策点 | 结论 | 理由与备选 |
|---|---|---|---|
| D1 | 结果版本模型 | 新建 `task_result_versions` 业务表；版本号 task 内递增；`task_result_manifests` 保持执行观测性质不动 | 版本 = 一次「已提交待验收」的业务交付物；manifest 是 run 级技术清单（每 run 一条），两者职责分离。备选（直接在 manifest 加评审列）被否：manifest 与 run 1:1 且状态枚举受限，塞业务评审语义会污染观测投影 |
| D2 | 结果版本提交时机 | manifest publish 且 status=SUCCEEDED 时同链路生成/追加结果版本（首次 v1，retry 新 run 后 v2…）；不提供手工提交端点 | 复用现有 runtime→manifest 链路，不引入新写入方。备选（业务方显式提交）被否：多一个来源就多一种漂移 |
| D3 | 评审对象范围 | 本轮仅主任务顶层结果（`subtask_id IS NULL`）；表结构预留 `subtask_id` 列，API 不暴露子任务评审 | 落实缺口修订第 5 节交集风险一次定清：G02 评审 = 主任务最终结果；子任务产物评审待 G03 方向决策后在 C 批次启用 |
| D4 | 打回后重新交付 | 显式动作：评审打回只记录决策与意见（结果版本 status=REVISION_REQUIRED），**不自动重排队**；重新交付 = 既有 `POST /{id}/retry`（终态→QUEUED） | 重新执行是重决策（可能改 spec/补充要求），自动化反而违反「评审者控制节奏」。若现状态机不允许 SUCCEEDED→QUEUED 则扩展该转移边 |
| D5 | 补充要求生效语义 | 持久化 `task_adjustments` + 领域事件；运行中任务的实时注入为 best-effort（经既有事件通道），**不保证正在执行的 run 收到**；下次执行组装上下文时并入全部未消费调整 | 上游 requestAdjustment 为 content-only 非破坏性调整；实时注入受 G06 语义限制，遵守过程 best-effort 公理 |
| D6 | 归档准入 | 仅终态（`TaskPhase.terminal()`）任务可 archive/unarchive；归档不改 phase，只加 `archive_status` | 避免归档掩盖运行中任务的可见性风险。备选（任意 phase 可归档）被否：运行中任务从默认视图消失会引发调度误判 |
| D7 | 列表默认归档过滤 | `archiveStatus` 默认 `ACTIVE`，可选 `ARCHIVED`/`ALL`；与上游语义一致 | 上线初期无 ARCHIVED 数据，存量调用方无行为变化；console 后续加归档视图 |
| D8 | assignedTo 映射 | `assignedTo` 过滤参数映射到现有 `actor` 列（操作者视角），文档记录为近似兼容 | tasks 表无独立处理人模型；真处理人与任务归属扩展绑定 G03 方向决策，避免本轮蔓延 |
| D9 | 元数据更新白名单 | PATCH `/api/v1/tasks/{id}` 仅允许 `title`/`description`/`priority`/`spec` 顶层键合并；spec 为浅合并（顶层键覆盖），并响应回显合并后 spec | corp-agent 已知 visibleMembers 整体覆盖须先读后写——服务端提供顶层键合并可消除该类事故；深层语义合并不做 |
| D10 | 受限删除准入 | 仅 `phase=DRAFT` 且无任何 `task_runs` 记录可 DELETE；否则 409 | 对齐「仅限未进入执行」；终态任务保留审计痕迹，以归档代替删除 |

## 4. 数据模型（迁移 V67）

```sql
-- 归档属性（tasks 既有表加列）
ALTER TABLE tasks ADD COLUMN archive_status TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (archive_status IN ('ACTIVE', 'ARCHIVED'));
ALTER TABLE tasks ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN archive_actor TEXT;
CREATE INDEX tasks_archive_idx ON tasks (archive_status);

-- 补充要求
CREATE TABLE task_adjustments (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    content JSONB NOT NULL,              -- 如 {"requirement": "..."}
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    consumed_run_id UUID,                -- 已并入执行的 run（可空）
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_adjustments_content_object CHECK (jsonb_typeof(content) = 'object')
);
CREATE INDEX task_adjustments_task_idx ON task_adjustments (task_id, created_at);

-- 业务结果版本
CREATE TABLE task_result_versions (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    run_id UUID,                         -- 产出的执行 run（观测投影，可空）
    manifest_id UUID,                    -- 关联 task_result_manifests（可空）
    subtask_id UUID,                     -- 预留：D3，本轮恒空
    seq INTEGER NOT NULL,                -- task 内版本号，从 1 递增
    status TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    content JSONB,                       -- 业务结果载荷（可空，artifacts 走 manifest）
    submitted_by TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    review_actor TEXT,
    review_comment TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_result_versions_status_check CHECK (status IN ('SUBMITTED','ACCEPTED','REVISION_REQUIRED')),
    CONSTRAINT task_result_versions_task_seq_unique UNIQUE (task_id, seq)
);
CREATE INDEX task_result_versions_task_idx ON task_result_versions (task_id, seq DESC);
```

要点：
- `task_adjustments.consumed_run_id`：执行上下文组装时把未消费调整并入并将 run_id 回填，实现「幂等并入」。
- `task_result_versions.task_seq_unique`：版本号唯一，评审打回后 retry 新 run 产出 seq+1。
- 结果 artifacts 不重复建模：结果版本经 `manifest_id` 间接挂 `task_result_artifacts`（文件名/storage_ref/sha256 已有）。

## 5. API 契约（全部要求 Idempotency-Key，沿用既有模式）

| 端点 | 语义 | 请求体 | 响应/错误 |
|---|---|---|---|
| `POST /api/v1/tasks/{id}/adjustments` | 补充要求（D5） | `{content:{requirement}, actor?, source?}`；requirement 非空 | 201 AdjustmentResponse；409 任务终态后仍允许（并入下次执行） |
| `GET /api/v1/tasks/{id}/adjustments` | 调整列表（时间升序分页） | - | `AdjustmentResponse[]` |
| `GET /api/v1/tasks/{id}/results` | 结果版本列表（D3：仅顶层，seq 倒序） | `?includeAll=false`（默认仅 SUBMITTED+最新终评） | `TaskResultVersionResponse[]` |
| `GET /api/v1/tasks/{id}/results/{resultId}` | 结果版本详情（含 artifacts 引用） | - | TaskResultVersionResponse |
| `POST /api/v1/tasks/{id}/results/{resultId}/review` | 结果评审（D4） | `{decision: ACCEPTED\|REVISION_REQUIRED, comment, actor?}`；REVISION_REQUIRED 必填 comment；仅 SUBMITTED 可评审 | 200；409 非 SUBMITTED；422 打回缺 comment |
| `POST /api/v1/tasks/{id}/archive`、`/unarchive` | 归档（D6：终态限定） | `{actor?, source?}` | 200；409 非终态 |
| `PATCH /api/v1/tasks/{id}` | 元数据更新（D9） | `{title?, description?, priority?, spec?}` + `expectedVersion` | 200；浅合并 spec 回显 |
| `DELETE /api/v1/tasks/{id}` | 受限删除（D10） | - | 204；409 非法状态 |
| `GET /api/v1/tasks/stats` | 团队维度统计 | `?teamId=&archiveStatus=` | `{teamId, archiveStatus, total, byPhase:{...}}` |
| `GET /api/v1/tasks` | 过滤扩展（D7/D8） | 增 `statuses`（多值）、`archiveStatus`；`assignedTo`→actor | CursorPage 不变 |

响应扩展：`TaskResponse` 增 `archiveStatus`、`latestResultSeq`；`CancelTaskRequest.reason` 落入 spec 顶层键 `cancelReason`（兼容：仍可选）。

评审响应示例：

```json
{
  "id": "…", "taskId": "…", "seq": 2, "status": "REVISION_REQUIRED",
  "summary": "…", "runId": "…", "manifestId": "…",
  "submittedBy": "agent-worker-1", "submittedAt": "…",
  "reviewActor": "alice", "reviewComment": "缺少附录 B 的数据核对",
  "reviewedAt": "…", "version": 0
}
```

## 6. 状态机与事件

- TaskPhase 状态机唯一新增转移边：`SUCCEEDED → QUEUED`（仅为「打回后重新交付」服务；FAILED/CANCELLED→QUEUED 已有）。转移仍走 `TaskTransitionService` + 乐观锁 + 幂等。
- 结果版本状态自转移（与 phase 无关）：`SUBMITTED → ACCEPTED`（终评）/ `SUBMITTED → REVISION_REQUIRED`（可再 retry 产出新版本；REVISION_REQUIRED 本身可被覆盖评审回 ACCEPTED，仅限同一版本）。
- 新领域事件（domain_events + outbox_events，沿用 push 链路）：`TASK_ADJUSTED`、`RESULT_SUBMITTED`、`RESULT_REVIEWED`、`TASK_ARCHIVED`、`TASK_UNARCHIVED`、`TASK_METADATA_UPDATED`、`TASK_DELETED`。
- 运行中任务的调整实时推送：`TASK_ADJUSTED` 经既有 NATS 通道 best-effort 通知 worker；worker 未确认不重试、不威胁终态（公理）。

## 7. 授权与安全

- 全部端点复用 `PrincipalContext.requireScope(specJson)` + `ResourceAction`：调整/评审/归档/PATCH/DELETE 归入 `TASK_OPERATE`；评审另要求 `TASK_APPROVE`（同人准入审批口径）。corp-agent「评审必须指定用户身份」映射为 actor 取 `PrincipalContext.actorOr(body.actor)`。
- 幂等：调整/评审/归档/PATCH/DELETE 均必填 `Idempotency-Key`（≤255），沿用 `IdempotencyService.requestHash` 模式；重放返回首次结果。
- DELETE 前置校验在 Service 层（phase+run 存在性），授权链不变。

## 8. 兼容性影响

| 变化 | 影响 | 缓解 |
|---|---|---|
| list 默认 `archiveStatus=ACTIVE` | 存量调用方上线初期无感（无 ARCHIVED 数据） | 缺口修订已记录；console 后续加归档视图 |
| `SUCCEEDED→QUEUED` 新转移边 | 既有状态机测试可能有穷举断言 | 同步更新转移表与单测 |
| `TaskResponse` 增字段 | 纯增量，JSON 向后兼容 | 无破坏 |
| cancel reason 落 spec.cancelReason | 纯增量 | 无破坏 |

## 9. 验收标准

1. 单测：Service 层每个决策（D1–D10）至少一条断言（含 409/422 反例）；状态机穷举表更新。
2. 集成测试（integration-tests）：adjust→(run)→result v1→review 打回→retry→result v2→review 通过 全链路；幂等重放一致性；PATCH 浅合并；DELETE 双准入条件。
3. OpenAPI：`agentteams-public.yaml` 补录全部新端点与 schema（既往教训：契约与实现同步交付）。
4. kind 验收：`scripts/run-kind-task-review-lifecycle.py`（对齐既有 run-kind-* 模式）——MCP 驱动任务完成 → 结果版本落库 → 评审打回（带 comment）→ retry → 新版本 → 评审通过 → 归档 → 列表默认过滤生效。
5. L5：真模型链路一轮（生成 PDF 交付物 → 结果版本 → 评审 → 重新交付）。

## 10. 范围外（显式不做）

- console 结果评审 UI（API 先行，UI 随 B 批次后续任务）。
- 子任务产物评审启用（schema 预留，等 G03 方向决策）。
- 独立处理人/任务归属模型（与 G03 绑定）；`assignedTo` 仅近似映射。
- manifest/结果版本的历史回填迁移（只对新执行生效）。
- D5 消费自动接线：`consumePending` 原语与持久化就绪，但执行链路（run 创建时组装上下文）的自动调用未接入，pending 调整暂需消费方显式拉取；接线随执行链路改造独立落地。
