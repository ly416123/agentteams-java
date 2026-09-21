# AgentTeams AI Agent 评测

`agentteams_eval.py` 是一个独立的 P0 评测 Runner。它通过现有 Task API 创建和排队任务，轮询任务终态，读取运行记录、过程事件和结果制品，再按 YAML 用例中的确定性断言生成 JSON 报告。

## 本地运行

先安装 Runner 的唯一第三方依赖：

```bash
python3 -m pip install pyyaml
```

Token 只能通过环境变量提供，不能写入 YAML、命令行参数或报告：

```bash
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/agentteams_eval.py \
  --base-url http://127.0.0.1:18081 \
  --token-env AGENTTEAMS_EVAL_TOKEN \
  --cases eval/cases/core.yaml \
  --report /tmp/agentteams-evaluation.json
```

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

适配器的输入是 Runner 采集的原始 observation JSON。导出不包含凭证或预签名 URL：

```bash
python3 eval/deepeval_adapter.py \
  --cases eval/cases/core.yaml \
  --case-id mcp-web-search-title \
  --observation /tmp/agentteams-observation.json \
  --output /tmp/agentteams-deepeval-case.json
```

代码中可按需调用 `build_deepeval_test_case` 和 `evaluate_with_deepeval`，再配置 DeepEval 的 `AnswerRelevancyMetric`、`FaithfulnessMetric` 或 `ToolCorrectnessMetric`。模型密钥只能通过本机环境变量或密钥管理系统提供，不能写入用例、导出 JSON 或 CI 日志。确定性断言失败仍然直接阻断，LLM Judge 结果只作为语义评分和人工抽检依据。

## L5 受控入口

L5 主机可以直接运行受控入口。它会检查 Worker CR 是否全部 Ready，自动解析集群内 Control Plane ClusterIP，并且只从环境变量读取短期 Token：

```bash
export AGENTTEAMS_EVAL_TOKEN='短期 Bearer Token'
python3 scripts/run-l5-agentteams-eval.py \
  --cases eval/cases/core.yaml \
  --report eval/reports/l5-core-latest.json
```

入口不会创建身份映射、修改 Kubernetes 资源或保存 Token。运行身份必须已经具备目标租户、项目和团队的读取、创建及执行权限。当前环境没有 GitHub self-hosted L5 Runner，因此没有把私网 L5 地址直接写入 GitHub 定时 Job；未来配置 self-hosted Runner 或主机 cron 时，复用此入口即可。

## 用例和断言

核心套件当前包含三个用例：

- `mcp-web-search-title`：将任务路由到 L5 的 AgentScope Worker，确认成功调用 `web_search`，并从结果制品读取 MCP 相关内容。
- `no-tool-simple-answer`：确认简单问题不触发搜索工具。
- `artifact-integrity`：确认结果制品使用 `tasks/` 持久化引用，并有正的大小和 64 位小写 SHA-256。

支持的确定性断言类型为 `phase`、`required_tool`、`forbidden_tool`、`output_contains` 和 `artifact_integrity`。未知类型会使该用例失败。Promptfoo 和 DeepEval 都以这层报告和任务运行证据作为基础；Phoenix 仍留待后续接入。

## 当前验证状态

2026-09-21 已完成本地 Runner 验证：6 个 `unittest` 用例通过、Python 编译检查通过、`scripts/validate-kind-manifests.py` 返回 `KIND_MANIFESTS_OK`。随后在 `ly-MacBookAir7-2` L5 主机执行核心套件，3 个用例全部 `SUCCEEDED`，分数均为 `1.0`；脱敏报告保存在 [`eval/reports/2026-09-21-l5-core.json`](reports/2026-09-21-l5-core.json)。验收使用的临时身份映射已清理。
