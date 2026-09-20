# L5 MCP Worker 接入实现计划

**目标：** 将已部署的 `agentteams-mcp-tools` 注册到 AgentTeams，为新建 Worker 注入 MCP 连接令牌，并通过 AgentScope Worker 完成一次真实 `web_search` 调用。

**边界：** `MCP_TOOLS_TOKEN` 来自 L5 的 `agentteams-mcp-tools` Secret；Worker 不读取该 Secret 中的 `bailian-api-key`。模型凭据使用独立 Secret 和独立环境变量，不能复用或复制 MCP 服务的百炼 Key，除非所有者明确批准。

## 任务 1：补齐 AgentScope 模型 Provider

- [x] 在 `agent-worker` 添加 AgentScope DashScope 模型扩展，版本与现有 AgentScope `2.0.1` 一致。
- [x] 先添加失败测试，证明当前生产类路径无法解析 `dashscope:qwen-plus`；增加依赖后测试通过。
- [x] 运行 `mvn -B -pl agent-worker -am test`。

## 任务 2：固化 L5 Worker 凭据映射

- [x] 在 Helm 契约测试中断言 L5 只将 `MCP_TOOLS_TOKEN` 映射到 `agentteams-mcp-tools/mcp-tools-token`。
- [x] 在 `deploy/helm/l5-values.yaml` 配置 `credentialSecretEnvs`。
- [x] 验证渲染后的 Control Plane 环境变量包含映射，Worker 模板只引用 `mcp-tools-token`，不引用 `bailian-api-key`。

## 任务 3：部署 AgentTeams 配置和 Worker 镜像

- [x] 构建并验证 `linux/amd64` Worker 镜像，使用不可变 L5 标签。
- [x] 将镜像导入 L5 K3s containerd。
- [x] 更新 L5 Control Plane 的 Worker Provisioner 配置并等待 Ready；现有 Worker 不切换运行时，统一使用能力隔离后的镜像。
- [x] 检查节点可用内存和核心 Deployment 状态。

## 任务 4：注册平台资源

- [x] 使用 L5 开发环境 `quota-admin` 的短期 OIDC Token 调用 API，Token 不落盘、不输出。
- [x] 幂等注册 MCP Server：`agentteams-web-search`、`STREAMABLE_HTTP`、`http://agentteams-mcp-tools:8090/mcp`、`credentialRef=MCP_TOOLS_TOKEN`。
- [x] 只验证注册响应中的非敏感字段；Control Plane 的 connection-test 不作为 Worker 验收依据。
- [x] 注册独立模型 Provider 和模型目录项；Provider 只保存 `k8s://agentteams/agentteams-dashscope#api-key` 引用，Worker 环境变量使用 `DASHSCOPE_API_KEY`。

## 任务 5：创建并验收新 Worker

- [x] 前置检查独立模型 Secret 已存在，且只向 Worker 注入 `DASHSCOPE_API_KEY`；不得从 `agentteams-mcp-tools/bailian-api-key` 复制。
- [x] 创建、评审并发布 AGENTSCOPE Worker Template。
- [x] 模型 Secret 就绪后实例化模板，由 Control Plane 创建新的 AgentSpec、Agent 和 Worker CR。
- [x] 创建并发布独立 AgentSpec，引用 `agentteams-web-search`。
- [x] 将已发布 AgentSpec 部署到新 Worker 对应的 Agent。
- [x] 验证新 Worker 环境存在 `MCP_TOOLS_TOKEN` 和模型凭据变量，不存在 `BAILIAN_API_KEY`。
- [x] 提交带强制工具调用指令的验收任务，任务成功并返回 `www.pbc.gov.cn`；MCP 服务日志确认收到 AgentScope 客户端初始化请求，未记录搜索正文。
- [x] 验收后确认 MCP Pod、新 Worker 和所有核心 Deployment Ready，节点可用内存不低于 `512 MiB`。

## 完成标准

- AgentScope Worker 生产包可解析 DashScope 模型。
- L5 配置以 Secret 引用方式自动向新 Worker 注入 MCP Token。
- MCP 注册、AgentSpec 配置和 Worker CR 中都不出现 Secret 明文。
- 新 Worker 实际调用 `web_search` 成功；既有 QwenPaw Worker 仍保持原运行时，仅统一切换到能力隔离后的 Worker 镜像。
- 代码、配置和非敏感验收记录已提交并推送。

## 2026-09-20 L5 执行记录

- Worker 镜像：`agentteams-agent-worker:l5-90b5ad7-amd64`，平台 `linux/amd64`，包含 AgentScope 与 QwenPaw 能力隔离、可写状态目录、无人值守工具权限和安全诊断修复。
- Control Plane 使用 `agentteams-control-plane:l5-c64508e-amd64`，Operator 使用 `agentteams-operator:l5-c64508e-amd64`，均为 `2/2 Ready`。
- 独立模型 Secret `agentteams-dashscope` 已创建；只向 Worker 注入 `DASHSCOPE_API_KEY`，未读取或复制 MCP Secret 的百炼 Key。
- Worker Provisioner 分别引用 `agentteams-mcp-tools/mcp-tools-token` 和待创建的 `agentteams-dashscope/api-key`；未引用 `bailian-api-key`。
- MCP Server `agentteams-web-search` 已注册，仅允许 `http`、域名 `agentteams-mcp-tools` 和工具 `web_search`，超时上限为 30 秒。
- 模型 Provider `dashscope-l5` 及模型 `dashscope:qwen-plus` 已注册，工具调用能力已标记为启用。
- Worker Template `l5-agentscope-web-search` 的版本 1 已完成评审并发布，模板实例已成功创建 Worker `ccea9e5f-b5a2-4cd4-8e75-74f516ab41e5`。
- AgentSpec `l5-agentscope-web-search` 已发布，并通过模型与 MCP 引用校验。
- 滚动更新后所有核心 Deployment Ready，节点可用内存为 `953 MiB`。
- Worker CR 已进入 Ready，Secret 引用仅为 `DASHSCOPE_API_KEY` 与 `MCP_TOOLS_TOKEN`，没有 `BAILIAN_API_KEY`。
- 初次任务被旧 Worker 接收，暴露出 Worker Hello 同时声明两种 runtime 能力的问题；已用回归测试修复为按实际 runtime 只声明一种能力，随后重新部署并重跑验收任务。
- AgentScope 默认权限模式会拒绝无人值守工具调用；已用回归测试锁定 Worker Harness 为 `PermissionMode.BYPASS`，同时继续禁用 Shell 和子代理。
- 验收任务 `ae54ce09-86fd-4342-aaa4-30a38af75df2` 已由 AgentScope Worker 成功完成，输出为 `www.pbc.gov.cn`；对应 MCP Pod 收到 `agentscope-java` 初始化请求。
- 当前已知平台缺口：Worker 重启后历史 ConfigChanged 不会自动重放；本次通过发布新的 AgentSpec 并重新部署绑定完成配置下发，后续应补充配置快照重放或启动拉取。
