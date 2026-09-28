# L5 Console 全模块测试数据设计

**状态：** 已确认，作为 L5 测试数据方案

**目标：** 为 L5 的 `alice` 测试账号补齐 Console 全部菜单所需的访问权限和业务数据，使各功能页面进入后能够展示真实业务记录，而不是因为 403 或空列表显示「无权访问」或无数据状态。

**适用环境：** L5 Ubuntu/K3s 环境 `192.168.122.55`，测试账号 `alice`，现有测试项目 `project-a`。

## 1. 设计原则

1. **API 优先。** 有正式创建接口的资源必须通过 Control Plane API 创建，保留正常的鉴权、校验、版本和审计语义。
2. **只读投影补齐。** 没有创建入口、仅用于管理端展示的记忆、Sandbox、告警、审计和评测投影，才允许通过 L5 PostgreSQL 幂等写入夹具数据；不得修改生产业务逻辑或绕过已有写入接口。
3. **统一夹具标识。** 所有新建资源的名称、标题、描述或元数据包含 `l5-console-fixture-20260928`，便于重复执行、核对和清理，不触碰历史测试数据。
4. **权限最小可测试。** 仅为 `alice` 补齐现有页面所需的业务 Realm 权限，保留既有 Keycloak 用户、项目成员和历史数据，不删除或降级任何成员关系。
5. **真实页面验证。** 造数后按路由逐页请求对应 API，要求返回 HTTP 200；列表型页面至少存在 1 条夹具或已有业务记录，详情页使用已存在的有效资源 ID。

## 2. 页面与数据覆盖

### 2.1 已有数据，仅做核对

- 概览、Tasks、Teams、Workers、对话；
- Templates、Agent Specs、Models、MCP、Skills；
- Usage、Artifacts、Project、角色与成员、组织和内部用户。

### 2.2 需要补齐的数据

| 页面 | 数据 | 首选写入方式 |
| --- | --- | --- |
| 定时任务 | 启用的周期任务及至少一条运行记录 | `scheduled-tasks` API |
| Budgets | 当前 Project 的预算和评估记录 | `usage/budgets` API，必要时补评估投影 |
| Alerts | 至少一条规则和一条告警事件 | 告警规则 API/数据库投影 |
| Audit | 与本次夹具相关的审计事件 | 通过正式管理 API 产生，缺失时补投影 |
| Memory | Project 或 Team 范围的记忆元数据 | 数据库只读投影夹具 |
| Sandboxes | 已完成或失败的 Sandbox 元数据 | 数据库只读投影夹具 |
| Evaluations | 至少一条评测运行及报告 | 评测 API，必要时补报告投影 |
| 身份、组织、集成 | 现有记录不足时新增夹具组织、集成和凭据元数据 | 管理 API |

## 3. 权限方案

`alice` 当前已经拥有 Project 级成员权限，但缺少组织管理、模型、MCP、Skill、审计和部分管理页所需的 Realm 权限。执行前通过 Keycloak Admin API 读取已存在的业务角色，只把现有角色映射到 `alice`，不创建新的权限名称，不授予 Keycloak 内建管理角色。

执行后必须重新获取 `alice` 的 OIDC Token，并验证以下接口不再返回 403：

- `/api/v1/management/users`；
- `/api/v1/management/organizations`；
- `/api/v1/model-providers`；
- `/api/v1/mcp-servers`；
- `/api/v1/skills`；
- `/api/v1/audit-events`；
- `/api/v1/agent-specs`。

## 4. 执行顺序

1. 读取当前项目、组织、团队、Worker 和已有夹具数据，建立资源 ID 映射。
2. 为 `alice` 补齐现有业务 Realm 角色并重新获取 Token。
3. 通过 API 创建定时任务、预算、告警规则、组织/集成元数据和评测运行。
4. 为没有创建接口的页面写入带前缀的只读投影夹具，并记录生成的 ID。
5. 重新请求所有 Console 页面数据接口，检查状态码、列表数量和跨资源关联。
6. 输出 L5 造数清单、权限清单、页面接口验收结果和清理范围。

## 5. 安全与清理

- 不在仓库、日志或最终报告中保存 Keycloak 管理员密码、用户 Token、模型 API Key 或凭据明文；凭据字段只使用安全的测试引用值。
- 不调用真实模型或外部供应商连通性作为造数前置条件；本任务只准备页面展示数据。
- 不删除现有 L5 数据。清理时仅允许按 `l5-console-fixture-20260928` 前缀和本次记录的 ID 定向删除。
- 如果某个页面的正式 API 校验失败，停止该模块写入并保留错误响应，不使用无边界 SQL 修改绕过业务约束。

## 6. 验收标准

- `alice` 重新登录后，项目菜单和设置菜单均可进入，页面不出现「无权访问」；
- 所有 Console 列表页面的首屏接口返回 HTTP 200；
- 定时任务、预算、告警、审计、Memory、Sandbox、Artifacts 和 Evaluations 至少各有 1 条可展示记录；
- 详情页使用真实存在的 Task、Conversation、Worker、Team 或 Evaluation ID；
- L5 Console、Control Plane、Worker 和数据库保持 Ready，未改变已有模型调用配置和业务数据；
- 造数结果可通过前缀和 ID 清单定向清理。
