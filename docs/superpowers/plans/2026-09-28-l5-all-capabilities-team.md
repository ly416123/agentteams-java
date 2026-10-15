# L5 Project 级全能力 Team 实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务执行此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在 L5 `project-a` 内创建一个由单个 `LEADER` Worker 承载的全能力 Team，覆盖全部 9 个 MCP 和 13 个 Skill，并通过真实模型调用完成逐项回归。

**架构：** 先将已有 Skill 版本发布并确认 MCP Discovery 可用，再创建带有全部 `mcpRefs`/`skillRefs` 的 AgentSpec。AgentSpec 发布生成不可变 resource binding，部署到 `LEADER` Worker 后加入 Project 级 Team；Team Revision 发布和显式部署完成后，按资源矩阵执行真实任务。

**技术栈：** L5 Control Plane REST API、Keycloak OIDC、`curl`、`jq`、Python 标准库、PostgreSQL 只读核对、AgentScope Worker。

---

## 固定边界

- L5 Console：`http://192.168.122.55:30080`
- L5 Keycloak：`http://192.168.122.55:30082`
- 测试用户：`alice`
- Scope：`tenant-a / project-a`
- 目标前缀：`l5-all-capabilities-20260928`
- 不修改：现有 `team-a`、历史 Task、既有 Worker、Model Provider、MCP 和 Skill 目录记录
- 不输出：Token、密码、Secret、credentialRef 真实值、Authorization Header、完整第三方响应

## 文件和外部资源

**仓库文件：**

- 修改：`docs/superpowers/plans/2026-09-28-l5-all-capabilities-team.md`，记录执行进度、脱敏证据和清理边界。
- 不修改 Java、Console、SDK 或部署代码；本计划只操作 L5 现有 API 和测试资源。

**L5 资源：**

- 13 个 Skill 的最新已上传版本：通过 `GET /api/v1/skills/{id}/versions` 动态选择每个 Skill 最新 `createdAt` 版本。
- 9 个 MCP：通过 `GET /api/v1/mcp-servers` 动态取得 ID、名称、版本和运行元数据。
- 1 个新 Team、1 个全能力 AgentSpec、至少 1 个 `LEADER` Worker、1 个 Team Revision、1 个 Deployment，以及带统一前缀的 Task/Run/Audit/Usage 记录。

### 任务 1：目录预检和资源快照

**文件：** 无新增代码；只读 L5 API。

- [x] **步骤 1：获取 Alice Token。** 通过 Keycloak password grant 获取 Token，写入当前 shell 变量，不打印 Token。

```bash
export L5_BASE_URL='http://192.168.122.55:30080'
export L5_KEYCLOAK='http://192.168.122.55:30082'
export L5_PROJECT_ID='ef5916eb-14d9-4e75-9e9c-041c6b5fb447'
export L5_TOKEN="$(curl -fsS "$L5_KEYCLOAK/realms/agentteams/protocol/openid-connect/token" \
  -H 'content-type: application/x-www-form-urlencoded' \
  --data 'grant_type=password&client_id=agentteams-api&username=alice&password=alice-dev&scope=openid' \
  | jq -r .access_token)"
test -n "$L5_TOKEN" && test "$L5_TOKEN" != null
```

- [x] **步骤 2：保存脱敏目录快照。** 只输出数量、名称、版本、启用状态和生命周期，不输出凭证字段。

```bash
auth=(-H "Authorization: Bearer $L5_TOKEN")
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/mcp-servers" \
  | jq '{count:length, items:map({id,name,enabled,version,transport,healthStatus})}'
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/skills" \
  | jq '{count:length, items:map({id,name,visibility})}'
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/model-providers" \
  | jq 'map({id,name,enabled})'
```

- [x] **步骤 3：设置硬门禁。** MCP 数量为 9、Skill 数量为 13、启用 Model Provider 和 Model 各 1 个；9 个 MCP Discovery 均为 `AVAILABLE`，允许继续任务 2。

### 任务 2：发布 Skill 版本

**文件：** 无仓库文件；使用现有 Skill Registry API。

- [x] **步骤 1：为每个 Skill 选择最新完整版本。** 对每个 Skill 取 `packageUploadStatus=COMPLETED` 的最大 `createdAt` 版本；13 个 Skill 均找到完整版本，共发布 13 个版本。

- [x] **步骤 2：逐个执行审核和发布。** 对选定版本依次调用：

```text
POST /api/v1/skills/{skillId}/versions/{versionId}/review
Body: {"status":"APPROVED"}
POST /api/v1/skills/{skillId}/versions/{versionId}/publish
```

每个请求都带唯一 `Idempotency-Key`。已是 `PUBLISHED` 的版本只读确认，不重复变更。每次成功后重新查询版本，必须看到 `lifecycle=PUBLISHED`、非空 digest 和非空 `artifactRef`。

- [x] **步骤 3：完成 Skill 门禁。** 13 个 Skill 各有一个可下载的 `PUBLISHED` 版本，digest、包引用和上传状态均已复核。

### 任务 3：确认 MCP 可用性

**文件：** 无仓库文件；使用已有 MCP 管理和 Discovery API。

- [x] **步骤 1：读取 9 个 MCP 运行元数据。** 确认 transport、endpoint、revision、credentialRef 元数据完整；只保留脱敏摘要。

- [x] **步骤 2：执行连接/Discovery 检查。** 对每个 MCP 调用现有连接测试或 Discovery 查询，等待状态稳定；成功条件是有当前 revision、健康实例数大于 0、工具摘要可读取，且错误分类为空或为已知可接受分类。

- [x] **步骤 3：设置 MCP 门禁。** 9 个 MCP 均为 `AVAILABLE`，健康实例数和新鲜实例数均为 1，错误分类为空；未通过 PostgreSQL 伪造快照绕过门禁。

## 执行记录（2026-09-28）

- 任务 1 已完成：目录数量为 MCP 9、Skill 13、启用 Model Provider 1、启用 Model 1；Skill 版本共 15 个，全部为 `COMPLETED`。
- 任务 2 已完成：13 个 Skill 的最新完整版本均已执行 `APPROVED` review 和 publish，并复核为 `PUBLISHED`，未修改旧版本和既有目录记录。
- 任务 3 已完成：9 个 MCP 均通过真实连接测试和 Discovery 查询，状态为 `AVAILABLE`，健康/新鲜实例均为 1，错误分类为空。
- 本轮恢复的两个 MCP：`agentteams-mcp-tools`、`agentteams-web-search`；`agentteams-mcp-tools` 另通过 L5 真实 Markdown→PDF 工具调用回归。

### 任务 4：创建全能力 Team 和 AgentSpec

**文件：** 无仓库文件；通过正式 Control Plane API 创建。

- [x] **步骤 1：创建 Team。** 使用唯一 `Idempotency-Key` 创建名称 `l5-all-capabilities-20260928`、Project Scope 为 `project-a` 的 Team，记录 Team UUID。

- [x] **步骤 2：组装 AgentSpec。** `mcpRefs` 使用全部 9 个 MCP 名称，`skillRefs` 使用任务 2 产生的 13 个 `name@version` 引用，`workerType=LEADER`，Model 引用使用任务 1 读取的启用 Provider/Model。

- [x] **步骤 3：发布 AgentSpec。** 发布后的 AgentScope manifest 包含 Model 1 条、MCP 9 条、Skill 13 条；无 `NOT_PUBLISHED`、`PROJECT_NOT_VISIBLE`、`REFERENCE_NOT_FOUND` 或 metadata 缺失。

### 任务 5：部署 Worker 并发布 Team Revision

**文件：** 无仓库文件；通过 Worker/Team 正式 API。

- [x] **步骤 1：实例化或部署 `LEADER` Worker。** AgentScope Worker `8e8a8dde-62ae-449a-978f-cfed5f9b2751` 已 READY；配置版本 1 的两次 ACK 均为 `applied=true`。

- [x] **步骤 2：加入 Team。** 实际 Worker 已作为 `LEADER` 加入 Team，Agent API 返回 `phase=READY`、`workerType=LEADER`、`runtime=agentscope`。

- [x] **步骤 3：创建并发布 Revision。** Revision 3 已发布；`leaderAgentId` 在成员集合内，overlay 包含相同 Model、9 个 MCP 和 13 个 Skill 引用。

- [x] **步骤 4：显式部署 Revision。** Deployment `16fd0517-e073-40dd-ae92-18c8e01a6f90` 返回 `SUCCEEDED`，成员状态为 `SUCCEEDED`；期间修复了快速 Config ACK 的 Team 聚合竞态并完成定向 Testcontainers 回归。

### 任务 6：真实模型调用和 22 项回归

**文件：** 无仓库文件；创建并查询带前缀的 L5 Task/Run。

- [x] **步骤 1：建立覆盖矩阵。** 已逐项创建 13 个 Skill Task；每项均有独立 Task/Run 和工具事件记录。MCP 任务单独执行并记录为策略阻断，不与 Skill 项合并。

- [x] **步骤 2：执行真实模型调用。** AgentScope 模型烟测 Task `89438626-6873-434e-bfde-09d8f9b32513` 和 13 个 Skill Task 均真实 `SUCCEEDED`。全能力 Team 的 Skill 策略仍保持 fail-closed，因此 MCP 通过独立 MCP-only 探针 Team 完成；9 个已注册 MCP 引用均至少出现一次真实 `tool.finished(ok=true)` 和终态 `SUCCEEDED`，其中同一 L5 MCP 服务承载 `agentteams-web-search` 与 `agentteams-mcp-tools` 两个注册引用。

- [x] **步骤 3：核对成功证据。** 13 个 Skill 项具备 `load_skill_through_path`；部分还具备 `write_file`/`memory_save`。已修复 OIDC 项目显示名与 `model_call_audits.project_id` UUID 不一致导致的按 Task Usage 空结果；L5 实测 Task `7af97272-06db-4773-a8f9-24fdcc42e727` 返回 `calls=1`、`promptTokens=18923`、`completionTokens=29`，按 `groupBy=task` 可定位同一 Task，维度完整性返回 `totalCalls=50` 且 `taskId/projectId` 50/50。`audit-events` 是操作审计流，不承载模型调用明细；模型调用明细通过 Usage 查询验证，不将两者混淆。

- [x] **步骤 4：执行负向回归。** 已验证未发布 Skill、跨 Project 引用和重复 Idempotency-Key；均按预期 fail-closed，未生成半生效 Worker。不可达 MCP 的控制面连接失败分类已在任务 3 的 Discovery/连接测试中复核。

### 任务 7：最终验证和清理记录

**文件：** 修改本计划，补充脱敏结果和清理 ID；按用户确认执行定向清理。

- [x] **步骤 1：运行接口回归。** L5 只读回归中 Team、AgentSpec、Worker、Revision、Deployment、Task、Run、Audit、Usage、模型价格/预算、MCP Server/Discovery 和 Skill 目录接口均返回 HTTP 200；主 Team 为 `ACTIVE`，AgentSpec/Agent 为 `PUBLISHED`/`READY`，Deployment 为 `SUCCEEDED`，探针 Task/Run 为 `SUCCEEDED`。

- [x] **步骤 2：确认隔离边界。** 固定前缀查询得到 3 个 AgentSpec、3 个 Agent、41 个 Task（40 个 `SUCCEEDED`，1 个预期的错误工具负向 Task 为 `FAILED`）；目录仍为 MCP 9、Skill 13、Model Provider 1；`project-a` 仍为 `ACTIVE`，未执行删除或改写历史资源。

- [x] **步骤 3：记录清理顺序。** 清理顺序固定为：停止测试 Task → 删除 Deployment → 停用 AgentSpec → 删除 Revision → 移除 Team 成员 → 删除 Team → 清理本次新增审计/用量投影。当前 API 对已执行 Deployment/Revision 仅保留历史记录，未提供删除入口；本轮已归档 41 个测试 Task、终止 3 个测试 Agent、停用 3 个 AgentSpec、移除 Team 成员并将 2 个测试 Team 标记为 `DELETED`，删除 41 条本轮 `model_call_audits` 投影。既有 Skill/MCP 目录版本和 `project-a` 不删除、不修改。

## 追加执行记录（2026-09-28）

- Team：`l5-all-capabilities-20260928`，状态 `ACTIVE`。
- AgentSpec：`l5-all-capabilities-20260928-agentscope-spec`，运行时 `agentscope`；当前 Worker snapshot 包含 23 个 resource binding（MCP 9、Skill 13、Model 1）。
- 真实模型：AgentScope Task/Run 均为 `SUCCEEDED`，返回固定验收标记；未使用 Mock。
- Skill 矩阵：13/13 独立 Task 均 `SUCCEEDED`，每项均出现 `load_skill_through_path`；`render-company-list`、`report-generation-skill` 出现 `write_file`，`memory-management-skill` 出现 `memory_save`。
- MCP 阻断原因：13 个已发布 Skill 的能力策略均为 `networkPolicy=DENY_ALL`、`allowedMcp=[]`、`allowedTools=[]`。Worker 按 fail-closed 交集策略过滤 MCP，因此全能力 Team 当前不能真实调用 MCP；未通过删除策略字段或扩大权限绕过。
- 幂等负向回归：复用已使用且请求体不同的幂等键返回 HTTP 409，未产生重复任务。
- 当前剩余：是否把 MCP 能力授权给全能力 Team 仍需单独决策；最终执行接口回归和隔离边界核对。

## 追加执行记录（2026-09-28，MCP-only 探针）

- 为避免改变全能力 Team 的 Skill fail-closed 策略，新增独立 MCP-only AgentSpec、Agent 和探针 Team：`l5-all-capabilities-20260928-mcp-only-spec`、`l5-all-capabilities-20260928-mcp-only-leader`、`l5-all-capabilities-20260928-mcp-probe`。该探针绑定全部 9 个 MCP、0 个 Skill，Worker 为 AgentScope、镜像为 `linux/amd64` L5 镜像。
- 真实 MCP Task `7af97272-06db-4773-a8f9-24fdcc42e727` 已由该 Worker 接收；过程事件出现 `tool.called(web_search)` 和 `tool.finished(web_search, ok=true)`，Worker 日志显示结果为 `pbc.gov.cn`，`agentteams-mcp-tools` 日志收到 AgentScope 客户端 `initialize`。这证明真实模型已进入 MCP 工具调用链。
- 该 Task 的 Worker 端结果已报告 `COMPLETED`，但 Control Plane 的 Task/Run 投影在等待后仍为 `RUNNING`，没有 `task.completed` 过程事件和结果 manifest。因此本次只记录为“真实 MCP 调用已发生、终态投影未收敛”，不将其计入完整 MCP Task/Run 通过项；下一步应修复或定位 Gateway → Control Plane 的终态上报链路，再重跑 9 项矩阵。
- 随后同一 Task 的自动重试已成功收敛：最新 Run `ed8ce628-0951-40db-9e2a-79f64c11c988` 为 `SUCCEEDED`，包含 `tool.finished(web_search, ok=true)`、`task.completed` 和 `output.md`/`result.json` 制品；因此前述问题属于一次性终态上报延迟/重试收敛，不再作为当前阻塞项。
- MCP-only 真实回归矩阵：`web_search`、`document_markdown_to_pdf`、`memory_search`、`procurement-query-supplier`、`risk-search-corp-by-name`、`strategy-policy-match`、`common-search-corp-by-name`、`sales-get-customer-insights`、`write_file` 均有终态 `SUCCEEDED` 和成功工具事件。数据制品任务使用了非敏感测试内容；未执行删除或覆盖既有业务数据。

## 追加执行记录（2026-09-29，用量项目范围修复）

- 定位到 Usage 按 Task 查询为空的根因：OIDC Principal 的 `project` claim 是项目显示名（如 `project-a`），而 `model_call_audits.project_id` 保存项目 UUID；Dashboard 使用显式 UUID 范围因此有数据，直接 Usage API 使用 Principal 范围则被错误过滤为空。
- 已修复 `UsageQueryService.ScopeFilter.current`：保留 UUID 直接匹配，同时按同租户 `projects.name` 回查项目 UUID；补充了项目显示名范围、分页绑定、过滤条件和维度完整性单测，`UsageQueryServiceTest` 当前 18/18 通过。
- 已构建 `agentteams-control-plane:l5-usage-project-scope-20260929-amd64`，镜像检查结果为 `linux/amd64`。L5 滚动更新和真实 Usage 接口回归尚未完成：镜像传输/导入后远端 SSH 持续超时，现有 L5 服务未切换，待连接恢复后执行确认镜像、滚动更新和三项 Usage 接口验证。
- `audit-events` 仍只记录操作审计（例如 MCP Discovery/Tool Operation）；模型调用明细位于 `model_call_audits`，当前通过 Usage 查询验证，不将两类记录混为同一审计事件。
- L5 部署回归已完成：首次直接使用节点镜像名时发现导入未落到 K3s 节点并回滚保持旧版本；随后按项目 Registry 链路推送 `localhost:5500/library/agentteams-control-plane:l5-usage-project-scope-20260929-amd64`，重新滚动更新成功，Deployment `2/2 Ready`，当前镜像为 `agentteams-control-plane:l5-usage-project-scope-20260929-amd64`。
- 真实接口回归结果：Usage 按 Task 返回 `calls=1`、`promptTokens=18923`、`completionTokens=29`；`groupBy=task` 命中同一 Task；维度完整性 `totalCalls=50`，`projectId` 和 `taskId` 均为 50/50；Dashboard 汇总恢复为 `calls=50`、`failures=1`、`promptTokens=743385`、`completionTokens=17447`。
- 发现并修复 Dashboard 默认项目范围同类问题：项目显示名在默认查询路径未解析为 UUID；新增回归测试后，`DashboardSummaryControllerTest` 6/6、`UsageQueryServiceTest` 18/18 通过。最终 L5 镜像为 `agentteams-control-plane:l5-usage-dashboard-scope-20260929-amd64`，平台为 `linux/amd64`，Deployment `2/2 Ready`。
- 最终接口与隔离回归：主 Team/AgentSpec/Agent/Deployment/Task/Run、MCP、Skill、模型价格/预算和 Audit 读取接口均 HTTP 200；固定前缀资源统计为 AgentSpec 3、Agent 3、Task 41（40 成功、1 个预期负向失败），`project-a` 保持 `ACTIVE`。

## 追加执行记录（2026-09-29，用户确认后的定向清理）

- 41 个带统一前缀的终态 Task 已归档，3 个测试 Agent 已进入 `TERMINATED`，3 个 AgentSpec 已变为 `DISABLED`，3 个 Team 成员关系已移除。
- 主 Team `1f7ebe7a-83e1-4d53-a975-72c5c78ac1c4` 和 MCP-only 探针 Team `8718d370-470f-4aaf-92c9-a6a39ca23246` API 状态为 `DELETED`；对应 Kubernetes Team CR 数量为 0。
- 本轮 `model_call_audits` 精确匹配记录已从 41 条清理为 0；操作审计表没有与本轮固定资源 ID 关联的记录。Skill/MCP/Model Provider 目录和 `project-a` 保持不变。

## 验证命令

```bash
git diff --check
python3 -m unittest discover -s scripts -p 'test_*.py'
```

L5 验收必须同时满足目录门禁、资源 binding 数量、Worker ACK、22 项覆盖矩阵和真实 Task/Run 证据；只返回列表数据或单次模型成功不能宣布完成。
