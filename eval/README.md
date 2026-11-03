# AgentTeams AI Agent 评测

`agentteams_eval.py` 是一个独立的 P0 评测 Runner。它通过现有 Task API 创建和排队任务，轮询任务终态，读取运行记录、过程事件和结果制品，再按 YAML 用例中的确定性断言生成 JSON 报告。

## 本地运行

先安装 Runner 的第三方依赖：

```bash
python3 -m pip install pyyaml pypdf pymupdf
```

Token 只能通过环境变量提供，不能写入 YAML、命令行参数或报告：

```bash
read -r -s -p 'Evaluation token: ' AGENTTEAMS_EVAL_TOKEN; echo
export AGENTTEAMS_EVAL_TOKEN
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
read -r -s -p 'Evaluation token: ' AGENTTEAMS_EVAL_TOKEN; echo
export AGENTTEAMS_EVAL_TOKEN
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
read -r -s -p 'Evaluation token: ' AGENTTEAMS_EVAL_TOKEN; echo
export AGENTTEAMS_EVAL_TOKEN
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

### ChatGPT OAuth 本地 PDF 质量评测

PDF 质量评测使用 DeepEval 4.2.8 和 `gpt-5.6-luna`。在本机隔离环境安装依赖：

```bash
python3 -m venv "$HOME/.cache/agentteams/pdf-quality-deepeval"
source "$HOME/.cache/agentteams/pdf-quality-deepeval/bin/activate"
python -m pip install -r eval/requirements-deepeval.txt
```

配置好经授权的 ChatGPT OAuth access token 后，可运行 10 个 L5 PDF 样本的语义评分：

```bash
export DEEPEVAL_TELEMETRY_OPT_OUT=1
export DEEPEVAL_DISABLE_DOTENV=1
python scripts/run_pdf_quality_deepeval.py
```

评测脚本从 `CHATGPT_OAUTH_ACCESS_TOKEN` 环境变量读取 access token，并通过 `gpt-5.6-luna` Responses API 评分。脚本不会把 token 写入报告或输出日志。DeepEval 会把 PDF 抽取文本和检索来源内容发送到 OpenAI；原始输入导出包含任务文本和来源正文，不应提交到仓库。

L5 套件运行时应设置 `--deepeval-export-dir`，以便保留事实准确性评分所需的检索来源正文。仅有来源 ID 的证据包不足以独立核验 PDF 事实，DeepEval 必须将这类分数标记为 `INCONCLUSIVE`。

DeepEval 导出会记录 `judgeMetadata`：运行者通过 adapter CLI 显式提供的 provider/model（未提供时为 `null`）、Rubric 中的 prompt 版本和 schema 版本、Rubric 文件 SHA-256，以及校准状态。当前核心 Rubric 状态为 `UNCALIBRATED`；该状态不会把语义评分变成硬门禁。不要将凭据放入 provider/model 参数。

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

L5 主机可以直接运行受控入口。必须显式指定目标 tenant/project/team（命令行参数或同名环境变量），且与 YAML 中每个 Case 的 scope 完全一致。入口会先只读检查项目是否属于该 tenant、目标 team 是否存在，并确认当前身份具有 ADMIN 角色（覆盖任务创建和执行权限），再确认目标范围内至少一个 Worker CR 为 Ready、自动解析集群内 Control Plane ClusterIP。入口会把已验证的 Team UUID 绑定到任务 `spec.teamId`，由 Team 调度选择 Leader/Executor；历史 Terminated Worker 不会阻止验收。短期 Token 只从环境变量读取：

```bash
read -r -s -p 'Evaluation token: ' AGENTTEAMS_EVAL_TOKEN; echo
export AGENTTEAMS_EVAL_TOKEN
export AGENTTEAMS_EVAL_TENANT='tenant-a'
export AGENTTEAMS_EVAL_PROJECT='project-a'
export AGENTTEAMS_EVAL_TEAM='team-a'
python3 scripts/run-l5-agentteams-eval.py \
  --cases eval/cases/core.yaml \
  --case multi-agent-pdf-positive \
  --report eval/reports/l5-core-latest.json \
  --deepeval-export-dir /tmp/agentteams-l5-deepeval
```

入口不会创建身份映射、修改 Kubernetes 资源或保存 Token。报告在写入前会拒绝凭证字段、Token 和 URL；报告记录 Suite 摘要版本、Evaluator 版本、Case/Task/Run 标识、双门禁及安全证据引用。当前环境没有 GitHub self-hosted L5 Runner，因此没有把私网 L5 地址直接写入 GitHub 定时 Job；未来配置 self-hosted Runner 或主机 cron 时，复用此入口即可。

当 YAML 使用项目名（例如 `project-a`）时，入口会先用 API 验证该项目及其权限，再把实际提交的 Task scope 项目字段绑定为 API 返回的规范项目 UUID；YAML 的租户和团队 scope claim 保持原样。这样与 Control Plane 在任务接口中的项目 scope 规范化保持一致。

## 用例和断言

核心套件当前包含四个用例：

- `mcp-web-search-title`：将任务路由到 L5 的 AgentScope Worker，确认成功调用 `web_search`，并从结果制品读取 MCP 相关内容。
- `no-tool-simple-answer`：确认简单问题不触发搜索工具。
- `artifact-integrity`：确认结果制品使用 `tasks/` 持久化引用，并有正的大小和 64 位小写 SHA-256。
- `multi-agent-pdf-positive`：要求规划依赖子任务、执行器成功调用搜索、完成依赖交接，并由聚合阶段产出唯一且有持久化引用及 HTTP(S) 下载地址的 PDF；该 Case 不要求加载 Skill。

支持的确定性断言类型包括 `phase`、`run_status`、`required_tool`、`failed_tool`、`forbidden_tool`、`tool_order`、`tool_count`、`dependency_success`、`dependency_handoff`、`retry_limit`、`source_citation`、`output_contains`、`artifact_integrity`、`artifact_pdf` 和 `artifact_pdf_quality`。每条断言返回 `PASSED`、`FAILED` 或 `INCONCLUSIVE`，并附安全证据引用；报告分别保留 `taskPhase`、`runStatus`、`manifestStatus`、原始 `evidenceCompleteness` 和独立的 `eventCompleteness`，不会从 `tool.called` 推断工具成功。全事件断言只有在事件完整度为 `COMPLETE` 时才可通过或否定；已观察到的失败调用可立即令 `failed_tool` 失败。工具调用和完成事件必须通过唯一 `callId` 配对；缺少、重复或不匹配的 ID 会令生命周期断言为 `INCONCLUSIVE`。成功与失败调用都完整记录时，`required_tool` 检查是否至少有一次成功，`failed_tool` 单独检查失败调用。

没有重试次数证据时 `retry_limit` 为 `INCONCLUSIVE`；Run 状态缺失时 `run_status` 也为 `INCONCLUSIVE`。依赖状态只依据 taskTree 可用性，不受过程事件截断影响。Task 4 尚无依赖交接引用数据源，因此缺少引用时为 `INCONCLUSIVE`；若观察数据包含引用，Runner 只接受 `task-result:<dependencyId>` 稳定格式。`source_citation` 读取当前任务 Run 与成功委派子任务 Run 的搜索调用回执和 URL 元数据，并保留每条来源的 task/run 身份；只有搜索调用覆盖完整、答案包含可解析的 HTTP(S) URL 且至少一个 URL 存在、所有引用都精确命中该受限来源集合时才通过。来源缺失、来源摄取不完整、答案 URL 无效或 task/run 不匹配时为 `INCONCLUSIVE`；完整证据下存在未匹配 URL 时为 `FAILED`。报告只保留稳定来源引用与覆盖计数，不保留搜索正文或来源 URL。

Runner 会按事件序号分页读取 `/process-events`（每页 100 条），最多检查 1001 条以确认是否超过 1000 条证据上限。PDF 断言先检查 MIME、扩展名、大小和 SHA-256，再用 `pypdf` 检查本地可用字节的 `%PDF-` 签名、文件结束标记和页树。`artifact_pdf_quality` 使用 PyMuPDF 检查文本提取、中文占比、替换字符、字号范围、字体嵌入和页面文字边界，并在十用例证据目录保存最终 PDF 供视觉复核。自动结构指标无法判断美观或事实正确性；人工/独立 Judge 依据 [`eval/rubrics/pdf-artifact-quality.yaml`](rubrics/pdf-artifact-quality.yaml) 对字体观感、视觉设计、语义完整性、相关性、准确性与引用完整性分别打分，准确性要对照两类 MCP 的来源。最终验收同时要求结构门禁和内容评审达到门槛。报告不会输出 PDF 正文或凭证。

真实 L5 正向 Case 结束后，在持有同一短期 Token 的 L5 终端单独验证 PDF 下载。`verify_l5_eval_pdf.py` 只接受当前 L5 的 `192.168.122.55:30090` 交付地址，禁止重定向，限制下载大小，并核对 manifest 的大小、SHA-256、PDF 签名和页树；输出文件不会保存预签名 URL 或文件正文：

```bash
python3 scripts/verify_l5_eval_pdf.py \
  --report eval/reports/2026-10-08-l5-multi-agent-pdf.json \
  --output eval/reports/2026-10-08-l5-multi-agent-pdf-download.json
```

运行离线契约测试：

```bash
python3 -m unittest scripts.test_agentteams_eval scripts.test_evaluation_quality_contract scripts.test_l5_agentteams_eval_contract -v
```

Promptfoo 和 DeepEval 都以这层报告和任务运行证据作为基础；Phoenix 仍留待后续接入。

## 当前验证状态

### 最近验收与收尾状态（2026-11-02）

- 2026-11-02 Operator 修复部署后，L5 隔离 Team 的真实多 Agent PDF 正向任务已完成：父 Task/Run `SUCCEEDED`，依赖成功与交接、`web_search`、来源 URL 与唯一 PDF 的确定性断言全部通过。报告和证据见 [`2026-11-02-l5-worker-reconcile-positive-verified.json`](reports/2026-11-02-l5-worker-reconcile-positive-verified.json) 与配套证据目录。首次启动遇到的 403 是 Runner 未把 `project-a` 名称规范化为 API 返回的项目 UUID；入口已补齐该绑定并新增回归测试。L5 主机未安装 `pypdf`，本次仅将本机版本放在远端 `/tmp` 供报告重评，未安装到 L5 系统。
- 本轮负向评测进度：工具失败通过，见 [`工具失败报告`](reports/2026-11-02-l5-tool-failure.json)；同一个已成功隔离任务仅用于执行一次预期失败工具调用，没有新建任务。缺失依赖通过，见 [`缺失依赖报告`](reports/2026-11-02-l5-missing-dependency.json)：真实 Subtask API 返回 400 `VALIDATION_ERROR`，前后子任务快照相同。事件截断通过受控限额验证，见 [`事件截断报告`](reports/2026-11-02-l5-event-truncation-controlled.json)：评测器事件上限临时设为 2，观察到 3 条并标记 `TRUNCATED`；这证明截断检测路径，不表示自然达到默认 1000 条上限。
- 无效 PDF L5 文件校验负例已通过，见 [`无效 PDF 报告`](reports/2026-11-02-l5-invalid-pdf.json)。新建了隔离 DRAFT Task，通过真实 L5 Task File API 上传内容为纯文本、文件名为 `.pdf`、MIME 为 `application/pdf` 的 OUTPUT 文件，再从 L5 下载回读字节验证签名、EOF 和页树均无效；用临时 `pypdf` 副本完成页树解析，随后清理远端脚本和依赖目录。此用例覆盖 L5 文件存储与字节校验边界，不声称经过 Worker 的最终 Result Manifest 发布路径。工具失败评测第一次执行超时后复用既有成功任务完成断言，未产生重复任务。
- 已归档的真实 L5 正向报告 `2026-10-09-l5-source-provenance-positive-final4.json` 有 7 项断言通过；同日负向报告 `2026-10-09-l5-source-provenance-negative-final3.json` 将伪造来源引用判为 `FAILED`，符合预期。2026-10-15 的双 MCP 治理 Case 也通过其确定性断言。旧计划中“来源正负向均未完成”的描述已由上述证据取代。
- 四类负向评测均有 L5 报告。无效 PDF 通过 Task File API 文件字节校验，不覆盖 Worker 最终 Result Manifest 发布；补充的 evaluator 单测对 manifest-shaped observation 注入无效签名和损坏页树字节。
- 本轮 L5 短期 Token 已在每次评测后请求 logout；今后重跑需要重新获取短期 Token，不在仓库或报告中保存凭证。
- 一次历史 Skill smoke 报告的 Runner 只检查父任务事件而标记未通过；后续报告已验证子任务工具事件成功，应将该历史报告视为 scope 判定问题，不能作为当前 Skill 运行失败证据。
- 语义 Judge 的人工标注样本、Rubric 校准和 Adapter 的 provider/model/prompt/rubric 版本元数据仍未齐备；语义分数继续作为建议，不进入硬门禁。
- 按阶段范围，L5 配对快照恢复演练与扩缩容验证延期，不作为本阶段验收阻塞项。

2026-09-21 已完成本地 Runner 验证：6 个 `unittest` 用例通过、Python 编译检查通过、`scripts/validate-kind-manifests.py` 返回 `KIND_MANIFESTS_OK`。随后在 `ly-MacBookAir7-2` L5 主机执行核心套件，3 个用例全部 `SUCCEEDED`，分数均为 `1.0`；脱敏报告保存在 [`eval/reports/2026-09-21-l5-core.json`](reports/2026-09-21-l5-core.json)。验收使用的临时身份映射已清理。

同日又在 L5 运行了带 `--deepeval-export-dir` 的核心套件，3 个用例仍全部 `SUCCEEDED`，并保存了脱敏的 DeepEval 输入样本。汇总记录见 [`eval/reports/2026-09-21-l5-deepeval-summary.json`](reports/2026-09-21-l5-deepeval-summary.json)，样本位于 [`eval/reports/2026-09-21-l5-deepeval/`](reports/2026-09-21-l5-deepeval/)。临时项目成员关系、Token、脚本和 L5 临时目录均已清理；人工语义标注和阈值校准仍待独立评测环境执行。

2026-09-28 在受控 L5 集群重新执行核心套件，`mcp-web-search-title`、`no-tool-simple-answer` 和 `artifact-integrity` 均通过，确定性分数均为 `1.0`。脱敏验收报告见 [`eval/reports/2026-09-28-l5-core.json`](reports/2026-09-28-l5-core.json)；报告不包含 Token、Cookie、预签名 URL 或原始工具响应。

随后发现 AgentScope 最终结果曾被事件安全摘要限制为 128 字符，导致语义 Judge 只能看到截断答案。Worker 现已将最终结果改为脱敏后最多 16 KiB，过程事件仍保持 128 字符上限。2026-09-21 的 L5 MCP 复验确定性门禁通过，输出 2257 字符，`ToolCorrectnessMetric=1.0`；同一次抽样的 `AnswerRelevancyMetric=0.7931`（候选阈值 0.8），Faithfulness 因当前事件协议不提供 `retrieval_context` 暂不执行。记录见 [`eval/reports/2026-09-21-l5-deepeval/mcp-web-search-title-latest.json`](reports/2026-09-21-l5-deepeval/mcp-web-search-title-latest.json) 和 [`eval/reports/2026-09-21-l5-deepeval/mcp-web-search-title-judge.json`](reports/2026-09-21-l5-deepeval/mcp-web-search-title-judge.json)。
