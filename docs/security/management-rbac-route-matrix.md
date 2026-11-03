# 管理 API 角色与动作矩阵

所有 `/api/v1/management/**` 请求先通过 OIDC 认证；授权在应用服务中读取数据库活动角色、检查目标资源所属组织，再校验本次操作所需 action。权限摘要只用于界面呈现；服务端会重新计算并强制 action。未命中登记路由的请求由 API filter 默认拒绝。主体和作用域不得由请求体提供。

| HTTP 路由 | action | 最低角色及范围 |
| --- | --- | --- |
| `GET /organizations` | `platform:organization:read` 或 `organization:read` | 平台管理员查看全部；组织管理员只查看已分配组织；无角色返回空集合且不查组织表 |
| `POST /organizations` | `platform:organization:create` | 平台管理员 |
| `GET /organizations/{id}/tenants` | `organization:admin` | 路径组织的组织管理员或平台管理员 |
| `POST /organizations/{id}/tenants` | `organization:admin` | 路径组织的组织管理员或平台管理员 |
| `POST /organizations/{id}/status` | `organization:write` | 路径组织的组织管理员或平台管理员 |
| `POST /tenants/{id}/status` | `organization:write` | 先解析租户所属组织，再校验该组织管理员或平台管理员 |
| `GET /organizations/{id}/integrations` | `integration:read` | 路径组织的组织管理员或平台管理员 |
| `POST /organizations/{id}/integrations` | `integration:write` | 路径组织的组织管理员或平台管理员 |
| `GET /integrations/{id}/credentials` | `credential:read` | 集成所属组织管理员或平台管理员；只返回标签、状态、访问密钥 ID |
| `POST /integrations/{id}/credentials` | `credential:manage` | 集成所属组织管理员或平台管理员 |
| `POST /credentials/{id}/rotate` | `credential:rotate` | 先解析凭据所属集成和组织，再校验组织管理员或平台管理员 |
| `POST /credentials/{id}/revoke` | `credential:revoke` | 先解析凭据所属集成和组织，再校验组织管理员或平台管理员 |
| `PUT /integrations/{id}/provisioning-policy` | `provisioning-policy:manage` | 集成所属组织管理员或平台管理员 |
| `GET /integrations/{id}/external-identities` | `identity-mapping:read` | 集成所属组织管理员或平台管理员 |
| `POST /integrations/{id}/external-identities` | `identity-mapping:write` | 集成所属组织管理员或平台管理员 |
| `POST /integrations/{id}/provisioned-users` | `external-user:manage` | 集成所属组织管理员或平台管理员 |
| `PUT /integrations/{id}/provisioned-users/{externalUserId}` | `external-user:manage` | 集成所属组织管理员或平台管理员 |
| `POST /integrations/{id}/provisioned-users/{externalUserId}/disable` | `external-user:manage` | 集成所属组织管理员或平台管理员 |
| `GET /integrations/{id}/provisioned-users/{externalUserId}/memberships` | `external-user:manage` | 集成所属组织管理员或平台管理员 |
| `GET /users` | `user:manage` | 平台管理员 |
| `POST /users` | `user:manage` | 平台管理员 |
| `POST /users/{id}/status` | `user:manage` | 平台管理员 |
| `GET /organizations/{id}/memberships` | `organization:admin` | 路径组织的组织管理员或平台管理员 |
| `POST /organizations/{id}/memberships` | `organization:admin` | 路径组织的组织管理员或平台管理员 |
| `GET /authorizations/assignments` | 平台角色检查 | 平台管理员，只能查询指定 scope 的角色记录 |
| `POST /authorizations/organizations/{organizationId}/admins` | 平台角色检查 | 平台管理员 |
| `POST /authorizations/platform/admins` | 平台角色检查 | 平台管理员 |
| `POST /authorizations/organizations/{organizationId}/admins/{subject}/revoke` | 平台角色检查 | 平台管理员；按角色版本回收并审计 |
| `POST /authorizations/platform/admins/{subject}/revoke` | 平台角色检查 | 平台管理员；禁止撤销当前操作者自身，按角色版本回收并审计 |
| `GET /authorizations/me` | 当前主体身份 | 任意已认证主体，只返回自身角色 |
| `GET /authorizations/permissions` | 当前主体有效权限 | 任意已认证主体，只返回自身角色及请求项目的有效权限 |

总计 31 条管理路由。未认证 401 已逐路由覆盖；无管理角色 HTTP 403 覆盖 20 条敏感写入和 8 条敏感读取。`GET /organizations` 是有意的无角色空集合例外。组织 A 管理员对组织 B 的租户、集成、凭据、身份映射、外部用户生命周期及成员关系读写均以通用 403 拒绝，测试同时验证不会执行敏感数据查询或写入。授权失败响应不区分对象不存在和对象越权。

项目 API (`/api/v1/projects/**`) 另按持久化项目 membership 授权。项目创建仅平台管理员；项目列表按活动 membership 或已授权的平台/组织范围筛选。成员身份列表、角色矩阵、成员变更要求项目 ADMIN/OWNER；Owner 转移仅 OWNER 可执行。公共 API 的 HTTP method 权限由 `ApiAuthorizationPolicy` 分类，未登记路由/方法默认拒绝。

实现位置：`OrganizationManagementController`、`OrganizationManagementService`、`ManagementAuthorizationService`、`ProjectAuthorizationService`、`ApiAuthorizationPolicy`。`ApiRouteAuthorizationCoverageTest` 阻止新增 public controller 路由绕过显式策略或服务授权边界。
