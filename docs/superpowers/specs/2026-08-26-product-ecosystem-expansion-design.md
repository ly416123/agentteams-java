# AgentTeams Java 产品生态扩展设计

**日期：** 2026-08-26
**状态：** Worker Template Registry 最小可用闭环、OpenAPI v1.0 与 Java/TypeScript SDK 核心客户端已实现；Console 管理闭环已实现；Channel SPI 已完成 Webhook 和 Matrix 出站第一纵切，DingTalk 适配器仍待后续批次
**优先级：** P2/P3
**依赖：** P0 生产主路径和 P1 治理接口稳定

**当前约束覆盖：** 后续功能开发以
[管理端优先与 Java SDK 冻结实施方案](../plans/2026-09-01-management-first-console-completion-plan.md)
和 [Worker Pod 与记忆隔离需求基线](2026-09-01-work-pod-and-memory-isolation-requirements.md) 为准。
Java SDK 在最终 Console 页面功能验证通过前冻结；能力关系为
`SDK 已有能力 ⊆ 管理端能力`，管理端新增能力不要求反向进入 SDK。

## 1. 目标

本规格定义 Worker Template、SDK、Web Console、Channel SPI、Sandbox Pool、CubeSandbox 以及云商业化扩展的边界。所有扩展必须复用现有 AgentSpec、Task、Sandbox、Usage、Audit 和权限模型，不创建第二套业务状态。

## 2. 实施顺序

1. Worker Template Registry；
2. OpenAPI 稳定化与 Java/TypeScript SDK；
3. Web Console；
4. Channel SPI 和 Webhook/DingTalk Adapter；
5. Sandbox Pool 与可选 CubeSandbox Provider；
6. 有明确商业需求后再实现多区域、云资源和最终账单适配。

Console 不得先于 OpenAPI 和权限契约开发。CubeSandbox 不得先于 `SandboxRuntimePort` Kubernetes Adapter 和生命周期状态机稳定。

## 3. Worker Template Registry

### 3.1 模型

模板是生成 AgentSpec 的不可变蓝图，不直接代表正在运行的 Worker。

```java
public record WorkerTemplateRevision(
        UUID templateId,
        long revision,
        String name,
        String specJson,
        String digest,
        TemplateStatus status,
        String tenantId,
        String projectId,
        Instant createdAt) {}
```

状态：`DRAFT -> REVIEWING -> PUBLISHED -> DEPRECATED`。已发布 revision 不允许修改；升级创建新 revision。实例化时重新执行 Model、Skill、MCP、Secret 引用和当前用户权限校验，模板不能绕过资源可见性。

### 3.2 持久化

- `worker_templates`：模板身份、scope、当前发布 revision 和版本；
- `worker_template_revisions`：不可变 spec、digest、状态和审批信息；
- `worker_template_instances`：模板 revision、生成的 AgentSpec、Worker 和升级状态；
- 唯一键：scope 内名称、`(template_id, revision)`、实例化幂等键。

### 3.3 API

- `POST /api/v1/worker-templates`；
- `POST /api/v1/worker-templates/{id}/revisions`；
- `POST /api/v1/worker-templates/{id}/revisions/{revision}/publish`；
- `POST /api/v1/worker-templates/{id}/revisions/{revision}/instances`；
- `POST /api/v1/worker-templates/{id}/instances/{instanceId}/upgrade/{revision}`；
- `GET` 列表、详情、revision 和实例状态。

实例化复用 AgentSpec 发布和部署服务，不直接操作 Kubernetes。

## 4. OpenAPI 与 SDK

当前第一纵切已冻结 `openapi/agentteams-public.yaml`，覆盖 Project/Task 核心公共读写接口、统一游标分页、错误结构、Bearer 鉴权和幂等请求头。`sdk/java` 与 `sdk/typescript` 提供对应的核心客户端；内部控制面、Matrix AppService 和 Kubernetes API 不进入公共契约。Java SDK 冻结期间，新增管理端能力可以先更新管理 API、OpenAPI 和 Console，不要求立即同步 Java SDK；最终页面验证通过后再进行 SDK 能力对账和独立更新。

### 4.1 API 稳定化

- 为全部公共 API 生成 OpenAPI 3.1；
- 错误统一为 `code/message/correlationId/details`，`details` 不含敏感值；
- 写请求明确 `Idempotency-Key` 和 `expectedVersion`；
- 分页统一 `items/nextCursor`；
- 公共枚举、时间、金额和 UUID 类型固定；
- `/internal` 和 Matrix AppService API 不进入公共 SDK。

### 4.2 SDK

从锁定的 OpenAPI Artifact 生成：

- `sdk/java`：Java 17，提供同步/异步 Client；
- `sdk/typescript`：Node.js LTS，提供 Promise Client；
- SDK 只处理认证 Header、幂等键、序列化、重试提示和错误映射，不隐藏业务状态机；
- 默认只重试 GET 和明确安全的幂等写请求。

CI 比较生成结果，OpenAPI 发生破坏性变更时必须失败并要求显式版本升级。

## 5. Web Console

### 5.1 页面范围

首期只覆盖已经稳定的后端能力：

- 项目、成员和角色；
- Worker、Team、AgentSpec、Template 和配置 revision；
- Model Provider、价格、Skill、MCP；
- Task、Attempt、Lease、Sandbox 和 Artifact；
- Usage、预算、审计和告警。

Console 不直接连接 Kubernetes、PostgreSQL、NATS、MinIO、Secret Manager 或模型供应商。

### 5.2 权限与安全

- 使用 OIDC Authorization Code + PKCE；
- 浏览器不保存模型 API Key、mTLS 私钥或长期平台 Token；
- 页面路由可按权限隐藏，但后端仍是最终授权点；
- 所有危险操作显示目标资源、expectedVersion 和影响范围；
- 删除、终止、回滚和密钥轮换要求二次确认并展示 correlation ID。

### 5.3 前端边界

采用独立 `console/` 工程，通过生成的 TypeScript SDK 调用 API。前端不复制状态机和错误分类，只渲染服务端返回的稳定枚举。UI 自动化至少覆盖登录、创建 Team、发布 AgentSpec、创建 Task、查看 Sandbox 和配置回滚。

## 6. Channel SPI

### 6.1 Port

```java
public interface ChannelPort {
    ChannelReceipt send(ChannelMessage message);
    ChannelHealth health(ChannelBinding binding);
}

public record ChannelMessage(
        UUID messageId,
        String tenantId,
        String projectId,
        String bindingId,
        String eventType,
        String renderedBody,
        String correlationId) {}
```

当前 Webhook 第一纵切通过 `WebhookChannelAdapter` 实现该 Port：发送只创建持久化投递记录，复用现有 leader-only scheduler、HMAC、重试、死信和事件 ID 去重；适配器在入队前强制校验 Organization/Tenant/Project 绑定、启用状态和事件白名单。Matrix 第一纵切通过 `MatrixChannelAdapter` 复用现有 Matrix Outbox 与 `MatrixDeliveryService`，新增独立的 room 绑定表，将 Organization/Tenant/Project、事件白名单和启用状态作为发送前约束，并使用调用方消息 ID 保证幂等入队。两类 Channel 适配器只返回稳定的 `QUEUED/DUPLICATE` 回执，不拥有 Task 状态，也不返回 Secret。

Inbound Adapter 只负责验证供应商签名、去重、身份绑定和转换为平台命令；Outbound Adapter 从持久化 Outbox 消费。Channel 不拥有 Task 状态。

### 6.2 首批 Adapter

- `MatrixChannelAdapter`：迁移现有 Matrix 投递到统一 Port，但保留 AppService 协议；
- `WebhookChannelAdapter`：HMAC 签名、allowlist、超时、重放保护；
- `DingTalkChannelAdapter`：仅在有真实企业验收环境时实现。

错误分类固定为 `AUTH_REJECTED`、`RATE_LIMITED`、`TEMPORARILY_UNAVAILABLE`、`INVALID_RESPONSE`、`PERMANENT_REJECTION`。供应商消息进入脱敏字段，重试遵守 `Retry-After` 和最大尝试次数。

## 7. Artifact 保留与合规

新增项目级默认政策和 Task 覆盖：

```java
public record ArtifactRetentionPolicy(
        Duration successfulTaskRetention,
        Duration failedTaskRetention,
        Duration temporaryUploadRetention,
        boolean legalHold) {}
```

- 删除采用数据库 Tombstone + 异步对象删除；
- Legal Hold 阻止内容删除，但不阻止正常权限收回；
- 对象删除失败保留待重试记录；
- 审计保存对象键 hash、策略、操作者和结果，不保存预签名 URL；
- 清理任务使用数据库 Scheduler Lease，多副本只执行一次。

当前实现第一纵切已落在 `artifact_retention_project_policies`、
`artifact_retention_task_overrides` 和 `artifact_retention_tombstones` 三张表：
项目策略按现有 Task 的 tenant/project resource scope 解析，Task 覆盖优先于项目策略，
部署默认策略作为最终回退；Tombstone 记录对象键 hash、策略来源和版本快照，实际删除仍通过
现有 `ObjectStorage` Port 执行。删除成功保留 artifact 元数据并标记为 `DELETED`，失败以
指数退避重试。当前临时窗口只覆盖已登记的非 `AVAILABLE` artifact，config upload 仍由
既有专用生命周期清理任务负责。企业管理 API、Legal Hold 审批流和完整
result-manifest/payload-ref 统一归档策略仍属于后续批次。

## 8. Sandbox Pool 与 CubeSandbox

### 8.1 Pool

Pool 是 `SandboxRuntimePort` 之上的优化层，不改变 Task-Sandbox 一对一绑定：

- 按 profile/template/runtime 维护最小和最大预热数量；
- Sandbox 分配给 Attempt 前必须执行清理和租户重绑定；
- 释放后只有通过清理证明的 Sandbox 才能回池；
- HARDENED 默认不复用，除非 Provider 能提供可信快照恢复；
- Pool 状态和租约持久化，实例崩溃后可回收孤儿资源。

### 8.2 CubeSandbox Adapter

```java
public final class CubeSandboxRuntime implements SandboxRuntimePort {
    // provision/status/renew/terminate 映射到 Cube/E2B 兼容 API
}
```

Adapter 保存外部 ID、Endpoint 引用、供应商状态和错误分类；Token 通过 Secret Port 获取。快照、克隆和长驻能力放在 Adapter capability 中，不增加到 Task 核心状态机。Cube 不可用时可按项目政策回退 Kubernetes Provider，但相同 Attempt 不得同时拥有两个活动 Sandbox。

## 9. 云资源、多区域与账单扩展

这些能力属于 P3，只有明确部署需求后启动：

- `InfrastructureProvisioningPort` 对接云实例、VPC、DNS 和证书；
- `BillingExportPort` 输出已核算用量，不修改 Usage 事实；
- 区域路由使用 tenant/project 数据归属，不允许隐式跨区域读取；
- 最终账单由外部计费系统生成，AgentTeams 仅提供带版本的 Usage Ledger 和对账导出；
- 多区域不复用单集群 Scheduler Lease，应按数据归属划分写入 Leader。

## 10. 核心实现文件

预计新增：

- `control-plane/src/main/java/io/agentteams/controlplane/template/*`
- `control-plane/src/main/java/io/agentteams/controlplane/channel/*`
- `control-plane/src/main/java/io/agentteams/controlplane/artifact/ArtifactRetentionService.java`
- `runtime/src/main/java/io/agentteams/runtime/sandbox/SandboxPool.java`
- `runtime/src/main/java/io/agentteams/runtime/sandbox/CubeSandboxRuntime.java`
- `sdk/java/`
- `sdk/typescript/`
- `console/`

预计修改：

- `control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecService.java`
- `control-plane/src/main/java/io/agentteams/controlplane/matrix/*`
- `control-plane/src/main/java/io/agentteams/controlplane/config/ConfigSnapshotCleanupService.java`
- 根 `pom.xml`、CI 工作流和文档索引。

## 11. 验收标准

### Worker Template

- 已发布 revision 不可修改；重复实例化只生成一个 AgentSpec/Worker；
- 模板引用在实例化时重新鉴权，跨项目资源被拒绝；
- 升级失败保留上一稳定配置并提供可审计状态。

### SDK 与 Console

- OpenAPI 破坏性变更检测生效；Java/TypeScript SDK 均可创建并查询 Task；
- Console 完成 OIDC PKCE、权限路由和核心端到端流程；
- 浏览器存储、日志和构建 Artifact 中不存在 Secret。

### Channel

- 重复 inbound event 只执行一次命令；
- Channel 中断不回滚业务事务，恢复后按 Outbox 重放；
- Webhook 签名错误、超时、429 和永久拒绝均得到稳定分类。

### Retention 与 Sandbox

- Legal Hold 下不会删除对象；对象删除失败可恢复且不丢 Tombstone；
- Pool 不会跨租户泄漏文件、进程或凭据；
- CubeSandbox 重试不重复创建资源，Provider 切换不产生双活动 Sandbox；
- 新 Provider 不要求修改 Task、Attempt、Lease 或 Team 领域模型。

### P3

- 账单导出可与 Usage Ledger 按稳定 ID 对账；
- 区域故障不会违反租户数据归属；
- 云厂商 Adapter 可以关闭或替换，核心平台仍可在 Kubernetes Provider 下运行。
