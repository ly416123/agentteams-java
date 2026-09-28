# L5 Project 级全能力 Team 设计

## 1. 文档信息

- **日期：** 2026-09-28
- **状态：** 待用户评审
- **适用环境：** L5，`project-a`
- **已确认方案：** 方案 A：单个全能力 Worker
- **范围：** Project 级 Team，不扩展为组织级跨 Project Team

## 2. 目标

在 L5 的 `project-a` 内创建一个可真实运行的 Team，由单个 `LEADER` Worker 承载当前目录中的全部 MCP 和 Skill：

- 9 个已登记 MCP；
- 13 个已登记 Skill；
- 通过一个 AgentSpec 的 `mcpRefs` 和 `skillRefs` 生成不可变 runtime binding；
- 通过真实模型调用逐项验证 MCP/Skill 的加载、执行、审计和用量记录。

本方案只创建测试用途资源，所有名称使用统一前缀 `l5-all-capabilities-20260928`，便于幂等重试和定向清理。

## 3. 当前 L5 前置状态

只读检查得到以下结果：

| 资源 | 当前状态 | 对实施的影响 |
| --- | --- | --- |
| MCP | 9 个记录均为 `enabled=true` | 仍需逐个完成 Discovery/连通性检查；当前均为 `UNKNOWN`，健康实例数为 0 |
| Skill | 13 个 Skill，包上传状态均为 `COMPLETED` | 所有版本仍为 `DRAFT`，必须先完成 Review/Publish，生成 `artifactRef` |
| AgentSpec | 当前有可见记录 | 新建全能力 AgentSpec 时必须使用可用的 Model Provider/Model |
| Team | 当前已有 `team-a` | 新建独立测试 Team，避免修改现有 Team |
| 引用上限 | 每类引用最多 100 个 | 9 个 MCP + 13 个 Skill 共 22 个引用，满足上限 |

在 Skill 发布和 MCP 可用性达到验收条件前，不创建最终 AgentSpec 或执行真实功能测试。

## 4. 领域与资源模型

### 4.1 Team 范围

Team 使用现有 Project Scope：`tenant-a / project-a`。Team 不能跨 Project 读取或绑定资源，也不新增组织级 Team 模型。

### 4.2 能力绑定

Team 不直接把 MCP/Skill 作为独立成员挂载。实际链路为：

```text
MCP/Skill Registry
        ↓ 发布与可见性校验
全能力 AgentSpec（mcpRefs + skillRefs）
        ↓ publish / resourceBindings
LEADER Worker 配置快照
        ↓ Team 成员与 Revision
真实模型调用
```

AgentSpec 的 `mcpRefs` 使用 MCP 名称，`skillRefs` 使用 `name@version`；Control Plane 在发布阶段解析可见性、生命周期、版本、digest 和运行时元数据。Worker 只消费不可变 binding，不直接查询 Registry。

### 4.3 Worker 和 Team Revision

- 创建一个 `LEADER` 类型 AgentSpec，并实例化或部署为一个真实可用的 `LEADER` Worker；
- 将该 Worker 作为测试 Team 的 Leader 和成员；
- Team Revision 的 overlay 保留完整的 `mcpRefs`、`skillRefs` 和模型引用；
- 发布 Revision 后显式部署，不依赖发布动作自动启动 Worker；
- 发布和部署必须携带唯一 `Idempotency-Key`，并记录审计事件。

## 5. 实施流程

### 阶段 1：准备目录

1. 读取 L5 当前 9 个 MCP 和 13 个 Skill 的稳定 ID、名称、版本和 digest。
2. 对每个 Skill 执行已有的包审核/发布流程，不重新上传已有完整包。
3. 对每个 MCP 执行 Discovery/连接检查；确认 transport、endpoint、credentialRef 元数据完整，禁止把 Secret 或 Authorization Header 写入 manifest。
4. 选择当前可用的 Model Provider/Model，先执行最小真实模型调用验证。

阶段 1 任一资源失败时停止后续发布，保留脱敏错误分类，不创建部分可用的最终 Team。

### 阶段 2：创建 Team 和 AgentSpec

1. 创建名称为 `l5-all-capabilities-20260928` 的 Project 级 Team。
2. 设置测试策略：有限并发、允许 `qwenpaw` Runtime、关闭不必要的人审阻塞。
3. 创建全能力 AgentSpec：

```json
{
  "workerType": "LEADER",
  "spec": {
    "modelRef": {"provider": "provider-from-l5-catalog", "model": "model-from-l5-catalog"},
    "mcpRefs": ["mcp-name-01", "...共 9 个 L5 MCP 名称..."],
    "skillRefs": ["skill-name-01@published-version", "...共 13 个已发布 Skill 引用..."]
  }
}
```

4. 发布 AgentSpec，确认生成的 resource binding 数量为 Model 1 条、MCP 9 条、Skill 13 条。
5. 实例化或部署 AgentSpec，等待 Worker Ready 和配置 ACK。

### 阶段 3：发布 Team Revision

1. 将实际 Worker 加入 Team，并赋予 `LEADER` 关系。
2. 创建 Draft Revision，Leader 必须同时出现在成员列表中。
3. 发布 Revision，检查所有引用仍处于 `PUBLISHED`、当前 Project 可见且 digest 未变化。
4. 显式部署 Revision 到该 Worker。
5. 只有当全部资源 ACK 为 `APPLIED` 时，才进入真实调用测试。

## 6. 真实功能测试

### 6.1 覆盖矩阵

为每个 MCP 和每个 Skill 建立独立测试项，不能只用一个成功模型调用推断全部能力已生效。

每个测试项至少记录：

- 资源名称、版本或 revision、binding digest；
- 触发任务 ID、Attempt/Run ID 和 Worker ID；
- 模型调用是否成功；
- MCP 工具或 Skill 是否实际被调用；
- 资源 ACK 状态和失败分类；
- 审计事件、用量和成本记录是否关联到 Project/Team。

### 6.2 成功路径

1. 创建一个使用测试 Team 的真实任务，要求模型按测试矩阵调用指定 MCP 或 Skill。
2. 等待任务进入 `SUCCEEDED`，确认事件流正常结束而不是断线重连。
3. 查询 Task、Run、Worker 配置、resource apply、Audit、Usage 和 Cost 记录。
4. 检查响应中不出现 credentialRef 的 Secret 内容、Authorization Header、完整 endpoint 凭证或未脱敏的第三方响应。

### 6.3 失败路径

至少验证以下场景：

- 一个 MCP 不可达：配置应 fail-closed，分类为 `MCP_RUNTIME_UNAVAILABLE` 或现有稳定分类，不得部分启用并静默成功；
- 一个 Skill 未发布：AgentSpec/Revision 发布应拒绝，分类为 `LIFECYCLE_NOT_AVAILABLE` 或对应稳定错误；
- 跨 Project 引用资源：发布应拒绝并返回 Project Scope 错误；
- 重复提交同一个 Idempotency-Key：不得创建重复 Team、AgentSpec、Revision 或部署。

## 7. 验收标准

### 目录和发布

- 9 个 MCP 均完成可用性检查，不能以 `UNKNOWN` 作为成功条件；
- 13 个 Skill 均有 `PUBLISHED` 版本、digest 和可下载 `artifactRef`；
- AgentSpec 发布成功，并生成 9 条 MCP binding 和 13 条 Skill binding；
- Worker 配置 ACK 全部为 `APPLIED`。

### Team 和运行时

- 新 Team 属于 `project-a`，不修改既有 `team-a`；
- Team 有一个真实 `LEADER` Worker，Revision 发布和显式部署成功；
- 真实模型调用至少成功完成一条任务；
- 22 个能力均有独立测试结果，不以列表存在代替实际调用；
- Task、Audit、Usage、Cost 和资源状态可以通过真实 ID 关联查询；
- 失败场景 fail-closed，不能生成半生效配置。

### 安全与可清理性

- 不把 Secret 写入 AgentSpec、Team overlay、配置快照、日志或测试报告；
- 测试资源均带 `l5-all-capabilities-20260928` 前缀或固定 UUID；
- 清理时按依赖顺序定向删除，不按 Project、Tenant 或全表删除；
- 现有 `team-a`、历史任务、既有 Worker、Model Provider、MCP 和 Skill 不被修改。

## 8. 风险与取舍

- **单 Worker 故障半径较大：** 方案 A 适合 L5 全量验收，不建议直接作为生产 Team 拓扑。
- **能力数量较多：** 22 个引用在 Schema 上允许，但可能带来工具名称冲突、上下文膨胀和启动时间增加；测试必须记录每个能力的独立结果。
- **MCP 真实可用性依赖外部服务：** `enabled=true` 只表示目录启用，不代表 endpoint、凭证和工具发现可用。
- **Skill 发布是硬前置：** `COMPLETED` 只代表包上传完成，不代表 Worker 可以下载和注册。
- **Team 级运行时绑定仍需真实 L5 验收：** 当前项目已有 AgentSpec/Worker binding 基础，但 Team Revision 的完整运行时生命周期需要通过本次部署验证。

## 9. 清理策略

本轮创建的 Team、AgentSpec、Worker、Revision、Deployment、Task、Run、Audit、Usage 和 Cost 记录均使用固定前缀或固定 ID 管理。清理顺序为：停止或删除测试任务与部署、停用 AgentSpec、删除 Team Revision、移除 Team 成员、删除 Team，最后清理本次新增的 Skill 发布状态和 MCP 测试投影；不删除已有目录资源和历史资源。
