# AI 新闻与 Web Search 团队集成评测设计

**状态：** 用户已确认；按隔离 Executor 的运行策略调整
**日期：** 2026-10-09  
**目标环境：** L5，`tenant-a / project-a / team-a`

## 1. 目标

让现有隔离评测 Team 同时使用 AI 新闻 MCP 和 `web_search` MCP，完成一项必须结合新闻线索与官方资料的多 Agent 研究任务。任务需要经过拆解、研究、依赖交接、汇总和 PDF 交付，并用确定性评测报告验证整体流程。完成部署和验收后，用户可以在 L5 手动运行 Team 并检查报告。

本轮不改共享 `team-a` 的已发布配置，也不创建新的长期 Worker。

## 2. 已核实的现状

- L5 尚未登记 `ai-news`；已有 `agentteams-web-search` 和 `agentteams-mcp-tools`。
- L5 上的隔离 Team `l5-agentteams-pdf-eval-20261008-1705-6d4a` 当前有 2 名成员，Team revision 为 `PUBLISHED`，Leader 和 Executor Worker 均为 Ready。
- Executor AgentSpec 当前绑定 `agentteams-web-search` 和 `industry-research@0.0.5`；Leader AgentSpec 已绑定 `agentteams-mcp-tools`，可执行任务编排和 PDF 工具。
- `quota-admin` 在 `tenant-a / project-a` 的成员状态为 `ACTIVE`，角色为 `ADMIN`。隔离 Team 的资源范围是 `tenant-a / project-a / team-a`。
- L5 节点内存使用率约为 66%，主机没有 Docker 命令，但 K3s 可以运行工作负载并导入本地构建的 OCI 镜像。
- `ai-news` 提供 `search_news(query, limit)`，返回标题、原文链接、来源、分类、相关度和发布时间。
- 现有评测框架支持成功工具生命周期、子任务依赖和交接、来源引用、PDF 完整性等确定性断言。来源证据目前只对 `web_search` 有结构化采集，因此 AI 新闻 MCP 的证据以工具成功事件和最终 PDF 中的新闻链接呈现，人工检查新闻内容与引用对应关系。

## 3. 方案与选择

| 方案 | 说明 | 取舍 |
|---|---|---|
| 复用现有隔离评测 Team | 将 `ai-news` 加入现有 Executor AgentSpec，发布新 AgentSpec 和 Team revision，保留两名现有成员 | 不新增常驻 Worker；Team 名称仍带 `pdf-eval`，但隔离边界清晰，且能直接复用 PDF 流程 |
| 新建隔离 Team | 新建 Leader、Executor、AgentSpec 和 Worker | 命名更贴合新任务，但会增加 L5 常驻资源和部署对象 |

**选择：** 复用现有隔离评测 Team。该 Team 与共享 `team-a` 分开，已经具备 `web_search`、任务编排和 PDF 交付能力，新增 `ai-news` 后即可覆盖目标流程，同时控制 L5 资源占用。

## 4. 架构与数据流

### 4.1 L5 AI 新闻 MCP 服务

将独立 Python `news` 项目构建为 `linux/amd64` OCI 镜像，在 L5 上导入 K3s。部署包含：

- Redis StatefulSet 和持久卷，保存新闻索引；
- MCP Deployment 和 ClusterIP Service，提供 `http://ai-news-mcp:8000/mcp`；
- 定时抓取 CronJob，调用 `python -m news run-once --dry-run` 更新 Redis，不配置或发送通知 Webhook；
- 通过按 Pod 标签选择目标的 NetworkPolicy，为 Control Plane 和 Agent Worker 分别开放到该 MCP Service 的 TCP 8000 出站访问；不扩大到其他目标或端口。

服务配置只读挂载来源列表和抓取设置。镜像不包含 `.env`、凭证或通知密钥。首次运行抓取成功并且 MCP `tools/list` 返回 `search_news` 后，才注册 MCP 目录。

### 4.2 MCP 注册与 Team 更新

在 L5 Control Plane 注册 `ai-news`，endpoint 指向集群内 Service。允许的协议仅为 HTTP，允许的主机仅为 `ai-news-mcp`，允许的工具仅为 `search_news`，超时不超过 30 秒。资源范围设置为 `tenant-a / project-a / team-a`。

更新隔离 Team 的 Executor AgentSpec：

```json
{
  "mcpRefs": ["agentteams-web-search", "ai-news"],
  "skillRefs": []
}
```

现有 `industry-research@0.0.5` 的 MCP capability policy 只允许 `web_search`，Worker 会对多个 Skill policy 取交集，因此保留它会过滤 `search_news`。用户已选择仅对隔离评测 Executor 移除此非必需 Skill binding；不修改共享 Team 或 Skill 资源。两项 MCP 都通过精确的 AgentSpec `mcpRefs` 绑定，Executor 子任务仅请求 `search_news` 与 `web_search`；AI News MCP 本身只暴露 `search_news`，平台 MCP 的任务工具过滤仅启用本 Case 请求的 `web_search`。通过正式 AgentSpec 发布、Worker 配置 ACK、Team revision 发布和显式部署流程生效。Leader 保留现有任务编排与 PDF 工具。完成后检查 Executor 的运行时配置同时包含两个 MCP binding，并确认实际工具调用集合仅包含预期工具。

### 4.3 端到端任务

新增独立评测用例 `ai-news-web-search-pdf-positive`，不覆盖或改写历史 Case 和报告。

Leader 计划研究和撰写子任务，并要求：

1. 研究子任务调用 `search_news` 搜索近期 AI Agent/MCP 行业新闻，保留实际返回的标题、日期、媒体和 URL。
2. 同一子任务调用 `web_search` 查询官方 MCP 文档，核实新闻中涉及的协议能力或技术判断，并保留官方来源 URL。
3. 报告子任务依赖研究结果，基于上游结果撰写简报。
4. Leader 汇总子任务材料，创建一个可下载 PDF。PDF 包含结论、新闻事实、官方资料核验、影响分析和来源列表。

所有子任务必须使用 `search_news` 和 `web_search`。由于现有委派契约为父输入提供一组统一的 `executorMcpTools`，两项工具会对每个 Executor 子任务都作为必需工具。Leader 的 `requiredMcpTools` 仍只包含 `plan_subtasks`，汇总阶段保留 `create_task_pdf_artifact`。

## 5. 评测门禁

新 Case 至少包含以下断言：

| 维度 | 通过条件 |
|---|---|
| 执行状态 | Task phase 和 Run status 均为 `SUCCEEDED` |
| 新闻 MCP | 子任务存在 `search_news` 调用及成功完成事件 |
| Web Search MCP | 子任务存在 `web_search` 调用及成功完成事件 |
| 编排 | 至少存在依赖边；所有依赖子任务成功；每条依赖都有结果交接引用 |
| 来源 | 最终输出引用已验证 `web_search` 来源 URL；新闻来源链接出现在 PDF 的来源列表，供人工核对 |
| PDF | 恰有一个可下载 PDF；内容类型、扩展名、大小、SHA-256、文件签名和 PDF 页面树均有效 |

任何必需工具失败、依赖失败或 PDF 契约失败，整体评测不得通过。关键证据截断或缺失时返回 `INCONCLUSIVE`。测试报告继续过滤 Token、凭证和预签名下载 URL。

## 6. 失败处理与安全边界

- MCP Service 未 Ready、Redis 未 Ready、首次抓取无结果或 `tools/list` 不含 `search_news` 时，不发布 AgentSpec 或 Team revision。
- MCP Discovery、连接测试或工具调用失败时，停止后续发布/评测，保留脱敏错误分类。
- Control Plane 用 MCP 连接器进行发现和连通性检查；Agent Worker 的 AgentScope MCP Runtime 会直接连接 MCP Endpoint 执行工具调用。因此两者都必须能访问 `ai-news-mcp:8000`，并由 NetworkPolicy 将访问范围限制到该服务和端口。
- 不在仓库、ConfigMap、日志和报告中存放密码、Token 或外部 MCP 凭证。
- 用户的 `quota-admin` Project ADMIN 权限保持现状；不创建或临时提升其他成员。
- 只调整隔离评测 Executor 的 Skill 引用；Skill 资源和共享 Team 配置保持不变。
- 所有新用例和报告使用独立文件名；部署仅涉及新增 AI 新闻工作负载、MCP 注册和隔离评测 Team 的新 revision。

## 7. 验收与交付

### 本地验收

- 静态检查新的 Kubernetes 清单、Helm 配置和评测 Case。
- 测评契约测试验证缺失 `search_news` 或 `web_search` 时 Case 失败；不调用 L5 的离线测试不产生真实 Task 报告。
- 构建 `linux/amd64` 镜像并验证镜像可导入本地 K3s 格式。

### L5 验收

1. 读取并复核镜像摘要、AI 新闻 Service、Redis PVC、抓取 CronJob、NetworkPolicy 和 MCP allowlist。
2. 运行 MCP Discovery 和真实 `search_news` 冒烟。
3. 发布 Executor AgentSpec 和 Team 新 revision，等待两个 Worker 的配置 ACK。
4. 运行新 Case，生成独立的脱敏 JSON 评测报告并保存实际 Task/Run/子任务 ID。
5. 检查门禁全通过后，提供 Team、Worker、PDF 和报告位置，供用户人工验证。

若任何门禁未通过，保留真实失败或不确定结果，不把任务执行成功当作评测通过。

## 8. 规格自检

- 部署、网络、权限、团队绑定、任务流程、评测断言和人工验收均有明确范围。
- L5 没有 Docker，因此镜像构建与导入路径已明确。
- AI 新闻来源缺少结构化来源采集，因此评测只对 `web_search` URL 进行机器验证，AI 新闻链接列入 PDF 并由人工核验；不会把该人工核验标记为确定性机器门禁。
- 不修改共享 `team-a`，也不创建额外常驻 Worker。
