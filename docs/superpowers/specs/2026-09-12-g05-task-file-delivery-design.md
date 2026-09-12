# 任务侧二进制交付与交付可靠性（G05 剩余）设计规格

- 日期：2026-09-12
- 基线：main `0f462a0`（G03 子任务委派已合并）
- 关联：`2026-09-10-aliyun-replacement-gap-revision.md`（§3.2 G05 剩余范围、立即行动 #3）；`2026-09-09-conversation-file-delivery-design.md`（会话文件交付，本设计的复用基座）；`2026-09-11-g03-multi-agent-delegation-design.md`（§5 交付物引用链契约）
- 文档性质：B 批次剩余收口设计。三个方向决策（D1 交付路径 / D2 输入附件入口 / D3 可靠性落点）已由项目负责人拍板，架构落点经方案对比选定。

## 1. 背景与决策输入

缺口基线修订 §3.2 将 G05 剩余范围定为两个子项：「任务文件」（输入附件落入执行环境；输出支持 PDF/图片/Office 真实二进制；关联 Task/Attempt/子任务）与「交付可靠性」（上传失败持久化重试队列、校验、去重、临时对象清理、数量对账）。验收重点：Worker 删除后仍可下载；重试不产生失控重复文件；「执行成功」与「交付成功」分离；失败交付可重试且可观测。

现状事实（2026-09-12 代码核验）：

- **输出链已有但仅文本内联**：`GatewayRuntimeAdapter.resultArtifacts` 解析最终 output JSON 的 `artifacts[].content`（UTF-8 文本，markdown fence 容忍），配置 `RuntimeArtifactUploadPort` 时经 gRPC `TaskArtifactService` presigned PUT 直传 MinIO（key `tasks/{taskId}/attempts/{attemptId}/artifacts/{name}`），`CompleteArtifactUpload` 由 Control Plane 重读对象校验 SHA-256 后落 `artifacts` 表与 `task_result_manifests`。qwenpaw workspace 内的二进制（PDF/图片/Office）不经过 output 文本，**不进交付物清单**。
- **输入侧无附件概念**：任务 spec `inputJson` 只有 `{"prompt": ...}` envelope；runtime `promptText()` 只取 prompt 注入平台上下文块。
- **会话文件已交付但仅会话域**：`conversation_files`（manager 迁移 V10）、MCP `upload_file`（qwenpaw pod 内读 workspace → multipart 直传 manager）、匿名 302 presigned 下载；与任务域不打通。
- **可靠性现状**：gRPC artifact 上传失败静默降级 `memory://` 引用；无重试队列、无去重语义、无数量对账；run 照常 SUCCEEDED 但交付物缺失不可见——「执行成功」与「交付成功」未分离。
- **部署形态约束**：agent-worker（Java）是独立 pod（Worker CRD），经 HTTP 调 qwenpaw 服务，**读不到 qwenpaw workspace**；MCP 脚本（`agentteams-task-mcp.py`）运行在 qwenpaw pod 内，读写 workspace 有成熟先例（`upload_file` 的 realpath 安全校验）。
- **presigned 受众约束（既有 pitfall）**：集群内服务端组件的 presigned URL 必须用服务发现地址构建；`presignEndpoint` 仅供浏览器受众。MCP 链路规避方式 = 服务端直传/代理流，不签发 presigned URL 给 pod 内组件。

## 2. 决策记录（已拍板）

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 输出二进制交付路径 | **MCP 工具主动上传**：qwenpaw pod 内读 workspace 文件 → multipart 直传 control-plane 任务域。不碰 gRPC 协议与 agent-worker 镜像 |
| D2 | 输入附件入口 | **会话文件引用为主**：MCP `create_task` 增加 attachments 参数引用既有会话文件，新增 MCP 下载工具落入 workspace；不做 REST 直传端点（corp-agent 上游调用面无任务附件方法，YAGNI） |
| D3 | 可靠性落点 | **收据落库 + 脚本侧队列**：`task_files` 记录为唯一交付事实源；上传失败进程内短重试 → 本地 JSON 队列（workspace 同生命周期）→ 结构化错误给 agent；对账/孤儿清理挂 control-plane 既有调度框架 |
| D4 | 传输方式 | **全服务端代理流，不走 presigned PUT**：上传 multipart 服务端直传；下载集群内流式代理（MCP 受众）+ 302 presigned（浏览器受众）双出口按受众区分 |
| D5 | 账本模型 | **独立 `task_files` 表**（role=INPUT/OUTPUT），不并入 manifest artifacts 清单 |
| D6 | 子任务附件继承 | **范围外（P1）**：plan_subtasks 不带 attachments，主任务附件对子任务 agent 的传递属编排语义，按需另批 |

D1 否决的替代方案：平台驱动协议扩展（gRPC artifacts 支持 workspace path 引用）——需引入跨 pod 文件通道（共享卷/sidecar/新 RPC），涉及 runtime/contracts/gateway/worker 四层，侵入大；混合双通道——两套机制同批交付体量翻倍。

D5 否决的替代方案：publish 时并入 manifest artifacts——`task_result_manifests` 由 TaskCompleted gRPC 链驱动，G02 D2 已确立「不引入新写入方」原则，MCP 上传是第二来源，并入会污染 manifest 与 run 1:1 的执行观测语义。

D4 否决的替代方案：presigned PUT 复用 gRPC artifact 协议——qwenpaw pod 需直达 MinIO（NP 新增 + presignEndpoint 双 client 改造）+ presigned 孤儿清理，三处复杂度换零收益。

## 3. 架构

### 3.1 职责划分与数据流

```
qwenpaw pod (workspace)                     control-plane（任务域账本）            manager（会话域）
┌───────────────────────┐   multipart    ┌───────────────────────────┐        ┌───────────────────┐
│ agent 生成 PDF/Office  │ ──OUTPUT 上传──▶│ POST /api/v1/tasks/{id}/  │        │ conversation_files │
│ agent 需要读取输入附件  │                │   files（直传 MinIO+落库）  │        │  （V10，对象 owner）│
│  └─ download_task_file │ ◀──流式代理──── │ GET .../files/{fid}/content│       │ +content 代理端点   │
│     （INPUT 走 manager）│ ◀──流式代理──── │ （OUTPUT）；INPUT→manager  │        │  （token 鉴权）    │
└───────────────────────┘                └───────────────────────────┘        └───────────────────┘
```

- 任务文件账本归 control-plane（任务域内聚，`artifacts` 表/`ArtifactService`/storage 依赖均在此）
- 会话文件对象与既有下载链归 manager（域边界不动，`conversation_files` 为对象 owner）
- NetworkPolicy 零新增：qwenpaw→control-plane（MCP create_task 已通）、qwenpaw→manager（upload_file 已通）

### 3.2 存储模型（V94 迁移，control-plane）

```sql
CREATE TABLE task_files (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID REFERENCES task_attempts (id),   -- 上传时服务端解析的当前活跃 attempt；INPUT 登记可为空
    role TEXT NOT NULL CHECK (role IN ('INPUT', 'OUTPUT')),
    name TEXT NOT NULL,
    content_type TEXT,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT,                                     -- OUTPUT 服务端直传时计算；INPUT 可空
    storage_key TEXT NOT NULL DEFAULT '',            -- OUTPUT: tasks/{taskId}/files/{fileId}/{name}；INPUT 空串
    source_session_id UUID,                          -- INPUT 逻辑引用 conversation_files（不建跨域 FK）
    source_file_id UUID,                             -- INPUT 逻辑引用（快照元数据落账本）
    status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MISSING')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (task_id, role, name, sha256)             -- 去重依据（sha256 为空时不参与去重，见 5.1）
);
CREATE INDEX task_files_task_idx ON task_files (task_id);
```

- 子任务即 task 行（G03 一等实体，`kind=SUBTASK`），「关联 Task/Attempt/子任务」由 `task_id`/`attempt_id` 天然覆盖
- INPUT 快照元数据（name/size_bytes）落账本，对象本体留在会话域（`storage_key` 空，账本不持有跨域对象键）

### 3.3 两次交付清单的聚合口径

| 清单 | 来源 | 内容 | 评审关联 |
|---|---|---|---|
| `artifacts`（manifest） | TaskCompleted gRPC 链（既有，不动） | 文本产物 + result.json envelope | G02 结果版本提交链 |
| `taskFiles`（task_files） | MCP 上传/登记（本批新增） | 二进制产物（OUTPUT）+ 输入附件（INPUT） | 不建评审链；结果版本的附属交付清单 |

`get_task_result`（`GET /api/v1/tasks/{taskId}/runs/{runId}/result`）聚合返回 `artifacts` + `taskFiles` 两个清单；G03 汇总轮读取子任务产物时二进制经 `taskFiles` 获取，文本照旧经 manifest。

## 4. API 设计

### 4.1 control-plane 端点（token 鉴权，requireScope 沿用 `TaskController` 模式）

| 端点 | 方法/形态 | 语义 |
|---|---|---|
| `/api/v1/tasks/{taskId}/files` | POST multipart | OUTPUT 上传：服务端直传 MinIO（key `tasks/{taskId}/files/{fileId}/{name}`）→ 服务端算 SHA-256 → 落 `task_files`；attempt 由服务端解析该任务在 `task_attempts` 中最近创建的一条（无则空，避免 RUNNING 判断的时态边界）；50MB 超限 413；storage 未启用 503、上传 IO 失败 500（全局 advice 兜底，`ConversationFileController` 同款降级） |
| `/api/v1/tasks/{taskId}/attachments` | POST JSON | INPUT 登记：body `[{sessionId, fileId, name, sizeBytes}]` 元数据快照落账本（`storage_key` 空串）；不跨域校验对象存在性（下载时 404 自然暴露，best-effort） |
| `/api/v1/tasks/{taskId}/files` | GET | 清单（`role` 过滤可选）；`get_task_result` 聚合复用同一查询 |
| `/api/v1/tasks/{taskId}/files/{fileId}/content` | GET 流式 | OUTPUT 集群内下载：`storage.download()` 流式回传；INPUT 记录返回 409（对象不在任务域） |
| `/api/v1/tasks/{taskId}/files/{fileId}/download` | GET 302 | 浏览器出口：302 presigned（presignEndpoint 浏览器受众，与既有 artifacts `downloadUrl` 同款）；仅 OUTPUT |

### 4.2 manager 端点（会话域）

| 端点 | 语义 |
|---|---|
| `GET /api/v1/conversations/{sessionId}/files/{fileId}/content` | INPUT 内容代理流：token 鉴权（`ConversationScopeAuthorizer.requireAccessible` 先例）+ `storage.download()` 流式转发；供 MCP 下载输入附件落 workspace。浏览器下载不变（仍走既有匿名 302 presigned） |

### 4.3 配套改动

1. `ObjectStorage` 接口加 `boolean exists(String objectKey)`（MinIO `statObject` 实现）——对账探测用，避免 download 流探测
2. control-plane `application.yml` 加 multipart 50MB/55MB 配置（manager 同款）
3. 幂等去重与上传顺序：`Idempotency-Key` header 沿用项目惯例（重放返回首次响应）；上传先 storage 后落库（`ConversationFileService` 同序），落库失败容忍极小概率孤儿对象（对账兜底）

## 5. 关键机制

### 5.1 幂等与去重

- 同 `(task_id, role, name, sha256)` 已存在 `AVAILABLE` 记录 → 返回既有记录（不新建对象）——对应「重试不产生失控重复文件」验收点
- INPUT 登记按 `(task_id, INPUT, name)` + `source_file_id` 查重
- 同名不同内容允许并存（fileId 区分，清单都列出）

### 5.2 失败重试队列（脚本侧，本地 JSON 文件）

- 队列文件：`$AGENTTEAMS_WORKSPACE_DIR/.task-upload-queue.json`（与 workspace 同生命周期；pod 重启即丢，此时 workspace 文件本身也不存在，语义一致）
- 触发：`upload_task_file` 失败时进程内短重试（2 次）→ 仍败 → 追加队列条目 `{taskId, filePath, name, contentType, sizeBytes, attempts, enqueuedAt}` 并返回结构化错误 `{ok:false, error, queued:true}`（agent 可感知、可再次调用重试）
- 冲刷时机：每次 `upload_task_file` 调用前机会性 flush + 队列条目重试成功即移除；重试时源文件已不存在 → 条目标记 `DROPPED` 并记 NOTE（workspace 与队列同生共死，不产生永久悬挂）

### 5.3 对账与孤儿清理

- task_files `AVAILABLE` 记录 → `storage.exists()` 探测 → 缺失标记 `MISSING`（清单可见，不删记录）
- 调度载体：仿照 `ArtifactRetentionCleanupJob`（`@Scheduled` + `SchedulerLeaseService` 多副本租约）新增 `TaskFileReconciliationJob`，fixedDelay 配置化
- 孤儿对象（`tasks/{taskId}/files/` 路径下无账本记录）→ 对账报告输出，不自动删除（保守）
- MCP 服务端直传无 presigned 中间态 → 任务文件侧无 presigned 孤儿问题；既有 gRPC artifact 协议的 presigned 生命周期归既有 retention 管辖，不动

### 5.4 「执行成功」与「交付成功」分离口径

- run 终态不因任务文件交付失败而改变（过程 best-effort 公理，与既有决策一致）
- 交付状态可观测：`get_task_result` 返回的 `taskFiles` 含 status（`AVAILABLE`/`MISSING`），上传失败时清单缺席 + 队列条目可见

### 5.5 安全与受众

- control-plane 端点 token 鉴权 + requireScope；manager content 端点 token 鉴权（不匿名）
- workspace 路径安全校验沿用 `upload_file` 的 realpath 模式（解析后必须位于 workspace 目录内）
- 文件名清洗沿用 `sanitizeName`（路径段剥离、控制字符、255 截断保扩展名）
- presigned 仅出现在浏览器受众出口（302）；集群内组件一律代理流

## 6. MCP 工具扩展（`scripts/agentteams-task-mcp.py`）

| 工具/扩展 | 入参 → 行为 |
|---|---|
| `upload_task_file`（新） | `task_id` + `file_path`（workspace 内路径，realpath 安全校验）→ 读文件 → multipart POST control-plane `/files` → 返回 `{fileId, name, sizeBytes, sha256}` |
| `download_task_file`（新） | `task_id` + `file_id` → 查清单判 role：OUTPUT → control-plane `/content` 流式落 workspace；INPUT → manager `/content`（用记录快照里的 `sessionId+fileId`）流式落 workspace → 返回落盘路径 |
| `create_task`（扩展） | 可选 `attachments` 参数（`[{sessionId, fileId, name, sizeBytes}]`，agent 从会话链平台上下文与 `upload_file` 响应取得）→ 调 `/attachments` 落账本 + 写入 `inputJson.attachments`（下发投影，见 §7） |
| `get_task_result`（扩展） | 响应追加 `taskFiles: [{fileId, role, name, contentType, sizeBytes, status}]`（task 级全量，含 MISSING）；note 指引更新 |

- 脚本容错模式全部沿用既有先例：配置缺失软失败（tools/call 时再校验）、URLError→RuntimeError、stdio loop 兜底永不退出
- unittest：沿用 `python3` 直接跑 `test_agentteams_task_mcp.py` 先例，新增队列 flush/去重/安全校验用例
- 部署链：qwenpaw ConfigMap 重生成 + rollout restart（既有 Fix 模式），NetworkPolicy 零新增

## 7. 输入附件进入执行环境的注入链路

### 7.1 单一事实源 + 下发投影（双写，各有职责）

```
MCP create_task(attachments=[...])
  ├─ POST /api/v1/tasks/{id}/attachments  → task_files 账本（role=INPUT，可查/可对账/可聚合）
  └─ spec.inputJson.attachments = [...]   → 随任务下发（worker 零查询，runtime 直接读）
```

`inputJson.attachments` 是「下发投影」，`task_files` 是「账本」——投影允许冗余（`inputJson.source` 起源标记同模式），文档明记两者同步由 MCP 工具单点完成。

### 7.2 runtime 注入（`QwenPawHttpRuntimePort.promptText()`，既有 `[平台上下文]` 块内扩展）

```
[平台上下文]
taskId=...
（可用 agentteams-task MCP 工具引用此 taskId...）
[输入附件]                              ← 仅当 inputJson.attachments 非空时追加
- name=报告.pdf file_id=<fid> session_id=<sid> size=12345
  （必须先用 download_task_file 下载到工作区再读取；除工具参数外不要复述本段）
（若你生成了 PDF/图片/Office 等二进制文件，必须调用 upload_task_file 上传；
 文本产物仍按最终 JSON artifacts 交付）   ← OUTPUT 引导，无条件追加
```

- 数据流：`inputJson.attachments` → worker 透传 → `promptText()` 读取注入，无新增控制面查询跳数
- 无附件任务：附件块不出现（零行为变化，向后兼容）
- 测试：`QwenPawHttpRuntimePortTest` 沿用「捕获请求体」mock 模式，新增「有附件注入清单 / 无附件不注入」两用例

### 7.3 验收驱动方式

- 确定性层：脚本 API 直调（建任务带 attachments → download 内容 sha256 回读 → upload PDF → 清单聚合 → 幂等重传去重断言 → 50MB 413 → INPUT content 409）——不依赖模型行为
- best-effort 层（L5 真模型）：会话上传输入文件 → 建任务引用 → agent 下载读取 → 生成 PDF → upload_task_file → get_task_result 聚合可见 → 下载回读
- kind mock 剧本：openai-mock 剧本触发 MCP 工具调用（G03 两段剧本同模式）

## 8. 批次拆分与验收

### 8.1 批次拆分（7 任务，TDD 分解）

| # | 内容 | 主要落点 |
|---|---|---|
| 1 | V94 迁移 + `TaskFileRecord`/`TaskFileRepository`（role/status/幂等查询）+ `ObjectStorage.exists()` 接口扩展 | control-plane |
| 2 | `TaskFileService` + 5 个端点（multipart 上传 / INPUT 登记 / 清单 / content 流 / 302）+ SHA-256、50MB、幂等去重、attempt 解析 + multipart 配置 | control-plane |
| 3 | 会话文件 `content` 代理流端点（token 鉴权 + 流式转发） | manager |
| 4 | MCP 脚本：`upload_task_file`/`download_task_file`/`create_task` attachments/`get_task_result` 聚合 + 失败队列 + unittest | scripts |
| 5 | `promptText()` 注入附件清单与 OUTPUT 引导 + 测试用例 | runtime |
| 6 | task_files 对账（MISSING 标记 + 孤儿报告，挂既有调度先例）+ OpenAPI 补录 + kind ConfigMap/helm 部署资产 | control-plane/scripts/deploy |
| 7 | kind mock 验收脚本（确定性层全链）+ L5 真模型验收脚本（`run-l5-task-file-delivery.py`）+ 全模块 `mvn -q test` | scripts |

### 8.2 验收总表（逐条对齐缺口分析验收重点）

| 验收重点 | 断言方式 |
|---|---|
| Worker 删除后仍可下载 | kind 验收删除 worker CR → `files/{id}/content` 仍 200 且 sha256 一致（对象在 MinIO，与 worker 生命周期解耦） |
| 重试不产生失控重复文件 | 同内容同名重传返回既有记录，清单数量不变、对象数不变 |
| 「执行成功」与「交付成功」分离 | 模拟上传失败 → run 照常 SUCCEEDED → `taskFiles` 缺席/队列条目可见 |
| 失败交付可重试且可观测 | 队列 flush 恢复链（删队列文件重调工具 → 上传成功）；对账 MISSING 标记可见 |

### 8.3 验收形态（与 G02/G03 对齐）

- kind mock 剧本全链：确定性层 API 直调全断言 + openai-mock 剧本触发 MCP 工具调用链
- L5 真模型一轮完整交付（best-effort 层）
- 全模块 `mvn -q test` 0 失败

## 9. 范围外与风险

| 项 | 处置 |
|---|---|
| 子任务自动继承主任务输入附件 | 范围外 P1（D6）：plan_subtasks 不带 attachments |
| console 任务详情附件卡片 | 范围外 P1：本轮 OpenAPI 补录 + API/MCP 可用即可 |
| REST 直传任务附件端点（POST /api/v1/tasks/{id}/attachments 之外的外部 multipart 入口） | 范围外：上游调用面无任务附件方法（YAGNI） |
| L5 真模型不主动调 `upload_task_file` | prompt 引导 + 确定性层兜底（二期/G03 已有先例） |
| 50MB 内存压力 | 与 manager 同标准（byte[] 全读，堆内可接受）；content 端点流式转发 |
| 浏览器 302 出口在 L5 可达性 | 沿用会话文件 302 同配置（该链路 L5 已 PASS） |
| MCP 新工具激活链多层坑 | 已知 Fix 模式照抄（ConfigMap 重生成 + rollout restart + 容器内冒烟） |
| 验收用户三层 membership | 验收脚本沿用 G02/G03 的 token 与 membership 前置检查 |

## 10. 对批次计划的影响

- G05 剩余在本批收口：B 批次（G02 评审闭环 + G05 会话文件 + G05 任务文件）至此全部交付
- G03 §5 的交付物引用链契约在本批补全二进制维度：汇总轮子任务产物 = manifest artifacts（文本）+ taskFiles（二进制）
- G09 SDK Java 17 spike 与 G12 前置盘点工具（缺口基线修订立即行动 #4/#5）与本批零耦合，可并行推进
