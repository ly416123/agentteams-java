# L5 Team 子任务聚合 PDF 成果物实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让 L5 Team 父任务在全部子任务完成后的聚合轮通过 MCP 生成并交付可下载 PDF 成果物。

**架构：** 在 `agentteams-mcp-tools` 增加专用 `create_task_pdf_artifact` 工具，复用现有 Markdown PDF renderer 和任务 Control Plane HTTP client，在 MCP 服务内生成 PDF 并上传到父任务文件账本，避免模型搬运内嵌二进制资源。扩展 L5 子任务验收脚本，核验终态、PDF OUTPUT 元数据和下载文件头；沿用现有子任务生命周期与部署流程。

**技术栈：** Java 21、Spring Boot、MCP Java SDK、PDFBox/OpenHTMLtoPDF、Java HttpClient、Control Plane multipart Task Files API、Python unittest、Helm/K3s。

---

## 文件清单

### MCP Tools 仓库 `/Users/gecko/code/agentteams-mcp-tools`

- 修改 `src/main/java/io/agentteams/mcptools/task/TaskControlPlaneClient.java`：增加 multipart 上传父任务 OUTPUT 的方法。
- 创建 `src/main/java/io/agentteams/mcptools/task/TaskPdfArtifactTool.java`：校验参数、调用 renderer、上传 PDF 并返回文件元数据。
- 修改 `src/main/java/io/agentteams/mcptools/config/McpServerConfig.java`：注册 PDF 成果物工具。
- 修改 `src/test/java/io/agentteams/mcptools/task/TaskControlPlaneClientTest.java`：验证 multipart 路径、认证、文件名、MIME、字节和非 2xx 脱敏错误。
- 创建 `src/test/java/io/agentteams/mcptools/task/TaskPdfArtifactToolTest.java`：验证 PDF 生成/上传成功、无效参数和上传失败。
- 修改 `src/test/java/io/agentteams/mcptools/McpServerIntegrationTest.java`、`src/test/java/io/agentteams/mcptools/L5McpSmokeTest.java` 和 `README.md`：验证/记录服务暴露新工具。

### AgentTeams Java 仓库 `/Users/gecko/code/agentteams-java`

- 修改 `scripts/run-l5-subtask-delegation.py`：聚合提示要求调用 `create_task_pdf_artifact`；成功后检查 OUTPUT 文件唯一性、`application/pdf`、`.pdf` 文件名和下载 `%PDF-` 文件头。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskGateService.java`：聚合时把独立 `aggregatePrompt`/`aggregateMcpTools` 转换成聚合轮 prompt/tool requirements。
- 修改 `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePort.java`：将 PDF 工具列入可显式开放的任务 MCP 工具。
- 修改 `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeRuntime.java`：聚合轮启用 PDF 工具时，将当前父任务 ID 和 PDF 文件名注入工具调用指令。
- 修改 `control-plane/src/test/java/io/agentteams/controlplane/task/SubtaskGateServiceTest.java`、`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePortTest.java`、`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeRuntimeTest.java`：锁定工具要求转换、工具白名单和调用上下文。
- 创建 `scripts/test_l5_subtask_pdf_artifact.py`：以 stub 响应覆盖 PDF manifest、类型/扩展名、缺失/重复文件和无效下载内容。
- 修改 `docs/superpowers/specs/2026-10-01-l5-team-subtask-pdf-artifact-design.md`：记录确认后的 MCP 侧生成并上传边界。

## 任务 1：MCP Control Plane 文件上传

**红灯：** 在 `TaskControlPlaneClientTest` 增加 multipart 上传测试，用 MockWebServer 捕获 `POST /api/v1/tasks/{taskId}/files`，断言 Bearer、multipart `file` 字段、文件名、`application/pdf` 和原始 PDF bytes；再增加 HTTP 503 被转换为不带响应正文的 `CONTROL_PLANE_REQUEST_FAILED: HTTP 503` 测试。运行：

```bash
cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest=TaskControlPlaneClientTest test
```

预期新增测试因 `uploadTaskFile` 未定义而编译失败。

**绿灯：** 在 `TaskControlPlaneClient` 实现 `uploadTaskFile(UUID taskId, String fileName, String contentType, byte[] content)`；构造 multipart 请求，发送至 `/api/v1/tasks/{taskId}/files`，仅接受 2xx JSON 响应，复用已有超时、Bearer 和异常脱敏。重跑上述命令，预期通过。

## 任务 2：注册生成并上传 PDF 的 MCP 工具

**红灯：** 新建 `TaskPdfArtifactToolTest`：注入真实 `MarkdownPdfRenderer` 和 MockWebServer Control Plane stub，调用 `create_task_pdf_artifact`，断言返回 `fileId`/文件名/MIME/字节数且 stub 收到 `%PDF-` bytes；分别断言无效 task UUID、空 Markdown、非 `.pdf` 文件名和上传非 2xx 返回稳定 `isError=true`。扩展 `McpServerIntegrationTest` 断言 `tools/list` 包含新工具。运行：

```bash
cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest=TaskPdfArtifactToolTest,TaskControlPlaneClientTest,McpServerIntegrationTest test
```

预期新工具测试因类/注册不存在而失败。

**绿灯：** 新增 `TaskPdfArtifactTool`，复用 `MarkdownPdfRenderer.render(markdown)`，将返回字节交给 `TaskControlPlaneClient.uploadTaskFile`，默认名 `report.pdf`、MIME `application/pdf`，响应只返回 fileId、name、contentType、sizeBytes、sha256。将工具 schema 注册到 `McpServerConfig`；对输入做 task UUID、非空 Markdown、PDF 文件名与最大长度校验，不将 PDF base64 回传给模型。重跑上述命令，预期通过。

随后运行 MCP Tools 完整验证：

```bash
cd /Users/gecko/code/agentteams-mcp-tools && mvn -q verify
```

预期退出码为 0。

## 任务 3：扩展 L5 子任务 PDF 交付断言

**红灯：** 创建 `scripts/test_l5_subtask_pdf_artifact.py`，导入 `run-l5-subtask-delegation.py` 并 mock `request_json`/`request_bytes`。覆盖唯一可用 PDF OUTPUT 成功、无 PDF、重复 PDF、非 PDF MIME/扩展名、缺少成功 MCP 事件以及下载内容缺少 `%PDF-` 时失败。运行：

```bash
cd /Users/gecko/code/agentteams-java && python3 -m unittest scripts.test_l5_subtask_pdf_artifact -v
```

预期因 PDF 断言 helper 不存在而失败。

**绿灯：** 在 L5 验收脚本增加 `require_pdf_output(base_url, tokens, task_id, run_id)`：读取聚合 run 的 process-events 并要求出现成功的 `create_task_pdf_artifact` `tool.finished` 事件；查询 `GET /api/v1/tasks/{taskId}/files?role=OUTPUT`，要求恰有一个 PDF、`contentType=application/pdf` 且名称以 `.pdf` 结尾；回读 `GET /api/v1/tasks/{taskId}/files/{fileId}/content`，要求非空且以 `%PDF-` 开头。创建任务时提供独立聚合提示和 MCP 工具要求；父任务 SUCCEEDED、结果版本提交后执行断言，打印 PDF 文件 ID 与字节数。重跑 unittest，预期通过。

再运行相关 Python 回归：

```bash
cd /Users/gecko/code/agentteams-java && python3 -m unittest scripts.test_l5_subtask_pdf_artifact scripts.test_agentteams_task_mcp scripts.test_l5_identity_membership_contract -v
```

## 任务 4：验证聚合 MCP 工具按轮启用

**红灯：** `SubtaskGateServiceTest` 输入 `requiredMcpTools=[plan_subtasks]`、`aggregateMcpTools=[create_task_pdf_artifact]` 和 `aggregatePrompt`，断言聚合结果保留 PDF 工具与聚合提示，并移除原计划工具字段；`AgentScopeMcpRuntimePortTest` 断言 PDF 工具仅在 required tool set 显式包含时开放；`AgentScopeRuntimeTest` 断言工具调用指令含父任务 UUID 和 `report.pdf`。运行：

```bash
cd /Users/gecko/code/agentteams-java && mvn -q -pl control-plane -am -Dtest=SubtaskGateServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
cd /Users/gecko/code/agentteams-java && mvn -q -pl agent-worker -am -Dtest=AgentScopeMcpRuntimePortTest,AgentScopeRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期 gate 测试缺少工具转换断言失败，worker 测试显示 PDF 工具未在 allowlist 开放或聚合调用上下文不含 task UUID。

**绿灯：** gate 在聚合时将 `aggregatePrompt` 转成 `inputJson.prompt`、将 `aggregateMcpTools` 转成 `inputJson.requiredMcpTools` 并移除两个临时字段；Worker 工具 allowlist 增加 `create_task_pdf_artifact`；AgentScope 聚合提示为该工具注入 task UUID。重跑上述两条 Maven 命令，预期通过。

## 任务 5：L5 部署和真实端到端验证

1. 只读记录 L5 `k3s`/Helm/Deployment/Pod 状态、节点资源和当前镜像；K3s 连续 Ready 后再继续。
2. 对两个仓库分别记录初始 `git status`、HEAD 和 diff。MCP Tools 构建 `linux/amd64` 新镜像并用 `docker image inspect` 核对平台；AgentTeams Java 组件使用当前已确认的工作树构建产物与项目发布脚本。不得覆盖、重置、暂存或提交其他已有工作区改动。
3. 通过已配置的项目 Registry/Helm 流程更新 Control Plane 与 MCP Tools，等待相关 Deployment rollout 成功；确认 Worker、Gateway、数据库和 MCP 都 Ready，检查 MCP `tools/list` 含 `create_task_pdf_artifact`。
4. 在 L5 运行 `python3 /tmp/run-l5-subtask-delegation.py`（脚本部署后），确认真模型拆解、平台调度、全部子任务成功、Leader 聚合、MCP PDF 工具调用、父任务 PDF OUTPUT 清单和下载内容 `%PDF-`。保存 task/subtask/file IDs 与关键阶段证据。
5. 若 K3s 再次重启、服务不 Ready 或需要当前账号无法执行的 sudo 操作，停止写入并记录具体状态/错误，不用本地 Kind 代替真实 L5 验收。

**最终验证命令：**

```bash
cd /Users/gecko/code/agentteams-java && git diff --check && python3 -m unittest scripts.test_l5_subtask_pdf_artifact scripts.test_agentteams_task_mcp scripts.test_l5_identity_membership_contract -v
cd /Users/gecko/code/agentteams-mcp-tools && git diff --check && mvn -q verify
```

提交/推送只在全部验证通过且明确区分两个仓库的需求变更后执行；本计划不把提交/推送作为 L5 验收的前置步骤。

## 执行记录（2026-10-07）

- L5 主任务 `fbf3fe98-be6e-43d2-9704-263d9ac8cafa` 成功完成；两个子任务 `3f1c9a4e-2b7d-4e58-9c01-7a6d2f8b5e10`、`a8d5e7f2-4c19-4b63-8f2a-1e9b6c3d0a47` 均为 `SUCCEEDED`，依赖结果已验证。
- 聚合 Run `8edb166f-0be6-4906-b312-07f61fbd74d6` 成功调用 `create_task_pdf_artifact`；唯一 PDF OUTPUT 为 `quarterly-project-report.pdf`（file ID `6f1d5e72-a62a-4fb7-ac8f-953378afb8ef`），下载 134,803 字节且 `%PDF-` 文件头有效。结果评审为 `ACCEPTED`，幂等重放和归档验证完成。
- 原验收脚本额外要求子任务调用 `load_skill_through_path` 和 `web_search`，这不属于本 PDF 交付验收范围，导致全脚本最终非零退出。已将该检查改为 `--verify-child-skill-mcp` 显式选项；PDF 交付断言仍为必选。新版脚本已同步至 L5 `/tmp/run-l5-subtask-delegation.py`（SHA-256 `f17baffe2fc2b3a2bb15c1b7e329b564c66db958ec0daee20f5ed04c724b5ec5`）。Python 回归 34 项通过，`git diff --check` 通过。
- 集群当前 Control Plane 主 NetworkPolicy `agentteams-agentteams-java-control-plane` 原先未包含 `192.168.1.13/32:30082`。排查初期先追加对应 TCP egress 规则并从集群回读确认；后续完成 Helm release values 核查与完整配置对齐（见下文）。
- 后续普通 Helm 查询确认 release 保存的 OIDC issuer 与 egress 仍是旧地址（`192.168.122.55:30082`、`198.51.100.0/24:443`）。当前 L5 values 已使用新地址；同时将其中 Control Plane 镜像 pin 更新为正在运行的验收镜像 `l5-team-subtask-pdf-scheduler-20261007-amd64`。
- 已通过 `kubectl set env` 将运行中 Control Plane 的 OIDC issuer 改为 `http://192.168.1.13:30082/realms/agentteams`。滚动更新成功，Deployment 1/1 Ready，新 Pod 0 次重启，镜像仍为验收版本；MCP Tools 仍 1/1 Ready。NetworkPolicy 的新 egress 规则已在此前回读验证。
- 初次渲染仅使用 L5 values 时发现 7 个差异；改为按 Helm 的 reset-then-reuse 合并语义审阅后，差异收敛并确认可应用，随后完成完整 Helm 升级（见下文）。
- 随后按 Helm release 原始用户值与 L5 values 合并后复核，差异收敛为 5 个资源，均为新 IP/当前镜像相关：Console issuer 配置、Control Plane 镜像/issuer/文件预签名地址、Manager issuer/预签名地址，以及 Control Plane OIDC egress 规则。release 所需 Secret 均存在。首次 `--reuse-values` 在模板阶段因新 chart 默认值缺失失败并留下 pending revision；通过 rollback 清理后，以 `--reset-then-reuse-values` 成功升级至 revision 86（deployed）。
- Helm 升级后 Console、Control Plane、Manager 和 MCP Tools 均为 1/1 Ready，Pod 重启次数为 0；Helm 保存值、Console `config.js`、Control Plane/Manager 环境变量及 NetworkPolicy 均回读为 `192.168.1.13` 对应配置。Control Plane Pod 内访问 Keycloak OIDC discovery 返回 HTTP 200。
- Helm 升级后的首次完整验收暴露出 Keycloak `start-dev` 使用临时 H2 数据，滚动重启后原 realm 用户和成员身份未保留。已将 `KC_HOSTNAME`、realm client redirect URI/Web Origin 与 L5 `192.168.1.13` 对齐，并通过 discovery 和 Alice access token 确认 issuer 一致。MCP service user `agentteams-mcp-task-bot` 使用既有 Kubernetes Secret 凭证恢复到 realm，保留 `task:create`/`task:read` 最小角色，并补齐 tenant-a/project-a 的只读 `VIEWER` 项目成员记录；接口回读 HTTP 200。此为 L5 当前身份存储的持久化运维风险，后续应将 Keycloak 切换到持久化 PostgreSQL 后再安排 Keycloak 重启。
- 重新执行完整 L5 验收成功：主任务 `7b4f6990-5321-4e68-8fc7-19c1a40e44da` 的真模型 `plan_subtasks` 调用成功，两个子任务 `3f9c1a52-7d84-4b16-9e2a-5c0d81b7f4a3`、`b7e4d920-6a13-4f58-8c07-2d91e6f3aa70` 均成功并验证依赖上下文。聚合 Run `2df313dc-d6b0-4b88-a6f3-bf158daf4e97` 提交结果 `10c2e575-4602-4179-baf1-620d39c94c2f`，评审为 `ACCEPTED`，幂等重放返回同一记录，归档可见性检查通过。`create_task_pdf_artifact` 生成唯一 PDF OUTPUT `quarterly-project-report.pdf`（file ID `d6346192-0061-417e-9fe7-bf6251fe6eb5`），下载 136,123 字节且 `%PDF-` 文件头有效。脚本最终输出 `PASS l5-subtask-delegation`，并还原了验收 Alice 的项目成员身份。
