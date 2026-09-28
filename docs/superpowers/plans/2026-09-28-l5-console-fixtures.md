# L5 Console 全模块测试数据实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 L5 为 `alice` 补齐 Console 所有菜单的访问权限和业务展示数据，并以接口回归证明页面不再返回 403 或空数据。

**架构：** 使用 Keycloak Admin API 为 `alice` 映射现有业务 Realm 角色；使用 Control Plane 正式 API 创建有写入口的资源；使用带固定前缀的幂等 PostgreSQL 投影夹具补齐没有创建入口的只读页面。所有数据归属现有 `tenant-a/project-a/team-a`，不创建新的运行时服务或模型配置。

**技术栈：** L5 Keycloak、Control Plane REST API、K3s PostgreSQL、Bash、`curl`、`jq`、`psql`。

---

## 固定环境和资源

- L5 Console：`http://192.168.122.55:30080`
- L5 Keycloak：`http://192.168.122.55:30082`
- 测试账号：`alice/alice-dev`
- 组织：`a14329d4-b7c6-f4b9-dd8c-ba6153d135d2`
- 租户：`tenant-a`
- Project：`ef5916eb-14d9-4e75-9e9c-041c6b5fb447`
- Project 名称：`project-a`
- Team：`b7aac93d-2e8c-4774-b04b-c80f839d4915` / `team-a`
- 已成功执行任务：`76ad74e3-86b7-4ef6-92e7-75370388f557`
- 已成功执行 Run：`f4ce614d-2903-486a-9f8c-11feeb777219`
- 统一夹具前缀：`l5-console-fixture-20260928`

### 任务 1：刷新权限并建立回归基线

**文件：** 无仓库文件变更；仅操作 L5 Keycloak 和读取接口。

- [ ] **步骤 1：获取 `alice` 新 Token。** 使用 password grant，不打印 Token 内容。

```bash
token=$(curl -fsS "$KEYCLOAK/realms/agentteams/protocol/openid-connect/token" \
  -H 'content-type: application/x-www-form-urlencoded' \
  --data 'grant_type=password&client_id=agentteams-api&username=alice&password=alice-dev&scope=openid' \
  | jq -r .access_token)
```

- [ ] **步骤 2：验证权限接口。** 请求 `management/users`、`management/organizations`、`model-providers`、`mcp-servers`、`skills`、`audit-events` 和 `agent-specs`，每个接口必须返回 HTTP 200。

- [ ] **步骤 3：记录已有资源数量。** 记录 projects、teams、workers、tasks、conversations、templates、agentspecs、models、MCP、skills、artifacts 和 usage 的当前数量，后续只比较前缀夹具增加量。

### 任务 2：通过正式 API 创建页面数据

**文件：** 无仓库文件变更；使用 L5 Control Plane API。

- [ ] **步骤 1：创建一个启用的定时任务。** 请求 `POST /api/v1/scheduled-tasks`，使用组织 UUID、`tenant-a`、Project UUID、Spring 六段 Cron `0 0 0 * * *`、`Asia/Shanghai` 和前缀标题；使用唯一 `Idempotency-Key`。

- [ ] **步骤 2：创建 Project 预算。** 请求 `PUT /api/v1/usage/budgets/<固定 UUID>`，设置 `USD`、30 天周期、软阈值 `100`、硬阈值 `200`、7 天预测窗口和 `ACTIVE`。

- [ ] **步骤 3：写入 Project 告警规则。** 对 `FAILURE_RATE`、`AVERAGE_LATENCY`、`COST` 分别请求 `PUT /api/v1/dashboard/alert-rules/<rule>`，首次版本使用 `expectedVersion=0`，并保留接口产生的审计记录。

- [ ] **步骤 4：补齐身份管理展示。** 查询现有组织和内部用户；如果集成列表为空，通过管理 API 创建前缀集成、测试凭据元数据和组织成员关系，凭据只写 `credentialRef`，不写明文 Secret。

### 任务 3：补齐无创建 API 的只读投影

**文件：** 无仓库文件变更；通过 L5 PostgreSQL `psql` 幂等写入。

- [ ] **步骤 1：写入审计事件。** 在 `operation_audit_events` 插入 2 条带前缀的 `CONSOLE_FIXTURE_CREATED` 记录，属性包含 tenant、project 和 fixture 前缀。

- [ ] **步骤 2：写入 Memory 元数据。** 在 `memories` 插入 1 条 `PROJECT_SHARED`、`CONFIRMED`、`NORMAL` 记录，`project_id` 使用 Project UUID，`content_ref` 使用前缀引用，保留正常的租户和组织字段。

- [ ] **步骤 3：写入 Sandbox 元数据。** 先读取已有任务和 `task_attempts` 的有效 ID，再在 `task_sandboxes` 插入 1 条 `DESTROYED` 或 `FAILED` 记录；`attempt_id` 必须使用未被占用的真实 Attempt，禁止伪造外键。

- [ ] **步骤 4：写入告警事件。** 在 `dashboard_alert_events` 插入 1 条前缀指纹的 `SENT` 事件，关联 `tenant-a/project UUID` 和已存在的告警规则。

- [ ] **步骤 5：创建评测 Suite/Case。** 在 `evaluation_suites` 和 `evaluation_cases` 插入 1 条前缀 Suite/Case，断言使用已成功任务的 `phase=SUCCEEDED`；随后通过 `POST /api/v1/evaluations/runs` 使用已成功任务和 Run 生成正式 Evaluation Run、Result 和 Report。

### 任务 4：逐页面接口回归

**文件：** 无仓库文件变更；只读 L5 API 验证。

- [ ] **步骤 1：验证项目菜单。** 对 `/overview`、`/tasks`、`/scheduled-tasks`、`/teams`、`/workers`、`/conversations`、`/templates`、`/agentspecs`、`/models`、`/mcp`、`/skills`、`/usage`、`/budgets`、`/alerts`、`/audit`、`/memory`、`/sandboxes`、`/artifacts` 和 `/evaluations` 对应接口请求。

- [ ] **步骤 2：验证设置菜单。** 请求 `/api/v1/management/users`、`/api/v1/management/organizations`、组织 tenants、integrations、credentials、memberships、projects、project members 和 authorization roles。

- [ ] **步骤 3：检查每个接口。** 状态必须为 200；列表页面至少返回 1 条已有记录或本次前缀夹具；Evaluation 详情和 Task/Conversation 详情使用真实 ID。

- [ ] **步骤 4：保存证据。** 输出脱敏的模块、HTTP 状态、数量和夹具 ID 清单，不输出 Token、密码或 Secret 引用值。

### 任务 5：部署健康和可清理性检查

**文件：** 无仓库文件变更；只读 L5 Kubernetes 和数据库检查。

- [ ] **步骤 1：确认服务健康。** 检查 Console、Control Plane、Manager、Worker 和 PostgreSQL Deployment/StatefulSet 为 Ready。

- [ ] **步骤 2：确认前缀边界。** 查询本次创建记录，只返回 `l5-console-fixture-20260928` 前缀或明确记录的固定 UUID；确认没有修改历史任务、模型 Provider、MCP Server 和已有 Worker。

- [ ] **步骤 3：记录清理命令。** 生成按前缀和固定 ID 定向删除的 SQL/API 清单，但本次不执行清理。

## 验证命令

```bash
git diff --check
python3 -m unittest discover -s scripts -p 'test_*.py'
```

L5 回归以接口返回的 HTTP 状态和 JSON 数量为准；如果任一写入接口返回 4xx/5xx，停止后续相关模块写入并保留响应摘要。
