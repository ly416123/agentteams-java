# AgentTeams Java 可观测与规模化闭环设计

**日期：** 2026-08-26
**状态：** 批次 C 配额压力验证已完成仓库侧短压测；资源级 ACK 与 revision 栅栏已进入仓库实现；L6 长压测待受控环境
**优先级：** P1
**依赖：** 运行时生产闭环、Team Revision、生产 Secret 和网络契约

**当前进度（2026-08-28）：** 已增加确定性配额并发压测脚本和 CI 短压测入口，覆盖 acquire/release 幂等、拒绝/超时分类、最大观察并发和 reservation 泄漏汇总。脚本只输出固定 JSON 字段，不输出请求、响应、scope claim 或凭据。4 客户端/200 请求是 L4 短压测；50 客户端/30 分钟并穿插 Gateway、Control Plane 重启仍需 L6 受控环境真实报告。

**资源 ACK 进度（2026-08-28）：** `ConfigApplied` 已增加资源级结构化结果，Gateway/NATS/Control Plane 完成透传，Control Plane 以 V50 表持久化并按 binding/snapshot/config version 栅栏拒绝旧结果。Worker 已完成绑定字段校验、Skill 下载/digest 校验和配置应用流程报告；MCP 工具发现及跨实例聚合仍待后续运行时 Port 接入。

**Skill Loader 进度（2026-08-28）：** Worker 已支持 manifest 显式提供 `artifactRef` 和 `sizeBytes` 时的 HTTP(S) Skill 包下载、大小/SHA-256 校验和原子落盘；Control Plane 已为已发布且上传完成的 Skill 版本生成 15 分钟短期预签名 `artifactRef`，并随 AgentSpec manifest 传递包大小/SHA-256；旧 manifest 保持兼容。Skill 包解压、扫描复核和真实运行时注册仍待后续任务。

## 1. 目标

本规格把已经存在的 Registry、Usage、Dashboard、Quota、Operator 和告警基础推进为多副本、长时间运行条件下可验证的生产能力。重点不是增加更多指标，而是保证业务维度完整、状态跨实例一致、告警能够送达、配额不会超发，并为运行时绑定提供可恢复事实。

## 2. 范围

包含：

- Skill 和 MCP 从 AgentSpec/Team Revision 到 Worker 的运行时生效；
- MCP 跨实例工具发现状态、健康状态和集中告警；
- 使用量维度完整性、预算政策、成本预测和告警；
- 配额跨实例压力、超时和长期运行验证；
- Operator Reconciler 行为测试、HPA、拓扑分散和工作负载安全基线；
- Gateway 关键依赖健康、Alertmanager 路由和 SLO；
- Matrix/mTLS 的生产恢复与轮换验收。

不包含：Web Console、最终财务账单、云资源购买和多区域复制。

## 3. Skill 与 MCP 运行时绑定

### 3.1 生效模型

Worker 不直接查询 Control Plane Registry。发布 AgentSpec 或 Team Revision 时，Control Plane 解析可见且已发布的引用，生成不可变 `resourceBindings`：

```json
{
  "skills": [{
    "id":"62d46258-61da-4ea5-a033-4de66c218523",
    "version":"1.2.0",
    "digest":"sha256:8d9f086b4b5be1f8e7491c2d371ac48b378837c194d29fe21692bdec45950df8",
    "artifactRef":"artifact://skills/62d46258-61da-4ea5-a033-4de66c218523/1.2.0"
  }],
  "mcpServers": [{
    "id":"2d85e034-1486-4df0-b4b9-6d8e622ace61",
    "revision":7,
    "transport":"STREAMABLE_HTTP",
    "policyDigest":"sha256:6375374e1cf77d5f677622248ae90d278edcd78b17e20e185489da702d98ac37"
  }]
}
```

配置消息不包含 Skill 包内容、MCP 密钥或认证 Header。Worker 根据预签名地址下载 Skill，校验大小和 digest，MCP 凭证由运行环境按 `credentialRef` 注入短期授权。

### 3.2 Worker 状态

在现有配置 ACK 中扩展资源结果：

```java
public record ResourceApplyResult(
        String type,
        String resourceId,
        long revision,
        String digest,
        ApplyStatus status,
        String failureCategory) {}
```

`ApplyStatus` 仅允许 `APPLIED`、`REJECTED`、`FAILED`。失败分类固定为 `NOT_VISIBLE`、`NOT_PUBLISHED`、`DIGEST_MISMATCH`、`DOWNLOAD_FAILED`、`AUTH_UNAVAILABLE`、`POLICY_REJECTED`、`RUNTIME_UNSUPPORTED`。供应商错误只进入脱敏诊断，不进入分类字段。

### 3.3 持久化

新增 `runtime_resource_apply_records`：

- 唯一键：`(binding_id, resource_type, resource_id, revision)`；
- 字段：期望 digest、观察 digest、状态、失败分类、Worker 观察时间；
- 所有更新携带 binding revision，旧 revision 不得覆盖新结果。

## 4. MCP 跨实例状态

### 4.1 状态投影

新增 `mcp_discovery_snapshots`：

- 主键：`(server_id, server_revision, instance_id)`；
- 保存工具列表 digest、健康状态、错误分类、探测时间和过期时间；
- 不保存认证信息和完整工具响应。

`McpDiscoveryAggregationService` 按 server revision 聚合：只要至少一个未过期实例健康即为 `AVAILABLE`；全部明确失败为 `UNAVAILABLE`；没有新鲜观测为 `UNKNOWN`。聚合结果用于 Dashboard 和告警，不改变 Registry 的期望启用状态。

### 4.2 接口

```java
public interface McpDiscoveryObservationPort {
    void record(McpDiscoveryObservation observation);
    McpDiscoveryAggregate aggregate(UUID serverId, long revision, Instant now);
}
```

探测和工具调用继续经过现有出站策略、限流、熔断和审计。工具名可进入低基数业务维度；URL、异常正文和参数不得成为 Prometheus 标签。

## 5. 使用量、预算与预测

### 5.1 统一维度

所有 Model、Task、Tool 和 Quota 事件必须携带：

```java
public record UsageDimensions(
        String tenantId, String projectId, UUID teamId, UUID workerId,
        UUID taskId, String provider, String model, String tool, String quotaDimension) {}
```

允许技术兼容路径使用 `null`，但生产入口缺少 tenant/project 必须 fail-closed。持久化聚合不得用字符串 `unknown` 混合多个租户；缺失维度单独计入 `dimension_completeness` 审计指标。

### 5.2 预算政策

新增：

- `usage_budget_policies`：项目、币种、周期、软阈值、硬阈值、预测窗口、状态和版本；
- `usage_budget_evaluations`：窗口、实际成本、预测成本、状态、评估时间；
- `usage_budget_events`：去重键、投递状态和审计关联。

金额使用 `NUMERIC`/`BigDecimal`，不使用浮点数。估算成本始终标记 `ESTIMATED`，没有价格时为 `UNPRICED`，不能当作 0。

预测采用可解释的线性外推：

```text
forecast = elapsedCost / elapsedSeconds * periodSeconds
```

当有效观测小于 1 小时或周期进度小于 5% 时不产生预测告警，只记录 `INSUFFICIENT_DATA`。同一 policy/window/threshold 使用唯一指纹去重。

### 5.3 API

- `PUT /api/v1/usage/budgets/{policyId}`：带 expectedVersion 更新；
- `GET /api/v1/usage/budgets`：按当前 scope 查询；
- `GET /api/v1/usage/budgets/{policyId}/evaluations`：分页查询；
- `GET /api/v1/usage/dimensions/completeness`：返回各维度覆盖率。

## 6. 配额规模验证

### 6.1 不变量

- 任意时刻有效并发 reservation 不超过项目上限；
- 相同幂等键最多占用一次；
- release 重复执行不重复扣减；
- acquire 超时后的服务端成功必须通过租约过期自动回收；
- Gateway、Manager、Worker 或 Control Plane 重启后约束仍成立；
- 多副本必须依赖数据库唯一约束和行锁，禁止新增进程级全局锁。

### 6.2 压力工具

新增 `scripts/run-quota-load.py`，参数固定包含并发数、请求数、项目数、超时和随机种子。输出 JSON 汇总：成功、拒绝、超时、重复、最大观察并发和未释放 reservation；不得输出 Token 或完整 scope claim。

CI 的短压测使用 4 个客户端、200 次 acquire；预发布环境使用至少 50 个客户端、持续 30 分钟，并在过程中重启 Gateway 和 Control Plane。

## 7. Operator 与 Kubernetes 规模化

### 7.1 Reconciler 行为测试

使用 Fabric8 Mock Server 或专用 Kind 测试覆盖 Worker、Team、TaskSandbox：

- 首次创建、重复 reconcile 和 generation 不变；
- 子资源被删除、被篡改后的恢复；
- status conflict、API 429/500 和短暂不可用；
- owner reference、finalizer 和删除路径；
- Leader Election 下只有 Leader 执行写操作；
- TaskSandbox Job 成功、失败、丢失和旧 generation 状态拒绝。

ResourceFactory 单测保留，但不能替代 Reconciler 行为测试。

### 7.2 Helm 生产基线

Control Plane、Gateway、Operator 分别配置：

- requests/limits；
- `runAsNonRoot`、`allowPrivilegeEscalation: false`、只读根文件系统和 `seccompProfile: RuntimeDefault`；
- topology spread、pod anti-affinity；
- 独立 PDB；
- 可选 HPA，默认关闭。

HPA 首期只使用 CPU 和内存；队列深度扩缩容在暴露稳定的外部指标后另行启用。Operator 使用 Leader Election 时可多副本，但 PDB 不得设置为与副本数相同的 `minAvailable`。

## 8. SLO、健康和通知

### 8.1 SLO

首期定义：

- API 可用性 99.9%；
- Task Assignment P95 小于 5 秒；
- Outbox 最老待处理事件小于 60 秒；
- Gateway Ready Worker 连接可用率 99.5%；
- Config Apply 成功率 99%；
- 预算和告警评估延迟小于 2 个调度周期。

SLO 通过 recording rules 计算，Alertmanager 只接收稳定低基数告警。

### 8.2 Gateway readiness

Gateway readiness 必须组合：应用 accepting traffic、数据库可读写、NATS 可发布/订阅、证书未过期。短暂 NATS 故障可以进入降级状态，但如果无法保证命令持久化，不得继续接收新的 Worker 会话。

### 8.3 通知闭环

Chart 提供可选 Alertmanager 路由值，不在 Git 保存接收器 Secret。每条告警包含 runbook URL、tenant/project 的安全标识、严重级别和 correlation ID；不得包含 Prompt、JWT、API Key 或完整外部响应。

## 9. Matrix 与 mTLS 长期运行

- Matrix：重启 homeserver 和 Control Plane 后，重复 transaction 不重复执行命令，未发送 Outbox 最终重放；
- mTLS：双 CA 重叠期间新旧证书均可连接，移除旧 CA 后旧证书被拒绝；
- 证书轮换必须通过稳定 Secret 名称触发滚动更新，并记录断连和重连指标；
- 生产验收连续运行至少 2 小时，期间执行证书轮换、Gateway 滚动重启和 Matrix homeserver 重启。

## 10. 核心实现文件

预计新增：

- `control-plane/src/main/java/io/agentteams/controlplane/usage/UsageDimensions.java`
- `control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetController.java`
- `control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryObservationPort.java`
- `control-plane/src/main/java/io/agentteams/controlplane/mcp/JdbcMcpDiscoveryObservationStore.java`
- `control-plane/src/main/resources/db/migration/`：新增 Runtime Resource Apply 与 Usage Budget 的连续版本迁移
- `scripts/run-quota-load.py`
- `deploy/helm/agentteams-java/templates/hpa.yaml`

预计修改：

- `manager/src/main/java/io/agentteams/manager/ModelCallDimensions.java`
- `runtime/src/main/java/io/agentteams/runtime/RuntimeModelCallAdmission.java`
- `control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertScheduler.java`
- `operator/src/main/java/io/agentteams/operator/*Reconciler.java`
- `deploy/helm/agentteams-java/values.yaml`
- `deploy/helm/agentteams-java/templates/poddisruptionbudget.yaml`
- `deploy/helm/agentteams-java/templates/prometheusrule.yaml`

迁移编号在实施时取当前最大 Flyway 版本之后的连续编号，计划不得硬编码会与其他并行分支冲突的版本号。

## 11. 验收标准

### L1/L2

- Skill/MCP 重复 ACK 不产生重复记录，旧 revision 不覆盖新状态；
- 预算金额和预测全部使用 `BigDecimal`，无价格不会被统计为 0；
- 预算窗口事件在多副本调度下只产生一次；
- 配额并发测试证明最大有效 reservation 不超过限制；
- 所有 Reconciler 行为场景通过 Mock Server 或 Testcontainers 验证。

### L3/L4

- Helm lint/template、PDB、HPA、securityContext 和 topology spread 校验通过；
- Kind 中完成 Skill 下载校验、MCP 工具发现、资源 ACK、预算告警和 Alertmanager 测试接收；
- Gateway 依赖失效后 readiness 变为非 Ready，恢复后自动回到 Ready；
- 4 客户端短压测无超发、无 reservation 泄漏。

### L6

- 50 客户端持续 30 分钟并穿插服务重启，无配额超发；
- Matrix 和 mTLS 2 小时长期运行与轮换验收通过；
- SLO、错误预算和告警通知能够从事件追溯到 runbook 与审计记录。
