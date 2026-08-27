# 批次 A：生产主路径修复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 `8b070c8` 基线上完成 Kubernetes Sandbox、AgentScope Worker 路由、Manager 正式服务、Team Revision/Effective Config，以及对应 Helm 安全配置。

**实施状态：** 已完成（2026-08-27）。批次 A 实现通过 `c1f4602` 合并到 `main`，并在后续提交中完成 CI 稳定性修复；当前 `main` 基线为 `fd721d3`。

**验收边界：** Java 模块测试、Helm/静态契约、迁移校验和 GitHub Actions 的 `verify`、`kind-recovery`、`kind-oidc` 均已通过。gVisor/Kata 真实 RuntimeClass、外部 Secret Manager、外部 IdP、生产镜像签名和预发布恢复演练属于 L5/L6 受控环境，不在本计划中宣称完成。

**架构：** 先冻结 `application-contracts` 中的异步 Sandbox Provider 和 Worker Runtime 语义，再按 Sandbox/Operator、Worker、Manager、Team/Config、Helm 五条泳道实现。PostgreSQL 保存业务事实，Kubernetes 只保存 CR 期望态和状态投影，所有外部调用都在已提交意图之后执行。

**技术栈：** Java 17、Spring Boot 3.4.5、Spring JDBC、Flyway、Fabric8 Kubernetes Client、Java Operator SDK、gRPC、Jackson、JUnit 5、AssertJ、Mockito、Maven、Helm、Kind。

---

## 文件清单与职责

### 公共契约和 Sandbox

- 修改：`application-contracts/src/main/java/io/agentteams/application/api/SandboxRuntimePort.java`，定义异步幂等 Provider 入口。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/SandboxProviderRef.java`、`SandboxProvisionCommand.java`、`SandboxProvisionReceipt.java`、`SandboxObservation.java`、`SandboxRenewCommand.java`、`SandboxRenewReceipt.java`、`SandboxTerminationCommand.java`、`SandboxTerminationReceipt.java`、`SandboxFailure.java`、`SandboxProviderPhase.java`。
- 创建：`application-contracts/src/main/java/io/agentteams/application/api/SandboxFailureCategory.java`、`SandboxProviderException.java`，统一 Provider 错误分类和异常边界。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/FakeSandboxRuntime.java`、`SandboxLifecycleService.java`、`ControlPlaneConfiguration.java`。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/KubernetesSandboxRuntime.java`、`SandboxRuntimeProperties.java`、`SandboxLifecycleScheduler.java`。
- 修改/创建：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskSandboxRepository.java`、`TaskSandboxRecord.java`、`control-plane/src/main/resources/db/migration/V43__sandbox_provider_state.sql`。
- 测试：`application-contracts/src/test/java/io/agentteams/application/api/SandboxProviderContractTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/sandbox/KubernetesSandboxRuntimeTest.java`、`SandboxLifecycleSchedulerTest.java`、`SandboxLifecycleServiceTest.java`。

### Operator 与 Worker

- 修改：`operator/src/main/java/io/agentteams/operator/TaskSandboxReconciler.java`、`TaskSandboxResourceFactory.java`、`WorkerResourceFactory.java`。
- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandboxStatusMapper.java`。
- 修改：`agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`。
- 创建：`agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeFactory.java`、`WorkerRuntimeRouter.java`、`agent-worker/src/main/java/io/agentteams/worker/agentscope/ConfiguredAgentScopeHarnessFactory.java`、`SandboxStateProbePort.java`。
- 测试：对应 `operator/src/test/java/io/agentteams/operator/*`、`agent-worker/src/test/java/io/agentteams/worker/WorkerRuntimeRouterTest.java`、`WorkerRuntimeFactoryTest.java`、`agentscope/ConfiguredAgentScopeHarnessFactoryTest.java`。

### Manager

- 修改：`manager/pom.xml`。
- 创建：`manager/src/main/java/io/agentteams/manager/ManagerApplication.java`、`api/ManagerSessionController.java`、`api/ManagerErrorHandler.java`、`session/JdbcManagerSessionRepository.java`、`session/ManagerSessionRecord.java`、`session/ManagerMessageRecord.java`、`session/ManagerToolCallRecord.java`、`session/ManagerEventRecord.java`、`session/ManagerSessionServiceFacade.java`。
- 创建：`manager/src/main/resources/db/migration/V1__manager_sessions.sql`。
- 测试：`manager/src/test/java/io/agentteams/manager/api/ManagerSessionControllerTest.java`、`session/JdbcManagerSessionRepositoryTest.java`、`ManagerSessionServiceFacadeTest.java`。
- 修改：`deploy/docker/manager.Dockerfile`、`deploy/helm/agentteams-java/templates/manager.yaml`。

### Team Revision 与 Effective Config

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevision.java`、`TeamRevisionRepository.java`、`TeamRevisionService.java`、`TeamDeploymentService.java`、`control-plane/src/main/java/io/agentteams/controlplane/config/EffectiveConfig.java`、`EffectiveConfigComposer.java`。
- 创建：`control-plane/src/main/resources/db/migration/V44__team_revisions.sql`、`V45__team_revision_bindings.sql`。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TeamController.java`、`agentspec/AgentSpecDeploymentService.java`、`config/ConfigDeploymentService.java`。
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamRevisionServiceTest.java`、`TeamDeploymentServiceTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/config/EffectiveConfigComposerTest.java`。

### Helm、权限和文档

- 创建：`deploy/helm/agentteams-java/values.schema.json`、`deploy/helm/agentteams-java/templates/manager.yaml`、`deploy/helm/agentteams-java/templates/manager-service.yaml`、`deploy/helm/agentteams-java/templates/manager-networkpolicy.yaml`。
- 修改：`deploy/helm/agentteams-java/values.yaml`、`templates/networkpolicy.yaml`、`templates/rbac.yaml`、`templates/poddisruptionbudget.yaml`、`templates/agent-runtime-config.yaml`、`templates/control-plane.yaml`、`templates/operator.yaml`、`templates/gateway.yaml`。
- 修改：`deploy/docker/manager.Dockerfile`、`.github/workflows/ci.yml`、`README.md`、`docs/architecture-map.html`。
- 测试：`scripts/test_batch_a_helm_contract.py`、`scripts/test_batch_a_security_contract.py`。

## 依赖与并行编排

```text
任务 1 公共契约
   +--> 任务 2 Sandbox Provider/Scheduler ----> 任务 3 Operator 联合接线
   +--> 任务 4 Worker Runtime 路由
   +--> 任务 5 Manager 正式服务
   +--> 任务 6 Team Revision/Effective Config
任务 7 Helm/CI 静态契约可与任务 2、4、5、6 并行，模板字段在合并阶段统一
任务 8 全量集成验收依赖任务 3、4、5、6、7
```

任务 2、4、5、6 之间不共享实现文件，可以并行；任务 3 依赖任务 2 的 CR/Provider 字段；任务 7 的 Java 无关静态测试可提前运行。共享 Flyway 版本只在同一分支提交时按实际最新版本调整，不能让并行任务各自假定同一版本号。

### 任务 1：冻结异步 Sandbox Provider 契约

**文件：** 见「公共契约和 Sandbox」第一组文件。

- [x] **步骤 1：编写失败测试**

验证 `SandboxRuntimePort` 能表达 `PROVISIONING`、同一 Attempt 重复 ensure、Provider UID、generation 和固定错误分类；验证 Fake Provider 重复请求只创建一个句柄。

```java
@Test
void duplicateProvisionReturnsSameProviderReference() {
    SandboxProvisionCommand command = commandFor(ATTEMPT_ID);
    SandboxProvisionReceipt first = runtime.ensureProvisioned(command);
    SandboxProvisionReceipt second = runtime.ensureProvisioned(command);

    assertThat(second.providerRef()).isEqualTo(first.providerRef());
    assertThat(runtime.provisionCalls()).isEqualTo(1);
}
```

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl application-contracts,control-plane -am -Dtest=SandboxProviderContractTest test`

预期：编译失败或测试失败，原因是异步命令/Receipt 类型和 `ensureProvisioned` 方法尚不存在。

- [x] **步骤 3：实现最小契约**

将 `SandboxRuntimePort` 改为 `ensureProvisioned`、`inspect`、`ensureExpiry`、`ensureTerminated` 四个幂等方法；新增值对象校验非空 ID、正 TTL、受限 phase 和稳定失败分类；将 Fake Provider 的内部索引改为幂等键与 Provider UID 双索引。

- [x] **步骤 4：运行测试确认通过**

运行同一命令，预期 `SandboxProviderContractTest` 全部通过。

- [x] **步骤 5：Commit**

```bash
git add application-contracts control-plane/src/main/java/io/agentteams/controlplane/sandbox control-plane/src/test/java/io/agentteams/controlplane/sandbox application-contracts/src/test
git commit -m "feat(沙箱): 冻结异步Provider契约"
```

### 任务 2：Kubernetes Sandbox Provider 与生命周期 Scheduler

**文件：** `KubernetesSandboxRuntime.java`、`SandboxRuntimeProperties.java`、`SandboxLifecycleService.java`、`SandboxLifecycleScheduler.java`、`TaskSandboxRepository.java`、`TaskSandboxRecord.java`、`V43__sandbox_provider_state.sql` 及对应测试。

- [x] **步骤 1：编写失败测试**

覆盖同名 CR 的相同 spec 重复返回、不一致 spec 返回 `IDEMPOTENCY_CONFLICT`、CR UID/generation 读取、`READY` 前不发布 `TaskAssigned`、Scheduler 非 Leader 不执行写操作、过期 operation lease 可被下一轮回收。

```java
@Test
void conflictingProvisionDoesNotReplaceExistingCustomResource() {
    KubernetesSandboxRuntime runtime = runtimeWithFakeKubernetes();
    runtime.ensureProvisioned(command("template-a"));

    assertThatThrownBy(() -> runtime.ensureProvisioned(command("template-b")))
            .isInstanceOf(SandboxProviderException.class)
            .extracting(error -> ((SandboxProviderException) error).category())
            .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
}
```

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane -am -Dtest=KubernetesSandboxRuntimeTest,SandboxLifecycleSchedulerTest test`

预期：测试失败，原因是 Kubernetes Provider、Provider 状态列和 Scheduler 尚不存在。

- [x] **步骤 3：实现数据库与 Provider**

迁移增加 provider reference、generation、workload UID、desired state、operation lease、重试和 dispatch event 字段，并保留 `attempt_id` 与 `(provider, provider_resource_id)` 唯一约束。Provider 使用稳定 CR 名称和 server-side apply/read-before-write，写入只包含受控 profile/template/expiry，错误统一分类并限制诊断长度。

- [x] **步骤 4：实现生命周期协调**

`SandboxLifecycleService` 增加 observe、renew、expire、terminate 的 expected-version 更新；`SandboxLifecycleScheduler` 复用 `SchedulerLeaseService`，仅 Leader 按 recover、provision、observe、renew、expire、terminate 顺序执行，单条失败不阻断批次。

- [x] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl control-plane -am -Dtest=KubernetesSandboxRuntimeTest,SandboxLifecycleSchedulerTest,SandboxLifecycleServiceTest test`

预期：全部通过，且重复 provision/terminate 不产生第二个外部资源或第二条 `TaskAssigned`。

- [x] **步骤 6：Commit**

```bash
git add application-contracts control-plane/src/main/java/io/agentteams/controlplane/sandbox control-plane/src/main/java/io/agentteams/controlplane/persistence control-plane/src/main/resources/db/migration control-plane/src/test
git commit -m "feat(沙箱): 接入Kubernetes生命周期调度"
```

### 任务 3：TaskSandbox Operator 强化与联合接线

**文件：** `TaskSandboxReconciler.java`、`TaskSandboxResourceFactory.java`、`TaskSandboxStatusMapper.java`、`ControlPlaneConfiguration.java`、`templates/rbac.yaml` 及 Operator 测试。

- [x] **步骤 1：编写失败测试**

覆盖 finalizer、删除 Job 后的 `DESTROYED`、曾经 Ready 的 workload UID 丢失后为 `LOST`、旧 generation 不覆盖新 status、Service Endpoint/健康检查缺失时不进入 `READY`、过期和 terminationRequested 删除 Job。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl operator -am -Dtest=TaskSandboxReconcilerTest,TaskSandboxResourceFactoryTest test`

预期：旧 Reconciler 行为缺少 finalizer、Service 和 generation 保护，新增断言失败。

- [x] **步骤 3：实现最小 Operator 行为**

为 CR 添加固定 finalizer；只在 observed generation 等于当前 generation 时更新状态；创建受控 ClusterIP Service 和 restricted Job；CR 删除或 termination intent 先删除子资源，确认子资源消失后再标记 `DESTROYED` 并移除 finalizer；不读取或输出容器日志。

- [x] **步骤 4：补齐 Control Plane 配置**

启用 `provider=kubernetes` 时创建 `KubernetesSandboxRuntime`，`enabled=true` 但 Provider 缺失时启动失败；Helm 只授予 TaskSandbox CR 读写权限，不授予 Pod、Job、Secret、Deployment 权限。

- [x] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl operator,control-plane -am -Dtest=TaskSandboxReconcilerTest,TaskSandboxResourceFactoryTest,KubernetesSandboxRuntimeTest test`

预期：Operator 单测和 Control Plane Provider 接线测试通过。

- [x] **步骤 6：Commit**

```bash
git add operator control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java deploy/helm/agentteams-java/templates/rbac.yaml
git commit -m "feat(operator): 完善沙箱终止与状态保护"
```

### 任务 4：Worker Runtime Factory、Router 与 AgentScope 接线

**文件：** `QwenPawWorker.java`、`WorkerRuntimeFactory.java`、`WorkerRuntimeRouter.java`、`ConfiguredAgentScopeHarnessFactory.java`、`SandboxStateProbePort.java`、`WorkerResourceFactory.java` 及对应测试。

- [x] **步骤 1：编写失败测试**

覆盖稳定分桶和 allowlist、运行中 owner 不迁移、cancel/stop 路由到原 delegate、显式 AgentScope 缺配置启动失败、灰度命中但运行时不可用返回 `RUNTIME_UNAVAILABLE`、Operator 将 CR runtime 写入 `AGENTTEAMS_RUNTIME` 并覆盖冲突 env。

```java
@Test
void changingRolloutDoesNotMoveAnInFlightTask() {
    router.start(contextWithPolicy(0.0));
    router.submit(task(TASK_ID));
    router.updatePolicy(policyWithAgentScopePercent(100));
    router.cancel(TASK_ID);

    assertThat(qwen.cancelled()).containsExactly(TASK_ID);
    assertThat(agentScope.cancelled()).isEmpty();
}
```

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl agent-worker,operator -am -Dtest=WorkerRuntimeRouterTest,WorkerRuntimeFactoryTest,WorkerResourceFactoryTest test`

预期：编译失败或断言失败，原因是 Worker 仍固定持有 QwenPaw Runtime，CR runtime 仍只生成标签。

- [x] **步骤 3：实现 Factory 与 Router**

将 Worker 字段改为 `AgentRuntime`，Factory 创建 QwenPaw、AgentScope 和 Router；Router 使用稳定输入和 `AgentScopeRolloutPolicy` 选择 delegate，并保存 Task owner；未知 runtime、显式 AgentScope 缺配置和缺少能力声明均 fail-closed。

- [x] **步骤 4：实现 AgentScope 配置工厂和只读 Sandbox 边界**

Factory 从既有 Provider/Secret 引用构造模型，Workspace 只接受受控绑定路径；工具先通过 AgentTeams 权限/出站策略再进入 AgentScope middleware；Worker 只依赖 `SandboxStateProbePort`，不持有 Kubernetes Client 或写 Sandbox 的 Port。

- [x] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl agent-worker,operator -am -Dtest=WorkerRuntimeRouterTest,WorkerRuntimeFactoryTest,ConfiguredAgentScopeHarnessFactoryTest,WorkerResourceFactoryTest test`

预期：所有路由、配置缺失、能力声明和 env 注入测试通过。

- [x] **步骤 6：Commit**

```bash
git add agent-worker operator/src/main/java/io/agentteams/operator/WorkerResourceFactory.java operator/src/test
git commit -m "feat(worker): 接入AgentScope运行时路由"
```

### 任务 5：Manager 正式服务与持久化会话

**文件：** Manager 文件清单、`manager/pom.xml`、Manager Dockerfile、Manager Helm 模板及对应测试。

- [x] **步骤 1：编写失败测试**

覆盖创建 Session、Message 幂等、expected version 冲突、无效结构化模型输出、工具权限拒绝、取消 Session、SSE cursor 重放和进程重启后读取持久化记录。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl manager -am -Dtest=ManagerSessionServiceFacadeTest,ManagerSessionControllerTest,JdbcManagerSessionRepositoryTest test`

预期：测试失败，原因是 Manager 没有 Spring Boot HTTP 入口、JDBC Repository 和 SSE 事件模型。

- [x] **步骤 3：实现存储和服务门面**

创建 Manager Session/Message/ToolCall/Event 表，写入 scope、actor、版本、内容 hash、脱敏摘要、幂等键和递增 cursor；所有更新使用 expected version，唯一键保证重复消息与 Tool 调用只产生一次业务副作用。

- [x] **步骤 4：实现 Controller 与错误边界**

增加五个 API，统一错误 code/message/correlationId/details；请求只接受文本和可选 Task/Team context，不接受任意 URL、SQL、Kubernetes 或 Shell 指令；只读查询和 `create_task` 经过现有 Tool Registry、权限、审批、配额和审计。

- [x] **步骤 5：实现部署入口**

加入独立 Spring Boot main、健康端点、Manager Dockerfile、Deployment、Service、PDB、NetworkPolicy 和 existingSecret 挂载；默认关闭，配置不完整时启动失败，Manager 不获得 Kubernetes RBAC。

- [x] **步骤 6：运行测试确认通过**

运行：`mvn -q -pl manager -am test`，再运行 `helm lint deploy/helm/agentteams-java`。

预期：Manager 单测全部通过，Helm 模板可渲染且不包含 Secret value。

- [x] **步骤 7：Commit**

```bash
git add manager deploy/docker/manager.Dockerfile deploy/helm/agentteams-java/templates/manager.yaml deploy/helm/agentteams-java/templates/manager-service.yaml deploy/helm/agentteams-java/templates/manager-networkpolicy.yaml
git commit -m "feat(manager): 增加正式会话服务"
```

### 任务 6：Team Revision 与 Effective Config

**文件：** Team/Config 文件清单、`V44__team_revisions.sql`、`V45__team_revision_bindings.sql` 及对应测试。

- [x] **步骤 1：编写失败测试**

覆盖已发布 Revision 不可修改、Rollback 产生新 Revision、规范化输入得到稳定 digest、数组去重、权限收紧、Sandbox profile 提升安全级别、重复发布不产生重复 binding、单成员失败 retry 只处理失败成员。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane -am -Dtest=EffectiveConfigComposerTest,TeamRevisionServiceTest,TeamDeploymentServiceTest test`

预期：测试失败，原因是 Revision、Effective Config 和部署 binding 类型不存在。

- [x] **步骤 3：实现 Revision 和 Composer**

Revision 写入不可变 spec、状态、scope、version 和 digest；Composer 按 Team → AgentSpec → Worker → Project policy 固定优先级合成 canonical JSON，用 SHA-256 计算 digest，禁止越权放宽权限或降低 Sandbox profile。

- [x] **步骤 4：实现发布、回滚和 Binding**

发布/回滚使用幂等键和唯一约束；每个成员拥有独立 binding 与 ACK 状态；失败状态为 `PARTIAL_FAILURE` 时 retry 只选择失败成员，稳定成员不重复发布。

- [x] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl control-plane -am -Dtest=EffectiveConfigComposerTest,TeamRevisionServiceTest,TeamDeploymentServiceTest test`

预期：状态机、digest、权限和幂等测试全部通过。

- [x] **步骤 6：Commit**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/team control-plane/src/main/java/io/agentteams/controlplane/config control-plane/src/main/java/io/agentteams/controlplane/api/TeamController.java control-plane/src/main/resources/db/migration control-plane/src/test
git commit -m "feat(team): 增加版本化配置发布"
```

### 任务 7：Helm、NetworkPolicy、CI 静态契约

**文件：** Helm/CI 文件清单和新增 Python 静态测试。

- [x] **步骤 1：编写失败测试**

检查 values schema 拒绝未知 profile/Provider，Control Plane 无 Pod/Job/Secret 权限，Operator 只有命名空间内受控资源权限，Worker runtime env 覆盖冲突值，Sandbox Job 关闭 privileged/hostPath/hostNetwork/hostPID/ServiceAccount Token，所有组件具备安全上下文和独立 PDB。

- [x] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts/test_batch_a_helm_contract.py scripts/test_batch_a_security_contract.py`，预期新断言失败，因为 schema、Manager 模板和最小 RBAC 尚不存在。

- [x] **步骤 3：实现 Helm 契约**

增加 `values.schema.json`、Manager 模板、Manager NetworkPolicy、Runtime ConfigMap 注入、Sandbox CR RBAC、安全上下文、topology spread、PDB 和可选 HPA；NetworkPolicy 默认 deny，再显式放行 PostgreSQL、NATS、OIDC/Secret 依赖和必要服务端口。

- [x] **步骤 4：运行静态验证**

运行：`helm lint deploy/helm/agentteams-java`、`helm template agentteams deploy/helm/agentteams-java --namespace agentteams --set manager.enabled=true`、上述 Python 测试、`git diff --check`。

预期：Lint、模板渲染和安全契约全部通过，渲染结果不含 Secret value。

- [x] **步骤 5：Commit**

```bash
git add deploy/helm .github/workflows/ci.yml scripts/test_batch_a_helm_contract.py scripts/test_batch_a_security_contract.py README.md docs/architecture-map.html
git commit -m "feat(交付): 完善批次A安全部署契约"
```

### 任务 8：批次 A 集成验证与审查

**文件：** 不新增生产文件；验证全部任务变更和文档。

- [x] **步骤 1：运行完整 Java 测试**

运行：`source deploy/dev-env.sh && docker info && mvn -q -Pintegration-tests verify`。

预期：Docker daemon 可访问，Maven 单元、集成和 Testcontainers 验证退出码为 0。

- [x] **步骤 2：运行迁移、Helm 和静态检查**

运行：`python3 -m unittest discover -s scripts -p 'test_*.py'`、`helm lint deploy/helm/agentteams-java`、`helm template agentteams deploy/helm/agentteams-java --namespace agentteams`、`git diff --check`。

预期：脚本、迁移、Helm 和格式检查全部通过；Docker-backed 检查已经在步骤 1 完成。

- [x] **步骤 3：运行 Kind 验收（CI 环境）**

验证 `NONE` QwenPaw、`ISOLATED` Sandbox 生命周期、AgentScope Fake Model 全链路、Manager 重复请求单 Task、Team Revision 发布/回滚和 Worker drain。

- [x] **步骤 4：进行规格合规审查**

逐项对照 `docs/superpowers/specs/2026-08-26-batch-a-production-main-path-design.md` 和五份原始子规格，确认没有占位 Adapter、越权 RBAC、Secret 泄漏、旧版本覆盖或重复副作用。

- [x] **步骤 5：进行代码质量审查**

检查错误分类、事务边界、并发锁、日志脱敏、API 兼容性、测试命名和模块依赖；Critical/Important 问题修复后重新运行对应测试。

- [x] **步骤 6：Commit 集成文档和验证结果**

```bash
git add docs/superpowers/specs docs/superpowers/plans README.md docs/architecture-map.html
git commit -m "docs(路线图): 更新批次A实施状态"
```
