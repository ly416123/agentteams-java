# Agent 搜索来源可信证据链实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 记录受信任搜索工具实际返回的来源，并让 Java Evaluation 与 Python Runner 依据当前任务 Run 及其成功委派子任务 Run 的来源证据判定 `source_citation`。

**架构：** 搜索 MCP 将文本响应解析为有界来源记录，并通过受信任的 Worker 执行上下文把搜索调用回执和 URL 元数据写入 Control Plane。Control Plane 按 task/run 范围持久化；Evidence Bundle 可合并当前任务 Run 和其 Task Tree 中成功委派子任务 Run 的来源，同时保留各来源原始身份。Java Evaluation 与 L5 Runner 只读该受限集合，缺失或不完整时返回 `INCONCLUSIVE`。

**技术栈：** Java 21、Spring Boot、Spring JDBC、Flyway、MCP Java SDK、AgentScope、JUnit 5、Python 3、PyYAML、现有 L5 Evaluation API。

---

## 实施约束

- 两个仓库都只在 `main` 分支工作：`/Users/gecko/code/agentteams-java` 与 `/Users/gecko/code/agentteams-mcp-tools`。
- 两个工作区都有既有未提交改动。实现时先确认 `git status`；测试应扩展现有文件，不覆盖或清理其他人的暂存、未暂存或未跟踪文件。提交必须使用明确路径，不使用不带路径的 `git add -A` 或普通全量提交。
- `agentteams-mcp-tools` 的 `TaskControlPlaneClient`、`TaskClientConfig`、`TaskProperties` 和 task 工具文件目前有未提交工作；复用其已存在能力，修改这些文件前先检查当时的 diff。
- 搜索来源不接受模型参数自报。任务/尝试/租约关联由 Worker 在 MCP HTTP 请求边界注入；Control Plane 使用已有内部 task-MCP 凭证认证，并核验 task 的活动 attempt/lease。MCP 参数中的 task ID 不作授权依据。
- 原始搜索响应、搜索查询、完整答案、凭证、认证头和预签名 URL 不进入来源表、评测报告或普通日志。
- 来源摄取不可用时不阻断搜索回答，但当前 Run 的来源覆盖必须显示不完整，门禁为 `INCONCLUSIVE`。
- 暂不访问引用 URL，也不评估来源相关性或事实正确性。

## 文件清单

### MCP 搜索与执行上下文

- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/search/SearchResponse.java` — 兼容既有文本块并增加结构化来源。
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/search/SearchSource.java` — 标题、规范化前 URL 和来源哈希的值对象。
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/search/BailianSearchClient.java` — 从上游搜索返回内容确定性抽取标题与 URL；保留现有错误脱敏。
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/search/WebSearchTool.java`、`config/McpServerConfig.java` — 记录真实搜索调用的来源与空结果回执，同时继续向 Agent 返回可读文本。
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/task/TaskControlPlaneClient.java` — 增加来源写入请求和稳定幂等键；沿用现有 OIDC/受限 task-MCP 认证配置。
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/config/SecurityConfig.java`、`TaskClientConfig.java`、`TaskProperties.java` — 仅增加所需的受控配置/请求上下文，保留既有认证模式。
- 测试：`/Users/gecko/code/agentteams-mcp-tools/src/test/java/io/agentteams/mcptools/search/BailianSearchClientTest.java`、`src/test/java/io/agentteams/mcptools/McpServerIntegrationTest.java` 及对应 task/config 测试。

### Worker 请求上下文

- 修改：`agent-worker/src/main/java/io/agentteams/worker/agentscope/McpRuntimePort.java`、`AgentScopeMcpRuntimePort.java` — 将任务执行上下文传给 MCP 配置，只对平台 task MCP 请求附加 task、attempt、lease 和每次 MCP 请求的调用标识；不得把这些值添加到模型可见参数。
- 修改：`agent-worker/src/main/java/io/agentteams/worker/agentscope/ConfiguredAgentScopeHarnessFactory.java` — 从 `RuntimeTask.metadata` 取 `attemptId` 与 `leaseId`，将执行上下文传给 MCP runtime。
- 测试：`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePortTest.java`、`ConfiguredAgentScopeHarnessFactoryTest.java` — 验证仅平台 task MCP 获得执行头、缺失上下文 fail closed、其他 MCP 服务器不接收内部头。

### Control Plane 来源写入、存储和 Evidence Bundle

- 创建迁移：`control-plane/src/main/resources/db/migration/V113__task_source_evidence.sql` — 当前 V112 已用于 Evaluation 门禁；实现时重新检查版本并顺延。建立搜索调用回执及来源项表、task/run/attempt 外键或范围约束、唯一幂等键与读取索引。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/task/TaskSourceEvidence.java`、`TaskSourceEvidenceRepository.java`、`JdbcTaskSourceEvidenceRepository.java`、`TaskSourceEvidenceService.java`、`TaskSourceEvidenceController.java` — 输入校验、授权范围解析、幂等写入与只读查询。摄取接口为 `POST /api/v1/tasks/{taskId}/source-evidence`；评测读取接口为 `GET /api/v1/tasks/{taskId}/runs/{runId}/source-evidence`。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthenticationFilter.java` — 精确允许来源摄取 POST 路径通过现有 task-MCP 内部凭证；不扩大该凭证到其他 API。评测 GET 继续走现有 OIDC 身份与项目/租户授权。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java` — 注册 repository/service/controller 依赖。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvidenceBundle.java`、`EvidenceBundleAssembler.java` — 增加 Run 内来源项和搜索调用覆盖状态；查询必须限定当前授权 context、task 和 run。
- 测试：对应 `TaskSourceEvidence*Test.java`、`ApiAuthenticationFilterTest.java`、`EvidenceBundleAssemblerTest.java`、`FoundationRepositoryIT.java`。

### 确定性 Evaluation、Runner 与用例

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationService.java` — 实现 `source_citation`：只在调用覆盖完整、来源记录完整、答案中至少一个 URL 可解析且所有引用都精确命中当前 Run 及成功委派子任务 Run 时通过；明确不匹配失败；缺证据或不可解析时不确定。
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/evaluation/EvaluationServiceTest.java`、`EvaluationServiceDeterministicAssertionsTest.java`。
- 修改：`scripts/agentteams_eval.py`、`scripts/run-l5-agentteams-eval.py` — 获取授权来源记录，校验 task/run 关联和完整度，并输出脱敏稳定证据引用。
- 测试：`scripts/test_agentteams_eval.py`、`scripts/test_l5_agentteams_eval_contract.py`。
- 修改：`eval/cases/core.yaml`、`eval/README.md`、`docs/superpowers/plans/2026-10-02-agent-evaluation-process-result.md` — 明确 source citation 的门禁输入、结果语义和当前证据限制。
- 创建/更新真实 L5 报告：仅在发布 MCP 服务和 Control Plane 后通过真实评测生成；不得手工改写旧报告或将旧 `INCONCLUSIVE` 标成通过。

## 任务 1：解析结构化搜索来源

**文件：** MCP 搜索响应模型、解析器及单元测试。

- [x] **步骤 1：先写结构化解析失败测试**

在 `BailianSearchClientTest`/新建 `SearchSourceParserTest` 覆盖当前 fixture `title — https://example.test`、多个来源、无 URL、非 HTTP(S)、带 userinfo、fragment、重复 URL、标题/URL 超长和空响应；断言错误/日志不包含上游正文或密钥。

- [x] **步骤 2：运行目标测试确认失败**

运行：`cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest='BailianSearchClientTest,SearchSourceParserTest' test`

预期：新结构化来源断言失败，既有转发文本测试继续可编译。

- [x] **步骤 3：增加来源值对象与确定性解析**

添加 `SearchSource(title, url, sha256)`，拒绝非 HTTP(S)、userinfo、空 host 和超长值，规范化时移除 fragment；按首次出现顺序去重。修改 `SearchResponse` 时保留 `SearchResponse(List<String> textBlocks)` 兼容构造器。解析无法识别的文本时保留原始可读块，但不生成来源对象。

- [x] **步骤 4：运行目标测试确认通过**

运行：`cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest='BailianSearchClientTest,SearchSourceParserTest' test`

预期：PASS，结构化来源与 Agent 可读文本分别断言。

- [x] **步骤 5：仅提交本任务文件**

已提交：`bfa40ba feat(search): parse structured source evidence`。提交只包含 6 个搜索解析/测试文件；仓库其余既有修改仍保留。

## 任务 2：为 MCP 搜索调用附加受信任执行上下文

**文件：** Worker MCP runtime 接口、AgentScope MCP adapter、Harness factory 与测试。

- [x] **步骤 1：写头部传递测试**

测试 `McpRuntimePort` 收到 `RuntimeTask(id, metadata)` 后，平台 task MCP 的请求 customizer 会附加 task ID、attempt ID、lease ID 和唯一调用 ID；外部 MCP 服务器完全不附带这些内部头；缺 attempt/lease 时拒绝配置 task MCP。

- [x] **步骤 2：运行 Worker 测试确认失败**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl agent-worker -Dtest='AgentScopeMcpRuntimePortTest,ConfiguredAgentScopeHarnessFactoryTest' test`

预期：编译或新头部断言失败，记录真实失败点。

- [x] **步骤 3：实现上下文传递**

新增不可变 `McpExecutionContext(taskId, attemptId, leaseId)`，由 Harness factory 从已接收的 `RuntimeTask` 构造并传入 `McpRuntimePort.configure`。只在 `isTaskOrchestrationServer(server)` 为真时，在 HTTP 请求 customizer 附加上下文头；每个 MCP HTTP 请求生成独立 UUID 调用标识。不得把值写入工具参数、Agent prompt 或日志。

- [x] **步骤 4：运行 Worker 测试确认通过**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl agent-worker -Dtest='AgentScopeMcpRuntimePortTest,ConfiguredAgentScopeHarnessFactoryTest' test`

预期：PASS，外部 MCP 头隔离和 fail-closed 用例通过。

- [ ] **步骤 5：提交 Worker 改动**

Worker 接口与测试文件含有先前未提交的 MCP capability 改动，不能使用 `git commit --only` 提交整个文件，否则会夹带旧差异。提交前使用交互式暂存或精确 patch staging，只暂存本任务新增的上下文差异；其余已有差异继续保留在工作区。

## 任务 3：由 MCP 搜索服务写入来源与调用回执

**文件：** `WebSearchTool`、MCP exchange 上下文适配、`TaskControlPlaneClient` 和 MCP 集成测试。

- [x] **步骤 1：写摄取请求测试**

在 MCP 集成测试中为成功搜索模拟结构化来源，断言服务端向 Control Plane 发出带 Bearer、task/attempt/lease、调用 ID 和 Idempotency-Key 的请求；空搜索也写入零来源的成功回执；Control Plane 失败时仍返回搜索文本但不泄露响应正文。

- [x] **步骤 2：运行 MCP 集成测试确认失败**

运行：`cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest='McpServerIntegrationTest,TaskControlPlaneClientTest' test`

预期：来源摄取请求及回执断言失败。

- [x] **步骤 3：实现 MCP 请求上下文解析和摄取客户端**

从 MCP 服务端 HTTP exchange 的认证请求上下文读取 Worker 注入的执行头，校验 UUID/长度后构造 `SourceEvidenceSubmission`；扩展 `TaskControlPlaneClient` 使用现有 Token supplier 提交回执及来源项。幂等键基于 task、attempt、调用 ID。不得记录查询、原始文本或 token。摄取失败仅设置安全的 `captureStatus=UNAVAILABLE` 运行态结果，不中断搜索响应。

- [x] **步骤 4：运行 MCP 集成测试确认通过**

运行：`cd /Users/gecko/code/agentteams-mcp-tools && mvn -q -Dtest='McpServerIntegrationTest,TaskControlPlaneClientTest' test`

预期：PASS，成功/空结果/摄取失败三种行为均有覆盖。

- [ ] **步骤 5：仅提交 MCP 搜索摄取文件**

提交使用的 task client/配置文件须保留已存在的未提交改动；通过 `git diff --cached` 和 `git show --stat --oneline HEAD` 核验提交仅含本任务路径。

## 任务 4：Control Plane 验证调用身份并持久化证据

**文件：** Flyway migration、Task Source Evidence repository/service/controller、API auth filter 与 repository/HTTP 集成测试。

- [ ] **步骤 1：写鉴权、幂等和隔离失败测试**

覆盖：无内部凭证拒绝；凭证只允许精确 POST 路径；task/attempt/lease 不匹配或租约失效拒绝；活动 task 的有效 attempt 可写；相同幂等键重试不重复计数；重复 URL 去重；普通 OIDC GET 必须具有目标 task/run 的读取权限；其他 task/run/tenant 查询不返回记录；空来源成功调用也保留回执。

- [ ] **步骤 2：运行 Control Plane 目标测试确认失败**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl control-plane -Dtest='TaskSourceEvidenceServiceTest,TaskSourceEvidenceControllerTest,ApiAuthenticationFilterTest' test`

预期：新 Controller/service/repository 契约缺失导致失败。

- [x] **步骤 3：实现 schema 与服务边界**

迁移创建搜索调用回执和来源项存储；task/run/attempt 从数据库校验并从 `task_runs` 解析，不信任请求体声明的组织、租户或 run。复用现有 task-MCP token 的窄路径授权，并为新 POST 路径增加精确匹配。写入限制来源数量、标题/URL 长度、请求体大小；HTTP 响应只返回回执 ID 和计数。

- [x] **步骤 4：运行目标测试及迁移集成测试**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl control-plane -Dtest='TaskSourceEvidenceServiceTest,TaskSourceEvidenceControllerTest,ApiAuthenticationFilterTest,FoundationRepositoryIT' test`

预期：PASS；迁移在 PostgreSQL 集成测试中应用成功，边界、幂等和范围隔离验证通过。

- [ ] **步骤 5：仅提交 Control Plane 摄取和 schema 文件**

使用明确路径提交，避免夹带当前已暂存的其他模块改动。

## 任务 5：Evidence Bundle 与 Java `source_citation` 门禁

**文件：** `EvidenceBundle`、assembler、来源查询 repository、`EvaluationService` 和测试。

- [x] **步骤 1：先写 Bundle 与 evaluator 状态矩阵测试**

覆盖：同一 Run 中完整来源与答案 URL 精确匹配为 `PASSED`；完整回执下 URL 不匹配为 `FAILED`；缺回执、缺少任一成功搜索调用回执、截断、无效答案 URL、task/run 关联不符为 `INCONCLUSIVE`；另一 Run 或 tenant 的相同 URL 不得命中。

- [ ] **步骤 2：运行 Evaluation 专项测试确认失败**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl control-plane -Dtest='EvidenceBundleAssemblerTest,EvaluationServiceTest,EvaluationServiceDeterministicAssertionsTest' test`

预期：`source_citation` 仍为不支持断言，Bundle 无来源数据，新增测试失败。

- [x] **步骤 3：扩展受权限过滤的 Evidence Bundle**

新增来源记录与 `SourceEvidenceCompleteness(status, observedSearchCalls, recordedSearchCalls, itemLimit)`；assembler 按 `(ExecutionContext, taskId, runId)` 读取有界数据，只有完整调用回执计数匹配且未超过上限才标 `COMPLETE`。稳定引用采用 `task-source:<call-id>:<source-hash>`，不包含正文。

- [x] **步骤 4：实现 URL 引用确定性 evaluator**

在 `EvaluationService` 为 `source_citation` 增加独立方法：从当前 Run 的最终 `resultManifest.summary` 解析显式 HTTP(S) URL；无完整来源覆盖或 URL 不可解析则 `INCONCLUSIVE`；完整覆盖下至少有一个 URL 且所有引用都精确命中该任务 Run 及成功委派子任务 Run 的来源集合时 `PASSED`；完整覆盖下存在未命中 URL 时 `FAILED`。结果只输出稳定证据引用与安全原因。

- [x] **步骤 5：运行 Evaluation 测试确认通过**

运行：`cd /Users/gecko/code/agentteams-java && mvn -q -pl control-plane -Dtest='EvidenceBundleAssemblerTest,EvaluationServiceTest,EvaluationServiceDeterministicAssertionsTest,JdbcTaskSourceEvidenceRepositoryTest' test`

预期：PASS，并确认报告中不含搜索响应正文、完整答案或凭证。

- [ ] **步骤 6：仅提交 Bundle、评测和测试文件**

按模块拆分提交，确保 Flyway 与当前其他暂存改动没有意外进入提交。

## 任务 6：Python Runner 与 Suite 采用相同证据契约

**文件：** `scripts/agentteams_eval.py`、`scripts/run-l5-agentteams-eval.py`、测试、`eval/cases/core.yaml` 与说明。

- [x] **步骤 1：先写 Runner 门禁契约测试**

使用脱敏 Fixture 覆盖同 Run URL 命中、明确不匹配、调用回执缺失、部分摄取、重复来源、跨 Run 来源隔离、URL 不合法及来源列表截断；确保失败响应正文和内部下载地址不会出现在报告。

- [x] **步骤 2：运行 Python 测试确认失败**

运行：`cd /Users/gecko/code/agentteams-java && python3 -m unittest scripts.test_agentteams_eval scripts.test_l5_agentteams_eval_contract -v`

预期：新 source evidence 形状和三态断言失败。

- [x] **步骤 3：实现 Runner 获取与验证**

Runner 通过 `GET /api/v1/tasks/{taskId}/runs/{runId}/source-evidence` 按 task/run 调用受授权的来源查询端点；核对返回的 taskId/runId 与请求完全一致，受限获取回执及来源项；规范化规则与 Java 一致。不可解析的报告答案和任何不完整采集返回 `INCONCLUSIVE`。报告仅包含来源稳定引用、计数与判定，不包含原始 query/result。

- [x] **步骤 4：更新回归用例与使用说明**

为正向 Case 配置最少一个显式 URL 引用；负向 Fixture 配置完整证据中的伪造 URL。保持 L5 真实报告由执行命令生成，离线 Fixture 不进入真实报告目录。

- [x] **步骤 5：运行 Python 离线测试**

运行：`cd /Users/gecko/code/agentteams-java && python3 -m unittest scripts.test_agentteams_eval scripts.test_l5_agentteams_eval_contract -v`

预期：PASS，Runner 输出与 Java 门禁的 `PASSED/FAILED/INCONCLUSIVE` 语义一致。

- [ ] **步骤 6：仅提交 Runner、Suite 和文档文件**

显式指定相关路径提交；保留工作区已有评测、PDF 和部署改动。

## 任务 7：部署与 L5 真实验收

**文件：** 若来源摄取需要新增配置，修改 `deploy/helm/agentteams-java/values.yaml`、`values.schema.json`、Control Plane/MCP deployment template 及 `agentteams-mcp-tools/deploy/l5` 的对应文件；只改本功能所需配置。

- [ ] **步骤 1：写部署契约测试**

验证内部 task-MCP token 只注入 MCP 服务与 Control Plane 的窄范围配置，不进入 Worker 日志、公共 values 明文或 Evaluation 报告；NetworkPolicy 仅开放现有 MCP 到 Control Plane 路径。

- [ ] **步骤 2：运行离线配置检查确认失败**

运行：`cd /Users/gecko/code/agentteams-java && python3 scripts/test_kind_infra_image_contract.py && python3 scripts/validate-kind-manifests.py`

预期：若需要新增配置，先由新断言定位缺少的 chart/schema/manifest contract。

- [ ] **步骤 3：完成构建与离线验证**

执行 Control Plane、Worker 和 MCP tools 相关单测及项目已有 kind 配置校验；不要触碰 L5 直到本地证据链和部署包完整。

- [ ] **步骤 4：部署 L5 并运行正向/负向真实评测**

部署两仓库 `main` 对应镜像后，运行 `multi-agent-pdf-positive` 与负向来源引用 Case。真实报告由 runner 命令生成；需短期评测 Token 时沿用已批准的无回显获取方式，绝不打印或提交 Token。

- [ ] **步骤 5：核对报告与工作区**

确认正向 `source_citation` 的 `PASSED` 有对应本 Run 来源证据引用，负向伪造 URL 为 `FAILED`，摄取不完整为 `INCONCLUSIVE`；执行 `git status --short` 与 `git show --stat` 核实只提交计划路径，并记录剩余既有修改。

## 实施前自检记录

- 规格中的来源解析、可信 task/run 关联、持久化、授权隔离、幂等、脱敏、失败三态、Java Evaluation、Python Runner、L5 验收均各有对应任务。
- 迁移基线目前包含 V112 Evaluation 门禁；执行时必须重新检查迁移序号，不能覆盖其他未提交迁移。
- 结构化来源仅从实际搜索 MCP 响应解析；工具调用回执包括零来源搜索，避免把空结果误判为写入丢失。
- 当前没有运行测试或修改业务实现；此文件只定义下一步实现顺序。
