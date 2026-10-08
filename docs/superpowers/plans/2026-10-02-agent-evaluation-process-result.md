# Agent 执行过程与结果测评实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让 YAML 回归 Runner 与 Control Plane 使用一致、可追溯的确定性证据契约，分别评测 Agent 执行过程和交付结果，并展示两个独立门禁。

**架构：** Evidence Bundle 作为只读输入，先补齐 Task、Run、工具成功/失败、子任务依赖和制品完整性证据，再统一实现过程门禁与结果契约。Evaluation 报告分别给出 `executionGate`、`resultGate` 和 Task 成功状态；语义 Judge 仍为独立的后续阶段，不覆盖确定性门禁。

**技术栈：** Java 21、Spring Boot、JDBC、Flyway、Jackson、JUnit 5、Python 3、PyYAML、React、TypeScript、Vitest，以及现有 L5 `agentteams_eval.py` 受控入口。

---

## 文件清单

### Control Plane 证据与评测

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvidenceBundle.java` — 增加 Execution Run 快照与证据完整性元数据。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvidenceBundleAssembler.java` — 组装 Run、工具生命周期、截断状态和安全引用。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationService.java` — 实现断言 evaluator 和分开的门禁状态。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationReportRecord.java` — 保存过程门禁和结果门禁状态。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationRepository.java`、`JdbcEvaluationRepository.java` — 持久化报告新增字段。
- 创建：`control-plane/src/main/resources/db/migration/V112__evaluation_execution_result_gates.sql` — 增加报告门禁列与兼容约束；实现前先检查 Flyway 实际最高版本，若已被占用则顺延版本号。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/evaluation/EvidenceBundleAssemblerTest.java`、`EvaluationServiceTest.java`、`JdbcEvaluationRepositoryTest.java` — 证据、断言、门禁和持久化测试。

### Worker 工具生命周期

- 修改：`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeRuntime.java` — 从 AgentScope 执行结果区分 MCP 工具成功与失败，发布 `tool.finished` 安全事件。
- 修改：`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeRuntimeTest.java` — 验证调用、成功、失败的事件和不泄漏响应正文。

### YAML Runner 与用例

- 修改：`scripts/agentteams_eval.py` — 支持 bundle 规范化、完整性状态和新确定性断言。
- 修改：`scripts/test_agentteams_eval.py` — 覆盖断言通过、失败、不确定、脱敏和兼容场景。
- 修改：`eval/cases/core.yaml` — 增加多 Agent 搜索与 PDF 用例及负向变体。
- 修改：`eval/README.md` — 记录新增断言、用例执行命令和结果解释。
- 修改：`scripts/test_evaluation_quality_contract.py`、`scripts/test_l5_agentteams_eval_contract.py` — 检查 Suite 格式、断言定义和报告脱敏契约。

### Console

- 修改：`console/src/api/types.ts` — 表达新增门禁和逐项结果状态。
- 修改：`console/src/features/management/ManagementEvaluationPage.tsx` — 分开显示 Task 成功、执行门禁、结果门禁和语义建议状态。
- 修改：`console/tests/features/ManagementEvaluationPage.test.tsx` — 覆盖双门禁、失败证据和不确定状态显示。

### L5 验收

- 创建：`scripts/test_l5_agent_evaluation_process_result.py` — 对 L5 真实 Suite 执行前置配置和报告契约做离线检查。
- 更新：`eval/reports/` 下的脱敏 L5 验收报告 — 仅在真实正向、负向 L5 执行后增加，不手工伪造结果。

## 实施约束

- 不修改 Task/Execution Run 的业务状态，不让 Evaluation 写回业务数据。
- 每个门禁结果都必须带稳定证据引用；读取上限导致截断时不能判为通过。
- `required_tool` 只有在工具成功完成时通过；调用失败要单独可见。
- 不将语义分数并入硬门禁；Judge 未配置时保持未执行或 `INCONCLUSIVE`。
- 报告和日志不得包含凭证、原始工具结果正文或预签名下载地址。
- L5 正向与负向真实执行必须使用获授权的短期身份；离线测试不得访问 L5。

## 任务 1：定义门禁报告契约

**文件：** `EvaluationReportRecord.java`、`V112__evaluation_execution_result_gates.sql`、`EvaluationRepository.java`、`JdbcEvaluationRepository.java`、对应测试。

- [x] **步骤 1：先写报告模型失败测试**

在 `EvaluationRecordTest` 增加以下契约：`executionGateStatus`、`resultGateStatus` 都仅允许 `PASSED`、`FAILED`、`INCONCLUSIVE`；Task `SUCCEEDED` 可以与任一 Evaluation 门禁状态并存。

- [x] **步骤 2：运行模型测试确认失败**

运行：`mvn -q -pl control-plane -Dtest=EvaluationRecordTest test`

预期：FAIL，报告类型缺少门禁字段或状态校验。

- [x] **步骤 3：增加向后兼容迁移和记录字段**

迁移给 `evaluation_reports` 添加非空门禁状态列并设为 `INCONCLUSIVE` 默认值，避免旧报告读取失败。报告 record 新增对应字段，Repository 的 INSERT/SELECT 和 row mapper 使用同一字段顺序。

- [x] **步骤 4：运行持久化测试确认通过**

运行：`mvn -q -pl control-plane -Dtest='EvaluationRecordTest,JdbcEvaluationRepositoryTest' test`

预期：PASS，新增报告状态可持久化，旧版构造/数据库数据仍能读取。

- [x] **步骤 5：提交契约变更**

  提交范围仅限迁移、报告记录、Repository 和对应测试，提交说明：`feat(eval): add execution and result gate states`。实际提交为 `4272b1f9`。

**任务 1 执行记录（2026-10-07）：** V112 为两个门禁列添加 `INCONCLUSIVE` 默认值和 CHECK 约束；`EvaluationReportRecord` 增加独立状态并保留旧构造器兼容；JDBC 保存、读取和映射均已扩展。TDD 先确认模型测试因缺字段失败，再实现后运行 `mvn -q -pl control-plane -Dtest='EvaluationRecordTest,JdbcEvaluationRepositoryTest' test` 通过。规格审查与代码质量审查无阻塞项。当前 EvaluationService 仍使用兼容构造器，因此新报告门禁状态在后续 evaluator 完成前为 `INCONCLUSIVE`；Mockito 测试没有替代真实 PostgreSQL/Flyway 验收。

## 任务 2：扩充 Evidence Bundle 并保留完整性

**文件：** `EvidenceBundle.java`、`EvidenceBundleAssembler.java`、`EvidenceBundleAssemblerTest.java`；为读取权限校验后的任务/Run 状态，扩展 `TaskRunObservationRepository`、`JdbcTaskRunObservationRepository` 及其测试；为精确识别第 1001 条事件，放宽 `TaskProcessEventRepository` 的受控 limit 上限并更新对应 JDBC 契约测试。

- [x] **步骤 1：编写缺失、截断和 Run 状态测试**

覆盖完整事件少于 1000 条时状态为 `COMPLETE`；读取到第 1001 条时状态为 `TRUNCATED`，但 Bundle 最多保留 1000 条事件；缺少权限范围内的 Run 快照或 manifest 时对应状态为 `UNKNOWN`，且 Bundle 不声称证据完整。Run 查询必须同时按 organization、tenant、task 和 run 限定；任务 phase、Run status/timestamps 与 manifest status 分别取自各自数据源，不互相推导。

- [x] **步骤 2：运行组装器测试确认失败**

运行：`mvn -q -pl control-plane -Dtest=EvidenceBundleAssemblerTest test`

预期：FAIL，当前 Bundle 无完整性元数据和独立 Run 快照。

- [x] **步骤 3：实现 Evidence Bundle 新字段**

加入 `RunSnapshot(status, startedAt, completedAt)` 与 `EvidenceCompleteness(status, observedEventCount, eventLimit)`。允许状态为 `COMPLETE`、`TRUNCATED`、`INCONCLUSIVE`；保持 Task phase、Run status 和 manifest status 独立。

- [x] **步骤 4：实现组装器边界判定并运行测试**

读取事件时请求 `EVENT_LIMIT + 1` 条以确认是否截断；Bundle 中最多保留 `EVENT_LIMIT` 条。所有事件继续通过现有 sanitize 流程；不通过分页数量推断工具调用成功。

运行：`mvn -q -pl control-plane -Dtest=EvidenceBundleAssemblerTest test`

预期：PASS，截断可见，脱敏和范围权限原有用例继续通过。

- [x] **步骤 5：提交证据契约**

提交范围仅限 `EvidenceBundle*` 和组装器测试，提交说明：`feat(eval): expose evidence completeness and run snapshot`。

**任务 2 执行记录（2026-10-08）：** TDD 测试先确认旧实现缺少 Run 快照与完整性元数据；实现后 Run 查询按 organization、tenant、task、run 限定，事件最多保留 1000 条并通过第 1001 条识别截断。缺 Run/manifest、截断时状态为 UNKNOWN/INCONCLUSIVE，不会声称输入完整。`mvn -q -pl control-plane -Dtest='EvidenceBundleAssemblerTest,JdbcTaskRunObservationRepositoryTest,JdbcTaskProcessEventRepositoryTest' test` 与修复后的 `mvn -q -pl control-plane -Dtest=EvidenceBundleAssemblerTest test` 通过。规格与代码质量复核均通过。实现提交为 `ae111300`，一致性修复为 `20d8bdb9`。

## 任务 3：可靠记录工具调用成功与失败

**文件：** `AgentScopeRuntime.java`、`AgentScopeRuntimeTest.java`；如 AgentScope 事件没有可靠失败信号，修改其事件适配接口所在文件，并在计划执行时把真实来源类型一并测试。

- [x] **步骤 1：写工具生命周期测试**

验证开始事件为 `tool.called`；正常返回产生 `tool.finished` 且脱敏 payload 含 `ok:true`；工具抛出异常产生 `tool.finished` 且 payload 含 `ok:false` 和稳定错误分类；原始异常消息和工具正文不出现在事件中。

- [x] **步骤 2：运行 Worker 测试确认失败**

运行：`mvn -q -pl agent-worker -Dtest=AgentScopeRuntimeTest test`

预期：失败事件当前被无条件记录为 `ok:true`，或 AgentScope 原始事件缺乏失败语义。

- [x] **步骤 3：从执行结果传播完成状态**

修改事件映射，让完成事件从真实工具执行状态获取 success/failure；只允许输出工具名、布尔结果、稳定错误类别和序号。若上游 AgentScope 事件在工具异常时不发完成事件，则在工具包装器边界捕获并发失败事件，不得根据 `safeMessage` 字符串猜测结果。

- [x] **步骤 4：运行 Worker 生命周期测试**

运行：`mvn -q -pl agent-worker -Dtest=AgentScopeRuntimeTest test`

预期：PASS，成功和失败均可区分且不含原始响应。

- [x] **步骤 5：提交工具事件变更**

提交范围仅限 Worker 运行时与测试，提交说明：`fix(worker): report MCP tool completion outcome`。

**任务 3 执行记录（2026-10-08）：** AgentScope 2.0.1 的真实工具执行结果来自 `ToolResultEndEvent.state`，不是模型工具块结束事件。运行时现按 SUCCESS 与 ERROR/DENIED/INTERRUPTED 生成安全生命周期事件；仅成功工具满足 required tool。HarnessAgent stream 测试覆盖成功、错误、事件序号、稳定错误分类、正文脱敏及 required-tool 成败门禁。`mvn -q -pl agent-worker test` 最终 159 项通过。规格审查提出的事件流测试缺口及质量审查提出的门禁测试缺口均已补齐并复核。实现提交 `91279b10`；事件流测试提交 `39233e28`；required-tool 回归测试提交 `5603f1f0`。

## 任务 4：实现 Control Plane 确定性过程和结果断言

**文件：** `EvaluationService.java`、`EvidenceBundle.java`、`EvaluationServiceTest.java`、报告模型和 Repository。

- [x] **步骤 1：先写断言矩阵测试**

覆盖：Run 状态与 Task phase 分开比对；required tool 必须有成功事件；failed tool 不通过且有失败证据；调用顺序/次数；子任务依赖成功及交接引用；失败重试超限；manifest/PDF 的 MIME、扩展名、大小和 SHA-256 元数据；`%PDF-` 文件头签名需要实际字节，未采集字节时返回 `INCONCLUSIVE`；截断证据返回 `INCONCLUSIVE`。

- [x] **步骤 2：运行服务测试确认失败**

运行：`mvn -q -pl control-plane -Dtest=EvaluationServiceTest test`

预期：FAIL，现有 evaluator 只支持 `phase`、调用摘要、`artifact_integrity` 和 `schema`。

- [x] **步骤 3：实现 typed evaluator 和证据引用**

把每种断言拆为小型 evaluator，统一输出 `EvaluationResultRecord`。只有已验证的必需证据缺失/条件不满足才判 `FAILED`；由于截断或缺失关键证据无法判断时判 `INCONCLUSIVE`。`executionGate` 汇总过程断言，`resultGate` 汇总结果契约；语义指标不参与两个门禁。PDF MIME、扩展名、大小和 SHA-256 属于 manifest 元数据校验；`%PDF-` 文件头签名必须检查文件字节。当前 Evidence Bundle 不采集制品字节，因此不能声称已验证 PDF 签名，该子断言必须返回 `INCONCLUSIVE`。

- [x] **步骤 4：实现硬门禁聚合测试**

运行：`mvn -q -pl control-plane -Dtest='EvaluationServiceTest,EvaluationRecordTest,JdbcEvaluationRepositoryTest' test`

预期：PASS；Task 成功不能抵消工具失败、无效 PDF 或不完整证据。

- [x] **步骤 5：提交 evaluator**

提交范围仅限服务、报告持久化和相关测试，提交说明：`feat(eval): add deterministic process and artifact assertions`。

**任务 4 执行补充记录（2026-10-08）：** Control Plane 可读取 manifest 中的 MIME、扩展名、大小和 SHA-256 元数据，但当前 Evidence Bundle 不读取制品正文。因此 `artifact_pdf` 只在元数据明确不匹配时判 `FAILED`；元数据通过时，因缺少实际字节无法检查 `%PDF-` 文件头，必须判 `INCONCLUSIVE`。不得把元数据校验描述成 PDF 内容或签名验证。

**任务 4 执行记录（2026-10-08）：** 增加 Run status、成功/失败工具、工具顺序/次数、依赖成功、依赖交接、重试上限、artifact integrity 和 PDF 元数据断言，并生成独立 `executionGate`、`resultGate`。工具断言按事件证据完整度及安全 payload 判定；事件序号只用于排序，缺失/脱敏/不完整结果返回 `INCONCLUSIVE`。TaskTree 空树或依赖缺项为 `INCONCLUSIVE`，已知失败依赖为 `FAILED`；handoff 引用与重试次数当前无数据源，显式 `INCONCLUSIVE`。PDF 字节签名尚未采集，按补充契约为 `INCONCLUSIVE`。Control Plane 全量和 Evaluation 专项测试通过；规格审查与代码质量复核通过。提交：`be7e53ec`、`f2f94e7d`、`0bebb617`、`4aaed4c9`。

## 任务 5：让 YAML Runner 遵循同一证据和断言语义

**文件：** `scripts/agentteams_eval.py`、`scripts/test_agentteams_eval.py`、`eval/cases/core.yaml`、`scripts/test_evaluation_quality_contract.py`。

- [x] **步骤 1：为新断言写 Python 单元测试**

覆盖成功/失败工具生命周期、顺序和次数、依赖边/结果引用、事件截断、PDF MIME/签名/大小/SHA-256 以及缺证据时 `INCONCLUSIVE`。断言输出包含安全证据引用，不包含 payload 正文。

- [x] **步骤 2：运行 Python 测试确认失败**

运行：`python3 -m unittest scripts.test_agentteams_eval scripts.test_evaluation_quality_contract -v`

预期：FAIL，Runner 当前不支持新断言或 `INCONCLUSIVE` 语义。

- [x] **步骤 3：扩展观察数据规范化和 evaluator**

为报告保留独立 `taskPhase`、`runStatus`、`manifestStatus`、`evidenceCompleteness`；断言计算不得从 `tool.called` 推出工具成功。artifact integrity 继续移除外部 URL，并对 PDF 做文件头和页数可解析性检查；远端 API 若无法取得字节，则明确输出 `INCONCLUSIVE`，不把元数据等同于内容验证。

- [x] **步骤 4：添加多 Agent PDF 正向和负向 YAML Case**

正向 Case 要求计划拆解、Skill/MCP 成功、依赖结果交接、来源引用、唯一可下载 PDF；负向 fixture 覆盖失败工具、缺依赖、损坏 PDF 和截断事件。离线 Fixture 不得假装成 L5 执行报告。

- [x] **步骤 5：运行离线 Runner 契约测试**

运行：`python3 -m unittest scripts.test_agentteams_eval scripts.test_evaluation_quality_contract scripts.test_l5_agentteams_eval_contract -v`

预期：PASS，报告脱敏并正确区分 PASSED、FAILED、INCONCLUSIVE。

- [x] **步骤 6：提交 Runner 和 Suite**

提交范围仅限 Runner、测试、YAML Suite 和说明文档，提交说明：`feat(eval): align runner with process and result gates`。

**任务 5 执行记录（2026-10-08）：** Runner 已与 Java 门禁语义对齐，覆盖工具生命周期、依赖、截断、制品元数据与本地 PDF 字节检查，并仅输出安全证据引用。当前 Bundle 无结构化来源引用，`source_citation` 明确为 `INCONCLUSIVE`；远端制品没有可读字节时，PDF 签名同样保持 `INCONCLUSIVE`。修复了非 2xx 响应正文泄露风险。Python 离线评测测试 56 项通过；实现及回归提交包括 `67719209`、`9554a46a`、`9a080a7d`、`92d1a6d4`。

## 任务 6：展示门禁与过程证据

**文件：** `console/src/api/types.ts`、`console/src/features/management/ManagementEvaluationPage.tsx`、`console/tests/features/ManagementEvaluationPage.test.tsx`。

- [x] **步骤 1：添加双门禁 UI 测试**

验证详情同时显示 Evaluation 状态、Task 成功、执行门禁、结果门禁；`INCONCLUSIVE` 显示证据不完整原因；每条失败项显示对应安全证据链接；不得将 aggregate score 显示成总门禁结论。

- [x] **步骤 2：运行页面测试确认失败**

运行：`cd console && npm test -- --run tests/features/ManagementEvaluationPage.test.tsx`

预期：FAIL，当前页面没有两个门禁状态字段和证据完整性显示。

- [x] **步骤 3：实现类型及页面展示**

扩充 API 类型并增加两个门禁摘要区。继续使用现有 `isSafeEvidenceRef`、`safeFinding` 做展示净化；缺少字段的历史报告显示“旧报告未记录此门禁”，不得推导出 `PASSED`。

- [x] **步骤 4：运行 Console 页面测试**

运行：`cd console && npm test -- --run tests/features/ManagementEvaluationPage.test.tsx`

预期：PASS，历史报告和新报告均可展示。

- [x] **步骤 5：提交 Console 展示**

提交范围仅限 API 类型、管理评测页面与测试，提交说明：`feat(console): show evaluation execution and result gates`。

**任务 6 执行记录（2026-10-08）：** 页面独立展示 Task 成功、执行门禁、结果门禁和聚合分；旧报告显示“旧报告未记录此门禁”，不确定项按断言类型归入对应门禁。Console 页面测试 4 项、全套 277 项与生产构建通过；实现提交 `075cbbce`，代码审查通过。

## 任务 7：L5 正向及负向验收

**文件：** `scripts/run-l5-agentteams-eval.py`、`scripts/test_l5_agent_evaluation_process_result.py`、`eval/cases/core.yaml`、真实运行生成的 `eval/reports/` 脱敏报告。

- [x] **步骤 1：写 L5 入口契约测试**

验证入口拒绝缺少短期 Token、目标项目/团队不匹配以及缺少可执行权限；报告含 Task/Run id、Case/evaluator 版本、双门禁、结果制品引用和每项安全证据引用；不得序列化 Token 或下载 URL。

- [x] **步骤 2：运行离线 L5 契约测试**

运行：`python3 -m unittest scripts.test_l5_agent_evaluation_process_result -v`

预期：在加入检查前 FAIL；离线运行不得发起网络连接。

- [x] **步骤 3：执行离线全套契约验证**

运行：`python3 -m unittest scripts.test_agentteams_eval scripts.test_evaluation_quality_contract scripts.test_l5_agentteams_eval_contract scripts.test_l5_agent_evaluation_process_result -v`，然后运行 `mvn -q -pl control-plane,agent-worker test` 和 `cd console && npm test -- --run`。

预期：所有离线测试 PASS。

**任务 7 当前状态（2026-10-08）：** 用户已手动修正 L5 的两个 systemd drop-in；k3s 为 active、节点 Ready，节点 IP 为 `192.168.122.55`，重启计数归零。入口已加入目标 scope、项目权限与 team 只读预检，只要求目标范围内至少一个 Worker Ready；从 Team API 取得 UUID 后绑定任务 `spec.teamId`，并支持 `--case` 单例运行。Control Plane 与 Team-a 的 Leader、两个 Executor 已滚动到从提交 `89aad28a` 构建的 amd64 镜像；Control Plane 的 MinIO 预签名地址已改为 `192.168.122.55:30090`。镜像包与 Runner 已传到 L5，PDF 下载独立校验脚本只保存安全元数据。本地离线测试 63 项通过；入口及下载校验提交 `ec9ba1ba`、`c0f64df4`、`424bd906`、`5dfe8ab3`、`9080cd5e`。真实正向报告尚未生成，等待用户在 L5 终端设置短期 Token 并执行；负向 L5 变体及验收记录仍待完成。当前 Control Plane 镜像和预签名环境变量由受控 `kubectl` 滚动更新，Helm release 的旧值仍需在正式持久化部署时同步。

- [ ] **步骤 4：运行真实 L5 正向 Case**

按 `eval/README.md` 使用现有受控 L5 入口和获授权的短期 Token，只跑新增多 Agent PDF Case，确认 PDF 可通过交付入口下载、解码为 PDF 且可解析页面。报告存入 `eval/reports/` 前检查不含凭证、预签名 URL 和原始工具正文。

- [ ] **步骤 5：运行 L5 负向变体**

在隔离测试数据中分别注入工具失败、缺依赖结果、无效 PDF 和事件截断，确认结果为 `FAILED` 或 `INCONCLUSIVE`，而非通过；结束后删除本测试创建的任务和临时身份数据。

- [ ] **步骤 6：完成验收记录**

更新 `eval/README.md`，记录日期、Suite/Case 版本、代码版本、各门禁结果和脱敏报告路径。只记录真实执行结果，不用离线 Fixture 替代 L5 结果。

## 任务 8：阶段二语义评测（独立发布门）

语义 Judge 依赖可追溯检索上下文、安全过滤和人工校准样本；在阶段一验收通过后单独启动，不得阻塞或更改确定性门禁。

**文件：** `eval/deepeval_adapter.py`、`eval/rubrics/core.yaml`、`eval/deepeval_calibration.py`、`eval/README.md`、Control Plane 对应 semantic evaluator 与测试（只有决定在 API 内运行 Judge 后再新增服务文件）。

- [ ] **步骤 1：冻结人工标注样本和 Rubric 版本**，每例记录匿名 ID、期望标签、可用证据种类和标注者一致性，不复制敏感原文。
- [ ] **步骤 2：编写校准契约测试**，无检索上下文时 Faithfulness 必须为 `INCONCLUSIVE`；未达到人工校准条件的指标不能进入硬门禁。
- [ ] **步骤 3：扩展 Judge adapter**，记录 provider、model、prompt/rubric 版本、分数理由和校准状态，并验证传给 Judge 的输入经过 Evidence Bundle 权限和脱敏过滤。
- [ ] **步骤 4：运行离线语义契约测试**，运行 `python3 -m unittest scripts.test_deepeval_contract scripts.test_deepeval_calibration -v`，预期 PASS；默认 CI 不安装 Judge 可选依赖。
- [ ] **步骤 5：在受控评测环境执行人工校准集**，报告语义建议分数和意见分歧；只有满足预先约定的校准标准后，再单独提出是否加入门禁的变更。

## 规格覆盖自检

- 双入口共享 Evidence Bundle：任务 2、任务 5。
- Task phase、Run status、manifest status 分离：任务 2、任务 4、任务 5。
- 子任务依赖、顺序、重试和策略证据：任务 4、任务 5；执行前须核实当前树记录是否包含重试与策略字段，缺失时明确返回 `INCONCLUSIVE`，不可猜测。
- 工具成功与失败：任务 3、任务 4、任务 5。
- PDF 可下载、签名、可解析页数和摘要：任务 4、任务 5、任务 7。
- 截断/缺证据状态：任务 2、任务 4、任务 5。
- 语义 Judge、人工校准、独立展示与门禁隔离：任务 8；阶段二单独验收。
- 脱敏、租户权限和安全证据引用：约束、任务 2、任务 5、任务 6、任务 7、任务 8。
- L5 多 Agent 正向与负向集成：任务 5、任务 7。
- 用量/延迟不混入质量分数：本计划不扩展用量评分，只保留现有 Usage/Trace 链接。
