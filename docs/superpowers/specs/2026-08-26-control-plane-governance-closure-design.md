# AgentTeams Java 控制面治理闭环设计

**日期：** 2026-08-26
**状态：** Team Revision/Effective Config 已随批次 A 完成；批次 B 已完成 Worker 运维、统一 RBAC、成员生命周期和 External Secrets 第一纵切，异步消费者接线与真实平台验收待后续
**优先级：** P0/P1
**代码基线：** `93d99fb`

**批次 B 增量（2026-08-27）：** 已实现可恢复 Worker Operation 与双事实 rollout 确认、ResourceAction/scope 授权矩阵第一纵切、项目邀请幂等和成员状态/Owner CAS、ExternalSecret Ready 与目标 Secret metadata 只读解析。当前剩余 Manager/异步消费者统一授权接线、成员状态脱敏审计以及真实 Kubernetes controller 收敛验收。

## 1. 目标

本规格把 Team、Worker、权限和凭据治理从“单资源 API 可用”推进为“可版本化发布、可回滚、可审计、可证明租户隔离”的控制面闭环。

具体目标：

1. Team 成为包含 Leader、成员、Model、文件、Skill、MCP 和策略的版本化发布单元；
2. Agent Base、Team Overlay、Task Overlay 合成为不可变 Effective Config；
3. Worker 的 drain、terminate、升级和配置回滚形成统一运维状态机；
4. 所有公共资源在 Service 层执行统一 RBAC 和 scope 校验；
5. 项目成员具备邀请、启用、禁用、角色变更和 Owner 转移生命周期；
6. External Secrets 等平台能够验证凭据引用已就绪，但 Secret 明文仍不进入 Control Plane 领域模型。

## 2. 当前能力与缺口

已有：

- Team CRUD、成员、Policy、Team CRD 同步、Leader 声明和调度约束；
- AgentSpec、Model/Skill/MCP 引用校验、配置修订、Agent 绑定、ACK、失败重试和回滚；
- Agent `drain/terminate` API、乐观锁和活动 Lease 保护；
- OIDC、权限枚举、项目角色、`resource_scopes` 和部分 Service 级可见性校验；
- `credentialRef` 校验、Kubernetes Secret 状态解析和 External Secrets Adapter 边界。

当前缺口：

- Agent 下线尚未自动联动持久化 Worker rollout、配置版本和活动 Assignment 排空；
- `ApiAuthorizationPolicy` 尚未由统一的 Service 层 Action 矩阵完全收敛，异步消费者和 Manager Tool 仍需复用同一授权边界；
- 项目成员尚缺邀请、重新启用、角色变更、Owner 转移和完整审计生命周期；
- `ExternalSecretsSecretResolver` 对合法引用仍固定返回 `UNAVAILABLE`，尚未读取 ExternalSecret Ready 与目标 Secret metadata。

## 3. Team Revision

### 3.1 状态机

```text
DRAFT -> REVIEWING -> PUBLISHED -> SUPERSEDED
   |         |             |
   +------> REJECTED       +--> ROLLED_BACK
```

`PUBLISHED` revision 不可修改。修改 Team 配置必须基于当前 revision 创建新 Draft。Rollback 不修改旧 revision，而是创建一个内容与目标稳定 revision 相同的新 revision，并记录 `rollbackOfRevision`。

### 3.2 领域类型

```java
public record TeamRevision(
        UUID teamId,
        long revision,
        UUID leaderAgentId,
        String overlayJson,
        String digest,
        TeamRevisionStatus status,
        Long rollbackOfRevision,
        String createdBy,
        Instant createdAt,
        long version) {}

public record TeamResourceBinding(
        UUID teamId,
        long teamRevision,
        ResourceBindingType type,
        String resourceId,
        long resourceRevision,
        String digest) {}
```

`ResourceBindingType` 固定为 `MODEL`、`FILE`、`SKILL`、`MCP_SERVER`。Secret 只作为已绑定资源内部的 `credentialRef`，不在 Team Revision 中复制 Secret 引用或明文。

### 3.3 数据库

新增：

- `team_revisions`：`(team_id, revision)` 唯一，保存 Leader、overlay、digest、状态和 rollback 来源；
- `team_revision_members`：Team revision 下的 Agent、角色和成员序号；
- `team_resource_bindings`：资源类型、ID、资源 revision 和 digest；
- `team_deployments`：Team revision 的部署状态、目标成员数、成功数、失败数和版本；
- `team_deployment_members`：每个 Agent 的 config binding、期望 revision、观察 revision、状态和失败分类。

索引：

- 每个 Team 最多一个 `PUBLISHED` 当前 revision，由 `teams.current_revision` 指向；
- `(team_id, agent_id, status)` 支持成员可见性和部署查询；
- `(deployment_id, agent_id)` 唯一，重试不能创建第二条成员部署；
- 所有表包含 tenant/project scope 或通过 Team 外键连接后由查询强制 scope 过滤。

### 3.4 API

```text
POST /api/v1/teams/{teamId}/revisions
GET  /api/v1/teams/{teamId}/revisions
GET  /api/v1/teams/{teamId}/revisions/{revision}
POST /api/v1/teams/{teamId}/revisions/{revision}/review
POST /api/v1/teams/{teamId}/revisions/{revision}/publish
POST /api/v1/teams/{teamId}/revisions/{revision}/deployments
GET  /api/v1/teams/{teamId}/deployments/{deploymentId}
POST /api/v1/teams/{teamId}/deployments/{deploymentId}/retry
POST /api/v1/teams/{teamId}/rollback
```

写接口要求 `Idempotency-Key`；状态转换要求 `expectedVersion`。发布前必须重新校验 Leader、成员、Model、文件、Skill 和 MCP 的存在、状态、revision、digest、scope 和权限。

## 4. Effective Config 合成

### 4.1 优先级

```text
Agent Base Config
    + Team Overlay
    + Task Overlay
    = Effective ConfigSnapshot
```

覆盖规则：

- 标量：后层覆盖前层；
- Map：按 key 深度合并；
- Skill/MCP 引用数组：按稳定资源 ID 去重，Task 可禁用但不能替换为不可见资源；
- capabilities 和权限：只能收紧，不能通过 Team/Task Overlay 扩权；
- 资源 limits：Task 可降低，提升必须通过项目策略显式批准；
- Secret：只合成 `credentialRef` 元数据，不解析或复制明文；
- Sandbox profile：取安全级别最高值，`NONE < ISOLATED < HARDENED`。

### 4.2 服务边界

```java
public interface EffectiveConfigComposer {
    EffectiveConfig compose(EffectiveConfigRequest request);
}

public record EffectiveConfigRequest(
        UUID agentId,
        UUID teamId,
        long teamRevision,
        UUID taskId,
        String baseManifest,
        String teamOverlay,
        String taskOverlay) {}

public record EffectiveConfig(
        String canonicalManifest,
        String sha256,
        ConfigProvenance provenance) {}
```

合成结果继续通过现有 `ConfigSnapshotService` 创建不可变 Snapshot，并通过 `ConfigDeploymentService` 绑定 Agent。不得建立第二套配置下发协议。

`ConfigProvenance` 保存 Agent Base Snapshot ID、Team Revision、Task ID、资源 binding digest 和 Composer schema version。相同输入必须生成相同 canonical JSON 和 digest。

### 4.3 Team 部署

发布 Team revision 不自动影响运行中任务。显式创建 Deployment 后：

1. 固化目标成员集合；
2. 为每个成员合成 Effective Config；
3. 创建 ConfigSnapshot 和 binding；
4. 通过现有 Outbox/Gateway 下发；
5. 聚合 Worker ACK；
6. 全部成功为 `SUCCEEDED`，部分失败为 `PARTIAL_FAILURE`；
7. retry 只重试失败成员；rollback 创建新 Team revision 并走同一部署路径。

成员在 Deployment 创建后被移除，不改变该 Deployment 的审计目标，但后续 Deployment 不再包含该成员。

## 5. Leader 与成员生命周期

### 5.1 Leader

- 每个 ACTIVE Team 必须且只能有一个 ACTIVE Leader；
- Leader 必须是当前 revision 的 ACTIVE 成员；
- 移除 Leader 前必须在同一命令中指定替代者；
- Leader 变化创建新 Team revision，不直接覆盖已发布 revision；
- Leader 不获得超出其项目角色的控制面权限，Leader 是调度/协作角色，不是 RBAC 角色。

### 5.2 Team 成员

Team 成员状态固定为：

```text
JOINING -> ACTIVE -> DRAINING -> INACTIVE -> REMOVED
                    |              |
                    +-----------> FAILED
```

成员变更必须通过创建新 Team revision 实现，不能直接改写当前稳定 revision。
现有 `TeamMembershipChangePolicy` 必须接入创建 revision、drain 和任务处置的同一事务，
不能只作为独立校验函数存在。移除策略固定为：

| 策略 | 活跃 Attempt 处理 | 新任务处理 |
|---|---|---|
| `KEEP_ACTIVE` | 允许当前 Attempt 完成后退出，默认策略 | `DRAINING` 后立即停止分配 |
| `REQUEUE` | fence 当前 Attempt、释放租约并创建新 Attempt | 不再分配 |
| `CANCEL` | 取消任务并终止 Attempt | 不再分配 |

约束：

- 新 Leader 在目标 revision 配置 ACK 成功前保持 `JOINING`，旧 Leader 继续服务；
- rollout 达到稳定状态时，Leader 和成员集合在一个事务中切换；
- `DRAINING` 成员不接受新任务，迟到事件必须通过 Attempt ID、Lease ID 和 version fencing 拒绝；
- 成员只有在活动 Lease、Assignment 和 Attempt 均为 0 后进入 `INACTIVE`；
- `REMOVED` 只表示不再属于后续 revision，历史 revision、审计和任务引用不得删除。

### 5.3 项目成员

状态：

```text
INVITED -> ACTIVE -> DISABLED
    |         |
    +-> EXPIRED
              +-> ACTIVE（重新启用）
```

新增 API：

```text
POST /api/v1/projects/{projectId}/invitations
POST /api/v1/projects/{projectId}/invitations/{invitationId}/accept
PUT  /api/v1/projects/{projectId}/members/{subject}/role
POST /api/v1/projects/{projectId}/members/{subject}/enable
POST /api/v1/projects/{projectId}/members/{subject}/disable
POST /api/v1/projects/{projectId}/ownership-transfer
```

邀请 Token 只存 hash 和过期时间。Owner 转移要求当前 Owner 发起、目标成员 ACTIVE、幂等键和 expected project version。项目始终至少一个 Owner，不能禁用或删除最后一个 Owner。

## 6. Worker 运维状态机

现有 Agent phase 继续使用，但增加持久化运维操作：

```text
READY/BUSY -> DRAINING -> DRAINED -> UPDATING -> READY
                               |          |
                               |          +-> UPDATE_FAILED -> ROLLBACK -> READY
                               +-> TERMINATED
```

如果不扩展领域枚举，`DRAINED/UPDATING/UPDATE_FAILED/ROLLBACK` 保存到独立 `worker_operations`，Agent phase 在没有活动 Lease 后保持 `DRAINING`，成功 rollout 后重新 `READY`。

新增：

```java
public interface WorkerOperationService {
    WorkerOperation drain(UUID agentId, long expectedAgentVersion, String idempotencyKey);
    WorkerOperation rollout(UUID agentId, WorkerRolloutRequest request);
    WorkerOperation rollback(UUID operationId, long expectedVersion);
    WorkerOperation terminate(UUID agentId, long expectedAgentVersion, String idempotencyKey);
}
```

规则：

- DRAINING 后 Scheduler 不再分配新任务；
- 活动 Lease 归零后 Operation 进入 `DRAINED`；
- rollout 只修改 Worker CR 的镜像 digest、runtime/config revision 或 Secret generation；
- Operator status 和 Gateway Hello 同时确认新版本后才成功；
- 超时或健康失败自动回滚到上一稳定 Worker spec；
- terminate 仍要求 DRAINING 且没有活动 Lease；
- 重启后的 Operation Worker 从数据库继续，不依赖内存 Future。

## 7. 统一 RBAC

### 7.1 Service 层授权

HTTP Filter 只完成身份认证和粗粒度入口检查。所有业务 Service 在读取或修改资源前调用：

```java
public interface ResourceAuthorizationService {
    void require(ResourceAction action, ResourceRef resource, Principal principal);
}
```

`ResourceAction` 使用稳定枚举：

- `PROJECT_MEMBER_MANAGE`、`PROJECT_OWNER_TRANSFER`；
- `AGENT_READ/WRITE/OPERATE`；
- `TEAM_READ/WRITE/PUBLISH/OPERATE`；
- `AGENT_SPEC_READ/WRITE/PUBLISH/DEPLOY`；
- `MODEL_READ/WRITE/TEST`；
- `SKILL_READ/WRITE/REVIEW/PUBLISH`；
- `MCP_READ/WRITE/EXECUTE`；
- `TASK_READ/CREATE/OPERATE/APPROVE`；
- `ARTIFACT_READ/WRITE/DELETE`；
- `USAGE_READ/BUDGET_WRITE`；
- `AUDIT_READ`。

角色到 Action 的映射由代码中的不可变矩阵和测试固定，不能由客户端提供。HTTP、Matrix、Manager Tool 和异步消费者必须复用同一 Service 层授权。

### 7.2 Scope 迁移

为历史资源执行可重入迁移：

- 能从直接父资源确定 scope 的记录自动回填；
- 无法确定 scope 的记录标为 `QUARANTINED`，默认不可见；
- 迁移输出计数和资源 ID hash，不输出资源内容；
- 数据库约束分两阶段启用：先回填和审计，再把生产写路径改为 NOT NULL；
- 跨项目读取、写入、事件消费和聚合查询都有负向集成测试。

## 8. External Secrets 状态解析

`SecretResolver` 当前只返回引用状态，不返回 Secret 明文；这一安全边界保持不变。

`ExternalSecretsSecretResolver` 应读取 External Secrets Operator 的 `ExternalSecret` Ready Condition 和目标 Kubernetes Secret 的 metadata/key 存在性：

```java
public final class ExternalSecretsSecretResolver implements SecretResolver {
    private final ExternalSecretStatusReader externalSecrets;
    private final KubernetesSecretMetadataReader targetSecrets;
    private final SecretResolverProperties properties;
}
```

返回规则：

- 引用非法：`INVALID_REFERENCE`；
- ExternalSecret 不存在或目标 key 不存在：`MISSING`；
- Condition 非 Ready、目标 Secret generation 落后或 API 不可用：`UNAVAILABLE`；
- Ready 且目标 Secret/key metadata 存在：`RESOLVED`。

Reader 不读取 Secret value。Manager、Worker 和 MCP Adapter 通过 Deployment/Worker CR 挂载 External Secrets 生成的目标 Secret。需要实际发起鉴权请求的进程从限定环境变量或只读文件读取值，并在内存中原子轮换。

Control Plane 只获得指定 namespace 下 `externalsecrets/status` 和 Secret metadata 的最小读取权限；不授予 list 所有 Secret 或读取无关 namespace。

## 9. 审计与错误

所有 Team Revision、成员、Worker Operation、RBAC 拒绝和 Secret 状态变化产生审计：actor、action、resource、before/after version、result、correlation ID 和 scope。审计不保存完整 Config、邀请 Token、Secret 引用中的 key 名或外部状态正文。

稳定错误码：

- `TEAM_REVISION_CONFLICT`、`TEAM_REFERENCE_INVALID`、`TEAM_LEADER_INVALID`；
- `EFFECTIVE_CONFIG_CONFLICT`、`EFFECTIVE_CONFIG_ESCALATION_REJECTED`；
- `WORKER_NOT_DRAINED`、`WORKER_HAS_ACTIVE_TASKS`、`WORKER_ROLLOUT_FAILED`；
- `MEMBERSHIP_LAST_OWNER`、`INVITATION_EXPIRED`；
- `RESOURCE_SCOPE_MISMATCH`、`AUTHORIZATION_REJECTED`；
- `CREDENTIAL_REFERENCE_NOT_READY`、`SECRET_BACKEND_UNAVAILABLE`。

## 10. 核心实现文件

预计新增：

- `control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/team/TeamDeploymentService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/config/EffectiveConfigComposer.java`
- `control-plane/src/main/java/io/agentteams/controlplane/worker/WorkerOperationService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ResourceAuthorizationService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/project/ProjectInvitationService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ExternalSecretStatusReader.java`
- 对应 Repository、Controller、Flyway migration 和测试。

预计修改：

- `control-plane/src/main/java/io/agentteams/controlplane/api/TeamController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/project/ProjectController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthorizationPolicy.java`
- `control-plane/src/main/java/io/agentteams/controlplane/security/ExternalSecretsSecretResolver.java`
- `control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecDeploymentService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/config/ConfigDeploymentService.java`
- Operator Worker CRD/Reconciler 和 Helm RBAC/配置。

## 11. 验收标准

### L1/L2

- 相同输入合成相同 Effective Config digest；数组去重、权限收紧和 Sandbox profile 提升规则稳定；
- 已发布 Team revision 不可修改，rollback 创建新 revision；
- 重复 Team Deployment 不产生重复 Config binding；
- 移除 Leader 没有替代者时失败；最后一个 Owner 不能禁用；
- Worker drain 期间不再获得新 Assignment，活动 Lease 归零后才能 rollout/terminate；
- 所有资源/角色/Action 组合有参数化权限测试；
- ExternalSecret Ready/Not Ready、目标缺失、API 故障和 generation 落后分类正确，测试证明未读取 Secret value。

### L3/L4

- Helm 为 Control Plane 渲染最小 ExternalSecret 状态和 TaskSandbox CR 权限，不渲染 Secret value；
- Kind 中发布 Team revision 后，两个成员收到同一 Team revision、各自独立 Config binding 和 ACK；
- 单成员失败得到 `PARTIAL_FAILURE`，retry 只处理失败成员；
- rollout 期间提交的新任务不会分配给 DRAINING Worker，回滚后 Worker 恢复 READY；
- OIDC 角色矩阵和跨项目负向请求全部通过；
- External Secrets 测试控制器更新目标 Secret 后，状态从 UNAVAILABLE 收敛为 RESOLVED。

### L6

- 真实 External Secrets/Vault 后端轮换目标 Secret，Worker/Manager 原子重连且失败时保留旧连接；
- Team 版本发布、单成员失败、重试和回滚在多副本 Control Plane 下无重复副作用；
- 历史 scope 迁移报告没有未处理资源；QUARANTINED 资源默认不可见；
- Worker 镜像 digest 跨版本 rollout 和自动回滚完整演练通过。
