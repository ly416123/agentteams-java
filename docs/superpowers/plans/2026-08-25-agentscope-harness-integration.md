# AgentScope Harness 集成实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 \`subagent-driven-development\`（推荐）或 \`executing-plans\` 逐任务实现此计划。步骤使用复选框（\`- [ ]\`）语法跟踪进度。

**目标：** 在不改变现有 Control Plane、Lease、审计和 Task-Sandbox 默认行为的前提下，将 AgentScope Harness 接入 Worker 和 Manager，形成可灰度、可回滚、可审计的 Agent 执行后端。

**架构：** AgentTeams 保留 Task、Attempt、Lease、Team、Quota、Audit、Outbox、NATS 和 \`SandboxRuntimePort\`；AgentScope Harness 通过 Worker/Manager Adapter 提供 Agent 会话、事件流、Workspace、模型和工具执行。运行时由应用层端口选择，默认继续使用 QwenPaw。

**技术栈：** Java 17、Maven、AgentScope Harness（固定版本）、Spring Boot、现有 gRPC/NATS、JUnit 5、Mockito、PostgreSQL、Kind、Helm、Python 契约测试。

---

## 文件清单

### 预计新增

- \`application-contracts/src/main/java/io/agentteams/application/api/ExecutionRuntime.java\`
- \`application-contracts/src/main/java/io/agentteams/application/api/AgentExecutionRuntimePort.java\`
- \`application-contracts/src/main/java/io/agentteams/application/api/ExecutionRequest.java\`
- \`application-contracts/src/main/java/io/agentteams/application/api/ExecutionHandle.java\`
- \`application-contracts/src/main/java/io/agentteams/application/api/ExecutionStatus.java\`
- \`application-contracts/src/main/java/io/agentteams/application/api/ExecutionEventSink.java\`
- \`application-contracts/src/test/java/io/agentteams/application/api/AgentExecutionRuntimeContractTest.java\`
- \`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeWorkerRuntime.java\`
- \`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeEventTranslator.java\`
- \`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeWorkspaceFactory.java\`
- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeEventTranslatorTest.java\`
- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeWorkerRuntimeTest.java\`
- \`manager/src/main/java/io/agentteams/manager/agentscope/AgentScopeModelProvider.java\`
- \`manager/src/test/java/io/agentteams/manager/agentscope/AgentScopeModelProviderTest.java\`
- \`integration-tests/src/test/java/io/agentteams/integration/AgentScopeWorkerEndToEndIT.java\`
- \`scripts/test-agentscope-runtime-contract.py\`

### 预计修改

- 根目录 \`pom.xml\`：统一 AgentScope 版本属性和依赖管理；
- \`agent-worker/pom.xml\`：增加 Harness 依赖和测试依赖；
- \`manager/pom.xml\`：增加 Manager 侧模型适配依赖；
- \`QwenPawWorker.java\`：通过运行时工厂选择执行后端；
- \`WorkerConfiguration.java\`：增加运行时白名单和灰度配置；
- \`ModelProviderRegistry.java\`：注册 AgentScope 模型适配器；
- \`JdbcModelCallAuditRecorder.java\`：验证 AgentScope 模型事件与现有审计字段兼容；
- \`deploy/helm/agentteams-java/values.yaml\`：增加运行时开关，但默认关闭；
- \`.github/workflows/ci.yml\`：只运行 Fake Model 验证，禁止注入真实模型凭据；
- \`README.md\` 和运维文档：补充运行时选择、Secret 和回滚说明。

---

### 任务 1：固定依赖并建立运行时开关

**目标：** 在不改变默认行为的情况下，让 Worker 和 Manager 能识别 \`AGENTSCOPE\` 运行时。

**测试文件：**

- \`application-contracts/src/test/java/io/agentteams/application/api/AgentExecutionRuntimeContractTest.java\`
- \`agent-worker/src/test/java/io/agentteams/worker/WorkerConfigurationTest.java\`

- [ ] **步骤 1：编写失败测试。**

覆盖以下行为：

~~~java
assertThat(ExecutionRuntime.from("QWENPAW")).isEqualTo(ExecutionRuntime.QWENPAW);
assertThat(ExecutionRuntime.from(null)).isEqualTo(ExecutionRuntime.QWENPAW);
assertThatThrownBy(() -> ExecutionRuntime.from("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(WorkerConfiguration.defaults().runtime()).isEqualTo(ExecutionRuntime.QWENPAW);
~~~

- [ ] **步骤 2：运行测试确认失败。**

~~~bash
mvn -q -pl application-contracts,agent-worker -am \
  -Dtest=AgentExecutionRuntimeContractTest,WorkerConfigurationTest test
~~~

预期：因运行时枚举和配置字段不存在而编译失败。

- [ ] **步骤 3：实现最小契约和配置。**

要求：

- 运行时只允许 \`LEGACY\`、\`QWENPAW\`、\`AGENTSCOPE\`；
- 缺省值为 \`QWENPAW\`；
- \`AGENTSCOPE\` 未启用时启动失败并给出明确配置错误，不能静默切换；
- 配置读取不打印任何凭证值。

- [ ] **步骤 4：固定 AgentScope Maven 版本并检查依赖树。**

以官方 README 当前示例版本作为初始固定版本，实际实现前验证 Maven Central 可解析和许可证信息；依赖只加入 \`agent-worker\`、\`manager\` 及其必要的适配模块，不加入 Control Plane 和 Operator。

~~~bash
mvn -q -pl agent-worker,manager -am dependency:tree
~~~

- [ ] **步骤 5：运行测试确认默认路径通过。**

~~~bash
mvn -q -pl application-contracts,agent-worker -am \
  -Dtest=AgentExecutionRuntimeContractTest,WorkerConfigurationTest,QwenPawConfigAppliedTest test
~~~

- [ ] **步骤 6：提交任务 1。**

~~~bash
git add pom.xml application-contracts agent-worker/pom.xml \
  agent-worker/src/main/java/io/agentteams/worker/WorkerConfiguration.java \
  agent-worker/src/test/java/io/agentteams/worker/WorkerConfigurationTest.java
git commit -m "feat(运行时): 增加 AgentScope 运行时开关"
~~~

### 任务 2：建立执行端口和事件转换契约

**目标：** 将 AgentScope API 与现有 \`ExecutionEventPort\` 隔离，先用 Fake Event 流证明协议兼容。

**测试文件：**

- \`application-contracts/src/test/java/io/agentteams/application/api/AgentExecutionRuntimeContractTest.java\`
- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeEventTranslatorTest.java\`

- [ ] **步骤 1：编写事件映射失败测试。**

测试 \`started\`、\`model_call\`、\`tool_call\`、\`content_delta\`、\`completed\`、\`failed\` 和未知事件，并断言所有已知事件均带有 Task、Attempt、Lease、Event 标识。

- [ ] **步骤 2：运行定向测试确认失败。**

~~~bash
mvn -q -pl application-contracts,agent-worker -am \
  -Dtest=AgentExecutionRuntimeContractTest,AgentScopeEventTranslatorTest test
~~~

- [ ] **步骤 3：实现最小端口和转换器。**

转换器必须：

- 使用 \`eventId + attemptId\` 生成幂等键；
- 对旧 Attempt 返回拒绝结果；
- 对未知事件输出脱敏诊断，不抛出会中断终态的异常；
- 截断模型输出和工具错误的超长字段；
- 不复制 Authorization Header、Prompt Secret 或完整工具参数。

- [ ] **步骤 4：运行测试确认通过。**

~~~bash
mvn -q -pl application-contracts,agent-worker -am \
  -Dtest=AgentExecutionRuntimeContractTest,AgentScopeEventTranslatorTest test
~~~

- [ ] **步骤 5：提交任务 2。**

~~~bash
git add application-contracts/src/main/java/io/agentteams/application/api \
  application-contracts/src/test/java/io/agentteams/application/api \
  agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeEventTranslator.java \
  agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeEventTranslatorTest.java
git commit -m "feat(执行协议): 增加 AgentScope 事件转换契约"
~~~

### 任务 3：实现 Worker AgentScope Runtime

**目标：** 使用确定性 Fake Model 完成 HarnessAgent 的创建、流式事件、取消、超时和关闭。

**测试文件：**

- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeWorkerRuntimeTest.java\`
- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/FakeAgentScopeHarnessTest.java\`

- [ ] **步骤 1：编写失败测试。**

覆盖：

~~~java
assertThat(runtime.start(request, sink).status()).isEqualTo(ExecutionStatus.RUNNING);
verify(sink).accept(argThat(event -> event.type().equals("ExecutionStarted")));
verify(sink).accept(argThat(event -> event.type().equals("ExecutionCompleted")));
~~~

另外验证取消只调用一次、超时会关闭 Session、Harness 异常转换为 \`ExecutionFailed\`，以及旧 Lease 不会继续发送事件。

- [ ] **步骤 2：运行测试确认失败。**

~~~bash
mvn -q -pl agent-worker -am \
  -Dtest=AgentScopeWorkerRuntimeTest,FakeAgentScopeHarnessTest test
~~~

- [ ] **步骤 3：实现 Worker Adapter。**

实现要求：

- AgentScope 类型只出现在 \`agent-worker/.../agentscope\` 包；
- 创建 HarnessAgent 时注入模型、Workspace、工具权限和 Session 标识；
- 通过 \`streamEvents()\` 转换并顺序写入 \`ExecutionEventSink\`；
- 取消、租约失效和超时均关闭 AgentScope Session；
- 所有异步回调携带 Attempt fencing 信息；
- 使用 Fake Model 时不访问网络。

- [ ] **步骤 4：运行定向测试。**

~~~bash
mvn -q -pl agent-worker -am \
  -Dtest=AgentScopeWorkerRuntimeTest,FakeAgentScopeHarnessTest,AgentScopeEventTranslatorTest test
~~~

- [ ] **步骤 5：提交任务 3。**

~~~bash
git add agent-worker/src/main/java/io/agentteams/worker/agentscope \
  agent-worker/src/test/java/io/agentteams/worker/agentscope \
  agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java
git commit -m "feat(Worker): 接入 AgentScope Harness 执行后端"
~~~

### 任务 4：接入 Task-Sandbox Workspace 和恢复语义

**目标：** 将 Sandbox 作为真实文件、进程和网络隔离边界，AgentScope Workspace 只做 Session 和文件访问适配。

**测试文件：**

- \`agent-worker/src/test/java/io/agentteams/worker/agentscope/AgentScopeWorkspaceFactoryTest.java\`
- \`integration-tests/src/test/java/io/agentteams/integration/AgentScopeSandboxRecoveryIT.java\`

- [ ] **步骤 1：编写 Workspace 隔离失败测试。**

验证相同 Attempt 可恢复同一 Workspace，新 Attempt 必须得到不同 Workspace；跨租户、跨 Team 的 Workspace 引用必须被拒绝；Sandbox 过期后 Session 不可继续写入事件。

- [ ] **步骤 2：实现 Workspace Factory。**

将 \`tenantId\`、\`projectId\`、\`teamId\`、\`agentId\`、\`taskId\`、\`attemptId\` 映射到 AgentScope Isolation Scope，并只接受 \`SandboxRuntimePort\` 返回的受控 Endpoint 或路径引用。

- [ ] **步骤 3：实现重启和 fencing 测试。**

使用 Fake Sandbox Provider 和 Fake Harness 验证 Worker 重启、Lease 过期、Sandbox \`LOST\`、任务重试和终态回收。

- [ ] **步骤 4：运行集成测试。**

~~~bash
mvn -q -pl integration-tests -am \
  -Dtest=AgentScopeSandboxRecoveryIT test
~~~

- [ ] **步骤 5：提交任务 4。**

~~~bash
git add agent-worker/src/main/java/io/agentteams/worker/agentscope \
  agent-worker/src/test/java/io/agentteams/worker/agentscope \
  integration-tests/src/test/java/io/agentteams/integration/AgentScopeSandboxRecoveryIT.java
git commit -m "feat(Sandbox): 接入 AgentScope Workspace 隔离"
~~~

### 任务 5：接入 Manager 模型和审计链路

**目标：** 让 Manager 可以使用 AgentScope 模型调用，同时继续使用现有价格、配额、凭证和审计事实。

**测试文件：**

- \`manager/src/test/java/io/agentteams/manager/agentscope/AgentScopeModelProviderTest.java\`
- \`manager/src/test/java/io/agentteams/manager/ModelCallAuditTest.java\`
- \`control-plane/src/test/java/io/agentteams/controlplane/service/ModelPriceCatalogServiceTest.java\`

- [ ] **步骤 1：编写模型适配失败测试。**

验证 Manager 从现有 \`ModelProviderRegistry\` 获得模型配置，调用前经过 \`ModelCallAdmission\`，完成后产生现有 \`ModelCallAudit\`，价格来自 \`ModelPriceCatalog\`，而不是 AgentScope 内置价格。

- [ ] **步骤 2：实现 AgentScope Model Adapter。**

适配器只负责将当前项目的模型配置、凭证和请求转换为 AgentScope 模型；不得读取任意环境变量，不得绕过 \`CredentialResolver\`、\`ModelPriceCatalogPort\` 或审计器。

- [ ] **步骤 3：增加凭证轮换验证。**

Fake Credential Provider 返回旧 Key 后切换新 Key，验证旧连接停止发送请求，新调用使用新 Key，日志和异常中不出现任一 Key。

- [ ] **步骤 4：运行 Manager 和 Control Plane 定向测试。**

~~~bash
mvn -q -pl manager,control-plane -am \
  -Dtest=AgentScopeModelProviderTest,ModelCallAuditTest,ModelPriceCatalogServiceTest test
~~~

- [ ] **步骤 5：提交任务 5。**

~~~bash
git add manager/src/main/java/io/agentteams/manager/agentscope \
  manager/src/test/java/io/agentteams/manager/agentscope \
  manager/src/test/java/io/agentteams/manager/ModelCallAuditTest.java
git commit -m "feat(Manager): 接入 AgentScope 模型审计链路"
~~~

### 任务 6：建立 Fake Model 端到端闭环

**目标：** 在不依赖外部模型和凭证的情况下验证完整链路。

**文件：**

- 创建 \`integration-tests/src/test/java/io/agentteams/integration/AgentScopeWorkerEndToEndIT.java\`；
- 创建 \`scripts/test-agentscope-runtime-contract.py\`；
- 修改 \`.github/workflows/ci.yml\`。

- [ ] **步骤 1：编写端到端失败测试。**

链路必须覆盖：创建 Task → Assignment → Lease → Worker 接收 → AgentScope Fake Model 事件 → Gateway → ExecutionEvent → Task 终态 → Sandbox 回收。

- [ ] **步骤 2：实现 Fake Model 和契约脚本。**

Fake Model 固定输出和 Token 用量，支持成功、工具拒绝、模型失败、取消和超时 5 种结果；脚本检查 CI 未引用 \`DEEPSEEK_API_KEY\`、\`QWENPAW_API_KEY\` 或其他凭证。

- [ ] **步骤 3：运行端到端测试。**

~~~bash
mvn -q -pl integration-tests -am \
  -Dtest=AgentScopeWorkerEndToEndIT test
python3 -m unittest scripts/test-agentscope-runtime-contract.py
~~~

- [ ] **步骤 4：提交任务 6。**

~~~bash
git add integration-tests scripts .github/workflows/ci.yml
git commit -m "test(AgentScope): 增加 Fake Model 端到端验收"
~~~

### 任务 7：灰度配置、指标和回滚

**目标：** 让 AgentScope 可以按 Agent、Team 或租户逐步启用，出现问题时回到 QwenPaw。

**文件：**

- 修改 \`deploy/helm/agentteams-java/values.yaml\`；
- 修改相关 Deployment 模板和 ConfigMap 模板；
- 修改 Worker 配置测试；
- 修改 \`README.md\` 和运维文档。

- [ ] **步骤 1：增加默认关闭的 Helm 配置。**

~~~yaml
agentRuntime:
  default: QWENPAW
  agentScope:
    enabled: false
    rolloutPercentage: 0
~~~

- [ ] **步骤 2：增加指标和脱敏日志测试。**

验证成功、失败、取消、工具拒绝、事件转换失败和 Session 数量都能观察，日志不包含凭证、完整 Prompt、工具 Secret 和宿主路径。

- [ ] **步骤 3：运行配置和清单校验。**

~~~bash
helm lint deploy/helm/agentteams-java
python3 scripts/validate-kind-manifests.py
python3 -m unittest scripts/test-agentscope-runtime-contract.py
~~~

- [ ] **步骤 4：提交任务 7。**

~~~bash
git add deploy/helm/agentteams-java README.md docs scripts
git commit -m "chore(部署): 增加 AgentScope 灰度和回滚配置"
~~~

### 任务 8：完整回归和可选真实 DeepSeek 冒烟

**目标：** 证明新运行时不破坏已有能力，并明确真实模型只在本地或受控环境验证。

- [ ] **步骤 1：运行模块测试。**

~~~bash
mvn -q -pl application-contracts,agent-worker,manager,runtime -am verify
~~~

- [ ] **步骤 2：运行完整集成测试。**

~~~bash
mvn -q -Pintegration-tests verify
~~~

- [ ] **步骤 3：运行 Kubernetes 契约校验。**

~~~bash
helm lint deploy/helm/agentteams-java
python3 scripts/validate-kind-manifests.py
python3 -m unittest scripts/test_kind_task_sandbox_contract.py
~~~

- [ ] **步骤 4：本地执行可选真实模型冒烟。**

仅当本机存在凭证时执行，凭证从环境变量读取，不能写入命令历史、文件或 CI：

~~~bash
if [ -n "\${DEEPSEEK_API_KEY:-}" ]; then
  DEEPSEEK_API_KEY="\$DEEPSEEK_API_KEY" \
    mvn -q -pl agent-worker,manager -am \
    -Dtest=AgentScopeDeepSeekSmokeTest test
fi
~~~

预期：没有本地 Key 时测试跳过；有 Key 时只验证真实 HTTP 调用、流式事件、成本审计和凭证轮换，不输出 Key。

- [ ] **步骤 5：提交最终回归结果。**

~~~bash
git add docs README.md
git commit -m "docs(AgentScope): 补充集成验收和回滚说明"
~~~

## 完成定义

- 所有任务的定向测试和完整回归通过；
- AgentScope 默认关闭时现有 QwenPaw CI 全部通过；
- Fake Model 端到端闭环通过；
- Manager 模型价格、Token、审计和 Secret 轮换验证通过；
- Sandbox Workspace 隔离、Lease fencing 和 Worker 重启恢复通过；
- Helm、Kind、权限和 Secret 扫描通过；
- 文档明确启用、灰度、回滚和本地真实模型验证方式。

