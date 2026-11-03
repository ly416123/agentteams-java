# 管理后台 RBAC 验收记录

日期：2026-11-02

## 已验证边界

| 范围 | 验收结果 | 证据 |
| --- | --- | --- |
| 平台/组织管理员 | 角色从数据库读取；组织管理员仅能访问其组织；停用角色立即失效；角色授予/回收记录审计 | `ManagementAuthorizationServiceTest`、`ManagementRoleBootstrapRunnerTest` |
| 首个管理员引导 | 配置为空不操作；首次启动只授予一个平台管理员；PostgreSQL advisory lock 序列化多个并发启动；仅成功授予时记审计 | `ManagementRoleBootstrapRunnerTest`、PostgreSQL 16 上 `ManagementProvisioningRepositoryIT`（2 项通过） |
| 项目角色 | VIEWER/DEVELOPER/OPERATOR/ADMIN/OWNER 的读写阈值来自持久化 membership；请求进入服务层时 Principal 也带有对应项目有效权限 | `ProjectApiAuthorizationTest`、`ApiAuthenticationFilterTest`、`ManagementAuthorizationServiceTest` |
| 项目 API 边界 | 项目路由逐方法/路径列入数据库授权分类；跨租户、非成员和非规范项目 scope 拒绝 | `ProjectApiAuthorizationTest`、`ProjectAuthorizationService` 及项目生命周期/邀请测试 |
| 公共 API 路由分类 | 已枚举用户 API 根路由；项目和管理路由使用精确边界，其它未登记 API 默认 403 | `ApiAuthorizationPolicyTest`、`ApiAuthenticationFilterTest` |
| Console | 菜单、路由和敏感操作受服务端有效权限驱动；权限加载中不显示页面；无权深链接显示拒绝页；项目角色编辑按所选项目单独读取权限 | `console/tests/auth/permissions.test.ts`、`RequirePermission.test.tsx` 与各 Management 页面测试 |
| 组织项目读取范围 | 组织管理员通过 tenant→organization 映射获得本租户项目只读列表；普通成员仍按活动项目 membership 列表，写操作仍走项目角色检查 | `ManagementAuthorizationServiceTest` |

## 本地验证命令

```bash
mvn -pl control-plane -Dtest='ApiAuthorizationPolicyTest,ApiAuthenticationFilterTest,ProjectApiAuthorizationTest,*ManagementAuthorizationServiceTest,ManagementRoleBootstrapRunnerTest,ProjectMembershipLifecycleTest,ProjectInvitationServiceTest,ManagementProvisioningControllerTest,*OrganizationManagement*Test,*IntegrationManagementControllerTest' test
cd console
npm run lint -- --no-warn-ignored
npm run build
npm test -- --run --reporter=dot tests/auth tests/features/Management
```

本次已在独立 `rbac-acceptance` Colima profile 的 PostgreSQL 16 测试容器中执行 `ManagementProvisioningRepositoryIT`：2 项通过，完整 Flyway 迁移到 V121 成功。该 profile 为避开默认 Colima profile 已满的 Docker 存储而创建；测试所需 postgres 镜像从本机镜像库导入。Ryuk 因 Docker 镜像代理返回 403 而禁用，JUnit/Testcontainers 正常停止并移除了 PostgreSQL 容器。默认 profile 的既有镜像、卷和容器未清理。

此前后端授权/项目测试 39 项通过；本轮重跑 API filter 测试 17 项通过，逐路由覆盖 31 条管理 API 和 12 条项目 API 的未认证 401。最新整合的项目与管理授权定向集合 83 项通过；`ProjectControllerAuthorizationHttpTest` 的 7 项 MockMvc 测试经实际 Controller/服务覆盖项目 API 全部 12 条路由，包括 OWNER/OPERATOR/平台管理员/邀请对象的允许与拒绝、非成员拒绝、跨租户对象通用 404 和拒绝路径无写副作用。另有 OWNER 成员列表和角色矩阵正向验证；ADMIN 不可转移 Owner 或授予/邀请 ADMIN 的限制由服务层测试覆盖。`OrganizationManagementAuthorizationHttpTest` 含 8 项 HTTP 测试，覆盖无角色时 20 条写路由和 8 条敏感读取路由的 403、以及组织 A 管理员对组织 B 的 19 种组织/集成/凭据/身份映射/外部用户路径逐项拒绝和无敏感读写。测试 token 即使带有高权限 claim，也不能绕过数据库角色。`GET /organizations` 无角色时返回空集合且不查询数据库；组织管理员列表 SQL 只读取已授权组织。`/authorizations/me` 与 `/permissions` 只查看主体自身信息。服务层同时检查精确 action 和资源归属。Console 20 个管理路由统一引用 `managementRoutePermissions`，表驱动深链接拒绝测试和导航/路由权限一致性检查通过；模型、模板、制品页已补只读拒绝测试，制品保留字段现也按 `artifact:write` 隐藏。`tests/auth` 与 `tests/features/Management` 最新 94 项通过（20 个测试文件），lint 和 production build 通过；本轮新增 8 项跨页面只读权限覆盖，包含身份管理的用户可读/不可写拆分。PostgreSQL 16 migration IT 2 项通过。此前全量 Console Vitest 为 302 项中 290 项通过、12 项失败，失败集中在任务/会话/状态标签相关测试（`OverviewPage`、`TaskDag`、`TaskInfoPanel`、`StateComponents`、`TaskPages`、`ConversationPage`、`statusLabels`、`TaskBoard`）。API 路由矩阵见 `management-rbac-route-matrix.md`。

项目角色矩阵现逐角色逐 action 穷举；拒绝用例确认 OPERATOR 无法读取、修改或邀请成员，ADMIN 无法转移 Owner、授予或邀请 ADMIN。完整导航快照修复角色管理菜单权限，并按已选项目权限过滤；身份页仅在 `admin:user:read` 下请求/渲染内部用户；列表独立于创建表单，停用/激活仍受 `admin:user:write` 控制。角色页仅在项目管理权限下请求当前角色。Console 验证结果见上文。

## 部署前验证状态

部署前的本地 PostgreSQL 集成测试通过只证明迁移可在 PostgreSQL 16 新库执行，不代表目标环境已部署验收。组织 A/B 隔离、管理员撤权即时生效、审计和 Secret 响应脱敏已在下方 L5 部署验收中完成。浏览器交互式 Console smoke 于 2026-11-02 补充完成，见后文；后续复测 L5 当前运行状态见非 L6 跟踪清单。

## L5 部署验收（2026-11-02）

- L5 主机 `ly-MacBookAir7-2`（`192.168.122.55`）节点为 Ready；部署前 Control Plane、Console、Keycloak、Manager、Gateway、MCP tools 均恢复 Ready。部署前 Helm `agentteams` revision 102，Control Plane/Console 为 2026-10-22 镜像。
- 使用本次源码构建 AMD64 Control Plane 与 Console 镜像，镜像 tar 传输前后 SHA-256 一致；镜像导入 L5 containerd。Helm chart lint 与模板渲染通过。
- 首次 rollout 暴露 `ManagementRoleBootstrapRunner` Spring 构造器注入错误。已回滚至 revision 102（生成 revision 104），L5 Control Plane 与 Console 均恢复 Ready；V121 是加表迁移，旧版本可在迁移后继续运行。修复构造器注入并通过 `ManagementRoleBootstrapRunnerTest` 3 项后，重建并重新部署。
- 初次 RBAC 部署 Helm `agentteams` revision 105 成功；修复租户 external_key 解析后 revision 106 `deployed`。Control Plane 与 Console rollout 均成功，当前 Control Plane 镜像为 `agentteams-control-plane:codex-rbac-tenant-key-20261102-amd64`，Console 镜像为 `agentteams-console:codex-rbac-20261102-amd64`；PostgreSQL 16.15 已迁移至 schema V121。
- 未认证请求对 `/api/v1/management/authorizations/me` 与 `/permissions` 均返回 401；早前未认证角色列表与平台管理员授予接口也返回 401。PostgreSQL `flyway_schema_history` 最新成功版本为 121。
- 已用仓库声明的开发账号 `alice` 做只读复现（没有修改 Keycloak 或授权数据）：修复前 token 验证成功，`/api/v1/management/authorizations/me` 返回 200 空列表；`/api/v1/management/authorizations/permissions` 与 `/api/v1/projects` 返回 400 `VALIDATION_ERROR`。根因是 `ManagementAuthorizationService.canReadTenantProjects()` 将 `tenant-a` 直接执行 `UUID.fromString()`；L5 数据库中 `tenant-a` 是 `tenants.external_key`，对应内部 UUID `429327d7-0250-600e-b51c-31d853528b8f`。现已新增按 UUID 或已注册 external_key 查询组织的 repository 方法和回归测试。
- 修复后以同一开发账号重新验收：`/api/v1/management/authorizations/me` 返回 200 空列表，`/api/v1/management/authorizations/permissions` 返回 200 且 `assignments`、`permissions` 均为空，`/api/v1/projects` 返回 200 空列表；租户解析 400 已消除。当前部署由 Helm revision 106 管理，Control Plane 1/1 Ready；`deploy/helm/l5-values.yaml` 已固定当前镜像 tag。


### L5 按角色运行时验收

- 通过应用的一次性 bootstrap 将现有开发账号 `quota-admin`（subject `b0a5e6a1-2c3d-4e5f-8a9b-0c1d2e3f4a51`）授予 `PLATFORM_ADMIN`；bootstrap 环境变量随后从 Deployment 移除。审计表包含 `PLATFORM_ADMIN_BOOTSTRAPPED`，且此账号的活动平台管理员角色保留在 L5，供开发环境后续 RBAC 管理。未修改 Keycloak 用户或凭据。
- 对无管理角色的 `alice` 验证：`/authorizations/me`、`/permissions` 和组织列表为 200 空结果；读取现存组织 A 的集成列表为 403。
- 经平台管理员 API（有审计）将 `alice` 授予现存组织 A 的 `ORGANIZATION_ADMIN`，其权限摘要含组织管理权限但不含 `admin:user:write`；组织列表只返回 A，A 的集成列表和项目列表为 200。创建带有验收标记的隔离组织 B 后，Alice 读取 B 集成列表返回 403。
- 使用同一 Alice access token 撤销组织角色后，`/me`、`/permissions`、组织列表立即变为空，A 的集成列表立即返回 403。数据库中 Alice 的角色状态为 `DISABLED`、version 3；授权/撤权各有审计事件。隔离组织 B 已标记 `SUSPENDED`。
- 创建验收专用集成及仅为引用的 dummy 凭据（未写入 Secret 明文）；创建和列表响应均只含 `accessKeyId,id,integrationId,label,status,version`，不含 `credentialRef`、`secret` 或 `accessKeySecret`。dummy 凭据已通过平台管理 API 撤销并审计。因当前 API 没有集成停用/删除路由，标记为 `RBAC Acceptance Credential Probe` 的集成仍在组织 A 下；其凭据已撤销。
- 当前角色表保留 `quota-admin` 的一条 ACTIVE `PLATFORM_ADMIN`，以及 Alice 一条 DISABLED `ORGANIZATION_ADMIN` 记录；隔离组织 B 为 SUSPENDED。角色引导、授予、撤销的数据库审计事件均可查。

最终本地定向回归集合 `ManagementAuthorizationServiceTest`、`JdbcOrganizationManagementRepositoryTest`、`ProjectApiAuthorizationTest`、`ProjectControllerAuthorizationHttpTest`、`OrganizationManagementAuthorizationHttpTest`、`OrganizationManagementScopeTest`、`ApiAuthorizationPolicyTest` 共 51 项通过。L5 最终复核 Helm revision 106 `deployed`、Control Plane/Console 均 1/1 Ready、数据库 V121，bootstrap 环境变量已从 Deployment 移除。PostgreSQL 审计记录包含 `PLATFORM_ADMIN_BOOTSTRAPPED`、组织角色授予/撤销、凭据创建/撤销事件。

此前 API 运行时验收通过 OIDC direct grant 获取现存开发用户 token，通过 L5 NodePort 实际调用 API；当时未执行浏览器交互式 Console 登录冒烟。隔离组织 B 已暂停；组织 A 下保留一个带验收标识的测试集成（dummy 凭据已撤销），因为当前 API 没有集成停用/删除路由。此前 nginx access log 曾记录 OIDC 回调查询串（含短时效 authorization code），未复用该 code。

### 浏览器 Console smoke（2026-11-02）

使用用户提供的 `quota-admin` 开发账号在 Chrome 完成 Keycloak 登录。Console 显示 Control Plane 已连接；直接访问 `/settings/roles` 可进入“角色与权限”页，并读取到活动平台管理员授权。默认项目 `testly` 的工作台显示“无权访问”，角色页项目区提示成员身份信息仅对项目管理员开放；该账号在此项目没有项目成员管理权限，符合平台管理角色与项目角色独立授权的边界。此次仅登录和读取页面，没有授予/回收角色或修改项目数据。Keycloak 登录地址为 L5 私网 HTTP NodePort（Chrome 标记为“不安全”），因此该次凭据传输没有 TLS 保护；本次操作没有将密码写入仓库，工具输出未回显密码。Chrome 中仍保留已登录的 Console 会话，供用户继续使用。

## L5 当前只读复核（2026-11-02）

后续通过 SSH 在 L5 主机执行只读 Kubernetes 检查：节点 Ready，Control Plane 与 Console 各 1/1 Ready，镜像 tag 与上方验收部署一致；metrics API 可用，节点内存约 72%，CPU 约 22%。当前没有 HPA 对象，符合单节点资源约束下未启用自动扩缩容的现状。此复核未执行浏览器登录，也未用实时授权 Token 重跑完整评测。
