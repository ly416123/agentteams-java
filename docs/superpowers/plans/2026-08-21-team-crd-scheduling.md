# Team CRD 与多 Agent 调度实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 让 Control Plane 直接监听 Team CRD，将团队期望状态同步到 PostgreSQL，并让调度器在事务内执行团队成员、能力、运行时、审批和并发约束。

**架构：** Control Plane 使用 Fabric8 动态 Kubernetes Client 读取 `agentteams.io/v1alpha1/teams`，通过稳定的 `namespace/name` UUID 对 Team 数据做幂等 upsert。调度器在 Team 行锁下选择确定性 Agent 并写入现有 task attempt、assignment、lease 和 team assignment 表；无 `teamId` 的任务继续走现有全局匹配路径。

**技术栈：** Java 17、Spring Boot 3.4、Fabric8 Kubernetes Client 7.3、PostgreSQL/Flyway、JUnit 5、Testcontainers、Kubernetes CRD、Helm、Kind。

---

## 文件清单

- 修改：`control-plane/pom.xml`，加入 Fabric8 Kubernetes Client。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdSnapshot.java`，保存解析后的不可变 CRD 快照。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdParser.java`，解析 GenericKubernetesResource、校验字段并生成稳定 Team UUID。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamResourceSource.java`，隔离 Kubernetes 资源读取边界。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/KubernetesTeamResourceSource.java`，实现 Fabric8 informer。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdSynchronizer.java`，处理 add/update/delete 并调用事务同步。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TeamRepository.java`，增加 Team 快照锁定、upsert、成员替换和删除标记。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`，增加团队候选 Agent 的带锁查询。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`，接入 Team policy、并发锁和审批字段。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`，注册同步器和 Kubernetes Client 条件 Bean。
- 修改：`operator/src/main/java/io/agentteams/operator/TeamPolicy.java`、`TeamSpec.java`、`TeamResourceFactory.java`，补充 runtime/capability policy。
- 修改：`deploy/helm/agentteams-java/values.yaml`、`control-plane.yaml`、`rbac.yaml`、`networkpolicy.yaml`，启用只读 Team sync 权限和 Kubernetes API 出站访问。
- 修改：`deploy/helm/agentteams-java/crds/teams.yaml`，补充 policy schema 和数组约束。
- 创建：对应 Java 单测、PostgreSQL 集成测试、Helm/manifest 静态检查和 `scripts/smoke-kind-team-scheduling.sh`。
- 修改：`README.md`、`docs/superpowers/plans/2026-08-18-kind-vertical-slice.md`，记录 Team sync 使用方式并校准旧进度状态。

### 任务 1：建立 Fabric8 依赖与 CRD 快照解析

**文件：**
- 修改：`control-plane/pom.xml`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdSnapshot.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdParser.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamCrdParserTest.java`

- [x] **步骤 1：编写失败测试。** 测试一个完整 Team JSON 的稳定 UUID、规范名称、policy 默认值、去重后的 runtime/capability 列表，并测试非法 UUID、重复成员和缺少 policy 字段被拒绝。

```java
@Test
void parsesTeamWithStableIdentityAndNormalizedPolicy() {
    TeamCrdSnapshot snapshot = parser.parse(resource("agentteams", "platform", "42",
            "{\"spec\":{\"leaderRef\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"members\":[],\"policy\":{\"maxConcurrentTasks\":4,"
                    + "\"requireApproval\":false,\"allowedRuntimes\":[\"qwenpaw\",\"qwenpaw\"],"
                    + "\"requiredCapabilities\":[\"python\",\"python\"]}}}"));

    assertThat(snapshot.id()).isEqualTo(TeamCrdParser.stableId("agentteams", "platform"));
    assertThat(snapshot.name()).isEqualTo("agentteams/platform");
    assertThat(snapshot.policy().allowedRuntimes()).containsExactly("qwenpaw");
    assertThat(snapshot.policy().requiredCapabilities()).containsExactly("python");
}
```

- [x] **步骤 2：运行红灯。** 执行 `mvn -q -pl control-plane -am -Dtest=TeamCrdParserTest test`，先以缺少快照解析类型失败，再在实现后转绿。
- [x] **步骤 3：实现最小解析器。** 使用 Jackson `JsonNode` 读取 `metadata.namespace/name/resourceVersion` 和 `spec.leaderRef/members/policy`；`agentRef` 使用 `UUID.fromString`；稳定 UUID 使用 `UUID.nameUUIDFromBytes`；数组去重且保持声明顺序。
- [x] **步骤 4：运行绿灯。** 重跑同一 Maven 命令，全部解析测试通过。
- [x] **步骤 5：提交。** 已提交 `feat(团队调度): 增加 Team CRD 快照解析`。

### 任务 2：实现 PostgreSQL Team 快照同步

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TeamRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamCrdSynchronizer.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamCrdSynchronizerTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/persistence/TeamRepositoryIT.java`

- [x] **步骤 1：编写失败测试。** 使用 Testcontainers PostgreSQL 验证首次同步创建 Team、policy 和 memberships；重复同一 resourceVersion 不新增记录；更新成员后旧成员变为 `INACTIVE`；删除事件将 Team 标记为 `DELETED`。
- [x] **步骤 2：运行红灯。** 先以缺少同步器和 Repository 快照方法失败，随后完成实现并转绿。
- [x] **步骤 3：实现事务同步。** 为 `TeamRepository` 增加带锁查询、upsert、`replaceActiveMembers`、`markDeleted`；使用稳定 UUID 和 `TeamCrdSnapshot` 一次事务写入，成员 UUID 必须先通过 `AgentRepository.findById` 解析，任何成员缺失都回滚。
- [x] **步骤 4：运行绿灯。** 同步测试验证幂等、成员失活和删除标记全部通过。
- [x] **步骤 5：提交。** 已提交 `feat(团队调度): 同步 Team CRD 到 PostgreSQL`。

### 任务 3：接入 Kubernetes informer 与 Spring 生命周期

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamResourceSource.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/team/KubernetesTeamResourceSource.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/KubernetesTeamResourceSourceTest.java`

- [x] **步骤 1：编写失败测试。** 用 Fabric8 mock/informer 测试验证 add/update/delete 分发和关闭行为；`teamSync.enabled=false` 时不创建 Kubernetes Client 的条件配置由 Helm/配置测试覆盖。
- [x] **步骤 2：运行红灯。** 先以 source 和配置 Bean 不存在失败，随后完成实现并转绿。
- [x] **步骤 3：实现 source。** 使用 `ResourceDefinitionContext(group=agentteams.io, version=v1alpha1, plural=teams, namespaced=true)` 创建 GenericKubernetesResource informer；namespace 使用 `agentteams.team-sync.namespace`，空值表示所有 namespace；异常只记录 namespace/name/resourceVersion 和固定错误类别。
- [x] **步骤 4：运行绿灯。** informer 测试验证事件分发和关闭行为通过。
- [x] **步骤 5：提交。** 已提交 `feat(团队调度): 接入 Team CRD informer`。

### 任务 4：将 Team policy 接入分配事务

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/TeamRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/persistence/AgentRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/service/TaskAssignmentService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamSchedulingPolicy.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/service/TaskAssignmentServiceTest.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamSchedulingPolicyTest.java`

- [x] **步骤 1：编写失败测试。** 增加 Team 任务测试：超过 `maxConcurrentTasks` 时保持 `QUEUED`；不允许 runtime、缺少 capability、未审批和非 READY Agent 时保持 `QUEUED`；满足约束时写入 `team_task_assignments`；无 `teamId` 任务保持既有行为。
- [x] **步骤 2：运行红灯。** 先以现有实现忽略 Team policy 失败，随后完成事务接入并转绿。
- [x] **步骤 3：实现最小事务修改。** 解析任务 `teamId` 和 `approvalGranted`；Team 任务先执行 `SELECT id FROM teams WHERE id = ? FOR UPDATE` 锁定 Team，再读取 policy/active members，调用固定拒绝原因的 `TeamSchedulingPolicy`，最后插入 task assignment、lease 和 team assignment；没有候选者抛出现有 `IllegalStateException` 以保持 QUEUED。
- [x] **步骤 4：运行绿灯。** 服务测试验证并发、runtime、能力、审批和兼容路径全部通过。
- [x] **步骤 5：提交。** 已提交 `feat(调度): 执行 Team policy 与并发约束`。

### 任务 5：扩展 CRD schema、RBAC 和网络配置

**文件：**
- 修改：`operator/src/main/java/io/agentteams/operator/TeamPolicy.java`
- 修改：`operator/src/main/java/io/agentteams/operator/TeamSpec.java`
- 修改：`operator/src/main/java/io/agentteams/operator/TeamResourceFactory.java`
- 修改：`deploy/helm/agentteams-java/crds/teams.yaml`
- 修改：`deploy/helm/agentteams-java/values.yaml`
- 修改：`deploy/helm/agentteams-java/templates/control-plane.yaml`
- 修改：`deploy/helm/agentteams-java/templates/rbac.yaml`
- 修改：`deploy/helm/agentteams-java/templates/networkpolicy.yaml`
- 测试：`operator/src/test/java/io/agentteams/operator/TeamResourceFactoryTest.java`
- 修改：`scripts/validate-kind-manifests.py`、`scripts/validate-kind-infra.py`

- [x] **步骤 1：编写失败检查。** 扩展 Operator 测试和静态验证，要求 CRD 暴露 `allowedRuntimes/requiredCapabilities`，Control Plane token 只在 sync 开启时挂载，RBAC 对 Team 只有 `get/list/watch`，NetworkPolicy 允许 Kubernetes API 443。
- [x] **步骤 2：运行红灯。** 先以缺少新 policy 字段和 Team sync RBAC 失败，随后完成模板和 validator 并转绿。
- [x] **步骤 3：实现 Helm 与模型变更。** 扩展 TeamPolicy Java record、CRD schema、TeamResourceFactory JSON；增加 `controlPlane.teamSync.enabled/namespace` 配置、独立只读 ClusterRole/Binding、条件 token 挂载和 API 出站规则。
- [x] **步骤 4：运行绿灯。** Operator 测试、manifest validator、`helm lint` 和 `helm template` 全部通过。
- [x] **步骤 5：提交。** 已提交 `feat(部署): 配置 Team sync 权限与 CRD policy`。

### 任务 6：Kind 端到端验证与文档收口

**文件：**
- 创建：`scripts/smoke-kind-team-scheduling.sh`
- 修改：`README.md`
- 修改：`docs/superpowers/plans/2026-08-18-kind-vertical-slice.md`
- 测试：`integration-tests/src/test/java/io/agentteams/it/TeamSchedulingInfrastructureIT.java`

- [x] **步骤 1：编写失败验收。** 增加集成测试创建 Team/Agent/Task 数据，验证一个 active assignment 上限和后续任务保持 `QUEUED`；成员替换/删除由同步器集成测试覆盖。
- [x] **步骤 2：运行红灯。** 集成测试首次运行暴露测试模块缺少 AssertJ 依赖，已改用现有 JUnit 5 断言后转绿；业务验收由真实 Spring/PostgreSQL 测试覆盖。
- [x] **步骤 3：实现 Kind smoke。** 脚本应用包含两个 Agent UUID 的 Team CR，创建 3 个带同一 `teamId` 的任务，验证一个进入 `ASSIGNED`、两个保持 `QUEUED`，不打印完整 CRD 或凭据。
- [x] **步骤 4：运行绿灯。** 集成测试、脚本语法检查、Kind/Helm 校验已通过；已准备第二个真实 QwenPaw Worker，修复 Kind Kubernetes API 出站 NetworkPolicy 和旧 CRD 自动应用顺序，并完成真实 Kind smoke：`TEAM_SCHEDULING_OK assigned=1 queued=2`。
- [x] **步骤 5：更新文档并提交。** README 已记录最短命令和脱敏成功标记，旧垂直切片计划已校准；本任务提交为 `test(集成): 验证 Team CRD 多 Agent 调度`。

### 任务 7：完整回归与交付

- [x] **步骤 1：运行单元和集成回归。** `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 clean test` 和 `TaskPushInfrastructureIT`、`TeamSchedulingInfrastructureIT` 均通过；集成测试命令对聚合模块使用 `-Dsurefire.failIfNoSpecifiedTests=false`。
- [x] **步骤 2：运行静态与安全检查。** 全部 `bash -n`、Python validator、Helm lint/template、`git diff --check` 通过；`git ls-files apikey` 无输出，变更中未发现凭据。
- [x] **步骤 3：检查工作树并提交。** 仅提交本功能文件，外部未跟踪文件不纳入提交。
- [x] **步骤 4：推送功能分支。** 已推送 `codex/team-crd-scheduling`。
