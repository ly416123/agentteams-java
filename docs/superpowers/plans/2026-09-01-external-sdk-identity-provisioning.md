# 外部 SDK 接入与内部身份授权实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 AgentTeams 改造成由 Integration 签名接入、通过 SDK 显式初始化用户、由 AgentTeams 内部维护 Organization/Tenant/Project 授权的企业接入平台。

**架构：** 外部系统只保存 AgentTeams 下发的应用级 Credential，SDK 使用 HMAC-SHA256 为每个请求生成带时间戳、Nonce、Body Hash 和外部用户上下文的签名。Control Plane 验证 Integration 后，依据外部组织/用户映射解析内部身份和项目角色；普通业务请求不会隐式创建用户。OIDC 仅作为控制台的人类身份认证适配器，Token 只提供 `issuer`、`audience` 和 `subject`。

**技术栈：** Java 17、Spring Boot Servlet Filter、JDK `Mac`/`Signature`、Spring JDBC、PostgreSQL/Flyway、JUnit 5、AssertJ、Testcontainers、Java 17 SDK、TypeScript SDK、OpenAPI 3.1、Docker、Ubuntu/KVM L5。

**设计依据：** `docs/superpowers/specs/2026-09-01-external-sdk-identity-provisioning-design.md`。

---

## 文件与职责总览

### Control Plane 身份、签名和授权

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/AuthenticatedPrincipal.java`，表示已验证的外部应用、用户或服务账号身份。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/AuthorizationContext.java`，表示解析后的 Organization/Tenant/Project/Team 和有效权限。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/PrincipalType.java`、`SignatureAlgorithm.java`、`CanonicalRequest.java`、`RequestSignatureVerifier.java`、`ReplayNonceStore.java`，定义无供应商依赖的认证协议。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/HmacSha256RequestSignatureVerifier.java`、`Ed25519RequestSignatureVerifier.java`，实现签名算法。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/security/SdkAuthenticationFilter.java`，解析并验证 SDK 签名请求。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/PrincipalContext.java`、`AuthorizationService.java`、`ResourceAuthorizationService.java`，改为消费新的身份和授权上下文。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/security/IdentityTokenValidator.java`、`OidcIdentityTokenValidator.java`、`ApiAuthenticationFilter.java`，OIDC 只输出 `issuer/subject/clientId/scopes`。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`、`HttpSecurityConfiguration.java`，注册新的认证过滤器。

### 数据模型与业务服务

- 创建：`control-plane/src/main/resources/db/migration/V72__platform_identity_and_integrations.sql`，创建主体、用户、Integration、Credential、外部身份、Provisioning 策略和 Nonce 表。
- 创建：`control-plane/src/main/resources/db/migration/V73__principal_membership_scope.sql`，将成员关系统一改为内部主体 ID；开发阶段不保留旧 Claim 或旧成员身份兼容列。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/identity/`，保存内部主体、用户和外部身份映射；`principals` 是 Organization/Tenant/Project 成员关系的统一目标。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/integration/`，保存 Integration、Credential、签名范围和轮换状态。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/provisioning/`，实现显式用户初始化、成员同步和角色策略计算。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/project/`、`control-plane/src/main/java/io/agentteams/controlplane/security/JdbcExecutionContextDirectory.java`，改用内部成员关系。

### 管理 API、公共契约和 SDK

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/organization/OrganizationManagementController.java`，提供企业、租户和 Integration 管理入口。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/provisioning/ProvisioningController.java`，提供显式 SDK 用户初始化与同步入口。
- 修改：`openapi/agentteams-public.yaml`，增加 SDK 签名、外部用户上下文和新的错误码。
- 创建：`openapi/agentteams-provisioning.yaml`，描述管理与 Provisioning API。
- 修改：`sdk/java/src/main/java/io/agentteams/sdk/AgentTeamsClient.java`、`sdk/java/src/test/java/io/agentteams/sdk/AgentTeamsClientTest.java`。
- 创建：`sdk/java/src/main/java/io/agentteams/sdk/signing/`、`sdk/java/src/test/java/io/agentteams/sdk/signing/`。
- 修改：`sdk/typescript/src/client.ts`、`sdk/typescript/src/index.ts`、`sdk/typescript/tests/client.test.ts`。
- 创建：`sdk/typescript/src/signing.ts`。

### 测试、脚本和文档

- 创建：`control-plane/src/test/java/io/agentteams/controlplane/security/CanonicalRequestTest.java`、`RequestSignatureVerifierTest.java`、`SdkAuthenticationFilterTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/identity/IdentityProvisioningServiceTest.java`、`JdbcExternalIdentityRepositoryTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/integration/IntegrationCredentialServiceTest.java`、`JdbcIntegrationCredentialRepositoryTest.java`。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/provisioning/ProvisioningControllerTest.java`。
- 修改：依赖 `Principal.scope()`、`Principal.permissions()` 和外部 `subject` 的 Control Plane/Manager 测试，全部迁移到新上下文。
- 创建：`integration-tests/src/test/java/io/agentteams/integration/EnterpriseSdkOnboardingIT.java`。
- 创建：`scripts/test_sdk_signature_contract.py`、`scripts/test_provisioning_api_contract.py`、`scripts/run-enterprise-sdk-onboarding-l5.sh`。
- 修改：`scripts/enterprise-execution-contract.sh`、`.github/workflows/ci.yml`、`README.md`。

---

## 任务 1：冻结签名协议和身份对象

**目标：** 建立与 Keycloak、钉钉和其他渠道无关的认证接口。

- [ ] **步骤 1：编写失败的 Canonical Request 测试。** 测试 Path 的 RFC 3986 编码、Query/Headers 稳定排序、Body SHA-256，以及组织 ID、外部用户 ID、Timestamp、Nonce 被纳入签名。修改任一安全字段必须得到不同 canonical 文本。

  运行：`mvn -q -pl sdk/java,control-plane -Dtest=CanonicalRequestTest test`

  预期：因签名对象尚不存在而失败，不能是环境错误。

- [ ] **步骤 2：实现签名接口和算法。** 创建 `RequestSigner`、`RequestSignatureVerifier`、`CanonicalRequest` 和 `SignatureAlgorithm`；HMAC 使用 JDK `Mac`，Ed25519 使用 JDK `Signature`；未知算法、空 Secret、非法签名和比较失败返回结构化认证错误，不泄露 Secret。

- [ ] **步骤 3：实现 Java/TypeScript canonicalizer。** 两个 SDK 必须对相同 Method、Path、Query、Headers、Body 产生相同 canonical 文本；TypeScript 实现限定 Node.js 服务端运行环境。

- [ ] **步骤 4：运行定向测试并提交。**

  运行：`mvn -q -pl sdk/java,control-plane -Dtest=CanonicalRequestTest,RequestSignatureVerifierTest test && npm --prefix sdk/typescript test`

  预期：签名算法、篡改检测和跨 SDK fixture 全部通过。

  提交：`git add control-plane/src/main/java/io/agentteams/controlplane/security sdk/java sdk/typescript && git commit -m "feat(安全): 增加 SDK 请求签名协议"`

---

## 任务 2：增加内部主体、用户、Integration 和 Credential

**目标：** 持久化外部应用、外部用户与 AgentTeams 内部用户的关系，并支持凭证生命周期。

- [ ] **步骤 1：编写 Repository 失败测试。** 覆盖 `(issuer 或 integrationId, externalOrganizationId, externalUserId)` 唯一、`accessKeyId` 唯一、Credential 状态、Secret 不明文返回、Nonce 唯一和 Provisioning 幂等。

  运行：`mvn -q -pl control-plane -Dtest=JdbcPlatformUserRepositoryTest,JdbcIntegrationCredentialRepositoryTest,JdbcExternalIdentityRepositoryTest test`

  预期：因 V72/V73 和 Repository 不存在而失败。

- [ ] **步骤 2：实现 V72/V73 迁移。** 创建 `principals`、`platform_users`、`integrations`、`integration_credentials`、`external_identities`、`provisioning_policies`、`integration_request_nonces` 和 `provisioning_idempotency`。成员关系使用内部主体 ID；旧外部 `subject`、旧业务 Claim 和旧映射列不保留。

- [ ] **步骤 3：实现领域记录和 JDBC Repository。** Credential 提供 `findActiveByAccessKeyId`、`revoke`、`rotate`、`recordNonce`；所有写入使用数据库唯一约束、版本号和事务。Secret 通过现有 Secret Resolver/生产 Secret Manager 边界保存加密值。

- [ ] **步骤 4：运行迁移和 Repository 测试。**

  运行：`mvn -q -pl control-plane -Dtest=JdbcPlatformUserRepositoryTest,JdbcIntegrationCredentialRepositoryTest,JdbcExternalIdentityRepositoryTest test`

  预期：Testcontainers PostgreSQL 中 V1–V73 迁移成功，所有测试通过。

- [ ] **步骤 5：提交任务 2。**

  `git add control-plane/src/main/resources/db/migration control-plane/src/main/java/io/agentteams/controlplane/identity control-plane/src/main/java/io/agentteams/controlplane/integration control-plane/src/test/java/io/agentteams/controlplane/identity control-plane/src/test/java/io/agentteams/controlplane/integration && git commit -m "feat(身份): 增加内部用户和企业接入凭证"`

---

## 任务 3：实现 SDK 认证过滤器和统一授权上下文

**目标：** 删除旧业务 Claim 认证路径，让所有业务服务使用内部身份和授权上下文。

- [ ] **步骤 1：编写认证过滤器失败测试。** 覆盖正确签名、错误 Secret、过期 Timestamp、重复 Nonce、撤销 Credential、组织范围不匹配、未知外部用户和线程上下文清理。

- [ ] **步骤 2：实现 `AuthenticatedPrincipal` 与 `AuthorizationContext`。** `PrincipalContext` 只保存规范化上下文；`AuthorizationService.resolve` 根据 Integration Scope、Organization/Tenant Policy、Project Membership、ProjectRole 和资源范围计算权限。

- [ ] **步骤 3：重构 OIDC 验证。** OIDC 只验证 `iss`、`aud`、`sub`、`azp/client_id` 和标准 `scope`；不再读取或要求 `tenant`、`project`、`team`、`permissions` Claim。OIDC 用户必须已经存在于内部用户目录。

- [ ] **步骤 4：迁移业务调用方。** 修改 `TaskRepository`、`AgentRepository`、`TeamRepository`、`DomainEventRepository`、`WorkerOperationRepository`、`ResourceScopeRepository`、Project/Task/AgentSpec/Usage/Dashboard/Webhook/ScheduledTask/Template 服务，以及 Manager 的 `ManagerPrincipal`、`ManagerRequestContext` 和认证过滤器。

- [ ] **步骤 5：运行授权回归并提交。**

  运行：`mvn -q -pl control-plane,manager -Dtest=SdkAuthenticationFilterTest,AuthorizationServiceTest,ResourceAuthorizationServiceTest,TaskServiceAuthorizationTest,ManagerAuthenticationFilterTest test`

  预期：旧 Claim 测试已直接改写或删除；新身份、作用域、角色交集和拒绝路径全部通过。

  提交：`git add control-plane manager && git commit -m "refactor(授权): 切换到内部身份和统一授权上下文"`

---

## 任务 4：实现企业、租户和 Integration 管理 API

**目标：** 提供后台建立信任和资源边界的能力，普通 SDK 业务凭证不能调用这些接口。

- [ ] **步骤 1：编写管理 API 失败测试。** 覆盖 Organization/Tenant 创建与冻结、Integration 绑定、Credential 首次展示、轮换版本冲突、撤销立即生效、IP/资源范围和管理权限隔离。

- [ ] **步骤 2：实现管理 Service 和 Controller。** 提供：

  ```text
  POST /api/v1/management/organizations
  POST /api/v1/management/organizations/{id}/tenants
  POST /api/v1/management/organizations/{id}/integrations
  POST /api/v1/management/integrations/{id}/credentials
  POST /api/v1/management/credentials/{id}/rotate
  POST /api/v1/management/credentials/{id}/revoke
  PUT  /api/v1/management/integrations/{id}/provisioning-policy
  ```

  管理 Scope 为 `platform:organization:create`、`organization:admin`、`integration:manage`、`credential:manage` 和 `provisioning-policy:manage`。首个 Organization 使用部署配置的受信任管理身份创建；普通 SDK Credential 不能创建平台管理员。

- [ ] **步骤 3：运行测试并提交。**

  运行：`mvn -q -pl control-plane -Dtest=OrganizationManagementControllerTest,IntegrationManagementControllerTest test`

  提交：`git add control-plane/src/main/java/io/agentteams/controlplane/organization control-plane/src/main/java/io/agentteams/controlplane/integration control-plane/src/test/java/io/agentteams/controlplane/organization control-plane/src/test/java/io/agentteams/controlplane/integration && git commit -m "feat(企业): 增加组织租户和接入管理 API"`

---

## 任务 5：实现显式用户 Provisioning 和成员同步

**目标：** 只有 SDK 或受权管理 API 能创建内部用户；业务请求发现未知用户时只返回错误。

- [ ] **步骤 1：编写 Provisioning 失败测试。** 覆盖首次创建、重复幂等、不同请求同幂等键冲突、外部组织不匹配、角色策略计算、`OWNER` 提权拒绝、用户禁用和未知用户业务请求返回 `USER_NOT_PROVISIONED`。

- [ ] **步骤 2：实现 Provisioning Service。** 提供：

  ```java
  ProvisioningResult createOrInitializeUser(ProvisioningCommand command);
  ProvisioningResult updateUser(ProvisioningCommand command);
  void disableUser(ExternalUserRef externalUser);
  MembershipSnapshot listMemberships(ExternalUserRef externalUser);
  ```

  事务包含内部用户、外部映射、组织/租户成员、项目成员、幂等记录和审计事件；Task 创建事务不能调用该 Service。

- [ ] **步骤 3：实现 Provisioning API。** 提供：

  ```text
  POST /api/v1/provisioning/users
  PUT  /api/v1/provisioning/users/{externalUserId}
  POST /api/v1/provisioning/users/{externalUserId}/disable
  GET  /api/v1/provisioning/users/{externalUserId}/memberships
  ```

  接口要求 `identity:provision` 或 `identity:manage`，必须带签名、外部组织 ID 和幂等键。角色由策略计算，SDK 不能直接授予 `OWNER`。

- [ ] **步骤 4：运行测试并提交。**

  运行：`mvn -q -pl control-plane -Dtest=IdentityProvisioningServiceTest,ProvisioningControllerTest test`

  提交：`git add control-plane/src/main/java/io/agentteams/controlplane/provisioning control-plane/src/test/java/io/agentteams/controlplane/provisioning control-plane/src/test/java/io/agentteams/controlplane/identity && git commit -m "feat(接入): 增加显式用户初始化和成员同步"`

---

## 任务 6：改造 Java/TypeScript SDK 和 OpenAPI

**目标：** 外部系统只依赖 SDK，不接触 Keycloak SDK、AgentTeams 内部对象或数据库。

- [ ] **步骤 1：冻结 OpenAPI。** `openapi/agentteams-public.yaml` 描述签名 Header、外部用户上下文和任务 API；`openapi/agentteams-provisioning.yaml` 描述用户初始化、用户禁用、成员查询和连接检查。内部用户 ID 只作为响应关联信息，不作为外部身份输入。

- [ ] **步骤 2：改造 Java SDK。** `AgentTeamsClient` 支持 `accessKeyId/accessKeySecret`、`asUser(externalUserId)`、`provisioning()`、统一签名和结构化错误；GET 默认安全重试，写请求仅在显式 `retrySafe` 时重试。

- [ ] **步骤 3：改造 TypeScript SDK。** 支持同一签名协议和 Provisioning API；Secret Provider 支持函数形式；文档明确长期 Secret 仅可用于 Node.js/服务端，浏览器使用 OIDC 或短期 Token。

- [ ] **步骤 4：运行 SDK 和契约测试。**

  运行：`mvn -q -pl sdk/java -Dtest=AgentTeamsClientTest,AgentTeamsSigningClientTest test && npm --prefix sdk/typescript test && npm --prefix sdk/typescript run build && python3 scripts/test_public_openapi_contract.py -v && python3 scripts/test_sdk_signature_contract.py -v && python3 scripts/test_provisioning_api_contract.py -v`

  预期：Java、TypeScript、OpenAPI 和 Python 契约全部退出 0。

- [ ] **步骤 5：提交任务 6。**

  `git add openapi sdk scripts/test_sdk_signature_contract.py scripts/test_provisioning_api_contract.py && git commit -m "feat(sdk): 增加签名接入和用户初始化客户端"`

---

## 任务 7：企业接入端到端验证

**目标：** 用真实 PostgreSQL、Control Plane 和 SDK 走通企业注册后的接入闭环。

- [ ] **步骤 1：实现 `EnterpriseSdkOnboardingIT`。** 固定覆盖：创建 Organization、Tenant、Integration、Credential、Provisioning Policy；SDK 显式初始化 Alice；查询成员；代表 Alice 创建 Task；查询 Progress、Process Events、Result Manifest 和 Artifact；禁用 Alice 后验证请求被拒绝。

- [ ] **步骤 2：增加安全负向场景。** 覆盖伪造组织、伪造用户、修改 Body/Path/Query、重复 Nonce、过期 Timestamp、撤销 Credential、未初始化用户、跨 Project 访问和 `OWNER` 提权。

- [ ] **步骤 3：运行本地 Docker 验证。**

  ```bash
  source deploy/dev-env.sh
  docker info
  mvn -q -Pintegration-tests verify
  ```

  预期：Colima Docker Context 下 V1–V73 迁移和企业 SDK 正负向场景全部通过；Docker 或 Testcontainers 不可用时非零退出。

- [ ] **步骤 4：提交任务 7。**

  `git add integration-tests scripts docs && git commit -m "test(企业接入): 增加 SDK 用户初始化全链路验收"`

---

## 任务 8：L5 验收与发布门禁

**目标：** 在 Ubuntu/KVM L5 环境验证部署后的新 Control Plane 能完成真实 SDK 接入闭环。

- [ ] **步骤 1：实现 `scripts/run-enterprise-sdk-onboarding-l5.sh`。** 脚本检查远端服务和依赖，执行企业、Integration、用户、任务、过程、产物和安全负向场景，完成资源清理；只有全部断言和清理通过才输出 `L5_ENTERPRISE_SDK_ONBOARDING_OK`，环境缺失或清理失败必须非零退出。

- [ ] **步骤 2：更新统一门禁。** 修改 `scripts/enterprise-execution-contract.sh` 和 `.github/workflows/ci.yml`，加入签名、Provisioning、OpenAPI、Java SDK、TypeScript SDK 和本地 Docker 验证。涉及部署产物时追加 L5 脚本。

- [ ] **步骤 3：运行完整验证。**

  ```bash
  git diff --check
  source deploy/dev-env.sh
  docker info
  mvn -q -Pintegration-tests verify
  python3 -m unittest discover -s scripts -p 'test_*.py'
  npm --prefix sdk/typescript test
  npm --prefix sdk/typescript run build
  bash scripts/run-enterprise-sdk-onboarding-l5.sh
  ```

- [ ] **步骤 4：提交任务 8。**

  `git add scripts .github/workflows/ci.yml README.md docs && git commit -m "ci(企业接入): 增加 SDK 签名和 L5 验收门禁"`

---

## 依赖顺序与并行边界

```text
任务 1 签名协议
       ↓
任务 2 数据模型
       ↓
任务 3 认证与授权上下文
       ↓
任务 4 管理 API ──────┐
       ↓              │
任务 5 Provisioning ──┤
       ↓              │
任务 6 SDK/OpenAPI ────┘
       ↓
任务 7 本地集成验收
       ↓
任务 8 L5 验收门禁
```

任务 1 完成后，Java/TypeScript 签名实现可以并行；任务 2 完成后，Repository 测试和 OpenAPI 草案可以并行。任务 3 的统一身份上下文、任务 4 的 Organization/Integration 管理和任务 5 的 Provisioning 共享主键、角色和授权语义，必须按顺序完成。

## 完成定义

- 外部系统只依赖 AgentTeams SDK 和 AgentTeams 下发的 Credential；
- 业务请求不会隐式创建内部用户；
- `externalOrganizationId + externalUserId` 只能映射到已初始化的内部主体；
- Project Role 由 AgentTeams 内部关系和策略决定；
- Credential Scope 不能扩大 Project Role；
- Credential 支持撤销、轮换、过期、Nonce 防重放和审计；
- 旧 `tenant/project/team/permissions` Claim、旧 Bearer SDK 和旧 Principal 兼容路径已删除；
- 本地 Docker、SDK 契约、OpenAPI 契约和 L5 真实验收全部通过；
- L6 仍作为独立环境门禁，不在本计划中宣称完成。
