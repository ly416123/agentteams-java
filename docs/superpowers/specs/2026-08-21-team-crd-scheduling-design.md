# Team CRD 与多 Agent 调度设计

## 目标

将现有 Team CRD 从「仅投影 ConfigMap」推进为可驱动运行时调度的期望状态来源：Control Plane 直接读取 Kubernetes 中的 Team 资源，将团队、成员和策略原子同步到 PostgreSQL；任务调度器据此执行成员资格、能力、运行时、审批和并发限制。

本阶段不实现 Team Leader 工作流、Matrix AppService、OIDC/mTLS 或生产级多集群容灾。现有单 Agent 任务路径保持兼容：没有 `teamId` 的任务继续使用全局 READY Agent 匹配逻辑。

## 现状与问题

- Operator 已注册 `agentteams.io/v1alpha1` 的 Team CRD，并将 Team spec 序列化到 ConfigMap。
- Control Plane 已有 `teams`、`team_memberships`、`team_policies`、`team_tasks` 和 `team_task_assignments` 表，也已有部分 Team 领域类型。
- `TaskAssignmentService` 已识别任务 spec 中的 `teamId`，但没有完整执行 Team policy；`TeamService.canAssign` 也没有接入实际分配事务。
- Control Plane 当前没有 Kubernetes API 依赖和读取 Team CRD 的同步器，CRD 与 PostgreSQL 之间没有运行时数据链路。
- Control Plane Pod 禁用了 ServiceAccount token，现有 Helm RBAC 只授予 Operator 读取 Team CRD 的权限。

## 方案

### 1. CRD 读取与同步边界

Control Plane 使用 Fabric8 Kubernetes Client 的动态资源 API，监听命名空间范围内的 `agentteams.io/v1alpha1`、plural `teams` 资源。同步器只读取 Team CRD，不读取 ConfigMap，也不向数据库写入 Kubernetes 凭据。

同步器行为如下：

1. 启动时执行一次全量 list，建立当前 Team 快照。
2. 通过 informer 接收 add、update、delete 事件；事件处理失败时保留数据库现状并记录脱敏错误，后续由 informer 重放或重新 list 修复。
3. 对每个 add/update，在一个 PostgreSQL 事务中完成 Team、policy 和 memberships 的 upsert；只有全部成员引用解析成功时才提交。
4. 对 delete，不物理删除历史数据，而是将 Team 标记为 `DELETED`，将其 active memberships 标记为 `INACTIVE`，使新任务不再被分配到该 Team。
5. 同一个资源的重复事件必须幂等；通过 `metadata.resourceVersion` 过滤旧事件，但不能依赖事件顺序保证最终一致性。

### 2. Team 身份与成员引用

Team CRD 的 Kubernetes 身份由 `namespace/name` 唯一确定。Control Plane 使用以下稳定 UUID 作为数据库 `teams.id`：

```text
UUID.nameUUIDFromBytes("agentteams.io/v1alpha1/" + namespace + "/" + name)
```

数据库 `teams.name` 保存规范化名称 `namespace/name`，`display_name` 保存 Kubernetes `metadata.name`。这样重启或重复事件不会产生重复 Team。

`spec.members[].agentRef` 必须是 Agent UUID 的字符串，并解析为现有 `agents.id`。不存在、格式非法或重复的成员引用都会使本次同步失败，不产生部分成员状态。成员的 `role` 持久化到 `team_memberships.role`；CRD 中的成员 `capabilities` 仅作为声明信息保留在同步审计日志中，实际调度能力以 Agent 注册的 `capabilities` JSON 为准，避免两份能力清单漂移。

### 3. Team policy

Team policy 扩展为以下字段：

```yaml
policy:
  maxConcurrentTasks: 4
  requireApproval: false
  allowedRuntimes: [qwenpaw]
  requiredCapabilities: [python]
```

缺省值为 `maxConcurrentTasks=1`、`requireApproval=false`、其余为空数组。Control Plane 将字段映射到现有 `TeamPolicyRecord`。`maxConcurrentTasks` 必须大于 0；数组元素必须非空且去重后保存。

### 4. 任务分配规则

任务 spec 使用以下字段参与 Team 调度：

```json
{
  "teamId": "<stable-team-uuid>",
  "requiredCapabilities": ["gpu"],
  "approvalGranted": true
}
```

Team 任务的候选 Agent 必须同时满足：

- Team 状态为 `ACTIVE`；
- membership 状态为 `ACTIVE`；
- Agent phase 为 `READY`；
- Agent runtime 在 `allowedRuntimes` 中，或允许运行时为空；
- Agent capabilities 包含 Team policy 和任务 spec 合并后的全部能力；
- Team 当前未释放的 `ASSIGNED`/`RUNNING` assignment 数量小于 `maxConcurrentTasks`；
- `requireApproval=true` 时，任务必须提供 `approvalGranted=true`。

分配事务先锁定 Team 行，再锁定候选 Agent，读取并发计数，写入任务 attempt、assignment、lease 和 `team_task_assignments`。Team 行锁保证多个 Control Plane 副本不会突破同一个 Team 的并发上限。候选 Agent 按 UUID 升序确定性选择，保持现有调度结果可复现。

如果没有合格成员，任务保持 `QUEUED`，调度器记录脱敏原因并在下一轮重试；不把资源暂时不可用误报为任务失败。

### 5. Kubernetes 权限与 Helm

Control Plane 使用独立的只读 Team 同步权限：

- `teams`: `get`、`list`、`watch`；
- 不授予 Control Plane 修改 Worker、Deployment、Service 或 Secret 的权限；
- Control Plane Pod 仅在 `teamSync.enabled=true` 时挂载 ServiceAccount token。

Kind values 默认启用 Team sync，并在 Control Plane Deployment 中注入 Kubernetes API 配置。非 Kubernetes 或单元测试环境默认关闭同步器，不要求本机 Docker socket。

NetworkPolicy 增加 Control Plane 到 Kubernetes API Service 的 HTTPS 出站许可；原有数据库、NATS 和 MinIO 规则保持不变。

### 6. 错误处理与可观测性

- CRD 解析、UUID、成员不存在和 policy 校验错误只输出 Team namespace/name、resourceVersion 和错误类别，不输出完整 spec 或凭据。
- 同步失败不覆盖最后一次成功的数据库快照。
- 为同步成功、同步失败、删除和跳过旧事件增加计数指标；日志包含 correlation/resourceVersion，便于从 Kubernetes 事件追踪到数据库。
- 调度拒绝原因继续使用固定枚举：`TEAM_INACTIVE`、`MEMBER_INACTIVE`、`AGENT_NOT_READY`、`TEAM_CONCURRENCY_LIMIT`、`APPROVAL_REQUIRED`、`RUNTIME_NOT_ALLOWED`、`CAPABILITY_MISMATCH` 和 `NO_ELIGIBLE_MEMBER`。

## 测试与验收

1. Team CRD 解析测试覆盖稳定 UUID、默认 policy、非法成员 UUID、重复成员和数组去重。
2. Kubernetes mock 测试验证 add/update/delete 事件、重复事件幂等和同步失败保留旧快照。
3. PostgreSQL 集成测试验证 Team upsert、成员失活、policy 更新、Team 行锁和历史 assignment 保留。
4. 调度测试验证并发上限、runtime、能力、审批、非 READY Agent 和无 Team 任务兼容路径。
5. Helm 渲染与静态检查验证 ServiceAccount token、只读 RBAC、NetworkPolicy、CRD schema 和默认配置。
6. Kind 冒烟创建两个 Worker 和一个 Team CR，创建超过并发上限的多个 Team 任务，确认只有 policy 允许数量进入 assignment，其余保持 `QUEUED`；释放任务后下一任务可被调度。
7. 全量 `mvn test`、`TaskPushInfrastructureIT`、Kind/Helm 校验和 `git diff --check` 必须通过。

## 非目标

- 不通过 Operator 直接访问 PostgreSQL。
- 不把 ConfigMap 作为 Team 运行时事实来源。
- 不在本阶段实现 Team Leader 选举、跨 Team DAG、Matrix 命令或 OIDC/mTLS。
- 不修改无 `teamId` 任务的现有全局 Agent 调度语义。
