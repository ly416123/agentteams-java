# AgentTeams AI Agent 评测

`agentteams_eval.py` 是一个独立的 P0 评测 Runner。它通过现有 Task API 创建和排队任务，轮询任务终态，读取运行记录、过程事件和结果制品，再按 YAML 用例中的确定性断言生成 JSON 报告。

## 本地运行

先安装 Runner 的第三方依赖：

```bash
python3 -m pip install pyyaml pypdf
```

Token 只能通过环境变量提供，不能写入 YAML、命令行参数或报告：

```bash
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/agentteams_eval.py \
  --base-url http://127.0.0.1:18081 \
  --token-env AGENTTEAMS_EVAL_TOKEN \
  --cases eval/cases/core.yaml \
  --report /tmp/agentteams-evaluation.json \
  --deepeval-export-dir /tmp/agentteams-deepeval
```

`--deepeval-export-dir` 是可选参数。启用后，Runner 会为每个成功采集到 observation 的用例写入一个已脱敏的 `case-id.json`，可以直接交给 DeepEval 适配层继续做语义评测。

进程退出码约定：`0` 表示所有用例通过，`1` 表示至少一个确定性断言失败，`2` 表示配置、网络或响应格式错误。报告可以提交到评测归档，但必须确认其中没有 Token、API Key、Cookie 或其他凭证。

## L5 运行

L5 运行前先建立 Control Plane port-forward，并使用短期 Token：

```bash
kubectl -n agentteams port-forward svc/agentteams-control-plane 18081:8081
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/agentteams_eval.py \
  --base-url http://127.0.0.1:18081 \
  --token-env AGENTTEAMS_EVAL_TOKEN \
  --cases eval/cases/core.yaml \
  --report /tmp/agentteams-l5-evaluation.json
```

用例中的 `scope` 必须与 Token 的租户、项目和团队范围一致。评测 Runner 不会创建或保存长期密钥；运行结束后应关闭 port-forward 并删除临时 Token。

## CI 门禁

每个 Pull Request 会运行离线 `eval-contract` Job。它检查 YAML 用例、Runner、确定性断言、CLI 编译和已提交验收报告的凭证脱敏，不访问 L5、模型或 MCP 服务，因此不需要在 GitHub Actions 配置任何 API Key。L5 套件仍通过受控环境手动执行，结果更新到 `eval/reports/` 后再提交。

## Promptfoo 可选矩阵

Promptfoo 只负责本地 CLI 和矩阵编排，实际任务执行、事件读取、制品校验和确定性评分仍由 AgentTeams Runner 完成。需要运行时：

```bash
export AGENTTEAMS_EVAL_BASE_URL='http://127.0.0.1:18081'
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
npx promptfoo@latest eval \
  --config eval/promptfooconfig.yaml \
  --output /tmp/agentteams-promptfoo.json
```

Promptfoo 配置和 Python hooks 都读取 `eval/cases/core.yaml`，不会复制 Prompt 或保存凭证。CI 只做离线契约检查，不自动启动 Promptfoo，也不把 Token 注入 GitHub Actions。

## DeepEval 可选语义评测

DeepEval 只作为 P2 的语义评测适配器，不替代 Runner 的确定性门禁。适配器把已经采集的 AgentTeams observation 转成 `LLMTestCase` 所需的输入、实际输出、期望工具和工具轨迹；它不会在导出文件中复制制品下载 URL。默认 CI 不安装 DeepEval，也不会调用外部模型。

需要进行本地语义评测时，在隔离虚拟环境中安装可选依赖：

```bash
python3 -m venv /tmp/agentteams-deepeval
source /tmp/agentteams-deepeval/bin/activate
python -m pip install pyyaml deepeval
```

也可以把其他受控流程采集的 observation JSON 交给适配器。导出不包含凭证或预签名 URL：

```bash
python3 eval/deepeval_adapter.py \
  --cases eval/cases/core.yaml \
  --case-id mcp-web-search-title \
  --observation /tmp/agentteams-observation.json \
  --output /tmp/agentteams-deepeval-case.json
```

代码中可按需调用 `build_deepeval_test_case` 和 `evaluate_with_deepeval`，再配置 DeepEval 的 `AnswerRelevancyMetric`、`FaithfulnessMetric` 或 `ToolCorrectnessMetric`。模型密钥只能通过本机环境变量或密钥管理系统提供，不能写入用例、导出 JSON 或 CI 日志。确定性断言失败仍然直接阻断，LLM Judge 结果只作为语义评分和人工抽检依据。

初始 Rubric 保存在 [`eval/rubrics/core.yaml`](rubrics/core.yaml)，目前只记录指标和候选阈值，不作为发布门禁。接入独立评测环境时，调用 `load_rubric` 读取配置，再用 `build_deepeval_metrics` 创建指标：

```python
from eval.deepeval_adapter import (
    build_deepeval_metrics,
    build_deepeval_test_case,
    evaluate_with_deepeval,
    load_rubric,
)

rubric = load_rubric("eval/rubrics/core.yaml")
metrics = build_deepeval_metrics(rubric, "mcp-web-search-title")
test_case = build_deepeval_test_case(case, observation)
result = evaluate_with_deepeval(test_case, metrics)
```

阈值需要使用脱敏真实样本和人工复核结果校准后，才可以进入发布策略。DeepEval 的指标字段和工具正确性评测方式见[官方指标文档](https://deepeval.com/docs/metrics-tool-correctness)。

## Judge 阈值校准

校准记录只保存 `caseId`、样本编号、DeepEval 指标分数和人工 `pass/fail` 标签，不保存 Prompt、答案、制品或凭证。示例输入结构：

```json
{
  "schemaVersion": "deepeval-calibration-v1",
  "suite": "core",
  "samples": [
    {
      "caseId": "mcp-web-search-title",
      "sampleId": "sample-001",
      "humanLabel": "pass",
      "metricScores": {"answer_relevancy": 0.94, "tool_correctness": 0.96}
    }
  ]
}
```

使用离线脚本计算可分离的候选阈值：

```bash
python3 eval/deepeval_calibration.py \
  --input /tmp/agentteams-deepeval-calibration.json \
  --output /tmp/agentteams-deepeval-thresholds.json
```

只有当人工通过样本的最低分高于人工失败样本的最高分时，脚本才会给出候选阈值；否则保留人工复核状态，不自动修改 [`eval/rubrics/core.yaml`](rubrics/core.yaml)。

## L5 受控入口

L5 主机可以直接运行受控入口。它会检查 Worker CR 是否全部 Ready，自动解析集群内 Control Plane ClusterIP，并且只从环境变量读取短期 Token：

```bash
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/run-l5-agentteams-eval.py \
  --cases eval/cases/core.yaml \
  --report eval/reports/l5-core-latest.json \
  --deepeval-export-dir /tmp/agentteams-l5-deepeval
```

入口不会创建身份映射、修改 Kubernetes 资源或保存 Token。运行身份必须已经具备目标租户、项目和团队的读取、创建及执行权限。当前环境没有 GitHub self-hosted L5 Runner，因此没有把私网 L5 地址直接写入 GitHub 定时 Job；未来配置 self-hosted Runner 或主机 cron 时，复用此入口即可。

## 用例和断言

核心套件当前包含四个用例：

- `mcp-web-search-title`：将任务路由到 L5 的 AgentScope Worker，确认成功调用 `web_search`，并从结果制品读取 MCP 相关内容。
- `no-tool-simple-answer`：确认简单问题不触发搜索工具。
- `artifact-integrity`：确认结果制品使用 `tasks/` 持久化引用，并有正的大小和 64 位小写 SHA-256。
- `multi-agent-pdf-positive`：要求工具成功加载 Skill、调用搜索、完成依赖拆分，并产出唯一且有持久化引用及 HTTP(S) 下载地址的 PDF。

支持的确定性断言类型包括 `phase`、`run_status`、`required_tool`、`failed_tool`、`forbidden_tool`、`tool_order`、`tool_count`、`dependency_success`、`dependency_handoff`、`retry_limit`、`output_contains`、`artifact_integrity` 和 `artifact_pdf`。每条断言返回 `PASSED`、`FAILED` 或 `INCONCLUSIVE`，并附安全证据引用；报告分别保留 `taskPhase`、`runStatus`、`manifestStatus` 和 `evidenceCompleteness`，不会从 `tool.called` 推断工具成功。缺少任一调用的完成事件时，`required_tool` 为 `INCONCLUSIVE`；成功与失败调用都完整记录时，`required_tool` 检查是否至少有一次成功，`failed_tool` 单独检查失败调用。

没有重试次数证据时 `retry_limit` 为 `INCONCLUSIVE`；Run 状态缺失时 `run_status` 也为 `INCONCLUSIVE`。依赖状态只依据 taskTree 可用性，不受过程事件截断影响。Task 4 尚无依赖交接引用数据源，因此缺少引用时为 `INCONCLUSIVE`；若观察数据包含引用，Runner 只接受 `task-result:<dependencyId>` 稳定格式。当前证据 Bundle 没有来源引用字段，所以来源引用校验应为 `INCONCLUSIVE`；正向 Case 只要求 Agent 输出来源引用，不把正文文本当成已验证的引用证据。

Runner 会按事件序号分页读取 `/process-events`（每页 100 条），最多检查 1001 条以确认是否超过 1000 条证据上限。PDF 断言先检查 MIME、扩展名、大小和 SHA-256，再用 `pypdf` 检查本地可用字节的 `%PDF-` 签名、文件结束标记和页树。Case 可要求唯一 PDF、持久化 `tasks/` 引用和 HTTP(S) 下载地址；报告只保留清单引用，不输出下载 URL。YAML 中的 `offlineFixtures` 仅用于本地单元测试，不会被 Runner 当作 Suite Case 或 L5 执行报告。远端 artifact API 不提供正文时，签名检查为 `INCONCLUSIVE`；Runner 不会跟随下载 URL。

运行离线契约测试：

```bash
python3 -m unittest scripts.test_agentteams_eval scripts.test_evaluation_quality_contract scripts.test_l5_agentteams_eval_contract -v
```

Promptfoo 和 DeepEval 都以这层报告和任务运行证据作为基础；Phoenix 仍留待后续接入。

## 当前验证状态

2026-09-21 已完成本地 Runner 验证：6 个 `unittest` 用例通过、Python 编译检查通过、`scripts/validate-kind-manifests.py` 返回 `KIND_MANIFESTS_OK`。随后在 `ly-MacBookAir7-2` L5 主机执行核心套件，3 个用例全部 `SUCCEEDED`，分数均为 `1.0`；脱敏报告保存在 [`eval/reports/2026-09-21-l5-core.json`](reports/2026-09-21-l5-core.json)。验收使用的临时身份映射已清理。

同日又在 L5 运行了带 `--deepeval-export-dir` 的核心套件，3 个用例仍全部 `SUCCEEDED`，并保存了脱敏的 DeepEval 输入样本。汇总记录见 [`eval/reports/2026-09-21-l5-deepeval-summary.json`](reports/2026-09-21-l5-deepeval-summary.json)，样本位于 [`eval/reports/2026-09-21-l5-deepeval/`](reports/2026-09-21-l5-deepeval/)。临时项目成员关系、Token、脚本和 L5 临时目录均已清理；人工语义标注和阈值校准仍待独立评测环境执行。

2026-09-28 在受控 L5 集群重新执行核心套件，`mcp-web-search-title`、`no-tool-simple-answer` 和 `artifact-integrity` 均通过，确定性分数均为 `1.0`。脱敏验收报告见 [`eval/reports/2026-09-28-l5-core.json`](reports/2026-09-28-l5-core.json)；报告不包含 Token、Cookie、预签名 URL 或原始工具响应。

随后发现 AgentScope 最终结果曾被事件安全摘要限制为 128 字符，导致语义 Judge 只能看到截断答案。Worker 现已将最终结果改为脱敏后最多 16 KiB，过程事件仍保持 128 字符上限。2026-09-21 的 L5 MCP 复验确定性门禁通过，输出 2257 字符，`ToolCorrectnessMetric=1.0`；同一次抽样的 `AnswerRelevancyMetric=0.7931`（候选阈值 0.8），Faithfulness 因当前事件协议不提供 `retrieval_context` 暂不执行。记录见 [`eval/reports/2026-09-21-l5-deepeval/mcp-web-search-title-latest.json`](reports/2026-09-21-l5-deepeval/mcp-web-search-title-latest.json) 和 [`eval/reports/2026-09-21-l5-deepeval/mcp-web-search-title-judge.json`](reports/2026-09-21-l5-deepeval/mcp-web-search-title-judge.json)。
