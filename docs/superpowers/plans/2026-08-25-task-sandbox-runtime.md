# Task Sandbox Runtime 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在不改变现有默认任务路径的前提下，引入 Task-Sandbox 生命周期抽象、持久化绑定、Fake Provider 和 Kubernetes CRD 契约，为 gVisor、Kata 和 CubeSandbox 留出可替换实现。

**架构：** Control Plane 通过 `SandboxRuntimePort` 管理 Attempt 级 Sandbox 绑定；数据库保存 Sandbox 事实状态，Outbox/调度任务触发外部 Provider；Kubernetes Provider 由 `TaskSandbox` CRD 和现有 Operator 管理。默认 `SandboxProfile.NONE`，只有显式 profile 才创建 Sandbox。

**技术栈：** Java 17、Spring Boot、JDBC、PostgreSQL/Flyway、NATS Outbox、Fabric8 Kubernetes Client、Java Operator SDK、JUnit 5、Helm、Python 静态契约测试。

---

## 文件清单

### 新增

- `application-contracts/src/main/java/io/agentteams/application/api/SandboxProfile.java`
- `application-contracts/src/main/java/io/agentteams/application/api/SandboxStatus.java`
- `application-contracts/src/main/java/io/agentteams/application/api/SandboxTerminationReason.java`
- `application-contracts/src/main/java/io/agentteams/application/api/SandboxRequest.java`
- `application-contracts/src/main/java/io/agentteams/application/api/SandboxHandle.java`
- `application-contracts/src/main/java/io/agentteams/application/api/SandboxRuntimePort.java`
- `application-contracts/src/test/java/io/agentteams/application/api/SandboxRuntimeContractTest.java`
- `control-plane/src/main/resources/db/migration/V41__task_sandboxes.sql`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/TaskSandboxRecord.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/TaskSandboxRepository.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/FakeSandboxRuntime.java`
- `control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxLifecycleService.java`
- `control-plane/src/test/java/io/agentteams/controlplane/sandbox/TaskSandboxRepositoryIT.java`
- `control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxLifecycleServiceTest.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandbox.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxSpec.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxStatus.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxResourceFactory.java`
- `operator/src/main/java/io/agentteams/operator/TaskSandboxReconciler.java`
- `operator/src/test/java/io/agentteams/operator/TaskSandboxResourceFactoryTest.java`
- `deploy/helm/agentteams-java/crds/task-sandboxes.yaml`
- `scripts/test_kind_task_sandbox_contract.py`

### 修改

- `control-plane/src/main/java/io/agentteams/controlplane/persistence/FoundationTransaction.java`
- `control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/service/ExecutionEventService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- `control-plane/src/test/java/io/agentteams/controlplane/service/TaskAssignmentServiceTest.java`
- `control-plane/src/test/java/io/agentteams/controlplane/service/ExecutionEventServiceTest.java`
- `operator/src/main/java/io/agentteams/operator/AgentTeamsOperatorApplication.java`
- `deploy/helm/agentteams-java/values.yaml`
- `deploy/helm/agentteams-java/templates/rbac.yaml`
- `deploy/helm/agentteams-java/templates/operator-deployment.yaml`
- `deploy/helm/agentteams-java/templates/crds.yaml`
- `.github/workflows/ci.yml`

---

### 任务 1：建立 Sandbox 应用契约和 Fake Provider

**目标：** 让上层只依赖稳定的 Sandbox 端口，默认实现不触碰 Kubernetes 或 Docker。

**文件：**

- 创建 `SandboxProfile.java`、`SandboxStatus.java`、`SandboxTerminationReason.java`、`SandboxRequest.java`、`SandboxHandle.java` 和 `SandboxRuntimePort.java`；
- 测试：`application-contracts/src/test/java/io/agentteams/application/api/SandboxRuntimeContractTest.java`；
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/FakeSandboxRuntime.java`；
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxLifecycleServiceTest.java`。

- [ ] **步骤 1：编写契约失败测试。**

测试必须覆盖：

```java
assertThat(SandboxRequest.defaults(taskId, attemptId, now).profile())
        .isEqualTo(SandboxProfile.NONE);
assertThatThrownBy(() -> SandboxRequest.of(taskId, attemptId, SandboxProfile.NONE,
        Duration.ZERO, now))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(FakeSandboxRuntime.provision(request).providerSandboxId())
        .isNotBlank();
```

- [ ] **步骤 2：运行契约测试确认失败。**

运行：

```bash
mvn -q -pl application-contracts,control-plane -am \
  -Dtest=SandboxRuntimeContractTest,SandboxLifecycleServiceTest test
```

预期：编译失败，原因是 Sandbox 类型和 Fake Provider 尚不存在。

- [ ] **步骤 3：实现不可变契约类型。**

要求：

- `SandboxProfile` 只允许 `NONE`、`ISOLATED`、`HARDENED`；
- `SandboxStatus` 包含 `REQUESTED`、`PROVISIONING`、`READY`、`RUNNING`、`STOPPING`、`DESTROYED`、`FAILED`、`EXPIRED`、`LOST`；
- `SandboxRequest` 校验 Task/Attempt ID、TTL、资源和幂等键；
- `SandboxHandle` 只保存 Provider ID、profile、状态、Endpoint 引用和到期时间；
- `SandboxRuntimePort` 不依赖 Spring、Kubernetes 或具体 Provider 类型。

- [ ] **步骤 4：实现 Fake Provider 和最小契约测试。**

Fake Provider 必须按幂等键返回同一个 `SandboxHandle`，支持配置创建失败和销毁失败，记录续期和销毁调用次数，且不访问文件、Docker Socket 或外部网络。

- [ ] **步骤 5：运行测试确认通过。**

运行相同 Maven 命令，预期相关测试全部通过。

- [ ] **步骤 6：提交任务 1。**

```bash
git add application-contracts/src/main/java/io/agentteams/application/api \
  application-contracts/src/test/java/io/agentteams/application/api \
  control-plane/src/main/java/io/agentteams/controlplane/sandbox/FakeSandboxRuntime.java \
  control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxLifecycleServiceTest.java
git commit -m "feat(sandbox): 增加运行时契约和 Fake Provider（任务 1/6）"
```

---

### 任务 2：增加 Sandbox 持久化模型和迁移

**目标：** 让 Sandbox 状态成为可恢复的数据库事实，并与 Attempt 一对一绑定。

**文件：**

- 创建：`control-plane/src/main/resources/db/migration/V41__task_sandboxes.sql`；
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/TaskSandboxRecord.java`；
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/TaskSandboxRepository.java`；
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/FoundationTransaction.java`；
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/sandbox/TaskSandboxRepositoryIT.java`。

- [ ] **步骤 1：编写 Repository 失败测试。**

测试插入 `REQUESTED`、按 Attempt 查询、重复 Attempt 唯一约束、错误 version 乐观锁拒绝、`claimRequested` 的 `FOR UPDATE SKIP LOCKED`、以及 `markReady`/`markFailed`/`markDestroyed` 的时间和脱敏错误持久化。

- [ ] **步骤 2：运行测试确认迁移和类型缺失。**

```bash
mvn -q -pl control-plane -am -Dtest=TaskSandboxRepositoryIT test
```

预期：因 V41 和 Repository 不存在而失败。

- [ ] **步骤 3：编写 V41 迁移。**

迁移创建 `task_sandboxes`，包含 Task/Attempt/Agent 外键、`UNIQUE(attempt_id)`、幂等键唯一索引、Provider ID 部分唯一索引、`(status, expires_at)` 回收索引和 JSONB 对象约束。

- [ ] **步骤 4：实现 Record、Repository 和事务访问器。**

Repository 提供以下方法：

```java
Optional<TaskSandboxRecord> findByAttemptId(UUID attemptId);
boolean insertIfAbsent(TaskSandboxRecord record);
List<TaskSandboxRecord> claimRequested(Instant now, int limit);
TaskSandboxRecord markProvisioning(UUID id, long expectedVersion, Instant at);
TaskSandboxRecord markReady(UUID id, String providerId, String endpointRef,
        Instant expiresAt, long expectedVersion, Instant at);
TaskSandboxRecord markFailed(UUID id, String code, String message,
        long expectedVersion, Instant at);
TaskSandboxRecord markDestroyed(UUID id, long expectedVersion, Instant at);
```

所有更新都必须带 version 条件。

- [ ] **步骤 5：运行 Flyway 和 Repository 集成测试。**

```bash
mvn -q -pl control-plane -am \
  -Dtest=TaskSandboxRepositoryIT,FoundationRepositoryIT test
```

预期：迁移成功，Sandbox Repository 测试全部通过。

- [ ] **步骤 6：提交任务 2。**

```bash
git add control-plane/src/main/resources/db/migration/V41__task_sandboxes.sql \
  control-plane/src/main/java/io/agentteams/controlplane/sandbox \
  control-plane/src/main/java/io/agentteams/controlplane/persistence/FoundationTransaction.java \
  control-plane/src/test/java/io/agentteams/controlplane/sandbox
git commit -m "feat(sandbox): 增加 Attempt 绑定和持久化（任务 2/6）"
```

---

### 任务 3：接入 Assignment、Lease 和 Outbox 生命周期

**目标：** 在不调用外部 Provider 的前提下，把 Sandbox 请求接入任务分配和任务终态。

**文件：**

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/sandbox/SandboxLifecycleService.java`；
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`；
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/ExecutionEventService.java`；
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`；
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/service/TaskAssignmentServiceTest.java`；
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/service/ExecutionEventServiceTest.java`；
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/sandbox/SandboxLifecycleServiceTest.java`。

- [ ] **步骤 1：增加 Assignment 的 Sandbox profile 失败测试。**

验证无 `sandbox` 字段和 `profile=NONE` 的任务与现有行为完全一致；`profile=ISOLATED` 在同一事务写入 `REQUESTED`，并追加 `SandboxProvisionRequested`；事务回滚时 Assignment 和 Sandbox 都不存在。

- [ ] **步骤 2：增加终态销毁事件失败测试。**

对 `SUCCEEDED`、`FAILED`、`CANCELLED` 验证 Agent Lease 释放、Sandbox 进入 `STOPPING`、追加一次 `SandboxTerminateRequested`，并验证相同 execution event 重放不会重复追加销毁事件。

- [ ] **步骤 3：实现 profile 解析和请求构造。**

从 Task spec 读取：

```json
{
  "sandbox": {
    "profile": "ISOLATED",
    "template": "python-untrusted",
    "ttlSeconds": 1800
  }
}
```

缺失字段按 `NONE`；profile 必须是大写枚举；TTL 范围为 60 到 86,400 秒；template 最大 128 字符；不复制完整 Task spec 到 Sandbox details。

- [ ] **步骤 4：修改 TaskAssigned payload。**

只有 Sandbox 为 `READY` 时才加入 sandbox 引用；`NONE` 任务保持现有 payload 兼容。对于需要 Sandbox 但尚未 READY 的 Assignment，暂不发布可执行的 `TaskAssigned`，由 SandboxReady 路径补发一次。

- [ ] **步骤 5：实现生命周期服务。**

`SandboxLifecycleService` 提供：

```java
int provisionRequested(Instant now, int limit);
int renewRunning(Instant now, Duration extension, int limit);
int terminateStopping(Instant now, int limit);
```

服务先 claim 数据库记录，再在事务外调用 Provider；创建最多重试 3 次，销毁最多重试 10 次；旧 Attempt 不能更新新 Sandbox；Lease 到期时 Sandbox 进入 `EXPIRED`。

- [ ] **步骤 6：运行定向测试。**

```bash
mvn -q -pl control-plane -am \
  -Dtest=TaskAssignmentServiceTest,ExecutionEventServiceTest,SandboxLifecycleServiceTest test
```

预期：既有 Assignment/Execution 测试和新增 Sandbox 测试全部通过。

- [ ] **步骤 7：提交任务 3。**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/sandbox \
  control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java \
  control-plane/src/main/java/io/agentteams/controlplane/service/ExecutionEventService.java \
  control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java \
  control-plane/src/test/java/io/agentteams/controlplane/service
git commit -m "feat(sandbox): 接入任务分配和租约生命周期（任务 3/6）"
```

---

### 任务 4：增加 TaskSandbox CRD 和 Operator Controller

**目标：** 为 Kubernetes Provider 建立安全的声明式资源边界，不让 Control Plane 直接创建任意 Pod。

**文件：**

- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandbox.java`；
- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandboxSpec.java`；
- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandboxStatus.java`；
- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandboxResourceFactory.java`；
- 创建：`operator/src/main/java/io/agentteams/operator/TaskSandboxReconciler.java`；
- 创建：`operator/src/test/java/io/agentteams/operator/TaskSandboxResourceFactoryTest.java`；
- 创建：`deploy/helm/agentteams-java/crds/task-sandboxes.yaml`；
- 修改：`operator/src/main/java/io/agentteams/operator/AgentTeamsOperatorApplication.java`。

- [ ] **步骤 1：编写 ResourceFactory 失败测试。**

验证固定 Job 名称、profile 到 RuntimeClass 的配置映射、关闭 ServiceAccount token、禁止 privileged/hostNetwork/hostPID/hostPath、资源和 TTL、Task/Attempt 标签，以及重复 reconcile 的幂等结果。

- [ ] **步骤 2：运行测试确认类型不存在。**

```bash
mvn -q -pl operator -am -Dtest=TaskSandboxResourceFactoryTest test
```

预期：因 TaskSandbox 类型不存在而失败。

- [ ] **步骤 3：实现 CRD Java 类型和资源工厂。**

`TaskSandboxSpec` 固定包含 taskId、attemptId、profile、runtimeClassName、image、resources、ttlSeconds。资源工厂只接受三个 profile，RuntimeClass 映射来自 Operator 配置。

- [ ] **步骤 4：实现 Reconciler。**

Reconciler 创建或替换 Job，通过 Job 状态更新 CR status；Job 成功/失败分别设置状态；删除 CR 时清理 Job；使用 owner reference，禁止 orphan 子资源。

- [ ] **步骤 5：补充 CRD、Operator 注册和 RBAC。**

CRD 限制 profile、TTL 和资源字段；Operator 只增加对 tasksandboxes、jobs 和 status 的最小权限；Control Plane 不增加 Pod/Job 管理权限。

- [ ] **步骤 6：运行 Operator 测试和 YAML 校验。**

```bash
mvn -q -pl operator -am -Dtest=TaskSandboxResourceFactoryTest test
python3 scripts/validate-kind-manifests.py
helm lint deploy/helm/agentteams-java
helm template agentteams deploy/helm/agentteams-java \
  -f deploy/helm/kind-values.yaml >/tmp/agentteams-kind-rendered.yaml
```

预期：测试、Kind 和 Helm 校验通过，渲染结果包含 TaskSandbox CRD 和最小 RBAC。

- [ ] **步骤 7：提交任务 4。**

```bash
git add operator/src/main/java/io/agentteams/operator \
  operator/src/test/java/io/agentteams/operator \
  deploy/helm/agentteams-java/crds/task-sandboxes.yaml \
  deploy/helm/agentteams-java/templates \
  operator/src/main/java/io/agentteams/operator/AgentTeamsOperatorApplication.java
git commit -m "feat(sandbox): 增加 TaskSandbox CRD 和 Operator（任务 4/6）"
```

---

### 任务 5：增加 Helm、Kind 契约和安全配置

**目标：** 确保默认部署不启用 Sandbox，Profile、RuntimeClass、资源和网络边界可静态验证。

**文件：**

- 创建：`scripts/test_kind_task_sandbox_contract.py`；
- 修改：`deploy/helm/agentteams-java/values.yaml`；
- 修改：`deploy/helm/agentteams-java/templates/rbac.yaml`；
- 修改：`deploy/helm/agentteams-java/templates/operator-deployment.yaml`；
- 修改：`deploy/helm/agentteams-java/templates/crds.yaml`；
- 修改：`.github/workflows/ci.yml`。

- [ ] **步骤 1：编写 Python 契约失败测试。**

检查默认 `sandbox.enabled=false`、默认 profile `NONE`、gVisor/Kata RuntimeClass 配置、Operator RBAC 不含任意 Pod/Secret 管理权限、TaskSandbox CRD 被 Helm 安装，以及 Kind CI 不安装真实 gVisor/Kata。

- [ ] **步骤 2：运行测试确认失败。**

```bash
python3 -m unittest scripts/test_kind_task_sandbox_contract.py
```

预期：因 Sandbox Helm 配置和契约测试不存在而失败。

- [ ] **步骤 3：实现 Helm 配置和 Operator 参数。**

配置固定为：

```yaml
sandbox:
  enabled: false
  defaultProfile: NONE
  provider: fake
  runtimeClasses:
    isolated: gvisor
    hardened: kata-qemu
  defaultTtlSeconds: 1800
  maxTtlSeconds: 86400
```

部署环境可以覆盖 Provider 和 RuntimeClass 名称，但 Task spec 不能直接提交任意运行时名称。

- [ ] **步骤 4：增加 NetworkPolicy 和资源限制模板。**

TaskSandbox 默认拒绝出站，只允许配置的 Control Plane、Gateway、Model 和 Artifact 服务；关闭 ServiceAccount token；不开放 Kubernetes API、Docker Socket、hostPath 和宿主设备；为不同 profile 设置可审计标签。

- [ ] **步骤 5：接入 CI 静态契约。**

在 verify job 中加入：

```bash
python3 -m unittest scripts/test_kind_task_sandbox_contract.py
helm lint deploy/helm/agentteams-java
python3 scripts/validate-kind-manifests.py
```

不在现有 kind-recovery 中安装 gVisor/Kata，不改变 QwenPaw 冒烟路径。

- [ ] **步骤 6：运行契约和 Helm 验证。**

```bash
python3 -m unittest scripts/test_kind_task_sandbox_contract.py
helm lint deploy/helm/agentteams-java
helm template agentteams deploy/helm/agentteams-java \
  -f deploy/helm/kind-values.yaml >/tmp/agentteams-kind-rendered.yaml
python3 scripts/validate-kind-manifests.py
```

预期：全部退出码为 0，默认渲染结果不创建真实 Sandbox Provider。

- [ ] **步骤 7：提交任务 5。**

```bash
git add scripts/test_kind_task_sandbox_contract.py \
  deploy/helm/agentteams-java/values.yaml \
  deploy/helm/agentteams-java/templates \
  .github/workflows/ci.yml
git commit -m "feat(sandbox): 增加 Helm 与 Kind 安全契约（任务 5/6）"
```

---

### 任务 6：全量回归、文档同步和远程验收

**目标：** 证明默认任务路径没有回归，并记录真实 RuntimeClass 验收边界。

**文件：**

- 修改：`docs/superpowers/specs/2026-08-25-task-sandbox-runtime-design.md`；
- 修改：`docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md`；
- 修改：`README.md`；
- 修改：`deploy/production/README.md`。

- [ ] **步骤 1：运行模块回归。**

```bash
mvn -q -DskipITs test
```

预期：所有模块测试退出码为 0，失败数为 0。

- [ ] **步骤 2：运行集成和静态校验。**

```bash
mvn -q -pl control-plane -am \
  -Dtest=TaskSandboxRepositoryIT,FoundationRepositoryIT test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/validate-kind-manifests.py
python3 scripts/validate-observability.py
helm lint deploy/helm/agentteams-java
git diff --check
```

预期：所有命令退出码为 0。

- [ ] **步骤 3：执行本地 Kind 默认路径验收。**

```bash
./deploy/install-kind-dev.sh
python3 scripts/smoke-kind-task-api.py
python3 scripts/run-kind-dashboard-alerts.py --timeout 180
```

预期：现有 Task API、Dashboard 告警和 QwenPaw Worker 链路保持通过；Sandbox 默认不创建外部隔离运行时。

- [ ] **步骤 4：执行独立 Linux/KVM RuntimeClass 验收。**

在具备对应运行时的专用环境运行：

```bash
kubectl get runtimeclass
kubectl apply -f deploy/examples/task-sandbox-isolated.yaml
kubectl apply -f deploy/examples/task-sandbox-hardened.yaml
kubectl wait --for=jsonpath='{.status.phase}'=READY \
  tasksandbox/task-sandbox-isolated --timeout=180s
kubectl wait --for=jsonpath='{.status.phase}'=READY \
  tasksandbox/task-sandbox-hardened --timeout=180s
kubectl get pod -o custom-columns=NAME:.metadata.name,RUNTIME:.spec.runtimeClassName,STATUS:.status.phase
```

预期：ISOLATED 使用 gVisor，HARDENED 使用 Kata；未配置 RuntimeClass 的集群只通过 Fake Provider，不创建失败 Pod。

- [ ] **步骤 5：同步规格验收结果并检查状态。**

文档只记录实际运行过的命令和结果，不把 Fake Provider 结果写成真实 gVisor/Kata 验收结果。

- [ ] **步骤 6：提交任务 6。**

```bash
git add docs/superpowers/specs/2026-08-25-task-sandbox-runtime-design.md \
  docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md \
  README.md deploy/production/README.md
git commit -m "docs(sandbox): 同步实现状态和验收边界（任务 6/6）"
```

---

## 执行顺序和提交检查点

任务必须按 1 → 2 → 3 → 4 → 5 → 6 顺序执行。每个任务独立提交；任务 1、3、5 完成后运行对应模块测试，任务 3 和任务 6 结束时运行全量回归。

实现完成前必须满足：

- 默认 `NONE` 任务与现有行为完全兼容；
- 所有 Sandbox 状态变更可恢复且具备版本校验；
- Provider 调用不出现在数据库事务内；
- Task 重试和 Sandbox 绑定通过 Attempt fencing 隔离；
- Kind CI 不依赖 gVisor、Kata 或 CubeSandbox；
- 真实 RuntimeClass 验收只在具备 Linux/KVM 的独立环境执行。
