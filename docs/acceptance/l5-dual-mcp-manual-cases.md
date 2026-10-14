# L5 双 MCP 手工验收用例

## 测试目标

验证一个多智能体任务能否在同一流程中调用 `ai-news/search_news` 和 `agentteams-web-search/web_search`，通过依赖子任务完成新闻研究、官方资料核验、中文报告撰写，并将唯一最终 PDF 作为父任务成果物展示和下载。

本轮 MCP Tools 部署版本：`l5-pdf-safe-diagnostics-20261010-amd64`。

## 测试前置条件

1. 打开 L5 控制台：<http://192.168.122.55:30080>，使用已准备的评测用户登录。
2. 进入 `project-a`，创建任务时选择之前准备的隔离评测 Team。该 Team 的 Executor AgentSpec 应同时绑定 `ai-news` 和 `agentteams-web-search`；Leader 应具备任务编排和 PDF 成果物工具。
3. 确认 Team 的 Leader、Executor Worker 均为 Ready，MCP 工具 allowlist 包含 `search_news`、`web_search` 和 `create_task_pdf_artifact`。
4. 每个用例都新建一个父任务，避免旧任务状态和文件影响结果。不要把 Token、密码或其他凭据写入任务消息。

如果创建任务时报 403，先确认登录账号在 `tenant-a / project-a` 有效，任务使用已发布 AgentSpec 对应的 team；授权 scope 使用规范 team 值 `team-a`，调度仍使用所选 Team ID。

## 用例列表

| 用例 ID | 研究主题 | `web_search` 官方核验重点 | 最终 PDF 文件名 |
| --- | --- | --- | --- |
| `dual-mcp-10-agent-frameworks` | AI 智能体框架 | 框架近期能力变化及项目官方文档中的技术事实 | `agent-framework-final.pdf` |
| `dual-mcp-10-mcp-ecosystem` | MCP 生态与协议 | MCP 生态新闻与 MCP 官方规范中的协议事实 | `mcp-ecosystem-final.pdf` |
| `dual-mcp-10-coding-agents` | AI 编程智能体 | 产品能力声明与厂商官方文档中的功能边界 | `coding-agent-final.pdf` |
| `dual-mcp-10-multimodal-agents` | 多模态与语音智能体 | 多模态能力声明与官方 API 的输入、输出能力 | `multimodal-agent-final.pdf` |
| `dual-mcp-10-agent-interoperability` | 智能体互操作 | MCP、A2A 进展与协议或项目官方文档中的机制 | `agent-interoperability-final.pdf` |
| `dual-mcp-10-agent-security` | 智能体安全 | 安全风险或缓解措施与 OWASP / 项目官方安全文档 | `agent-security-final.pdf` |
| `dual-mcp-10-agent-evaluation` | 智能体评测与基准 | 评测方法与论文或基准官方说明中的测量对象 | `agent-evaluation-final.pdf` |
| `dual-mcp-10-agent-infrastructure` | 推理与智能体基础设施 | 基础设施能力声明与供应商官方技术文档中的限制 | `agent-infrastructure-final.pdf` |
| `dual-mcp-10-ai-governance` | AI 治理与监管 | 政策或标准进展与监管机构 / 标准组织原始文件 | `ai-governance-final.pdf` |
| `dual-mcp-10-enterprise-agents` | 企业智能体落地 | 企业案例与企业或产品官方材料中的案例范围、效果口径 | `enterprise-agent-final.pdf` |

## 可复制的任务消息

每次新建一个任务，从上表选择一个主题和文件名，替换下面的方括号内容后发送：

```text
请用简体中文完成“[研究主题]”的双 MCP 多智能体研究，关注最近 7 天的行业动态。

必须创建两个有依赖关系的子任务：
1. 研究子任务调用 search_news 检索相关新闻，保留工具实际返回的标题、发布日期、媒体和原文链接；再调用 web_search 检索官方资料，按照“[官方核验重点]”核实至少一项技术或政策事实。来源 URL 必须逐字复制工具返回内容，不得编造。生成研究笔记 PDF，文件名可设为 [主题]-research-notes.pdf。
2. 报告子任务依赖研究子任务，基于已交接的研究结果撰写结论、新闻发现、官方核验、影响分析和来源列表，并且恰好调用一次 create_task_pdf_artifact，生成最终文件 [最终 PDF 文件名]。

最终 PDF 必须使用简体中文；产品名、标准名、工具名、来源标题和 URL 可保留原文。父任务只展示并选择报告子任务生成的这一个最终 PDF，不要把研究笔记 PDF 作为父任务成果物，也不要创建其他最终 PDF。报告中不得虚构新闻、事实或来源。完成后在父任务结果中提供简短结论和可下载的最终 PDF。
```

### 首次验证推荐消息

建议先跑 `dual-mcp-10-mcp-ecosystem`，覆盖两个 MCP、官方协议核验、子任务依赖、中文 PDF 和父任务成果物下载：

```text
请用简体中文完成“MCP 生态与协议”的双 MCP 多智能体研究，关注最近 7 天 MCP 生态、服务器 / 客户端和协议兼容性动态。

必须创建两个有依赖关系的子任务。研究子任务调用 search_news 检索相关新闻，保留工具实际返回的标题、发布日期、媒体和原文链接；再调用 web_search 对照 MCP 官方规范，核实至少一项协议事实，并保留逐字来源 URL。不得编造工具结果、事实或来源。研究子任务可生成中间 PDF `mcp-ecosystem-research-notes.pdf`。

报告子任务必须依赖研究子任务，基于已交接的材料整理结论、新闻发现、官方核验、影响分析和来源列表，并恰好调用一次 create_task_pdf_artifact，生成 `mcp-ecosystem-final.pdf`。最终报告和 PDF 使用简体中文，专有名词、工具名、来源标题和 URL 可保留原文。父任务只选择报告子任务的这个最终 PDF 作为唯一交付物，不展示中间 PDF。完成后给出简短结论和父任务中的 PDF 下载入口。
```

## 通过标准

- 父任务和最终 Run 均为 `SUCCEEDED`。
- 任务包含两个有依赖关系的子任务，依赖交接成功；研究子任务和报告子任务均完成。
- 执行记录显示 `search_news` 与 `web_search` 调用成功，且官方核验 URL 可在对应 `web_search` 结果中找到。
- 报告子任务恰好调用一次 `create_task_pdf_artifact`。
- 父任务成果物列表只显示选定的最终 PDF；文件名与用例表一致，类型为 PDF，可以下载。
- 下载后的 PDF 可打开，正文是简体中文，包含结论、新闻信息、官方核验、影响分析和来源；中间研究 PDF 不出现在父任务最终成果物列表中。

## 记录结果

每个用例记录以下信息，供后续复核：

| 字段 | 记录内容 |
| --- | --- |
| 用例 ID | 对应上表 ID |
| 父任务 ID / Run ID | 页面详情中的 UUID |
| 子任务 ID | 研究子任务、报告子任务的 UUID |
| MCP 调用 | `search_news`、`web_search`、`create_task_pdf_artifact` 的成功 / 失败 |
| 任务结果 | 父任务、Run、依赖交接状态 |
| 最终文件 | 文件名、是否只在父任务展示、是否可下载、PDF 是否能打开 |
| 结果 | 通过 / 失败，并附失败阶段和页面提示 |

## 失败时提供的信息

请提供父任务 ID、失败阶段、页面显示的错误类别和发生时间。不要提供 Token、密码或 PDF 正文。当前 L5 MCP 日志会记录任务 ID、文件名、Markdown 字符数、PDF 字节数和异常类型链，可据此区分 PDF 渲染失败与 Control Plane 上传失败。
