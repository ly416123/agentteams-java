# 批次 B：安全与运维闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在批次 A 的 `fd721d3` 基础上完成 Worker 运维操作、统一资源授权、项目成员生命周期、External Secrets 状态解析、签名制品发布和生产恢复入口。

**架构：** PostgreSQL 保存 Worker Operation、项目成员、邀请和发布事实；Kubernetes 只保存 Worker/ExternalSecret 的期望态与状态投影；发布和恢复通过签名 Release Manifest 驱动。所有外部调用在持久化意图提交后执行，重试和进程重启不依赖内存状态。

**技术栈：** Java 17、Spring Boot 3.4.5、Spring JDBC、Flyway、Fabric8 Kubernetes Client、JUnit 5、Mockito、Maven、Helm、Python、GitHub Actions、BuildKit、Cosign、CycloneDX/SPDX。

**项目环境：** 本机 macOS 使用 Colima 提供 Docker daemon；`source deploy/dev-env.sh` 是 Maven/Testcontainers 和本地 Kind 验证的统一入口。普通变更必须先通过本地 Docker-backed 验证，再推送到 GitHub Actions。

---

## 当前基线与范围

- 批次 A 的 Sandbox Provider、AgentScope Worker 路由、Manager、Team Revision/Effective Config 和 Helm 基础已在 `main` 完成，CI 的 `verify`、`kind-recovery`、`kind-oidc` 已通过。
- 当前 `AgentService` 只有直接的 `drain/terminate` 状态变更，没有可恢复的 rollout/rollback Operation。
- 当前 `ExternalSecretsSecretResolver` 只校验引用并固定返回 `UNAVAILABLE`，不读取 ExternalSecret Ready Condition 或目标 Secret metadata。
- 当前项目成员 API 只有新增、查询和禁用，缺少邀请、启用、角色变更和 Owner 转移。
- 当前发布流程没有签名 Release Manifest、SBOM、provenance、环境晋级和生产恢复编排。
- gVisor/Kata、真实 Secret Manager、外部 IdP、真实生产备份和 RPO/RTO 仍由 L5/L6 受控环境单独验收，本计划只实现仓库侧契约和入口。

## 文件清单与职责

### Worker 运维

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperation.java`、`WorkerOperationStatus.java`、`WorkerOperationType.java`、`WorkerRolloutRequest.java`、`WorkerRolloutConfirmation.java`、`WorkerOperationService.java`、`WorkerOperationRepository.java`。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/AgentService.java`、`control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java`、`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`、`operator/src/main/java/io/agentteams/operator/WorkerReconciler.java`、`operator/src/main/java/io/agentteams/operator/WorkerResourceFactory.java`。
- 创建迁移：`control-plane/src/main/resources/db/migration/V46__worker_operations.sql`。
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/worker/WorkerOperationServiceTest.java`、`WorkerOperationRepositoryTest.java`、`operator/src/test/java/io/agentteams/operator/WorkerReconcilerTest.java`。

### 统一 RBAC 与项目成员

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ResourceAction.java`、`ResourceRef.java`、`ResourceAuthorizationService.java`、`ResourceAuthorizationMatrix.java`、`control-plane/src/main/java/io/agentteams/controlplane/project/ProjectInvitationService.java`、`ProjectInvitationRepository.java`。
- 修改：`ApiAuthorizationPolicy.java`、`AuthorizationService.java`、`ProjectAuthorizationService.java`、`ProjectController.java`、`TeamController.java`、`AgentController.java`、`manager/src/main/java/io/agentteams/manager/HttpTaskCommandPort.java`。
- 创建迁移：`control-plane/src/main/resources/db/migration/V47__project_invitations_and_authorization_scope.sql`。
- 测试：`ResourceAuthorizationServiceTest.java`、`ProjectInvitationServiceTest.java`、`ProjectControllerTest.java`、`OidcApiAuthorizationIntegrationTest.java`。

### External Secrets 状态解析

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/ExternalSecretStatusReader.java`、`KubernetesSecretMetadataReader.java`、`ExternalSecretStatus.java`。
- 修改：`ExternalSecretsSecretResolver.java`、`SecretResolverFactory.java`、`SecretResolverProperties.java`、`ControlPlaneConfiguration.java`、`deploy/helm/agentteams-java/templates/rbac.yaml`。
- 测试：`ExternalSecretsSecretResolverTest.java`、`SecretResolverFactoryTest.java`、`ExternalSecretStatusReaderTest.java`、`scripts/test_batch_b_secret_contract.py`。

### 制品发布与生产恢复

- 创建：`.github/workflows/release.yml`、`.github/workflows/promote.yml`、`scripts/validate-release-manifest.py`、`scripts/validate-production-recovery.py`、`scripts/fixtures/release-manifest-valid.json`、`scripts/fixtures/release-manifest-invalid.json`、`deploy/production/recovery/README.md`、`deploy/production/recovery/preflight.sh`、`deploy/production/recovery/consistency-check.py`。
- 创建：`deploy/helm/agentteams-java/templates/ingress.yaml`、`deploy/helm/agentteams-java/templates/gateway-api.yaml`。
- 修改：`deploy/build-images.sh`、`deploy/helm/agentteams-java/values.yaml`、`values-production.example.yaml`、`templates/networkpolicy.yaml`、`scripts/validate-production-values.py`、`scripts/validate-production-network.py`、`deploy/production/README.md`、`deploy/production/observability-runbook.md`。
- 测试：`scripts/test_batch_b_release_contract.py`、`scripts/test_batch_b_recovery_contract.py`、`scripts/test_production_network_contract.py`。

## 依赖与执行策略

```text
任务 1 Worker Operation ───────────────┐
任务 2 RBAC/成员生命周期 ──────────────┼──> 任务 6 批次 B 联合验收
任务 3 External Secrets ──────────────┤
任务 4 Release Manifest/晋级 ─────────┤
任务 5 入口/网络/恢复 ─────────────────┘
```

任务 1、2、3 之间没有生产文件依赖，可以并行进行设计和失败测试；任务 4、5 也可以并行。为避免再次产生多分支状态，实际写入采用单一 `main` 主线：每个任务使用一个短生命周期工作树，完成后立即快进合并、推送并删除分支和工作树，不保留批次级开发分支。

## 任务 1：Worker 运维 Operation

**目标：** 把 drain、rollout、rollback、terminate 从瞬时 API 变成数据库驱动、可重试、可审计的 Operation。

**当前增量进度（2026-08-28）：** 已完成第一纵切：V46 `worker_operations` 表、JDBC Repository、Operation 类型/记录、DRAIN/TERMINATE 服务入口和 HTTP `202 Accepted` 入口；已验证幂等复用、幂等键请求变更拒绝、活动 Lease 终止保护、活动 Operation 唯一约束、过期 lease 回收、rollout 调度 fencing、DRAIN 后新任务排除和资源范围校验。随后补齐 Worker CR 的 rollout 版本期望字段（spec digest/runtime/config revision/Secret generation）、Deployment Pod template 版本 annotations 和 Reconciler 的就绪版本状态投影，旧 CR 兼容且已通过本地 Docker/Colima 全量 Gate。当前又完成 Gateway Hello 版本事实纵切：协议新增可选版本字段，Operator 将 CR 期望值注入 Worker 环境，Worker Hello 回传并由 Gateway 连接快照/JDBC 投影保存，旧 Worker 仍兼容；本地单测、集成测试和重跑的 `TaskPushInfrastructureIT` 已通过，首次全量 Gate 的 PostgreSQL SSL 握手失败为瞬态环境问题。随后新增带数据库 leader lease 的 Worker Operation 独立恢复调度器，过期 `PENDING/RUNNING` Operation 可在进程重启后脱离任务流量被回收；已通过本地全量单测和第二轮 Docker/Colima Gate。随后增加了 `WorkerRolloutConfirmation` 和 `confirmRollout`：Operator、Gateway 必须同时报告完全一致的 digest/runtime/config revision/Secret generation 才能进入 `SUCCEEDED`，部分或过期观察保持 `RUNNING/FAILED`；并补齐 Operation 查询、按 Agent 资源边界回滚，以及由 `X-AgentTeams-Internal-Token` 保护的 rollout 确认入口。当前新增 V49 `worker_operation_observations` 独立保存 Operator/Gateway 两方事实，并提供两个分别受内部 Token 保护的确认入口；部分事实可独立到达、幂等更新，只有两方事实同时匹配请求版本才进入 `SUCCEEDED`；同时增加仅返回未过期 ROLLOUT 的 Token 保护发现入口，返回 Operation version 与目标版本事实，供后续真实 Operator/Gateway 适配器使用。本地 Docker/Colima 定向测试、Maven 集成门禁、脚本和 Helm 校验均已通过。实际 Operator/Gateway 读取适配器以及失败 rollout 自动恢复稳定资源仍未完成，不能将任务 1 标记为整体完成。

- [ ] **步骤 1：编写失败测试**

覆盖以下不变量：重复 `Idempotency-Key` 返回同一 Operation；DRAINING Worker 不再被 `TaskAssignmentService` 选中；活动 Lease 不为零时不能 rollout/terminate；进程重启后可从 `PENDING/RUNNING` Operation 继续；Worker CR 与 Gateway Hello 未同时确认新 digest 时不能标记成功；失败 rollout 自动回滚上一稳定 spec。

```java
@Test
void drainingWorkerIsExcludedFromNewAssignments() {
    operations.drain(agentId, agentVersion, "drain-1");

    assertThat(assignments.findReadyMatching(taskSpec))
            .isEmpty();
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane,operator -am -Dtest=WorkerOperationServiceTest,WorkerOperationRepositoryTest,WorkerReconcilerTest test`

预期：编译或断言失败，因为 `worker_operations` 表、Operation 服务和 rollout 状态接线不存在。

- [ ] **步骤 3：实现持久化 Operation**

新增 `V46__worker_operations.sql`，保存 agent、type、status、requested spec digest、previous stable spec、idempotency key、expected version、owner、lease expiry、failure category、correlation ID 和审计时间；为 `(agent_id, idempotency_key)`、活动 Operation 和版本建立唯一约束。Repository 使用 `SELECT ... FOR UPDATE` 和 expected version。

- [ ] **步骤 4：实现服务与 Worker/Operator 接线**

`WorkerOperationService` 提供 `drain`、`rollout`、`rollback`、`terminate`；Scheduler 只在 Operation lease 有效时推进；`TaskAssignmentService` 过滤 DRAINING/活动 rollout Worker；Operator 将目标 image digest/runtime/config/Secret generation 写入 CR；Gateway Hello 和 Operator status 均确认后进入 `SUCCEEDED`，超时进入回滚。

- [ ] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl control-plane,operator -am -Dtest=WorkerOperationServiceTest,WorkerOperationRepositoryTest,WorkerReconcilerTest,TaskAssignmentServiceTest test`。

预期：Operation 幂等、重启恢复、活动任务保护、旧版本 fencing 和回滚测试全部通过。

- [ ] **步骤 6：Commit**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/worker control-plane/src/main/java/io/agentteams/controlplane/service/AgentService.java control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java control-plane/src/main/resources/db/migration/V46__worker_operations.sql control-plane/src/test operator
git commit -m "feat(worker): 增加可恢复运维操作"
```

## 任务 2：统一资源授权与项目成员生命周期

**目标：** 让 HTTP、Manager Tool 和异步业务入口复用同一 Action/scope 授权，并补齐邀请、启用、禁用、角色变更和 Owner 转移。

**当前增量进度（2026-08-27）：** 已完成第一纵切：V48 `project_invitations` 与邀请幂等表、namespace/tenant 约束下的邀请记录、SHA-256 token hash 存储、24 小时有效期、接受邀请时的 subject 校验、过期拒绝和数据库条件更新幂等激活，并开放项目邀请创建/接受 API；补齐成员重新启用、角色变更 CAS、最后 Owner 保护和带 expected project version 的 Owner 转移 API。随后新增不可变 `ResourceAction`/`ResourceRef`/`ResourceAuthorizationMatrix`/`ResourceAuthorizationService`，并接入邀请创建路径，覆盖跨 scope 与角色越权负向测试。现已补齐邀请、接受、启用、禁用、角色变更和 Owner 转移的成功审计，目标 subject 仅以 SHA-256 hash 进入事件，审计 sink 故障不改变成员主业务结果。当前仍未完成 Manager/异步消费者统一授权接线。

**任务提交授权纵切（2026-08-27）：** `TaskService.create` 现在在持久化前校验已认证主体的完整 scope，并通过项目名称解析到 UUID 后复用 `ResourceAuthorizationService` 的 `TASK_CREATE` 角色矩阵；拒绝请求不会写入任务。Matrix 入口在调用同一 Control Plane TaskService 前后绑定并恢复 `PrincipalContext`，因此不会依赖客户端权限集合扩大权限，也不会在线程复用时串身份。HTTP/Matrix/Service 定向测试、Control Plane 全量测试、脚本测试和本机 Colima Docker 集成验证已通过。Manager 跨服务用户身份委托协议及异步消费者接线仍待后续纵切，当前不以未定义的信任 Header 代替认证。

- [ ] **步骤 1：编写失败测试**

覆盖 Action 矩阵的允许/拒绝组合、跨 tenant/project 负向请求、异步消费者 scope 校验、邀请 Token 只保存 hash、过期邀请拒绝、重复 accept 幂等、最后一个 Owner 不能禁用、Owner 转移需要 expected project version，以及成员状态变化产生脱敏审计。

```java
@Test
void cannotDisableTheLastProjectOwner() {
    assertThatThrownBy(() -> invitations.disable(projectId, ownerSubject, "disable-1"))
            .isInstanceOf(ProjectMembershipConflictException.class)
            .hasMessageContaining("MEMBERSHIP_LAST_OWNER");
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane,manager -am -Dtest=ResourceAuthorizationServiceTest,ProjectInvitationServiceTest,ProjectControllerTest,OidcApiAuthorizationIntegrationTest test`

预期：新 API、统一 Action 矩阵和邀请持久化测试失败。

- [ ] **步骤 3：实现 Action 矩阵与 Service 边界**

定义不可变 `ResourceAction` 矩阵；所有 Service 先解析资源 scope，再调用 `ResourceAuthorizationService.require`。Filter 只负责身份认证和粗粒度入口检查，不能替代 Service 层授权；Manager Tool 和异步消费者复用同一方法。

- [ ] **步骤 4：实现成员状态机与迁移**

新增 `V48__project_invitations_and_authorization_scope.sql`，保存 invitation token hash、过期时间、目标 subject、角色、状态、project version 和审计关联；实现 `INVITED -> ACTIVE -> DISABLED`、过期和重新启用；Owner 转移在一个事务内检查目标 ACTIVE、当前 Owner 和 expected version。（V47 已用于 Gateway Worker 版本投影。）

- [ ] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl control-plane,manager -am -Dtest=ResourceAuthorizationServiceTest,ProjectInvitationServiceTest,ProjectControllerTest,OidcApiAuthorizationIntegrationTest test`。

预期：所有 Action、scope、成员状态和审计测试通过，测试输出不包含 Token 原文。

- [ ] **步骤 6：Commit**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/security control-plane/src/main/java/io/agentteams/controlplane/project control-plane/src/main/java/io/agentteams/controlplane/api control-plane/src/main/resources/db/migration/V47__project_invitations_and_authorization_scope.sql control-plane/src/test manager/src/main/java/io/agentteams/manager/HttpTaskCommandPort.java
git commit -m "feat(权限): 完善资源授权与成员生命周期"
```

## 任务 3：External Secrets Ready 状态解析

**目标：** 只读取 ExternalSecret 状态和目标 Secret metadata，不读取 Secret value，并返回稳定的 `MISSING/UNAVAILABLE/RESOLVED` 分类。

**当前增量进度（2026-08-28）：** 已完成 ExternalSecret 状态与 observed generation 读取、目标 Secret key/metadata 读取、`externalsecret://namespace/name#key` 引用解析和稳定状态分类；ExternalSecret 未 Ready、generation 落后、目标 Secret/key 缺失及 API 异常均 fail-closed。Helm 已通过显式后端配置为 Control Plane 注入配置，并将 ExternalSecret/Secret `get` 权限绑定到 Control Plane 的 namespace Role，未授予 list/watch 或写权限；Reader 已切换到当前 ESO v1 CRD API，Kind CI 安装固定版本 ESO 2.9.0 并执行真实 Kubernetes Provider 收敛验收，验证 Ready、同步标记、目标 Secret key 和 resourceVersion，且验收资源使用最小 namespace RBAC 并自动清理。Java 单测、Python 87 项脚本测试、Helm lint、本机 Colima Docker 全量 Gate 和真实 Kind 收敛验收均已通过。任务 3 的仓库侧实现与 L4 验收完成；真实生产 Secret Manager、外部集群和 L5/L6 仍需受控环境验收。

- [x] **步骤 1：编写失败测试**

覆盖非法引用、ExternalSecret 不存在、Condition 非 Ready、目标 Secret/key 缺失、generation 落后、Kubernetes API 异常和 Ready metadata 存在；使用 fake reader 证明 resolver 没有调用 Secret value 读取方法。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -q -pl control-plane -am -Dtest=ExternalSecretsSecretResolverTest,SecretResolverFactoryTest,ExternalSecretStatusReaderTest test`

预期：合法引用仍得到 `UNAVAILABLE`，Ready 状态和 metadata 读取测试失败。

- [x] **步骤 3：实现只读 Reader 与稳定分类**

`ExternalSecretStatusReader` 只读取指定 namespace 的 ExternalSecret status；`KubernetesSecretMetadataReader` 只读取指定 Secret metadata 和 key 列表；Resolver 按规格映射状态，诊断只保存固定错误分类和长度受限原因，不保存引用 key、Token 或 Secret 内容。

- [x] **步骤 4：收紧 Helm RBAC 与配置**

为 Control Plane 增加指定 namespace 的 `externalsecrets/status` 和单个稳定 Secret `get` 权限；禁止 `list/watch` 所有 Secret；Resolver 未配置 reader 时保持安全的 `UNAVAILABLE` 行为，启用后缺少依赖立即 fail-closed。

- [x] **步骤 5：运行测试确认通过**

运行：`mvn -q -pl control-plane -am -Dtest=ExternalSecretsSecretResolverTest,SecretResolverFactoryTest,ExternalSecretStatusReaderTest test`，再运行 `python3 -m unittest scripts/test_batch_b_secret_contract.py`。

预期：状态分类、无明文读取和最小 RBAC 契约全部通过。

- [x] **步骤 6：Commit**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/security control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java control-plane/src/test deploy/helm/agentteams-java/templates/rbac.yaml scripts/test_batch_b_secret_contract.py
git commit -m "feat(安全): 接入External Secrets状态解析"
```

## 任务 4：签名制品、SBOM 与环境晋级

**目标：** 让生产部署只接受可追溯、可验证的 digest 和签名 Release Manifest。

**当前增量进度（2026-08-27）：** 已完成第一纵切：新增 Release Manifest 校验器，强制校验 Git SHA、稳定 Chart 版本、四个服务组件的 SHA-256 digest、SBOM/signature/provenance HTTPS 引用、Manifest 签名元数据和显式环境；补充正向/负向 fixture。新增 tag 受限的 release workflow，完成 Java/集成/Helm 门禁、BuildKit digest 镜像构建、CycloneDX/SLSA attestation、Cosign keyless 签名、签名 Chart 打包和 Release Artifact 上传；新增 production Environment 晋级 workflow，验证 Manifest、Chart、镜像签名及 SBOM/provenance attestation，仅消费 digest，不重建镜像。脚本契约、YAML/Action pin、Helm 和本机 Docker/Colima Maven 全量 Gate 已通过。当前仍未完成生产 Canary、错误预算、自动回滚和真实 GHCR/受控环境演练，不能将任务 4 整体标记完成。

- [x] **步骤 1：编写失败契约测试**

检查 manifest 缺少组件、digest 格式错误、Git SHA 不匹配、签名/attestation 缺失、生产 values 使用 tag、未知目标环境和未批准 Environment；验证工作流中的第三方 Action 使用完整 commit SHA。

- [x] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts/test_batch_b_release_contract.py`。

预期：因 release/promote workflow 和 manifest validator 不存在而失败。

- [x] **步骤 3：实现 manifest 校验和 release workflow**

`release.yml` 只接受受保护分支 tag，依次运行 Java/Helm/Kind 验收、BuildKit 构建、SBOM、依赖/Secret 扫描、GHCR 推送、Cosign keyless 签名和 provenance 生成；输出包含组件 digest、Chart version、Git SHA 和 SBOM 引用的 `release-manifest.json`。

- [x] **步骤 4：实现 promote workflow**

`promote.yml` 只接收已签名 manifest 和显式环境，验证签名后替换 production overlay 的 digest，执行预检、Canary、Task/Config/Sandbox 冒烟和错误预算检查；失败时恢复上一签名 manifest，不重建镜像。

- [x] **步骤 5：运行静态验证**

运行：`python3 -m unittest scripts/test_batch_b_release_contract.py`、`python3 scripts/validate-release-manifest.py --manifest scripts/fixtures/release-manifest-valid.json`、`helm lint deploy/helm/agentteams-java`、`git diff --check`。

预期：正向 fixture 通过，tag/伪造 digest/缺签名/未审批环境 fixture 被拒绝。

- [ ] **步骤 6：Commit**

```bash
git add .github/workflows/release.yml .github/workflows/promote.yml scripts/validate-release-manifest.py scripts/test_batch_b_release_contract.py deploy/build-images.sh deploy/helm/agentteams-java
git commit -m "feat(交付): 建立签名制品晋级流程"
```

## 任务 5：生产入口、外部网络与恢复编排

**目标：** 补齐 Ingress/Gateway API 选择、外部 egress 契约和不含凭据的生产恢复入口。

**当前增量进度（2026-08-27）：** 已完成第一纵切：新增默认关闭的 Ingress/Gateway API 入口模板，仅暴露 `/api/v1` Control Plane HTTP API，Gateway gRPC 保持独立内部 Service；补齐 `CIDR`、`PROXY`、`PLATFORM` egress 模式的配置校验与 proxy CIDR 渲染，并扩展 values schema；新增恢复参数预检和恢复后元数据引用一致性校验，脚本 fail-closed 且不读取 Secret、dump 或业务载荷。恢复契约、网络模式负向测试、Ingress/Gateway API Helm render、Helm lint 已通过。当前仍未完成真实 CNI/平台 egress 验收、Canary 入口切换和完整生产恢复演练，不能将任务 5 整体标记完成。

- [x] **步骤 1：编写失败契约测试**

覆盖 CIDR/PROXY/PLATFORM 三种 egress 模式、公共网段拒绝、Ingress 不暴露 `/internal` 和 Actuator、Gateway gRPC 不与公共 API 共用入口、恢复脚本拒绝 dump/Secret 路径、恢复失败保持入口关闭。

- [x] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts/test_batch_b_recovery_contract.py scripts/test_production_network_contract.py`。

预期：Ingress/Gateway API 模板和生产恢复编排测试失败。

- [x] **步骤 3：实现 Helm 入口与 egress 模式**

增加可选 Ingress/Gateway API 模板和 values schema；默认关闭；公共 API 与内部端点分离；NetworkPolicy 根据 `CIDR`、`PROXY`、`PLATFORM` 生成对应规则，禁止未豁免 `0.0.0.0/0`/`::/0`，不生成虚假的 FQDN Policy。

- [x] **步骤 4：实现恢复 preflight 与一致性校验**

`preflight.sh` 只接受 backup ID、时间点和非 Secret endpoint，验证目标环境、版本和 manifest；`consistency-check.py` 检查 Task、Attempt、Artifact、Config binding、Quota reservation、Sandbox 和 Outbox 引用，不输出数据库 dump、对象内容或 Secret。

- [x] **步骤 5：运行静态验证**

运行：`python3 -m unittest scripts/test_batch_b_recovery_contract.py scripts/test_production_network_contract.py`、`python3 scripts/validate-production-network.py`、`python3 scripts/validate-production-values.py`、`helm lint deploy/helm/agentteams-java`、`helm template agentteams deploy/helm/agentteams-java --namespace agentteams`。

预期：模板、生产 values、网络规则和恢复参数校验全部通过。

- [ ] **步骤 6：Commit**

```bash
git add deploy/helm/agentteams-java deploy/production/recovery deploy/production/README.md scripts/test_batch_b_recovery_contract.py scripts/test_production_network_contract.py scripts/validate-production-network.py scripts/validate-production-values.py
git commit -m "feat(生产): 增加入口网络与恢复契约"
```

## 任务 6：批次 B 联合验收、文档和交付

**当前增量进度（2026-08-27）：** 本机 Docker/Colima Gate、脚本和 Helm 全量验证已通过；GitHub Actions `33077363017` 首次全绿，针对既有 Worker restart 时序问题的修复提交 `237c3ef` 已由 `33080377055` 的 `verify`、`kind-oidc`、`kind-recovery` 再次全绿确认。已完成安全/规格审查并同步路线图、两份子规格、架构地图和 L5/L6 验收边界；当前第一纵切收口完成，后续仍需补齐各模块整体完成定义。

- [x] **步骤 1：运行本地 Docker-backed、脚本和 Helm 全量验证**

运行：`source deploy/dev-env.sh && docker info && mvn -q -Pintegration-tests verify`、`python3 -m unittest discover -s scripts -p 'test_*.py'`、`helm lint deploy/helm/agentteams-java`、`git diff --check`。

预期：Docker daemon、Maven/Testcontainers、脚本和 Helm 检查全部通过；只有 KVM/外部平台验收单独记录环境条件，不将跳过写成通过。

- [x] **步骤 2：运行 CI 等价静态与 Kind 验收**

验证 Worker drain 后不接收新任务、rollout 失败回滚、跨项目授权拒绝、ExternalSecret Ready 收敛、入口与 egress 策略、签名 manifest 校验和现有 PostgreSQL/MinIO/NATS/Matrix/OIDC/Worker restart 场景。

- [x] **步骤 3：进行安全与规格审查**

逐项对照 `docs/superpowers/specs/2026-08-26-control-plane-governance-closure-design.md` 和 `docs/superpowers/specs/2026-08-26-production-delivery-reliability-design.md`，检查 Secret、JWT、Prompt/Response、数据库内容、artifact 和外部响应没有进入日志、事件、指标或 CI Artifact。

- [x] **步骤 4：同步路线图和验收边界**

更新路线图、两份子规格、生产 Runbook 和架构地图：W2/W3 只在 L1-L4 或真实 L5/L6 证据具备时标记对应层级；记录每个 release manifest、Git SHA、签名和恢复演练报告的关联关系。

- [x] **步骤 5：Commit 并推送主线**

```bash
git add docs/superpowers/plans/2026-08-27-batch-b-security-operations-plan.md docs/superpowers/specs docs/architecture-map.html deploy/production
git commit -m "docs(路线图): 建立批次B安全运维计划"
git push origin main
```

## 完成定义

批次 B 只有同时满足以下条件才可标记完成：

1. Worker Operation、统一授权、成员生命周期和 Secret 状态均有持久化事实、幂等键、expected version 和重启恢复测试；
2. 生产镜像引用 digest，Release Manifest 可验证 Git SHA、SBOM、provenance 和 Cosign 签名；
3. Ingress、Gateway API、NetworkPolicy、Secret RBAC 和恢复入口默认安全、配置错误 fail-closed；
4. `mvn test`、集成验证、脚本测试和 Helm 校验通过，Kind 验收结果与本地环境限制分别记录；
5. 生产 L5/L6 项目只依据真实 Linux/KVM 或预发布平台报告标记，不用静态模板或 Fake Provider 替代；
6. `main` 与 `origin/main` 一致，临时分支和工作树已清理，路线图状态与代码证据一致。
