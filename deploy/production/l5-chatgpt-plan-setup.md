# L5 双 MCP 评测 Team 使用 ChatGPT Plus

本说明只授权现有双 MCP 评测 Team 使用一个 ChatGPT 账户。Access/refresh token 不得写入 Helm values、仓库、命令参数或聊天。

## 1. 构建和导入镜像

在项目根目录执行：

```bash
scripts/build-l5-chatgpt-plan-images.sh
```

脚本构建 `linux/amd64` 的 Control Plane、Operator、Worker 与 Token Refresh 镜像，并核对镜像架构。将四个镜像归档传到 L5 后导入 containerd：

```bash
docker save agentteams-agent-worker:l5-chatgpt-plan-proxy-20261011-amd64 \
  agentteams-control-plane:l5-chatgpt-plan-proxy-20261011-amd64 \
  agentteams-operator:l5-chatgpt-plan-proxy-20261011-amd64 \
  agentteams-chatgpt-token-refresh:l5-chatgpt-plan-proxy-20261011-amd64 | gzip > /tmp/l5-chatgpt-plan-images.tar.gz
scp /tmp/l5-chatgpt-plan-images.tar.gz l5:/tmp/
ssh l5 'gzip -dc /tmp/l5-chatgpt-plan-images.tar.gz | sudo /usr/local/bin/k3s ctr -n k8s.io images import -'
```

`l5` SSH 别名和 sudo 权限需由运维账户预先配置。归档只含镜像，不含 OAuth 数据。

## 2. 确认评测 Team Scope

获取当前双 MCP 评测 Team 的 **Tenant UUID、Project UUID 和 Team UUID**，填入 `deploy/helm/l5-values.yaml` 的 `controlPlane.chatgptPlan`。项目和 Team 显示名称不能代替 UUID。将 `sessionSecretName` 保持为 `agentteams-chatgpt-oauth-session`；不要把 Token 放进该文件。

先部署 Worker/refresh 镜像与 CRD/Operator 变更，同时保持 `enabled: false`。集群需能访问 `auth.openai.com:443` 与 `api.openai.com:443`；Worker 侧还需访问 `api.openai.com:443`。如果 L5 不能直连，可配置 L5 可达的 HTTP CONNECT 代理到 `controlPlane.chatgptPlan.proxyUrl`。该地址只会注入获准的评测 Team Worker 和刷新 Job，URL 中不能包含账号或密码。桌面代理需开启 LAN 监听，并允许 L5 节点访问其监听端口。

## 3. 在本机完成 OAuth 并写入 L5 Secret

在已安装 `kubectl` 且能访问 L5 集群的本机运行。需要本机 Python 3.10+、浏览器、`kubectl` 当前上下文或明确的 L5 context，以及回环端口 `127.0.0.1:1455` 可用：

```bash
python3 -m pip install -r deploy/chatgpt-plan/requirements.txt
HTTPS_PROXY=http://127.0.0.1:7892 HTTP_PROXY=http://127.0.0.1:7892 \
python3 scripts/chatgpt_plan_oauth.py authorize --context l5 --namespace agentteams \
  --secret-name agentteams-chatgpt-oauth-session
```

在打开的浏览器中登录并同意 ChatGPT 计划额度授权。脚本校验 OAuth state、ID Token 签名/issuer/audience/nonce、账户主体和 `chatgpt.tokens.use.direct` Scope，然后直接创建或更新 L5 会话 Secret。Token 不会输出到终端。脚本使用并在本机保存稳定的 L5 host ID：`~/.config/agentteams/chatgpt-plan-host-id`。

查看该账户可用模型：

```bash
python3 scripts/chatgpt_plan_oauth.py models --context l5 --namespace agentteams \
  --secret-name agentteams-chatgpt-oauth-session
```

将返回的模型 slug 配置给评测 Team 的 AgentSpec。不要仅凭其他账号或文档里的示例选模型。OAuth 客户端同样需要本机能访问 `auth.openai.com` 和 `api.openai.com`；若需代理，请通过本机 `HTTPS_PROXY`/`HTTP_PROXY` 环境变量设置。

## 4. 启用 Team 授权并滚动 Worker

将 L5 values 中的 `enabled` 设为 `true`，并填写上一步核实的 Tenant、Project、Team UUID。再次 Helm upgrade。只有 `chatgpt-plan` Provider 且 Scope UUID 全部匹配的 `LEADER`/`EXECUTOR` Worker 才会获得 Secret 引用；其他 Team 会失败关闭。

验证 Worker Deployment 的 Secret volume 只投影 `access-token` 键，挂载到 `/var/run/agentteams/chatgpt/token`。Worker 没有 Kubernetes API Token，也不获得 refresh token。刷新 CronJob 以 `Forbid` 并发策略每 10 分钟检查一次，access token 接近过期后轮换整个 Secret；Worker 每次调用重新读取投影文件。

## 5. 运行验收

先用评测 Team 完成一次 Responses API 请求和本地 MCP 工具调用，再运行已有 10 case 评测和 PDF/DeepEval 验收。Responses 请求必须是 `store=false`、`stream=true`。报告应显示 `chatgpt-plan:<model>`，不能回退到 DeepSeek。

检查刷新作业状态时只查看非敏感日志：

```bash
kubectl --context l5 -n agentteams get cronjob,jobs -l app.kubernetes.io/name=agentteams-chatgpt-token-refresh
kubectl --context l5 -n agentteams logs job/<job-name>
```

作业只输出刷新成功/未到刷新时间或脱敏错误类别，不输出 Secret 内容。

## 6. 重新授权和撤销

账号重新授权时使用相同的 context、namespace 和 Secret 名称。脚本复用 Secret 中的已签发 client ID、校验账号主体一致后轮换会话内容。要撤销授权，在 ChatGPT 设置中断开对应 Agent；再删除 L5 Secret 并将 Helm `enabled` 设为 `false`。这会使评测 Team 的 ChatGPT Provider 请求失败，直到完成新的授权。
