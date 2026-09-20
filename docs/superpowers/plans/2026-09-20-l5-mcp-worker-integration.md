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
- [x] 更新 L5 Control Plane 的 Worker Provisioner 配置并等待 Ready；现有 Worker 不重建。
- [x] 检查节点可用内存和核心 Deployment 状态。

## 任务 4：注册平台资源

- [x] 使用 L5 开发环境 `quota-admin` 的短期 OIDC Token 调用 API，Token 不落盘、不输出。
- [x] 幂等注册 MCP Server：`agentteams-web-search`、`STREAMABLE_HTTP`、`http://agentteams-mcp-tools:8090/mcp`、`credentialRef=MCP_TOOLS_TOKEN`。
- [x] 只验证注册响应中的非敏感字段；Control Plane 的 connection-test 不作为 Worker 验收依据。
- [x] 注册独立模型 Provider 和模型目录项；Provider 只保存 `k8s://agentteams/agentteams-dashscope#api-key` 引用，Worker 环境变量使用 `DASHSCOPE_API_KEY`。

## 任务 5：创建并验收新 Worker

- [ ] 前置检查独立模型 Secret 已存在，且只向 Worker 注入 `DASHSCOPE_API_KEY`；不得从 `agentteams-mcp-tools/bailian-api-key` 复制。
- [ ] 创建、评审并发布 AGENTSCOPE Worker Template；实例化后由 Control Plane 创建新的 AgentSpec、Agent 和 Worker CR。
- [ ] 发布 AgentSpec，引用 `agentteams-web-search`，将配置部署到新 Worker。
- [ ] 验证新 Worker 环境存在 `MCP_TOOLS_TOKEN` 和模型凭据变量，不存在 `BAILIAN_API_KEY`。
- [ ] 提交带强制工具调用指令的验收任务，确认任务成功且事件中出现 `web_search` 工具调用；不记录搜索正文。
- [ ] 验收后确认 MCP Pod、新 Worker 和所有核心 Deployment Ready，节点可用内存不低于 `512 MiB`。

## 完成标准

- AgentScope Worker 生产包可解析 DashScope 模型。
- L5 配置以 Secret 引用方式自动向新 Worker 注入 MCP Token。
- MCP 注册、AgentSpec 配置和 Worker CR 中都不出现 Secret 明文。
- 新 Worker 实际调用 `web_search` 成功，既有 Worker 未被手工修改。
- 代码、配置和非敏感验收记录已提交并推送。

## 2026-09-20 L5 执行记录

- Worker 镜像：`agentteams-agent-worker:l5-d8243ce-amd64`，平台 `linux/amd64`，镜像摘要 `sha256:c2913bad420665c27a55200196065e76eb964a4753ef40045627f968cc8be4ec`。
- Control Plane 已滚动更新为 `2/2 Ready`；原有 3 个 QWENPAW Worker 保持 Ready，未重建。
- Worker Provisioner 分别引用 `agentteams-mcp-tools/mcp-tools-token` 和待创建的 `agentteams-dashscope/api-key`；未引用 `bailian-api-key`。
- MCP Server `agentteams-web-search` 已注册，仅允许 `http`、域名 `agentteams-mcp-tools` 和工具 `web_search`，超时上限为 30 秒。
- 模型 Provider `dashscope-l5` 及模型 `dashscope:qwen-plus` 已注册，工具调用能力已标记为启用。
- 滚动更新后所有核心 Deployment Ready，节点可用内存为 `953 MiB`。
- 尚未创建 `agentteams-dashscope` Secret，因此没有实例化新 AGENTSCOPE Worker，也没有执行真实模型工具调用验收。
