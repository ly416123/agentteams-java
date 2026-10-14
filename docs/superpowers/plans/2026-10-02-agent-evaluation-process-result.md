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

**任务 7 当前状态（2026-10-08）：** 用户已手动修正 L5 的两个 systemd drop-in；k3s 为 active、节点 Ready，节点 IP 为 `192.168.122.55`，重启计数归零。入口已加入目标 scope、项目权限与 team 只读预检，只要求目标范围内至少一个 Worker Ready；从 Team API 取得 UUID 后绑定任务 `spec.teamId`，并支持 `--case` 单例运行。Control Plane 与 Team-a 的 Leader、两个 Executor 已滚动到从提交 `89aad28a` 构建的 amd64 镜像；Control Plane 的 MinIO 预签名地址已改为 `192.168.122.55:30090`。镜像包与 Runner 已传到 L5，PDF 下载独立校验脚本只保存安全元数据。本地离线测试 63 项通过；入口及下载校验提交 `ec9ba1ba`、`c0f64df4`、`424bd906`、`5dfe8ab3`、`9080cd5e`。首次真实正向尝试未通过；负向 L5 变体及验收记录仍待完成。当前 Control Plane 镜像和预签名环境变量由受控 `kubectl` 滚动更新，Helm release 的旧值仍需在正式持久化部署时同步。

**身份预检记录（2026-10-08）：** 用户持有的 `quota-admin` Token 首次预检无法列出项目。只读检查确认 Keycloak 用户处于启用状态、目标项目唯一且 ACTIVE，但其当前 subject 缺少 `tenant-a/project-a` 成员关系。经用户确认，按 L5 身份迁移规则只为该 subject 新增一条 `ADMIN/ACTIVE` 项目成员记录；事务提交后只读核验为一条。首次真实尝试结束后，已按 subject、项目、角色、状态、版本及创建时间精确删除该临时成员记录，复核剩余数量为 0。Helm overlay 已对齐评测镜像及新 IP，chart 在本机和 L5 lint 通过，release 尚未升级；线上 Keycloak 与 Control Plane 仍使用旧 issuer，须在后续部署中统一切换。

**首次真实正向尝试（2026-10-08，未通过）：** 短期 Token 的有效期为 300 秒，项目权限修正后须重新获取。任务 `ed74a64f-99c0-4aa9-93f7-fd682a79bf55` 创建成功，但 L5 镜像的调度器只扫描最早 16 个 QUEUED 任务，本任务排第 17；将在线批量大小调至 32 后进入 RUNNING。Runner 在排队期间达到默认 180 秒上限，脱敏报告保存在 `eval/reports/2026-10-08-l5-multi-agent-pdf-timeout.json`。Worker 上传 `output.md`、`result.json`，未生成 PDF；过程事件只出现 `glob_files`、`list_files`、`memory_search`、`session_list`，没有要求的搜索和 Skill 工具。父任务经租约恢复进入 `WAITING_SUBTASKS`，两个子任务要求未在 Team-a Executor 注册的能力标签，分别停在 QUEUED 和 DRAFT。用户已通过受控 API 取消父任务；只读核验父任务及两个子任务均为 `CANCELLED`，活动租约为 0。该次执行不能作为正向通过证据；需修复能力约束和 PDF 产出链路后重跑。

**重跑前本地修复（2026-10-08，尚未部署 L5）：** 正向 Case 现在分别声明 Leader 规划工具、Executor 的 `web_search`/`load_skill_through_path` 和聚合 PDF 工具；子任务从父任务 `inputJson.executorMcpTools` 取得本轮 `requiredMcpTools`，不继承父任务 prompt。Leader 提示明确要求没有 Executor 能力注册表时不要生成 `requiredCapabilities`，避免模型臆造能力标签；Runner 未显式设置 `--timeout-seconds` 时按 Case `limits.timeoutSeconds` 等待（本 Case 300 秒）。回归验证：`SubtaskDelegationServiceTest` 7 项、`AgentScopeRuntimeTest`、Python 41 项通过，`git diff --check` 及 Python 编译通过。尚需构建并部署新镜像、同步 Runner 与 Case，再执行真实 L5 正向和负向验收。

**L5 只读复核（2026-10-08）：** SSH 只读检查确认 Control Plane 当前运行 `agentteams-control-plane:l5-eval-process-result-20261008-89aad28a-amd64`，故上述镜像实际上已部署；Keycloak、Control Plane 和 Manager 仍一致使用 `http://192.168.1.13:30082/realms/agentteams`，Keycloak discovery 也公布该 issuer。L5 可访问地址已是 `192.168.122.55`，但单凭这点不能解释 API 的 HTTP 401，因为当前三方 issuer 相互一致。401 发生在项目列表请求的 OIDC 校验阶段，项目成员关系不会令无效 JWT 变为有效。仓库随附的 `agentteams-api` Realm 配置含 audience、tenant、project、team 和 permissions mapper，示例 `quota-admin` 用户也定义了这些属性；这不能证明 L5 当前导入的 Realm 和用户状态相同。下一步先检查新 Token 的脱敏 `iss`、`aud` 和 `tenant/project/team/permissions` 声明是否符合 Control Plane 校验契约，再决定是否调整 Keycloak Client Scope 或部署配置。不得输出或传递 Token。

**身份与 process-events 根因更正（2026-10-08）：** 后续直接读取 Control Plane 确认短期 Token 的 issuer、audience 及目标范围声明有效，`GET /api/v1/projects?q=project-a` 返回 200。先前 `GET .../process-events` 的 403 是执行上下文需要组织、租户、项目三层成员关系，而临时只补项目层；不是 issuer 或 JWT 校验问题。为真实重跑临时新增三层成员关系，执行后全部删除，数据库复核三层记录均为 0。此前已完成的任务 `872db1be-4d78-42ee-bd55-2f23b46f76cb` 不是多 Agent 用例证据：没有子任务，工具事件仅含 `glob_files`、`list_files`、`memory_search`、`session_list`。

**真实正向 Case 重跑（2026-10-08，未通过）：** 按 `multi-agent-pdf-positive` 在 L5 重新执行，任务 `461523ee-68d3-4c9f-ad47-4fc3d69f8386` 达到 `SUCCEEDED`，但评测分数为 0.25，不能作为验收通过。依赖成功与交接为 `INCONCLUSIVE`（未形成依赖树）；`web_search` 和 `load_skill_through_path` 均未调用；结构化来源引用证据不可用；PDF 断言失败，结果清单仅有 `output.md` 和 `result.json`。脱敏报告已保存至 `eval/reports/2026-10-08-l5-multi-agent-pdf-rerun.json`，报告不含 URL 或 Bearer Token。下一步应查 L5 实际运行的 Worker 镜像、Team 工具注册和任务输入是否包含本地修复，再决定同步/滚动部署；部署后再跑正向验收。负向变体及最终验收记录仍待完成。

**重跑差异定位（2026-10-08）：** SHA-256 核验发现 L5 的 `eval/cases/core.yaml`、`scripts/run-l5-agentteams-eval.py`、`scripts/agentteams_eval.py` 均与当前工作区不同。L5 实际用例仍是旧版单段 prompt，落库任务 `inputJson` 只有 `prompt`，没有 `requiredMcpTools`、`executorMcpTools` 或 `aggregateMcpTools`；这直接解释了 Leader 未规划子任务，Worker 也未获知必须调用搜索、Skill 和 PDF 工具。Team-a 已 Ready Worker 包括本次调度用的 `l5-eval-process-result-20261008-89aad28a-amd64`，其 Worker 配置修订仍为 `l5-agentscope-deepseek-20260929-r2`。本地未提交改动还包含 Control Plane 子任务工具要求传递，以及 Worker MCP 工具注册和聚合 PDF 提示；需先在 `main` 当前工作树构建 Control Plane/Worker amd64 镜像并部署到 L5，再同步 Runner、Evaluator 和 Case，复核实际创建的任务输入后重跑。部署前沿用 L5 当前 amd64 镜像分发链，并确认资源状态。

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

**第二次真实正向尝试与根因（2026-10-08，未通过）：** 后续已在 `main` 当前工作树构建并部署 Control Plane/Worker amd64 镜像，更新 Team-a 本轮 Leader/Executor Worker 镜像，并同步 Runner、Evaluator 和 Case；本地 Helm overlay 的镜像标签也已对齐，但线上 release 尚未 Helm upgrade。最新真实运行创建了两个子任务；子任务尝试 `load_skill_through_path` 时失败，另一个保持 DRAFT，父任务未在入口超时内结束。直接对 L5 `agentteams-mcp-tools` 做 MCP initialize 和 `tools/list`，返回工具列表不含 `load_skill_through_path`；Team-a revision 48 的 Leader 与 Executor Worker CR 均为 `skillRefs: []`，revision resource bindings 为空。另一方面，L5 上 `industry-research` 已发布版本 0.0.5，审核 APPROVED、扫描 PASSED、包上传 COMPLETED。MCP `tools/list` 不包含 `load_skill_through_path` 符合该服务的职责边界：Skill loader 由 Worker 内 AgentScope `FileSystemSkillRepository` 提供。Worker 只在配置快照含 Skill artifact directory 时安装该 repository；当前 Team revision 没有 `skillRefs`/resource binding，因此 Worker 未安装 Skill repository，才是更符合代码证据的根因。不能据 Skill 包已发布就推断当前 Worker 可执行它。最新子任务状态仍需在 L5 复核后定点取消；当前本地 kube context 是 kind，SSH 对 `192.168.122.55` 返回 `Permission denied (publickey,password)`，因此本轮不能远程核实/取消任务。已检查本地可用的 2026-10-08 timeout 报告无 URL 或凭证字段，但其中 Task ID 与运行摘要中记录的最新父任务 ID 不一致，不能将其当成最新报告或据此取消任务。已通过有权限的 L5 终端确认并取消非终态父任务。下一步应在隔离的验收 Team/AgentSpec 中绑定精确的 `industry-research@0.0.5` Skill 和所需 MCP，再通过正式 API 发布并部署新 Team revision；先验证 Worker 配置 ACK 与 Skill repository 加载，再重跑完整 PDF Case，避免扩展共享 team-a 的能力。正向与负向验收仍未完成，Task 7 不得标为完成。

**挂起任务清理（2026-10-08）：** 在 L5 直接复核父任务 `4e4205a5-26bb-4945-a9a3-91bcbe0c7bcb` 为 `WAITING_SUBTASKS`，研究子任务 `b7c1f3a2-9d84-4e51-8a67-2f0c5b91d7e3` 为 `FAILED`，报告子任务 `c4e9a8d1-3b62-47f0-9c25-6e7a1b0f8d42` 为 `DRAFT`。使用新短期 Token 和 API 当前版本取消父任务，API 返回 `CANCELLED`；随后复核父任务与报告子任务均为 `CANCELLED`，研究子任务保留 `FAILED` 终态。操作前确认 quota-admin 对应的组织、租户、项目成员关系均不存在；脚本仅临时新增缺失行，并在结束时按精确 scope 删除，清理命令成功。取消不改变该评测失败结论；Skill 工具注册和 Team Skill 绑定缺口仍须解决，之后才能重跑正向 Case。此前错误输出暴露的 quota-admin 与数据库凭据应轮换，轮换值不要发送到聊天。


**Skill 执行路径更正（2026-10-08）：** MCP Tools 服务不负责注册 `load_skill_through_path`；该工具来自 AgentScope Worker 的 Skill repository。`ConfiguredAgentScopeHarnessFactory` 仅当配置快照中有 Skill directory 时调用 `builder.skillRepository(new FileSystemSkillRepository(...))`，目录则由有效的 Skill resource binding 下载和物化。`agentteams-mcp-tools` 的 `tools/list` 缺少 Skill loader 属预期；`AgentScopeMcpRuntimePort.PLATFORM_TASK_TOOLS` 仅约束平台任务编排工具，不应把 Skill loader 加入 MCP allowlist。现有 Team-a revision 48 的 Leader/Executor 清单 `skillRefs=[]` 且 resource bindings 为空，所以实际任务无 Skill repository。后续采用独立验收 Team/AgentSpec 绑定 `industry-research@0.0.5` 和 `agentteams-web-search`，通过受支持的 AgentSpec 发布、Team revision 发布/部署流程验证后再运行正向 Case。

**隔离 Team 实际部署与 L5 冒烟（2026-10-08，运行成功；确定性报告未通过）：** 新建隔离 Team `l5-agentteams-pdf-eval-20261008-1705-6d4a`（`7335a7df-fde4-41de-983c-f5045d1486f9`），包含独立 Leader `9742f546-eac9-46a1-886a-1b9dffb2d299` 和 Executor `6cecfa6b-ad94-42b1-ae39-15547f998539`。AgentSpec 使用 L5 已配置凭证的 DeepSeek Provider；发布的 Team revision 1 部署 `635e1624-97f6-40aa-989a-2d722415b3c4` 状态为 `SUCCEEDED`，两个成员均收到配置 ACK。Executor 的最终配置绑定 `industry-research@0.0.5`（revision `0.0.5`）和 `agentteams-web-search`；Leader 绑定 `agentteams-mcp-tools` 以生成 PDF。Team revision 的 `resourceBindings` 数量为 0：当前 Team 资源目录表没有注册入口，实际资源 pin 来自各成员已发布 AgentSpec 的 manifest，并已在部署后读取 Worker 配置核实。共享 `team-a` 未修改。

只验证 Skill 的委派冒烟中，父任务 `18802eeb-ac81-44ba-b908-ad9751f40593`、两个子任务 `b1f2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d` 与 `c2a3b4c5-6d7e-4f8a-9b0c-1d2e3f4a5b6c` 均为 `SUCCEEDED`；两个子任务的 `load_skill_through_path` 完成事件均为 `ok=true`。父任务 Runner 报告仍为 `passed=false`，因为它只读取父任务事件，未把子任务工具事件纳入 `required_tool` 断言。原始报告保存在 `eval/reports/2026-10-08-l5-industry-research-skill-smoke-delegated.json`；此前直接让 Leader 加载 Skill 的失败报告也保留在 `eval/reports/2026-10-08-l5-industry-research-skill-smoke.json`，该结果符合本 Team 的 Leader 无 Skill 绑定设计。

完整 PDF Case 的父任务 `9b6895db-4c91-4dbd-a703-b7e985259281`、Run `500e10e8-d3b4-4f5d-8ffd-ea1dd3d8acbf` 和研究/起草子任务 `6f2a1c94-3d7b-4e58-9a01-b2c7e5d41f88`、`b8d3f07e-91c2-4a6f-8e36-5c0a7d29e4b1` 均达到 `SUCCEEDED`。两个子任务的 `load_skill_through_path` 与 `web_search` 完成事件均为 `ok=true`。Leader 调用 `create_task_pdf_artifact` 上传 `quarterly-project-report.pdf`；从 Task File 下载接口读取到 28,883 字节，SHA-256 为 `567a3cca0b147180ee9c6480d27e1a3be8c08527de9e74e1713ca16b5432518f`，`%PDF-` 签名有效且可解析为 8 页。真实运行报告保留 `passed=false` 于 `eval/reports/2026-10-08-l5-multi-agent-pdf-isolated.json`：Task Tree 投影仍把已成功子任务显示为 `PENDING`，父 Run 事件不含子任务工具事件，来源引用结构化证据为 `INCONCLUSIVE`，Task File 中的 PDF 未进入 Result Manifest，所以 `artifact_pdf` 从结果清单查询时显示缺失。不能把“任务和 PDF 文件实际成功”描述为“确定性评测通过”。

首次尝试使用 DashScope 的临时 Leader 因 Worker Template 未注入 `DASHSCOPE_API_KEY` 而 CrashLoop；该 Worker CR 已删除，改用 L5 已配置的 DeepSeek。未使用的两个 DashScope AgentSpec 已停用。直接 Agent API 创建的临时记录仍为 `PROVISIONING`，因 API 拒绝对该阶段执行 terminate（HTTP 409）；没有对应 Worker CR 或 Pod。临时三层成员关系在每次脚本结束时均已删除并核验。

**后续工作：** 隔离资源与 Worker 绑定验收已完成，完整工作流的真实执行也成功；Task 7 仍未完成。完成其余证据来源实现和回归后，需同步 Runner 至 L5，重评正向 PDF Case、负向变体和最终契约。当前三个真实 Runner 报告均保留失败或不确定状态，不得将其改标为通过。

**评测证据修复进展（2026-10-08）：** `JdbcTaskTreeRepository.find` 与 `findLatestForParent` 现按 scope 已过滤的 Run 投影 LEFT JOIN 真实 Task 行；`tasks.phase` 是可用时的当前状态来源，DRAFT/QUEUED/PAUSED 继续显示 PENDING，活跃阶段显示 RUNNING，REJECTED 映射 FAILED，无 Task 行时回退到投影值。新增 PostgreSQL 回归用例先复现旧的 PENDING 错误，再验证 SUCCEEDED 真值，`JdbcTaskTreeRepositoryTest` 6 项通过。

L5 Runner 现在沿 Task Tree 读取直属子任务状态和各自 Run 的过程事件，并在 Evidence Bundle 中按独立 `subtaskEvents` 保留真实 Task/Run ID；工具生命周期断言可检查这些事件，父 Run 内工具顺序仍只使用父事件。Runner 还会对 `taskFiles` 中已知 OUTPUT 清单读取文件账本和受鉴权 `/content` 字节，只对 PDF 且不超过 50 MiB 的文件执行 SHA-256、签名、EOF 和页树校验；报告不保存字节、Presigned URL 或凭证。缺少文件内容时，若结果仍报告可用 PDF Task File，则断言为 INCONCLUSIVE 而非误报“文件缺失”。

**2026-10-08 来源证据实现更新：** `source_citation` 不再仅因缺少结构化字段而固定为 `INCONCLUSIVE`。搜索 MCP 从真实搜索结果解析 URL 元数据，并通过 Worker 注入的 task/attempt/lease/call 上下文写入 Control Plane；Control Plane 仅接受活动 Run 的有效 attempt/lease，并对查询按 organization、tenant、task、run 过滤。Java Evaluation 与 Python Runner 都要求完整搜索回执覆盖和答案中的显式 URL 精确命中当前 Run；缺失或不完整为 `INCONCLUSIVE`，完整证据下未命中为 `FAILED`。报告保留 `task-source:<callId>:<sha256>` 引用和覆盖计数，不保留原始查询、回答正文或 URL。此功能尚待完成部署及 L5 正负向真实验收。

验证：`python3 -m unittest scripts.test_agentteams_eval scripts.test_l5_agentteams_eval_contract scripts.test_l5_agent_evaluation_process_result scripts.test_source_citation_contract` 共 56 项通过；`mvn -pl control-plane -Dtest=JdbcTaskTreeRepositoryTest test -q` 7 项通过；`py_compile` 与 `git diff --check` 通过。新增回归覆盖子任务成功事件、Task File PDF 和依赖结果引用。尚未把 Runner 同步到 L5，也未重评已有任务，因此现存 L5 报告仍为失败/不确定。来源引用仍没有已核实的结构化持久化契约；工具事件目前只记录工具名、调用 ID、成功状态和错误类别，不含搜索结果 URL 或来源 ID。下一步先确定并实现可追溯的来源证据契约，再部署并运行正向与负向集成验收。

**依赖交接证据补充（2026-10-08）：** Task Tree 节点新增安全字段 `dependencyResultRefs`。Control Plane 仅在依赖子任务 `spec.inputJson.dependencyResults` 中确实存在对应依赖的非空 `result` 时，才投影稳定引用 `task-result:<dependencyId>`；不返回结果正文。连接 Task 行时同时校验其与 Run 根任务或当前父子关系一致，避免仅凭可控投影 ID 读取其他任务记录。评测 Evidence Bundle 仅保留与 `dependencyIds` 匹配且格式精确的稳定引用，过滤不匹配引用及任何依赖结果正文。
