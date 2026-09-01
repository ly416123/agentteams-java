# 外部 SDK 接入与内部身份授权设计

**状态：** 已确认总体方案，进入详细设计阶段

**适用范围：** 外部企业系统通过 AgentTeams SDK 接入，完成企业绑定、用户显式初始化、项目授权、任务调用和任务过程/产物访问。

**当前基线：** 当前 `main` 已具备 OIDC/JWT 验证、Organization/Tenant 执行上下文、Project Membership、Project Role、任务过程和结果清单，以及 Java/TypeScript 公共 SDK。当前 SDK 以 Bearer Token 为凭证；本设计将其直接改造为应用级签名接入和显式 Provisioning，不保留旧业务 Claim 和旧身份上下文兼容路径。

## 1. 背景与问题

当前 `OidcIdentityTokenValidator` 需要从 Token 中读取 `tenant`、`project`、`team` 和 `permissions`，并由 `ApiAuthenticationFilter` 创建包含业务范围的 `Principal`。这种方式把外部身份提供商的 Token Claim 与 AgentTeams 的业务授权模型耦合在一起，导致：

- 外部系统必须理解 AgentTeams 的业务 Claim 约定；
- 用户、组织、项目角色变化依赖外部 Token 刷新；
- SDK、钉钉、Matrix、企业网关等不同渠道需要重复实现相同的业务身份协议；
- 普通业务请求可能被误设计为隐式创建内部用户，破坏接口语义和审计边界。

本设计采用「外部应用凭证 + SDK 请求签名 + 显式用户初始化 + AgentTeams 内部授权」模型：

```text
外部应用身份
    → 请求签名验证
    → 外部组织/用户身份
    → AgentTeams 内部用户映射
    → Organization/Tenant/Project Membership
    → 有效权限
    → 业务执行
```

## 2. 目标与非目标

### 2.1 目标

1. 外部系统不依赖 Keycloak 专用 SDK、AgentTeams 内部 Java 类或数据库结构。
2. AgentTeams 通过标准签名协议识别外部应用，支持 Java 和 TypeScript SDK。
3. 用户创建和外部身份绑定必须通过显式 Provisioning API 完成。
4. 未初始化用户的任务、对话、查询请求必须拒绝，不能隐式创建用户。
5. AgentTeams 生成内部用户 ID，维护外部用户映射和 Organization/Tenant/Project 授权。
6. 外部应用可以携带外部组织 ID 和外部用户 ID，但不能自行决定内部组织、角色或权限。
7. OIDC 仅作为可选的人类用户身份验证适配器，Token 只提供 `issuer`、`audience` 和 `subject`；不保留旧业务 Claim。
8. 凭证可轮换、撤销、过期、限流、限制 IP 和资源范围，并产生完整审计。

### 2.2 非目标

- AgentTeams 不在本阶段实现用户密码、登录页面、MFA 或完整 IdP。
- SDK 不在客户端保存长期 Secret；浏览器和移动端不允许使用应用级 Secret。
- 不把外部部门直接建模为 AgentTeams Team；AgentTeams Team 仍表示 Agent/Worker 执行团队。
- 不允许普通 SDK 调用自动创建 Organization、授予 `OWNER` 或绕过企业审批。
- 不改变 Task、Sandbox、MCP、Skill 的既有领域边界；它们只消费新的统一授权上下文。
- 不保留旧 `Principal(subject, scope, permissions)`、旧 `tenant/project/team/permissions` Claim、旧 Bearer SDK 或旧的兼容解析器。

## 3. 核心概念与边界

```text
外部 Integration
  └── Credential
       └── 外部组织 externalOrganizationId
            └── 外部用户 externalUserId
                 └── AgentTeams User
                      └── Organization/Tenant Membership
                           └── Project Membership / ProjectRole
                                └── Task / Team / Agent / Artifact
```

### 3.1 Integration

`Integration` 表示一个外部系统、渠道或环境，例如 `acme-crm-prod`。它是请求签名和资源范围的主体，不等同于用户。

一个 Integration 必须绑定一个 AgentTeams Organization，并可选绑定 Tenant、Project、Team 和允许的 API Scope。

### 3.2 Credential

Credential 是 Integration 的调用凭证。默认支持 `HMAC-SHA256` 的 `accessKeyId + accessKeySecret`；高安全部署支持外部持有私钥、AgentTeams 保存公钥的非对称签名模式。

Credential 具备独立状态、过期时间、IP 白名单、允许 Scope、允许 Tenant/Project 和轮换记录。

### 3.3 外部用户身份

外部用户唯一键不能只使用 `externalUserId`，必须至少包含：

```text
integrationId 或 externalIssuer
externalOrganizationId
externalUserId
```

该复合键映射到 AgentTeams 内部 `userId`。内部 `userId` 由 AgentTeams 生成，外部系统不能自定义。

### 3.4 Project Role

`OWNER`、`ADMIN`、`OPERATOR`、`DEVELOPER`、`VIEWER` 仍由 AgentTeams 内部 Project Membership 管理。外部系统只能提交部门、群组或岗位等属性；角色由已审批的 Mapping Policy 计算。

### 3.5 Team

当前 Team 是 Agent/Worker 执行团队。企业部门、外部群组和人类用户不直接写入 `team_memberships`。外部部门只用于角色映射、默认 Project 和默认执行 Team 的选择。

## 4. 身份与授权模型

请求上下文拆成两层，避免当前 `Principal` 同时承载外部身份和业务授权：

```java
record AuthenticatedPrincipal(
        String principalId,
        PrincipalType principalType,
        String integrationId,
        String issuer,
        String externalSubject,
        String clientId,
        Set<String> tokenScopes) { }

record AuthorizationContext(
        String userId,
        String organizationId,
        String tenantId,
        String projectId,
        String teamId,
        String organizationRole,
        String tenantRole,
        ProjectRole projectRole,
        Set<String> effectivePermissions) { }
```

最终有效权限遵循交集规则：

```text
effectivePermissions
    = credentialScopes
      ∩ requestedOperation
      ∩ projectRolePermissions
      ∩ organizationTenantPolicy
      ∩ resourceScope
```

外部请求中的 `organizationId`、`tenantId`、`projectId` 和 `teamId` 只能作为路由或目标资源参数，不能覆盖 Credential 绑定和数据库中的实际归属。

## 5. 请求签名协议

### 5.1 请求头

```http
Authorization: AT-HMAC-SHA256 Credential=atk_acme_crm_prod,SignedHeaders=...
X-AT-Timestamp: 1780000000
X-AT-Nonce: 4e6c0b7e-...
X-AT-Organization-Id: acme-corp
X-AT-User-Id: ding-user-001
X-AT-Content-SHA256: <hex digest>
X-AT-Signature: <base64url signature>
```

`X-AT-User-Id` 代表被代理的外部用户；`accessKeyId` 代表调用应用。

### 5.2 Canonical Request

SDK 对以下内容做规范化并签名：

```text
HTTP Method
规范化 Path
排序后的 Query 参数
签名 Header 名称和值
Organization ID
External User ID
Unix Timestamp
Nonce
Body SHA-256
```

HMAC 模式：

```text
signature = HMAC-SHA256(accessKeySecret, canonicalRequest)
```

非对称模式使用同一 Canonical Request，替换为 Credential 注册的公钥验证。

### 5.3 服务端验证顺序

1. 解析 Credential ID，拒绝缺失或格式错误的凭证。
2. 查询 Credential 状态、过期时间、算法和 Integration 归属。
3. 检查 TLS、来源 IP、时间偏差和 Nonce 重放缓存。
4. 重新计算 Body Hash 和 Canonical Request。
5. 使用 Secret 或公钥验证签名。
6. 校验 Integration 绑定的 Organization 与请求组织一致。
7. 校验目标 Tenant/Project/Team 在 Credential 允许范围内。
8. 根据 `integrationId + externalOrganizationId + externalUserId` 查询内部用户映射。
9. 用户不存在时返回 `USER_NOT_PROVISIONED`，不执行创建。
10. 查询 Organization/Tenant/Project Membership 和企业策略。
11. 计算 `AuthorizationContext`，执行资源授权。
12. 记录应用身份、外部用户、内部用户和目标资源的审计事件。

### 5.4 重放、轮换和泄露防护

- Timestamp 默认允许 ± 5 分钟偏差，部署配置可收紧。
- Nonce 按 Credential 维度短期缓存，重复 Nonce 直接拒绝。
- Body Hash 防止签名后篡改请求体。
- Secret 仅创建/轮换时展示一次，不写日志、不进入错误响应。
- 轮换期间支持旧 Key 和新 Key 短暂并存；完成确认后撤销旧 Key。
- Credential 禁用必须立即拒绝新请求；缓存权限不能绕过撤销状态。
- 生产环境优先使用 Secret Manager 或 Kubernetes External Secret 保存 Secret。

## 6. 数据模型

新增表建议如下：

### 6.1 `platform_users`

```text
id UUID PRIMARY KEY
display_name TEXT NOT NULL
email TEXT
status ACTIVE / DISABLED / DELETED
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
version BIGINT NOT NULL
```

### 6.2 `principals`

Organization、Tenant 和 Project 成员关系统一指向内部主体，而不是直接保存外部用户 ID：

```text
id UUID PRIMARY KEY
type USER / SERVICE_ACCOUNT
status ACTIVE / DISABLED / DELETED
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
version BIGINT NOT NULL
```

`platform_users.principal_id` 指向 `principals.id`。Project Membership、Organization Membership 和 Tenant Membership 的现有 `subject` 字段在 V73 中统一改为 `principal_id UUID`，并建立 active 状态索引；按照全库约束，V73 不建立外键。

V74 负责清理历史迁移遗留的外键约束；V74 之后的迁移禁止新增任何外键，关联完整性由应用事务、唯一约束和查询授权边界维护。

### 6.3 `external_identities`

```text
integration_id UUID
issuer TEXT
external_organization_id TEXT NOT NULL
external_user_id TEXT NOT NULL
internal_user_id UUID NOT NULL
display_name TEXT
status ACTIVE / DISABLED
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
version BIGINT NOT NULL
```

SDK 身份使用 `integration_id`，OIDC 身份使用 `issuer`；两者必须且只能填写一种身份来源，并分别建立唯一索引。

### 6.4 `integrations`

```text
id UUID PRIMARY KEY
organization_id UUID NOT NULL
name TEXT NOT NULL
channel_type SDK / DINGTALK / MATRIX / GATEWAY
user_assertion_mode SERVICE_ONLY / DELEGATED_USER / OIDC_USER_REQUIRED
status PENDING / ACTIVE / SUSPENDED / REVOKED
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
version BIGINT NOT NULL
```

### 6.5 `integration_credentials`

```text
id UUID PRIMARY KEY
integration_id UUID NOT NULL
access_key_id TEXT NOT NULL UNIQUE
algorithm HMAC_SHA256 / ED25519 / RSA_PSS
secret_ciphertext TEXT
public_key TEXT
allowed_scopes JSONB NOT NULL
allowed_tenants JSONB NOT NULL
allowed_projects JSONB NOT NULL
ip_allowlist JSONB
status ACTIVE / EXPIRED / REVOKED
expires_at TIMESTAMPTZ
last_used_at TIMESTAMPTZ
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
```

### 6.6 `provisioning_policies`

策略至少包含：

```text
integration_id
external_group
target_tenant
target_project
target_role
default_team
enabled
version
```

`target_role` 只能使用当前 Project Role，且 `OWNER` 不允许由普通用户初始化策略自动授予。

## 7. 管理与 SDK API 边界

以下是稳定的方案级 API 分组，具体 HTTP 路径在实现计划中冻结。

### 7.1 管理后台或管理 API

必须由平台管理员或企业管理员操作：

- 创建、激活、冻结 Organization；
- 创建 Tenant；
- 创建 Integration；
- 下发、轮换、撤销 Credential；
- 配置 IP、mTLS、限流和资源范围；
- 配置外部群组到 Project Role 的映射；
- 创建首个 Project 和执行 Team；
- 授予或转移 `OWNER`；
- 查询安全审计和接入状态。

### 7.2 SDK Provisioning API

SDK 可以显式调用：

```text
checkConnection()
createOrInitializeUser()
updateUser()
disableUser()
listUserMemberships()
syncProjectMemberships()
```

所有 Provisioning API 都需要独立的 `identity:provision` 或 `identity:manage` Scope，并使用幂等键。

### 7.3 SDK 业务 API

已初始化用户可以调用：

```text
createTask()
getTask()
getTaskProgress()
listTaskProcessEvents()
getTaskResult()
listArtifacts()
```

业务 API 不允许创建用户、组织、租户或修改角色。

## 8. 显式用户初始化流程

```text
管理员创建 Organization
    ↓
管理员创建 Integration 和 Credential
    ↓
管理员配置 Tenant、Project 和角色映射策略
    ↓
外部系统使用 SDK 显式调用 createOrInitializeUser
    ↓
AgentTeams 创建内部用户并保存外部身份映射
    ↓
按策略加入 Organization/Tenant/Project
    ↓
外部系统调用业务 API
```

如果用户未完成初始化：

```text
业务请求 → USER_NOT_PROVISIONED → 不创建用户、不执行任务
```

## 9. 身份入口

项目只保留新身份模型，不实现旧 Claim 兼容。

### 9.1 SDK 签名入口

外部系统使用 Integration Credential 和 SDK 请求签名。签名验证产生 `AuthenticatedPrincipal`，再由 AgentTeams 显式解析外部用户映射和业务授权。这是企业系统、渠道和服务端集成的标准入口。

### 9.2 OIDC 人类用户入口

控制台可以继续使用 OIDC，但 OIDC Token 只证明外部身份：

```text
issuer
audience
subject
```

AgentTeams 根据 `(issuer, subject)` 查找已经显式初始化或绑定的内部用户，再解析 Organization/Tenant/Project 授权。OIDC Token 不得携带或覆盖业务组织、租户、项目、Team 和角色。

### 9.3 代码边界

`Principal`、`PrincipalContext` 和 `ExecutionContextResolver` 直接重构为新身份与授权上下文模型。现有调用方、旧测试和旧 SDK 不作为兼容约束，必须一次性迁移到新接口。

## 10. 错误码与审计

核心错误码：

| 错误码 | HTTP | 说明 |
|---|---:|---|
| `INVALID_SIGNATURE` | 401 | 签名不匹配 |
| `CREDENTIAL_REVOKED` | 401 | 凭证已撤销 |
| `REQUEST_REPLAYED` | 401 | Nonce 已使用 |
| `REQUEST_EXPIRED` | 401 | 时间戳超出允许窗口 |
| `INTEGRATION_SCOPE_DENIED` | 403 | 接入应用不属于目标组织或资源 |
| `USER_NOT_PROVISIONED` | 403 | 外部用户未显式初始化 |
| `USER_DISABLED` | 403 | 内部用户或映射已禁用 |
| `PROJECT_ROLE_DENIED` | 403 | 项目角色不允许当前操作 |
| `PROVISIONING_POLICY_DENIED` | 403 | 初始化策略不允许目标角色或资源 |
| `IDEMPOTENCY_CONFLICT` | 409 | 相同幂等键对应不同请求 |
| `KEY_ROTATION_CONFLICT` | 409 | 凭证轮换版本冲突 |

审计必须同时记录：

```text
integrationId
accessKeyId（只记录 ID，不记录 Secret）
externalOrganizationId
externalUserId
internalUserId
organizationId
tenantId
projectId
teamId
action
result
correlationId
sourceIp
```

## 11. 安全约束

- AccessKey Secret 只能出现在外部系统服务端，不能进入浏览器和移动端。
- `externalUserId`、`externalOrganizationId` 必须进入签名内容。
- 组织和项目范围必须由 Credential 绑定与数据库关系双重校验。
- 外部请求不能直接传入内部 `userId` 作为身份依据。
- 外部请求不能直接提交 `OWNER` 或任意 Project Role 覆盖策略。
- 内部管理 API 与外部 SDK Provisioning API 使用不同 Scope。
- 管理后台操作使用 OIDC/MFA；SDK 不拥有平台管理员能力。
- 所有用户创建、角色变化、禁用和密钥操作必须审计。
- 生产环境优先启用 mTLS；HMAC 作为默认签名算法，Ed25519/RSA-PSS 用于高安全接入。
- 业务服务只消费规范化身份和授权上下文，不解析渠道 Claim。

## 12. 验收范围

### 单元与集成测试

- HMAC Canonical Request 在 Java/TypeScript SDK 与服务端结果一致；
- Body、Path、Query、User ID 或 Organization ID 修改后签名失败；
- 过期 Timestamp 和重复 Nonce 被拒绝；
- Credential 撤销和轮换行为正确；
- 未初始化用户不能创建任务；
- 显式初始化重复调用幂等；
- 外部用户 ID 在不同 Integration 或 Organization 下不会串户；
- SDK 不能通过请求体把角色提升为 `OWNER`；
- Project Role 与 Credential Scope 取交集；
- 用户禁用后任务、过程和产物查询立即拒绝；
- 旧 OIDC Claim 客户端不属于新接口范围，必须迁移到新的 OIDC 纯身份模式或 SDK 签名模式；
- 审计同时包含应用主体、外部用户和内部用户。

### 本地与 L5 验收

本功能主要涉及 Control Plane、数据库、SDK 和公共 API，不涉及 Kubernetes、Operator、Worker、TaskSandbox 或 RuntimeClass 时，执行本地 Docker-backed Maven/Testcontainers、Java SDK、TypeScript SDK 和 OpenAPI 契约验证。所有现有旧 Bearer SDK、旧 OIDC Claim 测试和旧身份上下文测试都必须迁移或删除，不作为验收兼容项。

如果实现同时修改任务执行、Webhook、Worker 或部署链路，则必须在本地 Docker 验证后追加 Ubuntu/KVM L5 主机 `192.168.122.55` 的真实验收，并保留运行时证据和清理结果。L6 仍保持独立门禁。

## 13. 外部参考

- [阿里云 Managed Agents API：鉴权与 SDK](https://help.aliyun.com/zh/model-studio/managed-agents-api-overview)
- [阿里云百炼 API Key 获取与配置](https://help.aliyun.com/zh/model-studio/get-api-key)
- [阿里云百炼权限管理](https://help.aliyun.com/zh/model-studio/permission-management-overview)

阿里云的 API Key、业务空间、资源范围、成本核算和 SDK 模式作为参考；AgentTeams 在此基础上增加显式用户初始化、外部用户映射和项目级业务授权，避免把一个应用 Key 误当成终端用户身份。
