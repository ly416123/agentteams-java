# Evaluation / Quality Plane 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans。步骤使用复选框（- [ ]）语法跟踪进度。

**目标：** 把现有离线 Agent Evaluation Runner 提升为可消费生产执行证据的 Evaluation / Quality Plane，并独立保存 Suite、Case、Run、Result、Finding 和 Report，不改变 Task 业务状态机。

**架构：** Evaluation Plane 以 Evidence Bundle 为唯一输入，读取 Task Snapshot、Task Event、Process Event、Task Tree、Decision、Tool Call Summary、Result Manifest、Artifact 引用和权限结果。确定性断言是硬门禁，语义评测是可选增强；Evaluation Pass 与 Task success 分开持久化。

**技术栈：** Java 21、Spring Boot、JDBC、Flyway、Jackson、JUnit 5、Python 3、PyYAML、现有 unittest 和可选 DeepEval。

---

## 文件清单

- 创建：control-plane/src/main/resources/db/migration/V98__evaluation_quality_plane.sql
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationSuiteRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationCaseRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationRunRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationResultRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationReportRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvidenceBundle.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/JdbcEvaluationRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvidenceBundleAssembler.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/evaluation/EvaluationService.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/api/EvaluationController.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/evaluation/EvidenceBundleAssemblerTest.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/evaluation/EvaluationServiceTest.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/api/EvaluationControllerTest.java
- 修改：scripts/agentteams_eval.py、scripts/test_agentteams_eval.py、eval/deepeval_adapter.py、eval/rubrics/core.yaml、eval/README.md
- 创建：scripts/test_evaluation_quality_contract.py
- 创建：console/src/api/evaluations.ts、console/src/features/management/ManagementEvaluationPage.tsx
- 修改：console/src/api/types.ts、console 的实际路由配置文件

### 任务 1：定义 Evaluation 领域数据结构和迁移

**文件：** V98、五个 Record、EvidenceBundle、Repository 和测试。

- [ ] **步骤 1：编写失败测试**

覆盖 Suite version、Case id、evaluatorVersion、Task/ExecutionRun 关联、metric/score/threshold/evidenceRefs、Report 维度分数，以及 Evaluation 状态约束。验证 Task success 与 Evaluation status 同时存在且互不覆盖。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='Evaluation*Test' test
~~~

预期：FAIL，因为记录、迁移和 Repository 尚不存在。

- [ ] **步骤 3：创建 V98**

V98 创建 evaluation_suites、evaluation_cases、evaluation_runs、evaluation_results、evaluation_reports；所有 JSON 字段校验 object/array 类型；evaluation_runs 唯一键为 suite_id + case_id + execution_run_id + evaluator_version；为 tenant、task、execution_run、status 建索引。

~~~sql
CREATE TABLE evaluation_runs (
    id UUID PRIMARY KEY,
    suite_id UUID NOT NULL REFERENCES evaluation_suites(id),
    case_id UUID NOT NULL REFERENCES evaluation_cases(id),
    task_id UUID NOT NULL,
    execution_run_id UUID NOT NULL,
    evaluator_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','PASSED','FAILED','INCONCLUSIVE')),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (suite_id, case_id, execution_run_id, evaluator_version)
);

CREATE TABLE evaluation_results (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    metric TEXT NOT NULL,
    score NUMERIC(8, 5),
    threshold NUMERIC(8, 5),
    status TEXT NOT NULL,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    finding_summary TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL
);
~~~

- [ ] **步骤 4：实现 Repository 和记录类型**

固定接口：

~~~java
EvaluationRunRecord createRun(ExecutionContext context, CreateEvaluationRun command);
Optional<EvaluationRunRecord> findRun(ExecutionContext context, UUID runId);
void saveResults(ExecutionContext context, UUID runId, List<EvaluationResultRecord> results);
EvaluationReportRecord saveReport(ExecutionContext context, EvaluationReportRecord report);
~~~

createRun 依赖数据库唯一键保证同一执行证据和评测版本不重复创建。

- [ ] **步骤 5：运行测试验证通过**

运行同一条 Maven 命令，预期领域校验、状态和 Repository 测试 PASS。

- [ ] **步骤 6：提交**

~~~bash
git add control-plane/src/main/resources/db/migration/V98__evaluation_quality_plane.sql control-plane/src/main/java/io/agentteams/controlplane/evaluation control-plane/src/test/java/io/agentteams/controlplane/evaluation
git commit -m "feat(eval): add evaluation quality plane persistence"
~~~

### 任务 2：组装 Evidence Bundle

**文件：** EvidenceBundle.java、EvidenceBundleAssembler.java、现有 TaskProcessEventService、TaskTreeService、TaskDecisionRecordService、TaskResultManifestService 和测试。

- [ ] **步骤 1：编写失败测试**

使用 fake repositories 验证：

- bundle 包含 Task Snapshot、Run、Process Events、Task Tree、Decisions、Result Manifest 和 ArtifactRefs；
- INTERNAL 之外的调用摘要按权限过滤；
- payload 超过 16 KiB 时只保留 payloadRef；
- token、cookie、api key、预签名 URL 不进入 bundle；
- 缺少 Result Manifest 时返回 INCONCLUSIVE 输入标记。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest=EvidenceBundleAssemblerTest test
~~~

预期：FAIL，因为 EvidenceBundleAssembler 尚不存在。

- [ ] **步骤 3：实现证据对象和脱敏规则**

固定 EvidenceBundle：

~~~java
public record EvidenceBundle(
        UUID taskId,
        UUID executionRunId,
        TaskSnapshot taskSnapshot,
        List<UnifiedEventEnvelope> taskEvents,
        List<TaskProcessEvent> processEvents,
        List<TaskTreeNode> taskTree,
        List<TaskDecisionRecord> decisions,
        List<ToolCallSummary> toolCallSummaries,
        TaskResultManifest resultManifest,
        List<ArtifactRef> artifactRefs,
        List<ApprovalRecord> approvalHistory,
        AgentMetadata agentMetadata,
        List<PolicyOutcome> policyOutcomes) {}
~~~

Assembler 只接受 ExecutionContext；每个 Repository 查询都带 context。敏感字段通过现有 SensitiveFieldPolicy 和 Artifact 下载策略过滤，禁止把预签名 URL写入评测持久化。

- [ ] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期证据完整性、可见性和脱敏测试 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/evaluation control-plane/src/test/java/io/agentteams/controlplane/evaluation
git commit -m "feat(eval): assemble sanitized execution evidence"
~~~

### 任务 3：实现确定性评测服务和 API

**文件：** EvaluationService、EvaluationController、EvaluationServiceTest、EvaluationControllerTest。

- [ ] **步骤 1：编写失败测试**

覆盖创建 QUEUED、phase/tool/artifact/schema 断言、PASSED、FAILED、INCONCLUSIVE、evidenceRefs 脱敏，以及 EvaluationService 不调用 TaskService 状态转移。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='EvaluationServiceTest,EvaluationControllerTest' test
~~~

预期：FAIL，因为服务和端点尚不存在。

- [ ] **步骤 3：实现服务和端点**

固定端点：

~~~text
POST /api/v1/evaluations/runs
GET  /api/v1/evaluations/runs/{runId}
GET  /api/v1/evaluations/runs/{runId}/report
~~~

服务流程：

~~~text
createRun → assembleEvidence → deterministic assertions
          → save EvaluationResult → save EvaluationReport
          → update EvaluationRun status
~~~

语义评测不在本任务自动调用；请求声明 semanticRequested 但没有受控 evaluator 时返回 INCONCLUSIVE。只有显式 Quality Gate 才能调用 Task 操作，默认实现完全只读。

- [ ] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期评分、权限、脱敏和“不改变 Task 状态”测试 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/evaluation control-plane/src/main/java/io/agentteams/controlplane/api/EvaluationController.java control-plane/src/test/java/io/agentteams/controlplane
git commit -m "feat(eval): expose deterministic evaluation runs"
~~~

### 任务 4：升级现有 Python Runner 和语义适配

**文件：** scripts/agentteams_eval.py、scripts/test_agentteams_eval.py、eval/deepeval_adapter.py、eval/rubrics/core.yaml、eval/README.md、scripts/test_evaluation_quality_contract.py。

- [ ] **步骤 1：编写失败测试**

Python 测试覆盖：

1. Runner 将 Task API 响应归一化为 Evidence Bundle JSON。
2. report 同时包含 taskSuccess 和 evaluationStatus。
3. 结果只保存 artifactId/storageRef 的非敏感引用，不保存预签名 URL。
4. deterministic assertion 失败时 evaluationStatus=FAILED。
5. DeepEval 只在显式配置时加载，未安装时返回明确错误。
6. rubric 阈值不满足时输出 INCONCLUSIVE/FAILED 及证据引用。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
python3 -m unittest scripts/test_agentteams_eval.py -v
python3 -m unittest scripts/test_evaluation_quality_contract.py -v
~~~

预期：新增 Evidence Bundle 和 report 状态断言 FAIL。

- [ ] **步骤 3：实现归一化和提交接口**

新增：

~~~python
def build_evidence_bundle(task, run, events, tree, decisions, result):
    return {
        "taskId": task["id"],
        "executionRunId": run["id"],
        "taskSnapshot": sanitize_task(task),
        "taskEvents": normalize_events(events),
        "taskTree": tree,
        "decisions": decisions,
        "toolCallSummaries": summarize_tools(events),
        "resultManifest": sanitize_result(result),
        "artifactRefs": artifact_refs(result),
    }
~~~

CLI 增加 --evaluation-run-id 和 --evidence-output；默认仍运行离线确定性评测。调用 Evaluation API 使用现有 token-env 和独立幂等键，失败不能把 Token 写入报告。

DeepEval 适配器保持现有公共函数输入兼容；将 metric score、threshold 和 evidenceRefs 写入 EvaluationResult 结构。

- [ ] **步骤 4：运行测试和编译检查**

运行：

~~~bash
python3 -m unittest scripts/test_agentteams_eval.py scripts/test_evaluation_quality_contract.py -v
python3 -m py_compile scripts/agentteams_eval.py scripts/test_agentteams_eval.py eval/deepeval_adapter.py
python3 scripts/agentteams_eval.py --help
~~~

预期：测试、编译和 CLI 帮助全部 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add scripts/agentteams_eval.py scripts/test_agentteams_eval.py eval/deepeval_adapter.py eval/rubrics/core.yaml eval/README.md scripts/test_evaluation_quality_contract.py
git commit -m "feat(eval): normalize evidence and report quality results"
~~~

### 任务 5：Console 评测查看

**文件：** console/src/api/evaluations.ts、console/src/api/types.ts、console/src/features/management/ManagementEvaluationPage.tsx、实际路由配置文件和测试。

- [ ] **步骤 1：编写失败测试**

测试页面显示 Evaluation Run 与 Task success 两个独立状态、aggregateScore、维度分数、finding、evidenceRefs，并拒绝渲染 token、cookie、预签名 URL；无权限时只显示 ErrorState。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
npm --prefix console test -- --run
~~~

预期：新 API 和页面测试 FAIL。

- [ ] **步骤 3：实现 API 和页面**

evaluations.ts 提供 list/get/run/report API；页面只请求当前管理用户有权访问的 tenant/project 范围。报告详情通过 evidenceRefs 跳转到现有 Task Process/Result/Artifact 页面，不复制原始敏感内容。

- [ ] **步骤 4：运行测试验证通过**

运行：

~~~bash
npm --prefix console test -- --run
npm --prefix console run build
~~~

预期：Console 单测和生产构建 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add console/src/api/evaluations.ts console/src/api/types.ts console/src/features/management console/tests
git commit -m "feat(console): add evaluation quality view"
~~~

### 任务 6：项目回归和验收

**文件：** evaluation 测试、eval/README.md、验收报告。

- [ ] **步骤 1：编写集成测试**

流程：

1. 创建成功 Task 和成功 Result Manifest。
2. 组装 Evidence Bundle。
3. 执行 deterministic suite。
4. 验证 Evaluation PASS。
5. 将同一 Task 的评测结果设为 FAILED，确认 Task phase 不变化。
6. 重复相同 evaluatorVersion 返回同一 Evaluation Run。
7. 未配置语义评测时返回 INCONCLUSIVE，不阻断确定性结果。

- [ ] **步骤 2：运行定向测试**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='Evaluation*Test,EvidenceBundleAssemblerTest' test
python3 -m unittest scripts/test_agentteams_eval.py scripts/test_evaluation_quality_contract.py -v
npm --prefix console test -- --run
~~~

预期：全部 PASS。

- [ ] **步骤 3：执行离线和受控 L5 评测**

报告只提交 suite/case/status/score/evidenceRefs，不提交 Token、Cookie、预签名 URL 或原始工具响应。

~~~bash
python3 scripts/agentteams_eval.py --cases eval/cases/core.yaml --report /tmp/agentteams-evaluation.json
~~~

- [ ] **步骤 4：运行项目级回归**

运行：

~~~bash
mvn -q test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
npm --prefix console run build
git diff --check
~~~

预期：全部命令退出码为 0。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/test integration-tests eval scripts console
git commit -m "test(eval): accept quality plane and evidence boundaries"
~~~

### 完成定义

- [ ] Evaluation Suite、Case、Run、Result、Report 可持久化并按 tenant 授权查询。
- [ ] Evidence Bundle 覆盖 Task、Run、事件、拆分、决策、工具、结果和 Artifact 引用。
- [ ] 确定性评测可独立运行；DeepEval 仍是显式可选能力。
- [ ] Evaluation Pass 不自动修改 Task 状态。
- [ ] Console 能查看评测状态、分数、Finding 和证据引用。
- [ ] 离线、Java、Console 和项目级回归全部通过。
