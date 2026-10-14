# AgentTeams 任务编排 HTTP MCP 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框跟踪进度。

**目标：** 为 AgentScope Worker 提供可访问 Control Plane 子任务 API 的远程 Streamable HTTP MCP，恢复真实 Team 的 `plan_subtasks`、子任务查询和状态更新流程，同时保持 qwenpaw 不参与执行链路。

**架构：** 在现有独立 `agentteams-mcp-tools` 服务中增加任务编排工具。工具服务只负责 MCP 协议、输入校验和 Control Plane HTTP 调用；任务状态、作用域和幂等性仍由 Control Plane 负责。L5 通过 NetworkPolicy 允许 MCP 工具服务访问 Control Plane，Worker 继续通过同一个 MCP 服务发现工具。

**技术栈：** Java 21、Spring Boot、MCP Java SDK 2.0.1、Java `HttpClient`、JUnit 5、Testcontainers/MockWebServer。

---

### 任务 1：任务 MCP 客户端边界

**文件：**
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/config/TaskProperties.java`
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/task/TaskControlPlaneClient.java`
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/test/java/io/agentteams/mcptools/task/TaskControlPlaneClientTest.java`

- [ ] 先写测试：验证 `planSubtasks` 发送 `PUT /api/v1/tasks/{taskId}/subtasks`、`Idempotency-Key` 和 JSON 请求体；验证 Control Plane 非 2xx 响应转换为不泄露响应正文的可操作异常。
- [ ] 运行 `mvn -B -Dtest=TaskControlPlaneClientTest test`，预期因客户端类不存在而失败。
- [ ] 实现配置类和基于 `HttpClient` 的客户端，默认连接 `http://agentteams-agentteams-java-control-plane:8080`，超时 20 秒；Bearer Token 仅在显式配置时发送。
- [ ] 运行同一测试，预期全部通过。

### 任务 2：注册任务编排 MCP Tools

**文件：**
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/task/TaskOrchestrationTool.java`
- 创建：`/Users/gecko/code/agentteams-mcp-tools/src/test/java/io/agentteams/mcptools/task/TaskOrchestrationToolTest.java`
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/java/io/agentteams/mcptools/config/McpServerConfig.java`

- [ ] 先写测试：验证 `tools/list` 所需的 3 个工具定义；验证合法 `plan_subtasks` 调用转换为 UUID、序号、依赖关系并调用客户端；验证非法 UUID、空列表、超过 20 个子任务和非法状态返回 `isError=true`；验证下游异常不抛出到 MCP 传输层。
- [ ] 运行 `mvn -B -Dtest=TaskOrchestrationToolTest test`，预期因工具类不存在而失败。
- [ ] 实现 `plan_subtasks`、`list_subtasks`、`update_subtask_status`，工具返回结构化 JSON 文本，错误返回稳定错误码；不在工具服务内复制任务状态机。
- [ ] 在 `McpServerConfig` 注册 3 个工具。
- [ ] 运行测试，预期全部通过。

### 任务 3：配置和网络边界

**文件：**
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/main/resources/application.yml`
- 修改：`/Users/gecko/code/agentteams-mcp-tools/deploy/k8s.yaml`
- 修改：`/Users/gecko/code/agentteams-mcp-tools/deploy/l5/deployment-patch.yaml`
- 修改：`/Users/gecko/code/agentteams-java/deploy/helm/agentteams-java/templates/networkpolicy.yaml`
- 修改：`/Users/gecko/code/agentteams-java/deploy/helm/l5-values.yaml`

- [ ] 增加 `agentteams.task.control-plane-url`、请求超时和可选 Bearer Token 配置；不把 Token 写入镜像、AgentSpec 或日志。
- [ ] 为 MCP Tools Deployment 增加 Control Plane Service URL 配置。
- [ ] 为 Control Plane Ingress 增加 `agentteams-mcp-tools` Pod 来源，仅开放 8080；保持其他网络策略不变。
- [ ] 用 Helm/Kustomize 渲染校验，确认 Worker → MCP 和 MCP → Control Plane 两条链路均存在，且不存在 `0.0.0.0/0` 放宽。

### 任务 4：协议集成回归

**文件：**
- 修改：`/Users/gecko/code/agentteams-mcp-tools/src/test/java/io/agentteams/mcptools/McpServerIntegrationTest.java`

- [ ] 扩展集成测试，确认 Streamable HTTP `initialize → tools/list` 包含 3 个任务工具，并调用 `plan_subtasks` 时向 Mock Control Plane 发出正确请求。
- [ ] 运行 `mvn -B verify`，预期所有 MCP Tools 单元测试和集成测试通过。
- [ ] 在 Java 主仓库运行 `mvn -q -pl control-plane -am -Dtest=ExecutionEventServiceTest,SubtaskServiceTest,TeamPlanParserTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认分配释放和拆解投影回归不受影响。

### 任务 5：L5 部署与真实 Team 验收

**文件：**
- 修改：`/Users/gecko/code/agentteams-mcp-tools/deploy/l5/kustomization.yaml`
- 修改：`/Users/gecko/code/agentteams-java/scripts/run-l5-subtask-delegation.py`（仅在验收断言需要时）

- [ ] 构建并检查 `linux/amd64` MCP Tools 镜像，导入 L5 K3s，不轮换现有 MCP Secret。
- [ ] 滚动更新 MCP Tools，确认 Pod Ready、重启次数为 0、Control Plane 和 Worker 保持 Ready。
- [ ] 重新发布 Leader AgentSpec 的 MCP 引用，确认配置 apply 状态为 `APPLIED`，并确认 `tools/list` 实际包含 `plan_subtasks`。
- [ ] 运行真实 Team 验收脚本，验证 Leader 调用 `plan_subtasks`、子任务分别进入执行、依赖 gate 生效、汇总轮生成唯一最终结果、事件流无 stale lease 错误。
- [ ] 保存任务 ID、子任务 ID、关键事件和镜像摘要，执行 `git diff --check`；不声明未验证的结果。
