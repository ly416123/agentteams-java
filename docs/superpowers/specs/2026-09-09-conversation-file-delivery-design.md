# 会话文件交付（回复内嵌下载链接）设计规格

> 日期：2026-09-09 ｜ 状态：已获用户批准的设计 ｜ 范围：仅会话（conversation）链路

## 1. 背景与问题

用户在 L5 Console 的会话中要求 AI 生成 PDF 并提供下载。AI（QwenPaw + DeepSeek 真模型）确实在其
workspace（qwenpaw pod 内 `/app/working/workspaces/default/output/`）生成了 PDF 文件，但 Console
中只看到文字说明，无法下载文件。

根因：**会话链路只传输文本**。QwenPaw → Manager 的 SSE 流中正文走 `text delta` 帧、工具执行记录走
`data` 帧（仅调用参数摘要），没有文件/二进制帧协议；Manager → Console 也只有
`message.delta` / `message.completed` 文本事件。文件本体留在 pod 内出不来。

这与任务（task）链路的限制同源（worker 交付协议 artifact 只支持文本内联），但本规格只解决会话链路。

## 2. 目标与非目标

**目标**

- 会话中 AI 生成的文件（PDF、图片、文档等二进制）可由用户在 Console 内点击链接下载。
- 复用任务链路已有的 MinIO 对象存储与 presigned URL 设施。
- AI 行为 best-effort：未调用上传工具时，系统行为与现状一致（只输出文字），不得劣化。

**非目标（本次不做）**

- 任务链路 worker 交付协议的二进制扩展（contentBase64 / path 引用）。
- Console 附件卡片 UI（`file.completed` 事件 + 附件渲染），可作为后续增量。
- 文件删除、配额、生命周期清理任务。
- 大文件分片上传。

## 3. 数据流

```
AI 在 qwenpaw 会话中生成文件（现状不变，落 workspace）
   │
   ▼  AI 主动调用 MCP 工具 upload_file(sessionId, path, filename?)
MCP 脚本 agentteams-file-mcp.py（qwenpaw pod 内，stdio）
   │  realpath 校验路径在当前 workspace 内（防目录遍历）→ 读文件（上限 50MB）
   │  → service token（复用 task-mcp 的 token 获取模式）
   ▼  POST /api/v1/conversations/{sessionId}/files（multipart/form-data）
Manager ConversationFileController
   │  校验 token 与大小 → ObjectStorage.upload
   │  key: conversations/{sessionId}/files/{fileId}/{name}
   │  → INSERT conversation_files
   ▼  返回 {fileId, url, name, sizeBytes} → 回到 AI 手里
AI 在回复 markdown 中引用 [文件名](url)
   │
   ▼  用户点击（浏览器原生导航，无 Authorization 头）
GET /api/v1/conversations/{sessionId}/files/{fileId}（免鉴权）
   → Manager 校验记录存在 → 302 → 15 分钟 presigned GET（presignEndpoint，浏览器可达受众）
```

## 4. 组件设计

### 4.1 MCP 工具：`scripts/agentteams-file-mcp.py`（新建）

- 工具名 `upload_file`，入参：
  - `sessionId`（必填）：当前会话 ID，由 runtime 平台上下文注入块提供（与二期 taskId 注入同机制）；
  - `path`（必填）：workspace 相对路径或 workspace 内绝对路径；
  - `filename`（可选）：下载展示名，缺省取 basename。
- 安全校验：解析 realpath 后必须位于当前 workspace 目录内，否则拒绝；workspace 根目录
  由 `AGENTTEAMS_WORKSPACE_DIR` 配置（缺省 fallback 到 `/app/working/workspaces/default`，
  与 task-mcp 的配置 fallback 模式一致）。
- 大小上限 50MB，超限返回错误文本给 AI。
- 上传成功返回 JSON：`{"fileId", "url", "name", "sizeBytes"}`；`url` 为
  `{AGENTTEAMS_CONSOLE_PUBLIC_URL}/api/v1/conversations/{sessionId}/files/{fileId}`。
- 可靠性继承 task-mcp 全套容错模式：配置缺失软失败（tools/call 时再校验）、
  URLError 转 RuntimeError、stdio loop 兜底 `except Exception` 永不退出。
- 认证：password grant 获取 token（mcp-env.json 既有 `AGENTTEAMS_MCP_USERNAME/PASSWORD` 机制）。
- 新增配置项（mcp-env.json）：`AGENTTEAMS_CONSOLE_PUBLIC_URL`（L5 为
  `http://192.168.122.55:30080`；console 的 `/api/v1` 已反代到 manager）。

### 4.2 Manager：上传与下载端点

- `ConversationFileController`（`/api/v1/conversations` 域内）：
  - `POST /{sessionId}/files`：multipart 上传。需有效 token；校验会话存在；
    调用 `ObjectStorage.upload`（服务端直传 MinIO，不经 presigned PUT）；写库；返回 201。
  - `GET /{sessionId}/files/{fileId}`：免鉴权（Security 配置需对该路径 permitAll）。
    查记录 → 302 到 `presignEndpoint` 签发的 15 分钟 presigned GET；无记录 404。
- `ConversationFileService` + `JdbcConversationFileRepository`。
- Flyway 迁移新表：
  ```sql
  conversation_files (
    id uuid primary key,
    session_id uuid not null,
    name text not null,
    content_type text,
    size_bytes bigint not null,
    storage_key text not null,
    created_at timestamptz not null default now()
  )
  ```
- pom 新增依赖 `agentteams-storage`；storage 未启用（`storage.enabled=false`）时两个端点
  返回 503，仿照 `ArtifactService.getIfAvailable` 的可选装配模式。

### 4.3 Runtime：prompt 注入引导

在 runtime 既有平台上下文注入块（二期 taskId 注入同一位置）追加：
1. 当前会话 ID（`sessionId=...`，供 upload_file 工具入参使用）；
2. 指令：生成文件后必须调用 `upload_file` 工具上传并在回复中引用返回的链接。

### 4.4 部署

- kind 与 L5：qwenpaw-task-mcp ConfigMap 扩展（mcp-env.json 新键 + 新脚本文件），
  rollout restart qwenpaw 后生效（QwenPaw 需重启加载 MCP client）。
- L5 额外：manager 镜像重建分发（本地 registry mirror 链路）。
- NetworkPolicy：qwenpaw → manager 的 egress/ingress 已在一期放行，无新增。

## 5. 关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 端点归属 | Manager | 会话域内聚（归属校验、表、URL 与 ConversationController 同域）；storage 为独立库，加依赖即可 |
| 上传通道 | MCP 工具主动上传（服务端直传 MinIO） | Manager 与 qwenpaw 是不同 pod，互相读不到文件系统；MCP 工具是已验证的扩展点（二期 plan_subtasks 同路径）；不做 presigned PUT，规避内部受众 presign 错配问题 |
| 下载鉴权 | 免鉴权 + 高熵 fileId（UUID） | AI 生成的 markdown 链接无法携带 Bearer token（浏览器原生导航）；UUID 不可枚举，安全等级与任务链路匿名 presigned GET 同级 |
| 下载形态 | 回复内嵌 markdown 链接（用户选定） | 零 Console 改动；链接为永久有效短链，点击时动态签发 15 分钟 presigned URL |
| 302 受众 | presignEndpoint 签发 | 下载是浏览器受众；presignEndpoint（L5: 30090）已由任务链路验证可达；呼应 pitfall「Worker 直传 presigned PUT URL host 错配浏览器地址」 |
| AI 行为保证 | prompt 注入引导（best-effort） | 与二期自主拆解同哲学：引导而非强制，不调用时退化为现状 |

## 6. 错误处理与限制

- MCP 工具：文件不存在 / 超出上限 / 上传失败 → 返回错误文本给 AI（AI 可向用户解释），
  永不 crash stdio loop。
- Manager：文件名清洗（去路径分隔符与控制字符、截断 255 字符）；multipart 上限配置
  （`spring.servlet.multipart.max-file-size=50MB` / `max-request-size=55MB`，与工具侧
  50MB 上限对齐）；上传超限 413；storage 未启用 503；下载无记录 404；token 无效 401。
- 幂等：每次上传产生新 fileId，不做去重（MVP）。

## 7. 测试与验收

- **单测**：MCP 脚本合同测试（tools/list 含 upload_file、路径白名单拒绝、超限拒绝、
  成功返回 url——仿 `test_agentteams_task_mcp.py` 模式，含 ConfigMap 内嵌脚本一致性）；
  Manager controller/service 测试（上传写库+对象、下载 302/404/503、permitAll 生效）。
- **L5 端到端验收**：对话让 AI「生成 PDF 并给我下载」→ SSE 流确认 AI 调用 upload_file
  且回复含链接 → `curl -L` 下载 → sha256 与 pod 内原文件一致 → 浏览器点击可下载。
- **退化验证**：AI 未调用工具时，会话正常完成（仅文字），无报错。

## 8. 约束与假设

- QwenPaw 为上游镜像（agentscope/qwenpaw），不可修改其代码；扩展点仅限 MCP stdio 工具注入
  与 prompt 注入。
- 依赖既有事实：console `/api/v1` 反代到 manager；qwenpaw → manager NetworkPolicy 已放行；
  mcp-env.json 的 username/password token 机制可用。
- 单文件上限 50MB 为 MVP 常量，可在配置化增量中放开。
