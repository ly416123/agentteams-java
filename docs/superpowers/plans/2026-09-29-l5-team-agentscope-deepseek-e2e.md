# L5 AgentScope + DeepSeek Team E2E 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 L5 管理后台对话创建 Control Plane Task，由 AgentScope Leader 调用 DeepSeek，按 Team 成员分配 Executor，并可追踪过程事件与成果物。

**架构：** Manager Conversation 在 Team 使用 UUID 作用域时创建并 Queue 一个带 `teamId`、`sourceConversationId` 和原始 prompt 的 AgentScope Task，不再让 QwenPaw 重复执行该请求。Control Plane 负责调度 Team；AgentScope Worker 使用官方 OpenAI-Compatible DeepSeek Provider。Team Deployment 在配置已 APPLIED 时立即收敛，不把 APPLIED 误判为超时。

**技术栈：** Java 17、Spring Boot、AgentScope 2.0.1、`agentscope-extensions-model-openai`、PostgreSQL、Kubernetes/Helm、JUnit 5。

**开发阶段约束：** 本计划不承担旧逻辑、旧 API 或旧运行时兼容；生产组合根只装配本计划定义的
`Conversation -> Control Plane Task -> Team Scheduler -> Leader/Worker -> Attempt -> Artifact`
主链路。测试中的 Fake/Noop 仅用于隔离单元测试，不得进入管理后台或 L5 验收配置。

---

### 任务 1：AgentScope 接入 DeepSeek Provider

**文件：**
- 修改：`pom.xml`
- 修改：`agent-worker/pom.xml`
- 修改：`agent-worker/src/test/java/io/agentteams/worker/WorkerRuntimeFactoryTest.java`
- 修改：`deploy/helm/l5-values.yaml`

- [ ] **步骤 1：编写失败测试。** 在 Worker Factory 测试中增加 `ModelRegistry.canResolve("deepseek:deepseek-chat")`，并断言模型创建上下文可以读取 `AGENTTEAMS_MODEL_BASE_URL`。
- [ ] **步骤 2：运行 Worker 测试确认失败。** 运行 `mvn -pl agent-worker -Dtest=WorkerRuntimeFactoryTest test`，预期 DeepSeek Provider 解析断言失败。
- [ ] **步骤 3：添加官方 Provider。** 在根 POM 的依赖管理和 `agent-worker/pom.xml` 中加入 `io.agentscope:agentscope-extensions-model-openai:2.0.1`；L5 Worker Provisioner 将 `DEEPSEEK_API_KEY` 映射到已有 Manager Secret 的 `deepseek-api-key`，并将 Team AgentSpec 模型改为 `deepseek:deepseek-chat`、Base URL 配为 `https://api.deepseek.com`，密钥不进入 Git、镜像或日志。
- [ ] **步骤 4：运行测试确认通过。** 重新运行 `mvn -pl agent-worker -Dtest=WorkerRuntimeFactoryTest test`，预期全部通过。

### 任务 2：修复 Team Deployment 的 APPLIED 误超时与 ACK 竞态

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/JdbcTeamDeploymentPendingTimeoutRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamDeploymentRepository.java`
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamDeploymentPendingTimeoutRepositoryTest.java`
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamDeploymentRepositoryTest.java`

- [ ] **步骤 1：编写失败测试。** 增加两个回归场景：`config_apply_records.phase='APPLIED'` 时超时扫描不更新 Team member；配置 ACK 早于 `markMember` 时，后者读取已完成 Apply 记录并将 member 置为 `SUCCEEDED`。
- [ ] **步骤 2：运行测试确认失败。** 运行对应两个测试类，预期当前实现会把 APPLIED 成员置为 `APPLY_TIMEOUT`，或保持 `PENDING`。
- [ ] **步骤 3：实施最小修复。** 超时 SQL 排除 `APPLIED`；`markMember` 写入 binding 后检查最新 Apply 记录并刷新部署聚合状态；只更新同一 deployment/member/binding，保持旧 ACK 的 generation fence。
- [ ] **步骤 4：运行测试确认通过。** 重新运行两个测试类，并运行 Control Plane Team 相关测试。

### 任务 3：把管理后台 Conversation 接到 AgentScope Task

**文件：**
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/TaskCommandPort.java`
- 修改：`manager/src/main/java/io/agentteams/manager/HttpTaskCommandPort.java`
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/ConversationService.java`
- 修改：`manager/src/main/java/io/agentteams/manager/ManagerApplication.java`
- 新增：`manager/src/main/java/io/agentteams/manager/conversation/ConversationTaskBridge.java`
- 新增：`manager/src/main/java/io/agentteams/manager/conversation/ControlPlaneConversationTaskBridge.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskCommandAdapter.java`
- 修改：`manager/src/test/java/io/agentteams/manager/HttpTaskCommandPortTest.java`
- 修改：`manager/src/test/java/io/agentteams/manager/conversation/ConversationServiceTest.java`
- 新增：`manager/src/test/java/io/agentteams/manager/conversation/ControlPlaneConversationTaskBridgeTest.java`

- [ ] **步骤 1：编写失败测试。** 验证 Conversation 首条消息会生成包含 `teamId`、`sourceConversationId`、`inputJson.prompt` 的 Task，并在创建后自动 Queue；验证重放同一消息不会重复创建 Task；生产 Conversation 不再走直接运行时路径。
- [ ] **步骤 2：运行测试确认失败。** 运行 Manager Conversation 和 HTTP Task Command 测试，预期当前没有 queue 端口和 Conversation Task Bridge。
- [ ] **步骤 3：实施最小桥接。** 扩展 `TaskCommandPort` 的 queue 操作；HTTP 端调用 `/api/v1/tasks/{id}/queue`。桥接要求 UUID Team，创建 `taskType=agentscope` Task，写入原始 prompt 与 `sourceConversationId`，然后 Queue；Conversation 记录 `task.created`、`task.updated` 和 `message.completed`，生产路径不调用 QwenPaw。
- [ ] **步骤 4：运行测试确认通过。** 运行 Manager 模块相关测试和 Control Plane 编译测试。

### 任务 4：构建、部署和 L5 真实回归

**文件：**
- 修改：`deploy/helm/l5-values.yaml`（只保留非敏感 Provider/镜像配置）
- 新增/修改：`docs/production/l5-team-deepseek-e2e.md`

- [ ] **步骤 1：构建 `linux/amd64` Worker、Manager、Control Plane 镜像。** 不输出或写入任何密钥。
- [ ] **步骤 2：升级 L5 并确认 Worker Pod 环境只包含 Secret 引用。** 核验 AgentScope Worker READY、Team Deployment SUCCEEDED、成员 Apply 状态 APPLIED。
- [ ] **步骤 3：通过管理后台 Conversation 创建真实任务。** 核验 Conversation 产生 Task ID，Task 被分配到 Leader，过程事件包含模型调用和子任务分配，Executor 产生执行事件，最终 `output.md` 与 `result.json` 可下载且 UTF-8 正常。
- [ ] **步骤 4：回归重复提交、SSE 重连、任务详情和成果物查看。** 失败时保留脱敏状态、过程事件和 Worker 错误摘要。

### 任务 5：实现 Leader 规划与 Executor 子任务闭环

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamSchedulingPolicy.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskDelegationService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/task/SubtaskGateService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneTaskExecutionObservationAdapter.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/application/ControlPlaneExecutionEventAdapter.java`
- 修改：`application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventPort.java`
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/ControlPlaneGatewayApplicationHandler.java`
- 修改：`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeRuntime.java`
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/task/TeamPlanParser.java`
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/task/TeamOrchestrationService.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamSchedulingPolicyTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/task/TeamPlanParserTest.java`

- [x] **步骤 1：编写失败测试。** 验证 Team 任务的 `LEADER`/`EXECUTOR` 角色严格路由；验证 Leader 计划 JSON 能生成两个稳定 UUID 的子任务，并拒绝缺少两个独立计划项的输出。
- [x] **步骤 2：运行测试确认失败。** 运行 `mvn -q -pl control-plane -am -Dtest=TeamSchedulingPolicyTest,TeamPlanParserTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期因角色字段和计划解析器尚不存在而失败。
- [x] **步骤 3：实现最小闭环。** 扩展任务执行结果协议传递 `resultJson`；Leader 仅执行规划阶段，Control Plane 校验并创建子任务；子任务固定要求 `EXECUTOR`，父任务在子任务全部成功后进入 `AGGREGATE` 阶段并再次路由给 Leader。
- [x] **步骤 4：运行单元与模块测试。** 运行上述测试及 Control Plane、Gateway、Worker 定向测试，确认状态版本和旧的普通任务路径不回归。
- [ ] **步骤 5：部署 L5 真实回归。** 使用现有 DeepSeek Team 创建任务，核验一个 Leader 规划、两个 Executor 分配与执行、一次 Leader 汇总，以及最终父任务 Artifact。

### 完成前检查

- [ ] `mvn` 定向测试通过；已知无关的 `CommandDispatcherMatrixTrustTest` 编译问题单独记录，不混入本次修复。
- [ ] L5 真实任务终态为 `SUCCEEDED`，不是仅 Worker READY。
- [ ] 任务确实由 Team Leader 分配，并有 Executor 过程记录。
- [ ] Git diff 不包含 API Key、Bearer Token、Secret 内容或预签名 URL。
