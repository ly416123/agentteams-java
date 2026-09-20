# Worker 启动配置快照恢复设计

## 背景

Worker 当前只在 Gateway 推送 `ConfigChanged` 时加载配置。Worker 重启后不会主动获取已发布的当前配置，导致 AgentScope 等运行时可能在重新注册后仍处于无 MCP 配置状态，必须重新发布一份 AgentSpec 才能恢复。

## 目标

Worker 启动时自动恢复 Control Plane 为该 Agent 保存的全部当前期望配置绑定，并复用现有配置变更路径完成校验、文件暂存和运行时激活。恢复失败不应阻止 Worker 建立 Gateway 长连接，实时配置事件仍是后续修复通道。

## 非目标

- 不改变配置发布、绑定版本、回滚和应用确认语义。
- 不在 Worker 本地持久化新的配置权威状态。
- 不返回或复制任何模型、MCP 或其他凭据值。
- 不引入配置事件重放队列或修改 Gateway 事件协议。

## 架构与数据流

### Control Plane

新增只读接口：

`GET /api/v1/config/agents/{agentId}/current`

接口从 `config_bindings` 查询该 Agent 的全部当前绑定，关联对应的 `config_snapshots` 和已完成的 `config_files`，返回一个绑定数组。每个绑定包含：

- `bindingId`、`agentId`、`snapshotId`、`configVersion`、`rollback=false`
- `manifestUri`、`manifestSha256`、`sizeBytes`
- `manifestJson`（仅在现有内联大小限制内返回）
- `files[]`：路径、URN、摘要、大小、内容类型

快照内容继续由现有 `/api/v1/config/snapshots/{snapshotId}/manifest` 和 `/files/content` 提供。接口只返回引用、元数据和当前快照中已完成文件，不暴露 Secret 内容。若启用 API 鉴权，沿用现有 `PrincipalContext` 作用域检查；内部 Worker 调用保持当前配置下载接口的调用边界。

### Worker

新增 `ConfigBootstrapFetcher`，使用现有配置基础 URL 和超时/大小限制调用上述接口，严格校验 HTTP 状态、响应大小、Agent ID、UUID、摘要字段和文件元数据，将 JSON 转换为 `ConfigChanged` 列表。

`QwenPawWorker.start()` 在 Gateway 连接前执行一次启动拉取：

1. 请求当前绑定列表。
2. 对每个绑定构造无序列号的 `ConfigChanged`。
3. 调用现有 `onConfigChanged`，复用 manifest 校验、资源绑定校验、文件下载、运行时应用和版本协调。
4. 启动拉取不发送 ACK；实时 `ConfigChanged` 仍按原逻辑 ACK。

如果接口返回空数组，Worker 正常启动。如果请求、解析或应用失败，记录绑定标识、快照标识、版本和脱敏错误摘要，继续建立 Gateway 连接。

## 幂等与并发

启动拉取在 Gateway 连接前串行执行，避免与实时配置事件并发。已有 `RuntimeConfigCoordinator` 按 binding 管理版本和回滚语义，因此重复启动、重连或随后到达的相同事件不会绕过现有版本检查。启动拉取不产生新的 Control Plane 应用记录。

## 错误处理与安全

- HTTP 非 2xx、响应超限、JSON 缺字段、Agent ID 不匹配、非法 UUID、非法摘要或文件路径均视为该绑定恢复失败。
- 日志禁止输出 manifest 内容、文件内容、Authorization、MCP token、模型密钥和其他凭据；只记录标识、版本和 `rootMessage` 脱敏摘要。
- Control Plane 接口不增加凭据读取能力；Worker 仍通过现有受限配置下载接口获取文件。
- 启动恢复失败不改变 Worker 的健康状态和 Gateway 注册能力，避免短暂 Control Plane 故障造成全量 Worker 重启风暴。

## 测试策略

1. **Control Plane 仓储/接口**：验证按 Agent 返回全部绑定、只包含已完成文件、空结果和不存在 Agent 的空数组；验证作用域检查仍生效。
2. **Worker Fetcher**：验证成功响应转换为 `ConfigChanged`，验证非 2xx、超限、缺字段、Agent ID 不匹配和非法文件元数据均拒绝。
3. **Worker 启动**：验证启动拉取失败仍调用 Gateway 连接；验证成功绑定进入现有 `onConfigChanged` 路径且不发送 ACK。
4. **回归**：运行 Worker/Control Plane 相关 Maven 测试和协议契约测试。
5. **L5 验收**：部署新镜像，重启一个 AgentScope Worker，在不重新发布 AgentSpec 的情况下执行 `web_search` 任务，确认任务成功并保留 MCP 服务端 initialize 日志。
