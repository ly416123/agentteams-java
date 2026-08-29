# AgentTeams 管理端与对话工作台设计

## 1. 文档信息

- **日期：** 2026-08-29
- **状态：** 已获用户确认，待创建实现计划
- **适用范围：** AgentTeams 独立 Web 管理端第一期
- **参考产品：** 阿里云 TeamAgent / AgentCore 管理控制台

本文定义 AgentTeams 管理端的产品信息架构、页面边界、前后端协作方式、对话运行时和验收标准。本文只描述设计，不包含具体前端实现。

## 2. 目标

第一期交付一个可登录、可管理资源、可观察任务、可操作 Worker 并支持对话的独立 Web 控制台：

1. 用户通过 OIDC 登录，并在 Project 上下文中工作。
2. 用户可以创建和管理 Project、Team、Task 和 Worker。
3. 用户可以查看任务列表、任务详情、执行状态和运行记录。
4. 用户可以执行受权限保护的 Worker 生命周期操作。
5. 用户可以在对话工作台中与选定的 Worker 或 Team 交互。
6. 对话在第一阶段可由 Fake Worker 或 Mock QwenPaw 驱动，第二阶段切换到真实 Worker 和模型。
7. 页面在 Worker 暂时不可用时仍可以完成资源管理和状态查看，并明确展示不可执行原因。

## 3. 非目标

第一期不实现以下能力：

- 知识库、记忆库、Prompt 调试和模板市场；
- 模型、Skill、MCP 和配额的完整配置页面；
- 钉钉、飞书、企业微信等外部账号集成；
- 生产级多区域部署、计费中心和移动端应用；
- 浏览器直接访问 QwenPaw 内部接口；
- 使用阿里云品牌资源、页面代码或受版权保护的视觉资产。

上述能力只在导航和领域边界上预留扩展位置，不进入第一期验收。

## 4. 参考与设计原则

阿里云公开文档中的 AgentCore 用户侧采用侧边栏导航、Workspace 切换、资源列表、详情页和对话入口；管理侧提供概览、权限管理、系统设置和运行状态。Team 管理包含基本信息、Leader、成员和 Agent 配置；任务页面支持按状态查看、搜索、Team 筛选和执行记录。

- 用户界面参考：[用户界面功能详解](https://help.aliyun.com/zh/functioncompute/detailed-explanation-of-user-interface-features)
- 管理界面参考：[管理员界面功能详解](https://help.aliyun.com/zh/functioncompute/detailed-explanation-of-administrator-interface-features)
- Team 管理参考：[管理 Agent](https://help.aliyun.com/zh/document_detail/3052302.html)
- 任务工作台参考：[使用 Team Web 端设置任务](https://help.aliyun.com/zh/agentcore/use-team-web-to-set-tasks)

AgentTeams 只借鉴产品结构，不复制阿里云的品牌、页面文案和视觉资源。具体设计遵循以下原则：

- **资源中心化：** Project 是页面数据和权限的主要上下文。
- **状态可解释：** 所有状态都展示名称、原因、最近更新时间和下一步可操作项。
- **服务端为准：** 前端不复制 Task 状态机、权限规则或 Worker 运行时逻辑。
- **失败可恢复：** 断线、版本冲突、Worker 不可用和权限变化都给出可恢复路径。
- **运行时解耦：** 对话页面依赖 AgentTeams Conversation API，不依赖具体 QwenPaw 协议。

## 5. 用户角色与权限

页面根据 OIDC 用户的租户、Project、Team 和权限声明展示资源。隐藏按钮只改善体验，Control Plane 仍是最终授权方。

第一期沿用现有权限模型：

| 页面能力 | 主要权限 |
| --- | --- |
| 查看 Project、Team、Task、Worker | `project:read`、`team:read`、`task:read`、`agent:read` |
| 创建 Task、Team 或 Agent | `task:create`、`team:write`、`agent:write` |
| 修改 Team、Worker 配置 | `team:write`、`agent:write` |
| 审批、暂停、重试、取消 Task | 对应 Task 操作权限 |
| Drain、Rollout、Terminate、Rollback Worker | `agent:write` |
| 发送 Manager/Conversation 消息 | `manager:write` 或 Conversation 写权限 |
| 审批需要执行的工具操作 | `manager:approve` |

前端必须在登录后保存当前 Project 上下文，并在每次 API 请求中带上 Bearer Token。资源的 `tenant`、`project`、`team` 作用域必须与 Token 声明一致。

## 6. 信息架构与路由

控制台是独立的 `console/` 单页应用（SPA）。建议使用 React、TypeScript、Vite 和企业级组件库；开发环境使用 Vite 代理，部署环境由 Ingress 将 `/` 路由到 Console，将 `/api` 路由到后端。

推荐路由如下：

```text
/login
/
/:projectId/overview
/:projectId/teams
/:projectId/teams/:teamId
/:projectId/tasks
/:projectId/tasks/:taskId
/:projectId/workers
/:projectId/workers/:workerId
/:projectId/conversations
/:projectId/conversations/:conversationId
/settings/profile
```

顶栏包含 Project 切换器、当前用户、帮助和退出登录。左侧导航包含：

1. 概览；
2. Tasks；
3. Teams；
4. Workers；
5. 对话；
6. 运行记录。

模型、Skill、MCP、配额和系统设置在第一期只作为后续设置模块的预留入口。

## 7. 页面设计

### 7.1 登录页

登录页使用 OIDC Authorization Code + PKCE 跳转 Keycloak。前端不保存任何模型 API Key，也不在浏览器中暴露 Control Plane 内部凭据。

登录失败需要区分：用户取消登录、OIDC 配置错误、会话过期和无权访问 Project。成功登录后，如果用户没有可访问 Project，应展示空状态和联系管理员的引导。

### 7.2 概览页

概览页面向当前 Project，展示：

- Task 总量、排队中、执行中、成功和失败数量；
- Worker Ready、连接中、异常和 Draining 数量；
- 最近任务和最近运行事件；
- 当前告警和最近一次 Worker/依赖异常；
- Team 数量和活跃 Team 摘要。

卡片只展示服务端聚合数据。若某个数据源不可用，局部卡片显示「暂不可用」及重试入口，不影响其他区域。

### 7.3 Project / Workspace

Project 是页面的顶级上下文。Project 列表支持搜索和切换；当前 Project 变化后，所有资源查询、创建表单和权限判断都必须重新加载，不能继续复用旧 Project 的缓存。

第一期只实现 Project 选择和基本信息展示；成员邀请、Owner 转移等能力沿用现有 Project API，按权限显示。

### 7.4 Team 列表与详情

Team 列表支持搜索、状态筛选和创建。列表列出名称、Leader、Agent 数量、成员数量、并发上限、状态、更新时间和操作菜单。

创建 Team 采用分步表单：

1. 基本信息：名称、描述、管理员；
2. Leader：选择已有 Agent 或预留新建入口；
3. Team Agent：选择成员 Agent；
4. 策略：并发上限、允许 Runtime、能力要求、审批要求。

Team 详情采用页签：

- 概览：基本信息、Leader、状态和资源摘要；
- 成员 Agent：成员列表、能力、Runtime 和健康状态；
- 策略：调度、审批、配额和 Sandbox 策略；
- 版本与部署：版本、审核、发布、部署状态和回滚入口；
- 运行记录：该 Team 关联的 Task 和事件。

### 7.5 Task 列表与详情

Task 页面同时提供看板和表格两种视图。看板按服务端状态分列：待确认、已创建、排队中、执行中、已完成、失败和已取消。表格支持分页、搜索、状态、Team、Worker、创建人和时间范围筛选。

Task 卡片和表格行展示标题、摘要、优先级、Team、执行 Worker、状态、更新时间和操作入口。

Task 详情包含：

- 基本信息和完整作用域；
- 状态时间线；
- Attempt、Assignment 和 Lease；
- 当前及历史 Worker；
- 执行事件、错误原因和审计信息；
- 输入、输出和制品；
- 审批、暂停、恢复、重试、取消等操作。

Task 操作必须携带服务端返回的 `version`。如果服务端返回 `409`，页面保留用户输入，刷新最新状态，并提示用户重新确认操作。

### 7.6 Worker 列表与详情

Worker 页面将 Agent 和 Kubernetes Worker 的信息合并展示：名称、Agent ID、Runtime、连接状态、能力、当前任务、配置版本、镜像版本和最近心跳。

Worker 详情包含：

- 当前连接和健康状态；
- Hello 能力与 Runtime；
- 当前执行的 Task/Attempt；
- 配置快照和资源绑定摘要；
- Rollout、Drain、Terminate、Rollback 历史；
- 最近错误和审计事件。

操作按钮根据服务端状态动态启用。Worker 未 Ready 时允许查看和编辑声明配置，但执行依赖 Worker 的操作必须显示阻塞原因，例如「Worker 尚未连接」或「镜像拉取失败」。

### 7.7 对话工作区

对话页面采用三栏布局：

- 左栏：会话列表、搜索、新建会话；
- 中栏：消息流、输入框、发送、取消和重试；
- 右栏：Project、Team、Worker、Task、Runtime 和当前执行状态。

会话可以绑定 Project、Team 和可选 Worker。未指定 Worker 时，由服务端根据 Team 策略选择可用 Worker。对话产生的 Task 和工具调用必须可回链到会话。

浏览器只访问 AgentTeams Conversation API。Fake Worker、Mock QwenPaw 和真实 QwenPaw 都实现同一服务端运行时接口，前端不感知具体适配器。

## 8. 前端技术架构

```text
OIDC Provider
      │
      ▼
Console SPA ── REST/JSON ── Control Plane API
      │                         │
      └── SSE ──────────────────┤
                                ├── PostgreSQL
                                ├── NATS JetStream
                                ├── Agent Gateway
                                └── Manager / Conversation Runtime
```

前端分为以下边界：

- `auth`：登录、Token 生命周期、Project 上下文；
- `api`：REST 客户端、统一错误解析、幂等键和版本号；
- `queries`：列表、详情、缓存失效和分页状态；
- `streams`：SSE 连接、游标、重连和取消；
- `features`：Overview、Teams、Tasks、Workers、Conversations；
- `components`：状态徽标、时间线、确认弹窗、错误态和空状态。

服务端状态使用 Query 缓存管理；表单状态与服务端缓存分离。任何写操作成功后，只失效受影响的 Project、Team、Task、Worker 或 Conversation 查询，不全局刷新页面。

## 9. API 补充与服务端边界

当前后端已经提供部分资源详情和生命周期 API，但产品列表和统一对话需要补充以下接口：

```text
GET  /api/v1/projects
GET  /api/v1/teams
GET  /api/v1/teams/{teamId}
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}/events
GET  /api/v1/agents
GET  /api/v1/agents/{agentId}/operations
GET  /api/v1/manager/sessions
POST /api/v1/conversations
GET  /api/v1/conversations/{conversationId}
POST /api/v1/conversations/{conversationId}/messages
GET  /api/v1/conversations/{conversationId}/events
POST /api/v1/conversations/{conversationId}/cancel
```

列表接口统一支持：

- `page`、`pageSize` 或明确的 Cursor 分页；
- `search`、状态、Project、Team 和时间筛选；
- 稳定排序字段；
- `items`、`nextCursor`、`total` 和服务端时间戳。

所有写接口继续使用 `Idempotency-Key`。涉及状态更新的请求继续使用 `expectedVersion`，返回资源的新版本。

## 10. 对话运行时与 SSE

Conversation API 负责会话生命周期、消息持久化、权限校验和运行时路由。其内部通过 `ConversationRuntimePort` 连接以下实现：

1. `FakeConversationRuntime`：单元测试和页面开发，生成确定性消息与状态；
2. `MockQwenPawConversationRuntime`：Kind 验收，模拟 HTTP/SSE、延迟、断线和失败；
3. `QwenPawConversationRuntime`：真实 Worker/QwenPaw/模型链路。

SSE 事件至少包含：

```text
conversation.started
message.delta
message.completed
task.created
task.updated
tool.started
tool.completed
conversation.cancelled
conversation.failed
```

每个事件携带单调递增游标。浏览器断线后使用 `Last-Event-ID` 或 `after` 游标重连；重连期间显示「连接已断开，正在重连」，超过退避上限后提供手动重试。取消请求必须幂等，服务端返回取消确认后页面停止继续发送消息。

真实 Worker 未 Ready 时，Conversation API 返回可识别的 `WORKER_UNAVAILABLE`，页面显示状态和重试入口；不能伪装成模型生成失败。

## 11. 错误处理与交互约定

| HTTP 状态 | 场景 | 页面处理 |
| --- | --- | --- |
| `400` | 表单或请求参数错误 | 在字段附近展示校验原因 |
| `401` | Token 缺失或过期 | 保存当前路由并重新登录 |
| `403` | 权限或作用域不匹配 | 展示无权访问，不重试 |
| `404` | 资源已删除或不可见 | 返回列表并提示资源不存在 |
| `409` | 版本或幂等冲突 | 获取最新资源，保留用户输入并要求确认 |
| `429` | 配额或频率限制 | 展示限流/配额信息和稍后重试 |
| `503` | Worker、模型或依赖不可用 | 显示依赖状态，不把它归类为业务失败 |
| SSE 中断 | 网络或服务重启 | 自动重连，恢复游标后继续接收 |

删除 Team、Terminate Worker、取消运行中 Task 等不可逆或高风险操作必须二次确认，并展示影响范围。所有危险操作完成后刷新服务端状态，不依赖前端乐观猜测。

## 12. 部署与环境

本地开发：

- `console/` 使用 Vite 开发服务器；
- `/api` 代理到本机 Control Plane；
- OIDC 回调地址使用本地 Keycloak；
- 对话默认使用 Fake Worker 或 Mock QwenPaw；
- UI 验收可以在真实 Worker 不可用时执行管理流程。

Kind 验收：

- 构建并加载 Console 镜像和四个 Java 服务镜像；
- 使用 Keycloak OIDC 验证登录、权限和作用域；
- 使用 Mock QwenPaw 验证 SSE、重连、取消和错误态；
- 使用当前真实 Worker 和 DeepSeek 验证至少一条真实对话及一条真实 Task 闭环。

生产部署：

- Console 静态资源与 API 使用受控域名；
- OIDC、TLS、CORS 和 CSP 由部署环境明确配置；
- 模型凭据只存储在外部 Secret 或平台 Secret 管理系统；
- 生产 PostgreSQL、NATS、对象存储和 Worker 镜像由平台提供；
- gVisor/Kata Sandbox 不作为第一期 Console 上线前置条件。

## 13. 测试与验收

### 13.1 前端单元与组件测试

- Project 切换会清理并刷新资源查询；
- 权限不足时隐藏操作并处理服务端 `403`；
- `409` 冲突不会丢失表单输入；
- Task/Worker 状态徽标和时间线正确映射服务端枚举；
- 空列表、加载中、局部失败和全局失败状态可渲染。

### 13.2 API 契约测试

- 列表分页、筛选、排序和作用域隔离；
- 所有写请求的幂等键行为；
- Task、Team、Worker 版本冲突；
- Worker 不可用、权限不足、配额不足和依赖失败；
- Conversation SSE 事件顺序、游标和重放。

### 13.3 浏览器端到端测试

使用 Playwright 覆盖：

1. Keycloak 登录和退出；
2. Project 切换；
3. 创建 Team、添加 Agent、查看 Team 详情；
4. 创建 Task、看板/列表切换、查看详情和执行操作；
5. 查看 Worker、发起 Drain 或 Rollout、处理状态变化；
6. Fake/Mock 对话的发送、SSE 增量、断线重连和取消；
7. 真实 Worker/DeepSeek 对话和 Task 完成验收。

### 13.4 产品可用性标准

- 首次登录后 3 步内可以到达当前 Project 的概览；
- 任何列表页面都具备搜索、筛选、分页、刷新和空状态；
- 任何异步操作都展示进行中、成功、失败和重试状态；
- 任何危险操作都有确认和结果反馈；
- 不把 Worker 未就绪、模型失败、权限不足和业务校验错误混为同一类提示。

## 14. 实施顺序

### 阶段 A：管理端基础

实现 `console/` 工程、OIDC 登录、Project 上下文、通用布局、API 客户端和统一状态组件。

### 阶段 B：资源管理

补齐 Project、Task、Agent 列表 API，完成概览、Team、Task、Worker 列表和详情页，以及对应的 CRUD 和生命周期操作。

### 阶段 C：Mock 对话

实现 Conversation API、Fake/Mock Runtime、SSE 事件、断线重连、取消和 Playwright 对话测试。

### 阶段 D：真实对话与 E2E

接入当前已验证的 QwenPaw Worker 和 DeepSeek，完成真实对话、真实 Task 回链、权限/作用域和 Worker 重启恢复验收。

阶段 A 至 C 不以真实 QwenPaw Worker Ready 作为开发前置条件；阶段 D 才要求真实 Worker、QwenPaw 和模型全部可用。

## 15. 已确认决策

- 第一阶段采用独立 `console/` SPA；
- 产品上下文以 Project/Workspace 为顶层；
- 第一阶段包含概览、Project、Team、Task、Worker 和对话；
- 模型、Skill、MCP、配额和知识库设置延后；
- 前端使用真实 Control Plane API；
- 对话先使用 Fake Worker/Mock QwenPaw，再切换真实运行时；
- 浏览器不直接调用 QwenPaw；
- 管理页面与真实 Worker 可用性解耦；
- 真实 Worker、QwenPaw 和 DeepSeek 只作为真实对话及完整 E2E 的验收依赖。
