# L5 DashScope 连通性与密钥检查

## 当前结论

- `/Users/gecko/code/agentteams-mcp-tools/ali_api_key` 仅保存在本地，已被 `.gitignore` 忽略；文件权限应保持 `0600`。
- L5 Secret `agentteams-dashscope` 与该文件内容一致。只比较摘要，不在日志中输出 Key。
- 该 Key 对 `https://dashscope.aliyuncs.com` 有效；对 `https://dashscope-intl.aliyuncs.com` 无效。
- 当前 L5 节点到中国区端点的 TCP 连接超时，因此 AgentScope 任务会在模型调用阶段失败，尚未进入 MCP 工具调用。

## 修复路径

优先恢复 L5 到中国区 DashScope 的出网访问：

1. 放行 L5 节点到 `dashscope.aliyuncs.com:443` 的出站访问，并确保 DNS 可解析 IPv4 地址。
2. 在 L5 Worker Pod 内执行连通性检查：

   ```bash
   curl -4 -sS -o /dev/null \
     -w '%{http_code} %{time_connect} %{time_total}\n' \
     https://dashscope.aliyuncs.com/compatible-mode/v1/models
   ```

3. 重新提交带 `requiredMcpTools:["web_search"]` 的验收任务，数据库过程事件必须出现
   `tool.called` 和 `tool.finished`。

如果网络只能访问国际端点，需要同时换用国际区域签发的 API Key，并在 Worker 环境中设置：

```yaml
DASHSCOPE_BASE_URL: https://dashscope-intl.aliyuncs.com
```

不能只切换端点而继续复用中国区 Key。

## 安全要求

- Key 只进入 Kubernetes Secret `agentteams-dashscope`，不写入 Helm values、日志、任务输入或 Git。
- 修改 Secret 后滚动重启 Worker，并重新执行 MCP 工具调用验收。
- 验收失败时保留 `task_process_events` 和 Worker 脱敏日志，用于区分网络超时、Key 无效和 MCP 工具未调用。
