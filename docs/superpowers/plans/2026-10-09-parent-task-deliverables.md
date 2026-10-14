# 父任务最终交付物实现计划

> **面向 AI 代理的工作者：** 按顺序逐项实现此计划，每步完成后运行列出的定向验证。当前协作约束为只在 `main` 工作，并保留所有既有未提交改动；本轮不提交代码。

**目标：** 让父任务在最终 Manifest 中明确选定子任务输出文件，并在父任务结果区显示来源与下载链接。

**架构：** 聚合 Worker 从成功子任务的 `get_task_result` 读取 task-file 元数据，在最终 `artifacts` JSON 项中声明 `sourceTaskId` 和 `sourceFileId`。Gateway 将引用作为 metadata-only ArtifactRef 传递；Control Plane 校验源文件属于同一运行树中的成功任务且是可用 OUTPUT，并为父任务结果生成短时下载 URL。文件字节保留在原 task-file 对象中。

**技术栈：** Java 21、Spring Boot、gRPC protobuf、PostgreSQL/Flyway、React/TypeScript、Vitest。

---

## 文件清单

- 修改 `runtime/src/main/java/io/agentteams/runtime/GatewayRuntimeAdapter.java`：识别带 `sourceTaskId`、`sourceFileId` 的 metadata-only final artifact，不上传内容。
- 修改 `application-contracts/src/main/java/io/agentteams/application/api/TaskResultManifest.java`：为 Manifest artifact 加可选源任务和文件标识。
- 修改 `control-plane/src/main/resources/db/migration/V117__task_result_artifact_task_file_reference.sql`：持久化来源标识及成对约束。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/task/JdbcTaskResultManifestRepository.java`：读写引用字段。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/task/TaskResultManifestService.java` 与 `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileService.java`：校验来源文件与运行树关系。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java`：为引用型 Artifact 生成短时下载 URL，并返回来源标识。
- 修改 `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePort.java`、`scripts/agentteams-task-mcp.py` 和聚合提示拼装处：允许聚合阶段读取子任务结果并要求 Leader 明确列出被选文件。
- 修改 `console/src/api/types.ts` 与 `console/src/features/tasks/TaskExecutionObservability.tsx`：显示来源子任务并复用现有下载入口。
- 修改对应 Java、Python、TypeScript 测试文件，覆盖引用选择、校验拒绝及下载展示。

## 任务 1：运行结果接受显式 task-file 引用

**文件：**
- 修改 `runtime/src/main/java/io/agentteams/runtime/GatewayRuntimeAdapter.java`
- 测试 `runtime/src/test/java/io/agentteams/runtime/GatewayRuntimeAdapterArtifactUploadTest.java`

- [ ] **步骤 1：添加失败测试**

给聚合输出 JSON 增加 artifact 引用：

```json
{"summary":"完成","artifacts":[{"name":"brief.pdf","sourceTaskId":"<child-uuid>","sourceFileId":"<file-uuid>","contentType":"application/pdf","sizeBytes":123,"sha256":"<64-hex>"}]}
```

断言 TaskCompleted 中对应 ArtifactRef URI 是 `task-file://<child-uuid>/<file-uuid>`、大小/哈希/MIME 被保留、上传端口没有收到该引用的字节。

- [ ] **步骤 2：运行定向测试确认失败**

运行：`mvn -q -pl runtime -Dtest=GatewayRuntimeAdapterArtifactUploadTest test`

预期：新断言失败，因为当前协议只识别 `{name, content}`。

- [ ] **步骤 3：实现 metadata-only 引用解析**

只接受 UUID 格式的 source IDs、非空名称/MIME、非负大小和 64 位十六进制 SHA-256；其余引用按无效结果拒绝，不走 artifact 上传端口。保留现有 `{name, content}` 行为。

- [ ] **步骤 4：重跑定向测试**

运行：`mvn -q -pl runtime -Dtest=GatewayRuntimeAdapterArtifactUploadTest test`

预期：退出码 0，旧文本 artifact 上传断言仍通过。

## 任务 2：Manifest 持久化并校验来源

**文件：**
- 修改 `application-contracts/src/main/java/io/agentteams/application/api/TaskResultManifest.java`
- 创建 `control-plane/src/main/resources/db/migration/V117__task_result_artifact_task_file_reference.sql`
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/task/JdbcTaskResultManifestRepository.java`
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/task/TaskResultManifestService.java`
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileService.java`
- 测试 `application-contracts/src/test/java/io/agentteams/application/api/TaskResultManifestTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/task/JdbcTaskResultManifestRepositoryTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/task/TaskResultManifestServiceTest.java`

- [ ] **步骤 1：先添加红灯测试**

覆盖无来源的既有 artifact 兼容；成对 source IDs 可构造；只给一个 source ID 被拒；Manifest 发布拒绝非 OUTPUT、MISSING、失败子任务及不属于本次 run 的文件。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl application-contracts,control-plane -Dtest=TaskResultManifestTest,JdbcTaskResultManifestRepositoryTest,TaskResultManifestServiceTest test`

预期：因 source fields 和来源校验缺失而失败。

- [ ] **步骤 3：实现来源字段和数据库约束**

ArtifactMetadata 增加可空 `sourceTaskId`/`sourceFileId`；V117 为 `task_result_artifacts` 添加 UUID 列、要求两列同时为空或同时非空，并更新 repository mapper/upsert。

- [ ] **步骤 4：实现发布校验**

在 manifest 发布边界使用运行树验证源任务属于 `manifest.runId`、子任务终态成功、TaskFile 为 `OUTPUT/AVAILABLE`；不满足时拒绝发布。父任务自己的 artifact 无来源字段时保持原行为。

- [ ] **步骤 5：重跑定向测试**

运行：`mvn -q -pl application-contracts,control-plane -Dtest=TaskResultManifestTest,JdbcTaskResultManifestRepositoryTest,TaskResultManifestServiceTest test`

预期：全部通过，Flyway schema 测试无迁移错误。

## 任务 3：安全下载和父任务结果展示

**文件：**
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java`
- 修改 `control-plane/src/test/java/io/agentteams/controlplane/api/TaskProcessControllerTest.java`
- 修改 `console/src/api/types.ts`
- 修改 `console/src/features/tasks/TaskExecutionObservability.tsx`
- 修改 `console/tests/features/TaskPages.test.tsx`

- [ ] **步骤 1：添加红灯测试**

Control Plane 测试要求引用 artifact 返回带下载地址和来源 task ID/file ID；普通 artifact 仍使用原下载地址；伪造来源或缺失文件时不返回可下载链接。Console 测试断言来源名称/ID展示且下载链接可点击。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane -Dtest=TaskProcessControllerTest test` 及 `cd console && npm test -- --run tests/features/TaskPages.test.tsx -t 'artifact'`

预期：新断言因结果响应缺少来源元数据/下载地址而失败。

- [ ] **步骤 3：实现下载 URL**

根据父 Manifest 已校验的 source task/file 查找原 TaskFile，使用现有 browser presign 服务生成 15 分钟下载 URL；API 仍先用父任务权限校验结果，再限制引用只读原文件，不向调用者暴露 storage key。

- [ ] **步骤 4：实现 Console 展示**

将来源字段加入 ResultArtifact 类型，在当前“运行结果与成果物”表格显示 `来源子任务 <UUID>`；保持下载按钮复用 `downloadUrl`。

- [ ] **步骤 5：重跑定向测试**

运行上述 Control Plane 和 Console 定向命令，预期均通过。

## 任务 4：让 Leader 发现并选择子任务文件

**文件：**
- 修改 `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePort.java`
- 修改 `agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeRuntime.java`
- 修改 `scripts/agentteams-task-mcp.py`
- 修改 `scripts/test_agentteams_task_mcp.py`
- 修改 `agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePortTest.java`
- 修改 `agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeRuntimeTest.java`
- 修改双 MCP 案例定义及合约测试 `eval/cases/ai-news-web-search.yaml`、`scripts/test_ai_news_web_search_eval_contract.py`

- [ ] **步骤 1：添加失败测试**

MCP 合约测试断言 `get_task_result` 在聚合工具 allowlist 内；Leader 聚合提示要求先对成功子任务调用 `get_task_result`，再仅把选中的 `taskFiles[].fileId` 以 `sourceTaskId/sourceFileId` 写入最终 artifacts，未选择文件不发布。

- [ ] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts.test_agentteams_task_mcp scripts.test_ai_news_web_search_eval_contract` 和 `mvn -q -pl agent-worker -Dtest=AgentScopeMcpRuntimePortTest,AgentScopeRuntimeTest test`

预期：因工具过滤和聚合约束缺失而失败。

- [ ] **步骤 3：开放受限查询并更新聚合契约**

只在 aggregateMcpTools 明确包含 `get_task_result` 时启用它；扩展平台工具白名单；聚合提示要求 Leader 依据成功子任务返回的 `taskFiles` 明确输出来源 ID，且不得猜测文件 ID。

- [ ] **步骤 4：更新 eval case**

在 `aggregateMcpTools` 中加入 `get_task_result`；aggregatePrompt 要求选中 PDF 文件并在最终 artifacts 以来源字段引用，不再只依赖子任务文件自动出现。

- [ ] **步骤 5：重跑定向测试**

运行任务 4 步骤 2 的命令，预期 Python 和 Maven 测试均通过。

## 任务 5：端到端契约与本地验证

**文件：**
- 修改 `scripts/run-l5-subtask-delegation.py` 与相关 eval assertions
- 按任务 1-4 的定向测试结果修复实际失败点

- [ ] **步骤 1：新增验收断言**

多智能体 PDF 用例须在父任务 Manifest 中恰有一个来源子任务的 PDF 引用；子任务仍保留原始 task file；未被父 Manifest 选择的其他子任务输出不可出现在父任务 artifact list。

- [ ] **步骤 2：运行项目相关自动化**

运行：`mvn -q -pl application-contracts,runtime,control-plane,agent-worker -am test`、`cd console && npm test -- --run tests/features/TaskPages.test.tsx`、`python3 -m unittest scripts.test_agentteams_task_mcp scripts.test_ai_news_web_search_eval_contract scripts.test_l5_subtask_pdf_artifact`。

预期：命令均退出码 0。

- [ ] **步骤 3：构建 Console 和 Control Plane 镜像**

按 `deploy/build-images.sh` 对应的 L5 amd64 流程构建本次变更镜像；只更新本次涉及的 image tag，不重置工作区其他改动。

- [ ] **步骤 4：L5 发布后验证**

部署完成后检查对应 Deployment Ready；对既有 PDF 任务创建一个新的测试 run，使 Leader 显式选择子任务 PDF；确认父任务结果显示一个可下载 PDF、来源字段正确，未选择文件不出现在父结果中；使用短时下载链接验证 `%PDF-` 文件头。既有旧 Run 不回填、不改写。
