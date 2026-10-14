# L5 Team 子任务聚合 PDF 成果物验收设计

## 目标

在真实 L5 Team 流程中补充 PDF 交付场景：Leader 拆解任务，Executor 子任务被调度并全部成功后，Leader 聚合结果并用 `document_markdown_to_pdf` MCP 生成 PDF；PDF 必须上传为该父任务的 `OUTPUT` 成果物，并能从任务文件下载接口回读。

## 现有能力与边界

- Team 子任务等待、调度和聚合由 Control Plane 负责；验收应沿用现有 L5 子任务脚本，不改变 gate 或任务状态语义。
- MCP Tools 已提供 `document_markdown_to_pdf`，返回 `application/pdf` 的内嵌 resource；但 AgentScope 的 `upload_task_file` 读取工作区路径，不能直接消费另一个 MCP 工具返回的二进制 resource。
- MCP Tools 另有任务 Control Plane HTTP client 和子任务编排工具。增加专用 MCP 工具 `create_task_pdf_artifact`，在 MCP 服务端复用现有 Markdown PDF renderer 生成 PDF，并通过 Control Plane 任务文件 API 上传到指定父任务；不要求模型搬运 PDF 二进制。PDF 的生成必须发生在所有子任务成功后的聚合轮。
- AgentScope 仅开放每轮显式声明的任务 MCP 工具。任务拆解轮保留 `requiredMcpTools=[plan_subtasks]`；任务声明独立的 `aggregateMcpTools=[create_task_pdf_artifact]` 与 `aggregatePrompt`，子任务 gate 在进入聚合轮时将其转换为聚合轮专用工具要求与提示，防止计划工具和计划提示泄漏到聚合轮。
- 验收数据使用合成内容；不读取或修改业务文件与既有业务数据。

## 端到端验收

扩展 L5 Team 子任务验收脚本，要求并验证：

1. 父任务 Leader 规划出至少一个子任务并进入 `WAITING_SUBTASKS`。
2. 子任务由平台调度，全部进入 `SUCCEEDED`；父任务随后进入聚合轮并最终 `SUCCEEDED`。
3. 聚合轮真实调用 `create_task_pdf_artifact` MCP 工具；它使用现有 PDF renderer 生成 PDF 并上传为父任务 `OUTPUT` 成果物，返回文件 ID、文件名、MIME 与字节数；`tool.finished` 事件须记录该工具成功。
4. 父任务结果清单中恰有目标 PDF，文件名以 `.pdf` 结尾、MIME 为 `application/pdf`、状态为 `AVAILABLE`。
5. 使用任务文件下载接口回读成果物；校验内容非空且以 `%PDF-` 文件头开头。
6. 任一必要状态、工具事件、OUTPUT 元数据或下载校验缺失时，验收失败，不以单独的 MCP 调用记录代替成果物交付。

## 部署与回归

- 保留当前工作区已有的未提交修改；仅同步确认后的实现变更。
- 本地运行与新增脚本断言匹配的测试，并按项目门禁执行验证。
- L5 部署前检查 K3s 稳定、现有服务 Ready、Helm revision 和节点资源；使用 `linux/amd64` 镜像及仓库现有发布流程。部署后验证 Control Plane/MCP/Worker Ready，再运行真实 Team + PDF 验收并记录任务、子任务、成果物 ID 与下载结果。
- 若 L5 控制面不稳定或服务恢复需要非免密 sudo，则停止变更部署并报告可复现的前置故障；不绕过发布流程。
