# Kind 开发链路与真实垂直切片实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从干净的 Kind 集群启动 PostgreSQL、NATS JetStream、MinIO、Control Plane、Gateway 和 Operator，并用真实 PostgreSQL/NATS/MinIO 验证 Agent 注册、任务推送、断线重连、完成和 artifact 幂等链路。

**架构：** PostgreSQL 继续作为 Agent、Task、Attempt、Lease、Outbox 和 Artifact 的权威状态源；NATS JetStream 负责 Control Plane 与 Gateway 之间的可靠事件传递；Gateway 的连接状态投影必须同步回调度器查询的 `agents` 状态。Kind 只提供开发环境，使用 `emptyDir` 和本地 Secret，不改变生产部署边界。

**技术栈：** Java 17、Maven、Spring Boot、gRPC、JDBC/Flyway、PostgreSQL 16、NATS JetStream、MinIO、Testcontainers、Docker/Colima、Kind、Helm。

---

## 当前基线与缺口

- 本地工具链已具备：Java 17、Maven、Docker/Colima、Compose、Buildx、Kind、Helm、kubectl。
- 最近一次全量 Maven 验证结果为 `200 tests, 0 failures, 0 errors`；真实基础设施聚焦测试另有 3 个测试通过。
- Docker/Colima、Kind 集群和 PostgreSQL、NATS、MinIO 开发依赖均已验证可用。
- `integration-tests/TaskPushE2ETest` 当前只启动内存版 Netty gRPC 服务和内存 command store，没有真实 Control Plane、Gateway、PostgreSQL、NATS 或 MinIO。
- `JdbcAgentStateStore` 只写 `gateway_agent_state`，而 `AgentRepository` 的调度查询只接受 `agents.phase = READY`，真实连接状态还没有进入调度主表。
- Kind 规格需要先修正 Helm 资源名、MinIO 凭据传递、MinIO NetworkPolicy 和 ServiceMonitor 配置。

## 文件职责清单

- 修改：`docs/superpowers/specs/2026-08-18-kind-dev-link-design.md`，修正实现依据和验收命令。
- 修改：`deploy/kind-dev-infra.yaml`，增加可认证的 MinIO、bucket bootstrap 和固定开发凭据引用。
- 新建：`deploy/build-images.sh`，构建并加载三个本地服务镜像。
- 新建：`deploy/helm/kind-values.yaml`，提供 Kind 专用存储、镜像和观测覆盖。
- 修改：`deploy/helm/agentteams-java/templates/networkpolicy.yaml`，放行 Control Plane 到 MinIO 的 9000 端口。
- 修改：`README.md`，提供可复制执行、使用真实资源名的联调步骤。
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/JdbcAgentStateStore.java`，把已认证 Agent 的连接状态同步到 `agents` 主表和 Gateway 投影表。
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayGrpcConfiguration.java` 和 `agent-gateway/pom.xml`，启用真实 JDBC 持久化装配并补齐 Java Time/NATS 运行依赖。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsExecutionEventConsumer.java`，过滤同主题上的通用 Agent outbox 事件。
- 修改：`agent-gateway/src/test/java/io/agentteams/gateway/JdbcAgentStateStoreTest.java`，覆盖 UUID 身份、READY/OFFLINE 和未知 Agent 行为。
- 修改：`integration-tests/src/test/java/io/agentteams/it/FakeAgent.java`，支持真实 UUID、能力、进度、心跳、artifact 和重连。
- 新建：`integration-tests/src/test/java/io/agentteams/it/TaskPushInfrastructureIT.java`，接入真实应用上下文和三类容器；保留 `TaskPushE2ETest` 作为快速协议级回归。
- 验证：`pom.xml` 的 `integration-tests` profile，确保 `TaskPushE2ETest` 已由 Failsafe 执行。

### 任务 1：修正 Kind 规格中的阻断项

**文件：**

- 修改：`docs/superpowers/specs/2026-08-18-kind-dev-link-design.md`

- [x] **步骤 1：修正 Helm 资源名。** 以 `helm template agentteams deploy/helm/agentteams-java` 的实际结果为准，使用 `agentteams-agentteams-java-control-plane`、`agentteams-agentteams-java-gateway` 和 `agentteams-agentteams-java-operator`；同步修正 Service port-forward 命令。
- [x] **步骤 2：补充 MinIO 凭据契约。** MinIO Deployment 使用 `MINIO_ROOT_USER` 和 `MINIO_ROOT_PASSWORD` 引用 `agentteams-storage` Secret；bootstrap Job 使用同一组变量执行：

```bash
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/agentteams
```

- [x] **步骤 3：明确 NetworkPolicy 要求。** storage 开启时，Control Plane egress 必须允许 TCP 9000；验证渲染结果中包含该端口。
- [x] **步骤 4：关闭 Kind 默认 ServiceMonitor。** Kind 不安装 Prometheus Operator，因此 `deploy/helm/kind-values.yaml` 的 `observability.serviceMonitor.enabled` 设为 `false`；ServiceMonitor 留到观测设施计划处理。
- [x] **步骤 5：将冒烟请求改为可执行脚本。** 使用 `curl -fsS`、后台 port-forward、`trap` 清理进程和 `jq -r '.id'` 保存任务 ID，避免 `<taskId>` 手工替换。
- [x] **步骤 6：验证规格。** 运行 `helm template agentteams deploy/helm/agentteams-java -f deploy/helm/kind-values.yaml`，并检查三个 Deployment、Control Plane Service、MinIO 端口和 ServiceMonitor 均符合预期。

### 任务 2：补齐 Kind 开发基础设施和镜像加载

**文件：**

- 修改：`deploy/kind-dev-infra.yaml`
- 新建：`deploy/build-images.sh`
- 新建：`deploy/helm/kind-values.yaml`
- 修改：`deploy/helm/agentteams-java/templates/networkpolicy.yaml`
- 修改：`README.md`

- [x] **步骤 1：先写清单验证。** 用 Ruby YAML parser 解析 `deploy/kind-dev-infra.yaml` 和 `deploy/kind-config.yaml`，再运行 `helm lint deploy/helm/agentteams-java`。
- [x] **步骤 2：添加 MinIO Secret 和 Deployment。** 固定 `minio/minio:RELEASE.2024-11-07T00-52-20Z`，配置 root 凭据、9000/9001 端口、`/minio/health/live` 探针和 `emptyDir`。
- [x] **步骤 3：添加 bucket bootstrap Job。** 使用可获取的固定版本 `minio/mc:RELEASE.2025-07-21T05-28-08Z`，每 2 秒重试 `mc alias set`，成功后执行幂等 bucket 创建。
- [x] **步骤 4：实现 `deploy/build-images.sh`。** 检查 `docker`、`kind`、`kind get clusters` 和 `agentteams` 集群；依次构建并加载：

```bash
docker build -f deploy/docker/control-plane.Dockerfile \
  -t ghcr.io/ly416123/agentteams-control-plane:latest .
docker build -f deploy/docker/gateway.Dockerfile \
  -t ghcr.io/ly416123/agentteams-agent-gateway:latest .
docker build -f deploy/docker/operator.Dockerfile \
  -t ghcr.io/ly416123/agentteams-operator:latest .
kind load docker-image ghcr.io/ly416123/agentteams-control-plane:latest --name agentteams
kind load docker-image ghcr.io/ly416123/agentteams-agent-gateway:latest --name agentteams
kind load docker-image ghcr.io/ly416123/agentteams-operator:latest --name agentteams
```

- [x] **步骤 5：补充 NetworkPolicy 和 Kind values。** storage 开启、ServiceMonitor 关闭、镜像拉取策略保持 `IfNotPresent`。
- [x] **步骤 6：运行脚本级检查。** 执行 `bash -n deploy/build-images.sh`、Helm lint、Helm template，并确认本地脚本不会引用不存在的资源名。
- [x] **步骤 7：Commit。** 使用 `build(基础设施): 补齐 Kind 本地依赖和镜像加载链路`。

### 任务 3：修复 Agent 身份与调度状态同步

**文件：**

- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/JdbcAgentStateStore.java`
- 修改：`agent-gateway/src/test/java/io/agentteams/gateway/JdbcAgentStateStoreTest.java`
- 验证：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`
- 验证：`control-plane/src/test/java/io/agentteams/controlplane/service/TaskAssignmentServiceTest.java`

- [x] **步骤 1：先增加失败测试。** 使用一个真实 UUID 字符串 Agent ID，断言 `registered` 除了写 `gateway_agent_state`，还会把 `agents.phase` 更新为 `READY`；`disconnected` 更新为 `OFFLINE`；未知 Agent ID 不会静默创建错误的主表记录。
- [x] **步骤 2：实现 canonical Agent 更新。** Gateway Hello 必须使用 Control Plane API 返回的 Agent UUID；数据库更新应带 `WHERE id = ? AND phase IN ('PROVISIONING', 'OFFLINE', 'READY')`，更新 runtime、capabilities、updated_at 和 version。
- [x] **步骤 3：保持 Gateway 投影独立。** `gateway_agent_state` 继续保存 presence、connection 时间和运行时细节，不能用它替代 `agents` 主表，也不能把任务状态放进连接注册表。
- [x] **步骤 4：处理连接生命周期。** 首次合法 Hello 进入 READY；连接断开进入 OFFLINE；重复连接先关闭旧连接再更新新连接；未知 UUID 令 Hello 失败并留下可诊断错误。
- [x] **步骤 5：运行聚焦测试。** 执行 `mvn -pl agent-gateway -am -Dtest=JdbcAgentStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认旧的 Gateway 单元测试仍通过。
- [x] **步骤 6：Commit。** 使用 `fix(网关): 同步 Agent 连接状态到调度主表`。

### 任务 4：把 TaskPushE2ETest 改成真实基础设施集成测试

**文件：**

- 修改：`integration-tests/src/test/java/io/agentteams/it/FakeAgent.java`
- 修改：`integration-tests/src/test/java/io/agentteams/it/TaskPushE2ETest.java`（保留协议级快速回归，不改为真实容器）
- 新建：`integration-tests/src/test/java/io/agentteams/it/TaskPushInfrastructureIT.java`
- 修改：`integration-tests/pom.xml`，导入 Spring Boot BOM，避免 Testcontainers 传递的 SLF4J 版本冲突。
- 验证：`agent-gateway/src/main/java/io/agentteams/gateway/AgentGatewayGrpcConfiguration.java`
- 验证：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`

- [x] **步骤 1：先把当前内存测试标识为不满足验收。** 保留 `TaskPushE2ETest` 的协议级重连断言，新增 `TaskPushInfrastructureIT`；真实测试不允许用 `NoopApplication`、`InMemoryCommandStore` 或随机端口 Netty server 代替应用上下文。
- [x] **步骤 2：配置容器。** 使用 Testcontainers core 的 `GenericContainer` 启动 PostgreSQL 16、NATS 2.10 JetStream 和固定 MinIO 镜像；通过动态属性注入 JDBC URL、NATS URL、MinIO endpoint、bucket 和凭据。Docker 不可用时使用 `@Testcontainers(disabledWithoutDocker = true)` 跳过。
- [x] **步骤 3：启动 Control Plane 和 Gateway。** 两个 Spring Boot 上下文共享 PostgreSQL 和 NATS；Control Plane 开启 Flyway、scheduler、NATS consumer/outbox relay，Gateway 开启 JDBC state/command/inbound store、NATS consumer 和真实 gRPC server。
- [x] **步骤 4：扩展 FakeAgent。** 先通过 Control Plane API 创建 Agent，保存返回的 UUID；Hello 使用同一 UUID 和相同能力集合，等待 Ready；收到 TaskAssigned 后发送 Accepted、Progress、Heartbeat、Completed，并通过 MinIO SDK 上传小文件后发送 artifact 引用。
- [x] **步骤 5：覆盖完整业务断言。** 创建并入队任务，断言 Agent 进入 READY、任务生成 Attempt/Assignment/Lease、Gateway 收到 TaskAssigned、Control Plane 收到执行事件、任务进入 SUCCEEDED、Artifact 元数据和对象内容可读。
- [x] **步骤 6：覆盖恢复与幂等。** 完成前断开连接并重新连接，断言未确认命令只重放一次；重复 completion 和重复 artifact completion 不新增 attempt/artifact；过期 lease 恢复能力留待后续 lease recovery 测试补齐。
- [x] **步骤 7：更新 Maven 执行入口。** `TaskPushInfrastructureIT` 使用 `*IT.java` 命名，由已有 Failsafe `**/*IT.java` 规则执行；默认 `mvn test` 保持快速单测，并在报告中区分“协议级 E2E”和“基础设施级 E2E”。
- [x] **步骤 8：运行聚焦验证。** 执行真实容器聚焦测试，确认 PostgreSQL、NATS JetStream、MinIO 和两个应用上下文均启动且完整任务链路通过。
- [x] **步骤 9：Commit。** 使用 `test(集成链路): 验证真实容器任务推送闭环`。

### 任务 5：执行干净 Kind 联调和可重复性验证

**文件：**

- 修改：`README.md`（仅记录实际可执行结果和资源名）
- 验证：`deploy/kind-config.yaml`、`deploy/kind-dev-infra.yaml`、`deploy/build-images.sh`、`deploy/helm/kind-values.yaml`

- [x] **步骤 1：准备镜像资源。** 确认 `kindest/node`、PostgreSQL、NATS、nats-box、MinIO、MinIO mc、Maven build base 和 Java runtime 镜像能够通过当前 registry 或预加载方式取得；Docker Hub 直连超时已记录，并通过固定版本镜像预加载解决。
- [x] **步骤 2：创建干净集群。** 复用已按 `deploy/kind-config.yaml` 创建且未部署业务资源的 `agentteams` 集群，确认 control-plane 和 worker 节点均为 Ready。
- [x] **步骤 3：部署依赖。** 执行 `kubectl apply -f deploy/kind-dev-infra.yaml`，确认 `nats-stream-bootstrap`、`minio-bucket-bootstrap` 完成，以及 PostgreSQL、NATS、MinIO readiness。
- [x] **步骤 4：构建并加载服务镜像。** 执行 `./deploy/build-images.sh`，确认三个 tag 在 Kind 节点可见；Dockerfile 内置 Maven mirror 以支持受限网络。
- [x] **步骤 5：安装 Helm。** 执行 `helm upgrade --install agentteams deploy/helm/agentteams-java -n agentteams -f deploy/helm/kind-values.yaml`，确认实际 Deployment 名称 `agentteams-agentteams-java-control-plane`、`agentteams-agentteams-java-gateway`、`agentteams-agentteams-java-operator` 均可用。
- [x] **步骤 6：执行 API 冒烟。** 使用动态任务 ID，确认 `/actuator/health` 为 200、Agent/Task 为 201、Queue 为 200，任务保持 `QUEUED`；该冒烟不包含真实 Agent 连接。
- [x] **步骤 7：验证 Operator。** 创建 `Worker` CR，确认 Operator 创建 Deployment/Service 并更新 Worker status；修改副本数后状态收敛，删除 CR 后子资源自动删除。
- [x] **步骤 8：验证幂等重跑。** 在同一集群重复执行依赖 apply 和 Helm upgrade，确认无手工清理数据库、Secret 或已完成 Job 的要求。
- [x] **步骤 9：Commit。** 使用 `fix(开发环境): 完成 Kind 联调与 Operator 收敛`，同时提交清单、镜像构建和 Operator 修正。

### 任务 6：阶段验收与后续产品阶段排序

- [x] **步骤 1：运行回归。** 执行 `source deploy/dev-env.sh && mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 test`，全量 Surefire 回归通过；真实基础设施测试另行以 `TaskPushInfrastructureIT` 聚焦命令通过。
- [x] **步骤 2：验证交付门槛。** 真实容器 E2E、Kind 部署、Operator Worker 收敛和重复运行均已验证，基础设施垂直切片完成。
- [x] **步骤 3：持续推进产品阶段。** 基础切片和当前已纳入的产品阶段状态如下：
  1. [x] QwenPaw Runtime Adapter：已实现 JSON Lines 外部进程边界、gRPC 双向流端口、assignment/lease 回调和异常退出处理；官方 HTTP/SSE 适配器已接入，真实 QwenPaw + DeepSeek Kind 任务已完成验证。
  2. [x] ConfigSnapshot 与 Artifact lifecycle：已完成版本、checksum、上传确认、配置下发协议、Worker 暂存、幂等元数据校验和按保留策略清理。
  3. [x] DeepSeek Manager：已完成 Provider、结构化意图、审批/工具权限和审计代码；本地 DeepSeek API smoke、模型配置和真实任务链路已完成验证。
  4. [x] Team CRD 与调度：已完成 Fabric8 informer、稳定 Team 身份、PostgreSQL 幂等同步、成员替换/删除、runtime/capability/approval/concurrency policy 和独立只读 RBAC；确定性 Spring/PostgreSQL 验收已通过，已准备第二个真实 QwenPaw Worker 并完成 Kind 真实多 Agent 调度冒烟（1 个 ASSIGNED/RUNNING、2 个 QUEUED）。
  5. [ ] OIDC/mTLS/RBAC/Secret rotation：已完成可配置 OIDC JWT 签名、issuer、audience、过期时间和租户/权限 claim 映射；已完成 API 路由权限校验、Agent/Task/ConfigSnapshot/ConfigFile 资源 scope 校验、JWKS `kid` 轮换测试、Kind 本地 Gateway↔Worker 双向 mTLS、证书挂载验证、Workload ServiceAccount 隔离，以及按安装命名空间收紧 Operator/Team sync RBAC；已补齐稳定 Secret 名称、可选 Reloader 滚动刷新注解、生产 Secret 契约、OIDC JWKS 轮换说明、仓库级 OIDC 验收脚本、Kind Keycloak 本地签发端、实时 `kid` 轮换 smoke 和 CI job；真实目标 IdP 联调仍待补齐。
  6. [ ] Matrix AppService：已完成 Control Plane 适配器和幂等处理，并补齐本地 Tuwunel、文件式 AppService registration、shared-token 校验、真实用户/房间 `start/status` 命令 smoke 和 Kind CI；`cancel/retry/pause/approve/reject` 已接入带 scope、权限、版本和幂等保护的任务生命周期，生产部署与完整命令 smoke 仍待补齐。
  7. [ ] OpenTelemetry、HA、备份恢复和故障注入：当前具备基础指标、告警、备份脚本和部分 HA 机制，完整生产化验收仍待补齐。

当前未完成项主要是生产化能力和外部服务联调：mTLS 证书与验证器、完整资源级 RBAC/Secret rotation、Matrix/Tuwunel 实例、Prometheus Operator，以及完整灾备和故障注入验收。OIDC JWT 验证已具备代码和单元测试，但仍需在目标 IdP 上进行 JWKS/claim 联调。Team Kind 冒烟已在两个真实 READY Agent 上完成；脚本仍要求显式传入两个 Agent UUID，避免将占位 Agent 当作真实 Worker。

## Verification checklist

- `git diff --check` 通过，计划引用的路径存在或被本计划明确创建。
- Helm lint/template 通过，且不在无 Prometheus Operator 的 Kind 集群中渲染 ServiceMonitor。
- MinIO bootstrap 使用与 Deployment 相同的 root 凭据，并能幂等创建 bucket。
- Gateway Hello 能让同一个 UUID 的 `agents` 记录进入 READY；断开后进入 OFFLINE。
- 真实 TaskPushE2ETest 启动 PostgreSQL、NATS JetStream、MinIO 和两个应用上下文。
- 任务完成、artifact、重连重放和重复事件均有数据库断言。
- Kind 集群可从零启动，依赖、镜像、Helm、冒烟和 Operator 验证均可重复执行。
- 任何未具备的外部资源（OIDC、Matrix/Tuwunel、Prometheus Operator 和第二个 Team smoke Agent）都明确列为资源前置条件，不伪装成代码已完成。
