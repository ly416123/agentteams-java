# Team 成员选择与 Worker 类型设计

## 1. 文档信息

- **日期：** 2026-09-03
- **状态：** 待用户评审
- **选择方案：** A：Worker 显式类型
- **适用范围：** Control Plane、Console、Worker Template、Team 成员与版本管理

## 2. 背景与问题

当前 Team 详情页通过手工输入 `Worker ID` 添加成员，存在两个问题：

1. 用户容易输入名称、过期 ID 或其他 Project 的 ID，服务端会返回「当前账号没有访问此资源的权限」，但页面无法帮助用户找到正确资源。
2. Agent/Worker 没有显式的 Leader 类型，Team 只能依赖成员关系中的 `LEADER` 字段表达 Leader 身份，无法在选择阶段阻止普通 Worker 被任命为 Leader。

Worker Template 当前也没有类型声明。模板实例化后无法从配置层保证生成的 Worker 具备正确的 Leader 能力边界。

## 3. 目标

本次设计解决以下问题：

- Team 添加成员改为从当前 Project 可访问的 Worker 列表中选择。
- Team Leader 只能选择 `LEADER` 类型的 Worker。
- Worker 在资源层显式区分 `LEADER` 与 `EXECUTOR` 两种类型。
- Worker Template 声明类型，实例化生成的 Worker 继承模板类型。
- 保留现有 Project、Tenant、Resource Scope 和权限模型，避免通过前端隐藏控件绕过服务端授权。
- 对现有 Worker、Template 和旧客户端保持可迁移、可回滚的兼容路径。

## 4. 非目标

- 本次不新增第三种 Worker 类型。
- 本次不把 Worker 类型等同于 Team 成员关系角色。`LEADER`/`MEMBER` 仍表示某个 Worker 在具体 Team 中的任命关系。
- 本次不改变 Task 调度策略、Conversation 路由或 Worker Pod 生命周期。
- 本次不允许前端直接访问数据库、Kubernetes 或 Resource Scope 存储。

## 5. 核心模型

### 5.1 Worker 类型

新增统一枚举 `WorkerType`：

| 值 | 页面名称 | 含义 |
| --- | --- | --- |
| `LEADER` | Leader Worker | 可以被任命为 Team Leader |
| `EXECUTOR` | 执行 Worker | 只能作为普通成员参与 Team |

类型属于 Worker 资源本身，不属于某一次 Team 成员关系。一个 `LEADER` Worker 可以在多个允许的 Team 中担任普通成员，也可以在满足 Team 约束时担任 Leader。

现有 Worker 统一迁移为 `EXECUTOR`。这样不会把历史 Worker 静默提升为 Leader，也不会改变已有 Team 的执行行为。

### 5.2 Team 成员关系

继续保留当前成员关系字段：

- `role = MEMBER`：允许 `LEADER` 和 `EXECUTOR` 两类 Worker。
- `role = LEADER`：只允许 `workerType = LEADER`。

服务端必须执行校验，前端过滤只用于改善交互体验。Leader 关系还必须满足以下条件：

- Worker 在当前调用者的 Project Scope 内可见；
- Worker 是当前 Team 的有效成员；
- 一个 Team Revision 只能有一个 Leader；
- `leaderAgentId` 必须出现在 `memberAgentIds` 中；
- `leaderAgentId` 对应的 Worker 类型必须为 `LEADER`。

### 5.3 Worker Template

Worker Template 增加 `workerType` 字段，创建模板时选择类型。类型属于模板的稳定身份，创建后不可在 Revision 中覆盖。

- Template 的每个 Revision 继承模板类型；
- 实例化时把模板类型传递给 AgentSpec、Worker 记录和 Worker 配置 Manifest；
- 已有 Template 迁移为 `EXECUTOR`；
- 如果需要 Leader Worker，创建新的 `LEADER` Template，避免通过修改历史模板改变已发布实例的身份。

AgentSpec 作为 Worker 配置对象不单独拥有另一套类型语义。由 Template 生成的 AgentSpec 必须携带继承后的 `workerType`；直接创建 Agent/Worker 时由请求明确指定或按兼容默认值处理。

## 6. API 设计

### 6.1 Worker 列表与详情

现有接口继续使用当前 Project Scope：

```text
GET /api/v1/agents?projectId={projectId}
GET /api/v1/agents/{agentId}?projectId={projectId}
```

响应增加：

```json
{
  "id": "…",
  "name": "worker-alpha",
  "workerType": "LEADER",
  "phase": "READY",
  "runtime": "qwenpaw"
}
```

列表接口只返回当前用户可见的 Worker。Console 添加 Team 成员时必须复用该列表，并排除当前 Team 已经处于 `ACTIVE` 状态的成员。

### 6.2 创建 Worker

`POST /api/v1/agents?projectId={projectId}` 的请求增加 `workerType`：

```json
{
  "name": "worker-alpha",
  "runtime": "qwenpaw",
  "workerType": "LEADER",
  "capabilities": {},
  "metadata": {}
}
```

为兼容旧客户端，省略 `workerType` 时按 `EXECUTOR` 处理，并在响应中始终返回实际类型。新 Console 表单必须显式选择类型。

### 6.3 添加 Team 成员

接口路径和请求结构保持兼容：

```text
POST /api/v1/teams/{teamId}/members?projectId={projectId}
```

```json
{
  "agentId": "worker-uuid",
  "role": "LEADER"
}
```

服务端先验证 Team 和 Worker 在当前 Project Scope 内可见，再执行类型校验。类型不匹配时返回可识别的业务错误，例如 `WORKER_TYPE_NOT_ALLOWED_FOR_ROLE`，消息应说明「只有 Leader Worker 可以担任 Team Leader」。不得把类型不匹配伪装成资源无权限。

成员查询响应增加展示所需字段：

```json
{
  "agentId": "worker-uuid",
  "agentName": "worker-alpha",
  "workerType": "LEADER",
  "role": "LEADER"
}
```

### 6.4 Team Revision

现有 Revision 接口仍支持 `leaderAgentId` 和 `memberAgentIds`，但创建草稿时增加完整校验：

- 所有 ID 必须属于当前 Project 且当前用户可见；
- 所有成员必须是当前 Team 的有效成员；
- Leader 必须是 `LEADER` 类型；
- Leader 必须包含在成员列表中；
- 成员列表中最多一个 Leader 关系。

Console 不再要求用户输入逗号分隔的 ID，而是使用当前 Team 成员下拉列表选择 Leader 和成员。服务端仍保留 UUID 请求字段，避免不必要的 API 破坏性变更。

### 6.5 Worker Template

模板创建请求增加 `workerType`，模板和 Revision 查询响应返回继承后的类型：

```json
{
  "name": "leader-template",
  "displayName": "Leader Worker Template",
  "workerType": "LEADER"
}
```

Revision 的 `workerType` 只读。实例化接口不接受覆盖类型的参数，实例化链路必须保证模板类型、AgentSpec 类型、Worker 类型和 Manifest 类型一致；发现不一致时整个实例化操作失败，不创建可用的半成品 Worker。

## 7. Console 交互

### 7.1 Team 成员页

将「Worker / Agent ID」文本框改为 Worker 下拉框：

- 数据来源：`GET /api/v1/agents?projectId=当前 Project`；
- 只显示当前用户有权访问的 Worker；
- 显示名称、类型、状态和 Runtime，例如「worker-alpha · Leader Worker · READY」；
- 排除已在当前 Team 中的有效成员；
- 选择 `MEMBER` 时显示两类 Worker；
- 选择 `LEADER` 时只显示 `LEADER` Worker；
- 没有可选 Worker 时显示明确空态，并提示先在当前 Project 创建或实例化 Worker；
- 列表加载失败时显示重试入口，不允许用户退回手填 ID。

添加失败时按错误类型展示：

- `403`：当前账号没有访问该 Project 或 Worker 的权限；
- `WORKER_TYPE_NOT_ALLOWED_FOR_ROLE`：类型不满足角色要求；
- `409`：成员关系已被其他操作修改，需要刷新成员列表。

### 7.2 Team Revision 页

- Leader 使用只显示 `LEADER` Worker 的下拉框；
- 成员使用当前 Team 有效成员的多选控件；
- 删除原始 `leaderAgentId` 和 `memberAgentIds` 文本输入；
- 创建前在页面提示「Leader 必须同时属于 Team 成员」。

### 7.3 Worker 页

Worker 列表、详情和筛选器展示 `Leader Worker`/`执行 Worker` 标签。类型与生命周期状态分开显示，避免把 Leader 类型误解为当前在线状态。

### 7.4 Template 页

创建模板时增加类型下拉框，模板卡片和实例结果展示类型。已发布 Revision 不允许通过实例化表单修改类型。

## 8. 数据库迁移与兼容

新增数据库迁移（版本号按仓库当前 Flyway 版本顺延）：

1. `agents.worker_type TEXT NOT NULL DEFAULT 'EXECUTOR'`；
2. `worker_templates.worker_type TEXT NOT NULL DEFAULT 'EXECUTOR'`；
3. 为两个字段增加非空和枚举值约束；
4. 为 Worker 列表增加 `(worker_type, phase, updated_at)` 相关查询索引（以实际执行计划为准）。

迁移策略：

- 先加字段和默认值，再发布兼容读写代码；
- 旧数据全部读取为 `EXECUTOR`；
- 新代码响应始终带 `workerType`；
- 旧客户端仍可省略创建请求中的 `workerType`；
- 回滚代码时保留新增字段，不删除数据；
- 不自动把历史 Team 的某个成员改成 Leader。

如果运营需要把已有 Worker 提升为 Leader，必须通过显式管理操作完成，并要求该 Worker 没有与当前类型冲突的有效 Team 任命；该操作需要独立的版本保护和审计记录。本次实现计划需要据此决定是否同时提供「变更 Worker 类型」入口。

## 9. 权限与错误语义

下拉列表解决的是资源发现问题，不改变授权边界：

- `GET /api/v1/agents` 按当前 Project Scope 返回可见资源；
- 添加成员、创建 Revision 和实例化 Template 仍由服务端重新校验 Scope；
- 跨 Project 或不可见 Worker 继续返回授权错误；
- 类型合法但资源不可见，优先返回授权错误；
- 资源可见但类型不匹配，返回业务校验错误；
- 所有写操作继续使用 `Idempotency-Key`，并保留现有审计链路。

## 10. 验收标准

### 后端

- Worker 创建、查询、列表响应包含 `workerType`；
- 历史 Worker 和 Template 迁移后类型为 `EXECUTOR`；
- `EXECUTOR + LEADER` 添加成员被拒绝，并返回明确业务错误；
- `LEADER + LEADER` 添加成员成功；
- 跨 Project Worker 仍被拒绝，且不会通过手工构造 UUID 绕过 Scope；
- Team Revision 拒绝非 Leader Worker 作为 Leader；
- Template 实例化后 AgentSpec、Worker 和 Manifest 类型一致；
- 幂等重试、版本冲突、重复成员和不存在资源行为保持正确。

### 前端

- Team 成员页不再出现 Worker ID 文本输入；
- Worker 下拉只显示当前 Project 可见资源，并正确排除已加入成员；
- 切换成员角色会重新过滤可选 Worker；
- Leader 下拉只显示 `LEADER` Worker；
- 无 Worker、加载失败和权限失败都有可理解的页面状态；
- Revision 和 Template 页面不再要求用户输入原始 UUID；
- Worker 和 Template 页面展示类型标签。

### 浏览器手工测试主路径

1. 登录拥有 `project:read`、`agent:read`、`team:read`、`team:write` 的账号。
2. 进入目标 Project 的 Team 详情页，打开「成员 Agent」。
3. 确认 Worker 下拉只列出当前 Project 可见 Worker。
4. 选择 `EXECUTOR` Worker 添加为普通成员，确认成功。
5. 切换角色为 Leader，确认该 Worker 不可选。
6. 选择 `LEADER` Worker 添加为 Leader，确认成功。
7. 创建 Team Revision，确认 Leader 和成员均通过下拉控件选择。
8. 创建 `LEADER` Template，发布 Revision 并显式实例化，确认生成 Worker 类型为 `LEADER`。
9. 使用无 Project 访问权限的账号重复验证，确认页面显示权限引导而不是可操作的空 ID 输入框。

## 11. 实施拆分建议

建议按以下顺序进入实现计划：

1. 领域枚举、数据库迁移、Repository 映射和 API 类型扩展；
2. Agent/Worker 创建与列表响应补齐类型；
3. Team 成员和 Revision 的服务端类型校验；
4. Template 类型传递及实例化一致性校验；
5. Console Worker、Team、Template 页面改造；
6. 单元测试、API 测试、迁移测试和真实 OIDC 浏览器回归；
7. 在 L5 环境重新验证，不进入 L6 外部依赖验收。

## 12. 待评审事项

本规格已按用户选择的 A 方案落稿，当前只保留 1 个实现前需要确认的细节：

- 是否需要在 Worker 管理页提供「将已有 `EXECUTOR` Worker 提升为 `LEADER`」的显式操作？默认建议是不提供，使用新的 `LEADER` Template/Worker 创建路径，避免改变历史 Worker 的身份和已有 Team 行为。
