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

## 用例和断言

核心套件当前包含三个用例：

- `mcp-web-search-title`：确认任务成功调用 `web_search`，并返回 MCP 相关内容。
- `no-tool-simple-answer`：确认简单问题不触发搜索工具。
- `artifact-integrity`：确认结果制品使用 `tasks/` 持久化引用，并有正的大小和 64 位小写 SHA-256。

支持的确定性断言类型为 `phase`、`required_tool`、`forbidden_tool`、`output_contains` 和 `artifact_integrity`。未知类型会使该用例失败。后续接入 Promptfoo、DeepEval 或 Phoenix 时，仍以这层报告和任务运行证据作为基础。
