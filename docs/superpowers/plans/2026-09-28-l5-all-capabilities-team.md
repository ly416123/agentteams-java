# L5 Project 级全能力 Team 实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务执行此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在 L5 `project-a` 内创建一个由单个 `LEADER` Worker 承载的全能力 Team，覆盖全部 9 个 MCP 和 13 个 Skill，并通过真实模型调用完成逐项回归。

**架构：** 先将已有 Skill 版本发布并确认 MCP Discovery 可用，再创建带有全部 `mcpRefs`/`skillRefs` 的 AgentSpec。AgentSpec 发布生成不可变 resource binding，部署到 `LEADER` Worker 后加入 Project 级 Team；Team Revision 发布和显式部署完成后，按资源矩阵执行真实任务。

**技术栈：** L5 Control Plane REST API、Keycloak OIDC、`curl`、`jq`、Python 标准库、PostgreSQL 只读核对、QwenPaw Worker。

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

- [ ] **步骤 1：获取 Alice Token。** 通过 Keycloak password grant 获取 Token，写入当前 shell 变量，不打印 Token。

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

- [ ] **步骤 2：保存脱敏目录快照。** 只输出数量、名称、版本、启用状态和生命周期，不输出凭证字段。

```bash
auth=(-H "Authorization: Bearer $L5_TOKEN")
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/mcp-servers" \
  | jq '{count:length, items:map({id,name,enabled,version,transport,healthStatus})}'
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/skills" \
  | jq '{count:length, items:map({id,name,visibility})}'
curl -fsS "${auth[@]}" "$L5_BASE_URL/api/v1/model-providers" \
  | jq 'map({id,name,enabled})'
```

- [ ] **步骤 3：设置硬门禁。** 必须满足 MCP 数量为 9、Skill 数量为 13、至少存在一个启用 Model Provider 和 Model。MCP Discovery 全部为 `AVAILABLE` 或已有明确可诊断的受支持状态后才能继续任务 2；否则停止并记录阻塞资源。

### 任务 2：发布 Skill 版本

**文件：** 无仓库文件；使用现有 Skill Registry API。

- [ ] **步骤 1：为每个 Skill 选择最新完整版本。** 对每个 Skill 取 `packageUploadStatus=COMPLETED` 的最大 `createdAt` 版本；若没有完整版本，停止，不创建 AgentSpec。

- [ ] **步骤 2：逐个执行审核和发布。** 对选定版本依次调用：

```text
POST /api/v1/skills/{skillId}/versions/{versionId}/review
Body: {"status":"APPROVED"}
POST /api/v1/skills/{skillId}/versions/{versionId}/publish
```

每个请求都带唯一 `Idempotency-Key`。已是 `PUBLISHED` 的版本只读确认，不重复变更。每次成功后重新查询版本，必须看到 `lifecycle=PUBLISHED`、非空 digest 和非空 `artifactRef`。

- [ ] **步骤 3：完成 Skill 门禁。** 13 个 Skill 必须各有一个可下载的 `PUBLISHED` 版本；否则停止后续任务，记录失败 Skill 名称和固定错误分类。

### 任务 3：确认 MCP 可用性

**文件：** 无仓库文件；使用已有 MCP 管理和 Discovery API。

- [ ] **步骤 1：读取 9 个 MCP 运行元数据。** 确认 transport、endpoint、revision、credentialRef 元数据完整；只保留脱敏摘要。

- [ ] **步骤 2：执行连接/Discovery 检查。** 对每个 MCP 调用现有连接测试或 Discovery 查询，等待状态稳定；成功条件是有当前 revision、健康实例数大于 0、工具摘要可读取，且错误分类为空或为已知可接受分类。

- [ ] **步骤 3：设置 MCP 门禁。** 任何 MCP 仍为 `UNKNOWN`、健康实例数为 0、凭证缺失或 endpoint 不可达时停止。不得通过 PostgreSQL 伪造生产 MCP Discovery 快照来绕过此门禁。

### 任务 4：创建全能力 Team 和 AgentSpec

**文件：** 无仓库文件；通过正式 Control Plane API 创建。

- [ ] **步骤 1：创建 Team。** 使用唯一 `Idempotency-Key` 创建名称 `l5-all-capabilities-20260928`、Project Scope 为 `project-a` 的 Team，设置有限并发、`qwenpaw` Runtime 和测试所需策略。记录 Team UUID。

- [ ] **步骤 2：组装 AgentSpec。** `mcpRefs` 使用全部 9 个 MCP 名称，`skillRefs` 使用任务 2 产生的 13 个 `name@version` 引用，`workerType=LEADER`，Model 引用使用任务 1 读取的启用 Provider/Model。请求必须带 `projectId=$L5_PROJECT_ID` 和唯一 `Idempotency-Key`。

- [ ] **步骤 3：发布 AgentSpec。** 发布后查询 Spec 的 resource binding 或部署 manifest，必须包含 Model 1 条、MCP 9 条、Skill 13 条；任何 `NOT_PUBLISHED`、`PROJECT_NOT_VISIBLE`、`REFERENCE_NOT_FOUND` 或 metadata 缺失都停止任务 5。

### 任务 5：部署 Worker 并发布 Team Revision

**文件：** 无仓库文件；通过 Worker/Team 正式 API。

- [ ] **步骤 1：实例化或部署 `LEADER` Worker。** 使用已发布 AgentSpec 创建或部署真实 Worker，等待 Kubernetes Workload Ready、Gateway 注册和配置 ACK。资源结果必须全部为 `APPLIED`。

- [ ] **步骤 2：加入 Team。** 将实际 Worker 作为 Team 成员并设置 `role=LEADER`。服务端必须确认 Worker 属于 `project-a` 且 Worker 类型为 `LEADER`。

- [ ] **步骤 3：创建并发布 Revision。** `leaderAgentId` 必须在 `memberAgentIds` 中；overlay 包含相同的 Model、MCP 和 Skill 引用；发布时带预期版本和唯一 `Idempotency-Key`。

- [ ] **步骤 4：显式部署 Revision。** 部署到该 Worker，确认 Team Revision、AgentSpec binding 和 Worker snapshot 的 revision/digest 一致；不依赖发布动作自动部署。

### 任务 6：真实模型调用和 22 项回归

**文件：** 无仓库文件；创建并查询带前缀的 L5 Task/Run。

- [ ] **步骤 1：建立覆盖矩阵。** 为 9 个 MCP 和 13 个 Skill 各生成一个测试项，记录资源名称、revision/version、digest、测试 Prompt、Task ID、Run ID 和结果；不把 22 项合并成一个无法判定具体覆盖的 Prompt。

- [ ] **步骤 2：执行真实模型调用。** 每项通过目标 Team 创建真实 Task，等待 `SUCCEEDED` 或稳定失败；不使用 Mock，不直接写数据库伪造成功结果。

- [ ] **步骤 3：核对成功证据。** 每个成功项必须同时具备：指定 MCP 工具或 Skill 实际调用证据、Worker `APPLIED` 资源状态、Task/Run 关联、审计事件、Usage/Cost 记录和无敏感信息泄露。

- [ ] **步骤 4：执行负向回归。** 分别验证未发布 Skill、不可达 MCP、跨 Project 引用和重复 Idempotency-Key；预期是发布或配置 fail-closed，不生成半生效 Worker。

### 任务 7：最终验证和清理记录

**文件：** 修改本计划，补充脱敏结果和清理 ID；不执行清理。

- [ ] **步骤 1：运行接口回归。** 重新验证 Team、AgentSpec、Worker、Revision、Deployment、Task、Run、Audit、Usage、Cost、MCP Discovery 和 Skill 版本接口均返回预期状态。

- [ ] **步骤 2：确认隔离边界。** 查询固定前缀和固定 ID，确认现有 `team-a`、历史资源和既有目录未被修改。

- [ ] **步骤 3：记录清理顺序。** 清理顺序固定为：停止测试 Task → 删除 Deployment → 停用 AgentSpec → 删除 Revision → 移除 Team 成员 → 删除 Team → 清理本次新增审计/用量投影。已有 Skill/MCP 目录版本不删除，只恢复到执行前生命周期（若执行前为 `DRAFT`）。

## 验证命令

```bash
git diff --check
python3 -m unittest discover -s scripts -p 'test_*.py'
```

L5 验收必须同时满足目录门禁、资源 binding 数量、Worker ACK、22 项覆盖矩阵和真实 Task/Run 证据；只返回列表数据或单次模型成功不能宣布完成。
