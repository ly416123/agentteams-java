# ConfigSnapshot 与 Artifact lifecycle 设计

**目标：** 在现有版本化配置、直传对象存储和 Agent gRPC 链路之上，完成配置文件从上传确认、事件下发、Worker 校验/暂存、应用确认到安全清理的闭环。

## 范围

本阶段覆盖 Control Plane、Gateway、Agent Worker、Runtime SPI 和 protobuf 合约；不引入新的存储系统，不把 API Key 或对象存储凭据写入事件。PostgreSQL 继续作为配置期望态和应用态的权威来源，MinIO 只保存二进制对象，Outbox/NATS 只传递可重放的配置元数据。

## 方案

1. `ConfigChanged` 增加可选的 `ConfigFile` repeated 字段，字段包含相对路径、Agent 可解析的 URI、checksum、大小和 content type。旧 Agent 忽略新增字段仍可处理仅含 manifest 的事件。
2. Control Plane 在部署配置时读取已确认的 `config_files`，把文件引用写入 Outbox payload。文件 URI 使用 AgentTeams 的 URN，不把预签名 URL 持久化到 Outbox；Worker 通过现有 Control Plane 基址读取文件内容。
3. Control Plane 增加已确认配置文件的下载接口。接口只允许读取 `COMPLETED` 文件记录，按 snapshot/path 查询数据库记录后从对象存储流式返回，避免任意对象 key 读取。
4. Worker 收到配置事件后按以下顺序处理：校验 Agent、manifest checksum/size；逐个下载并校验文件路径、checksum、size；把文件写入版本目录；构造包含 manifest 值和本地文件引用的不可变 `RuntimeConfigSnapshot`；Runtime 成功应用后发送 `ConfigApplied`，任何失败都发送带脱敏错误的失败确认。版本/校验和相同的重复事件保持幂等。
5. Runtime SPI 的配置快照增加只读文件引用。QwenPaw HTTP runtime 继续只提交其支持的值配置，不把本地路径或文件内容发送到 QwenPaw；JSON Lines runtime 收到文件引用，供自定义进程使用。这样文件传输能力不污染官方 QwenPaw HTTP 协议。
6. 增加配置保留清理：每个 subject 默认保留最近 5 个 snapshot，并始终保留当前 `config_bindings` 引用的 snapshot；只有已确认的文件和未被保留 snapshot 引用的对象才可删除。删除顺序为对象存储删除成功后删除数据库记录，失败时保留记录供下次幂等重试。

## 错误与恢复

- 上传完成前或校验失败的对象由现有过期上传清理任务删除。
- Worker 下载失败、checksum/size 不匹配或 Runtime 应用失败时，Control Plane 记录 `FAILED`，事件仍 ACK，避免坏配置阻塞后续任务；重新部署同一 snapshot 可重发确定性 event。
- 配置清理按数据库候选记录批处理。对象删除或数据库删除任一步骤失败都不标记完成，下一轮可以安全重试。
- 不在日志、Outbox、NATS、审计或 API 响应中输出对象存储凭据或签名 URL。

## 验收标准

- protobuf、Gateway 和 Worker 可传递并处理包含文件的 `ConfigChanged`；旧的无文件事件仍兼容。
- 集成测试验证：创建 snapshot、预签名上传、完成校验、部署、Worker 下载/校验/应用、`ConfigApplied` 回写为 `APPLIED`。
- 测试覆盖重复配置事件、文件 checksum/size 错误、下载失败、Runtime 应用失败和重启后清理重试。
- 清理测试证明当前 binding 和最近保留窗口不会被删除，孤立旧 snapshot 的文件对象最终可删除。
- 全量 Maven 测试、协议生成、Kind/Helm 静态检查和 `git diff --check` 通过。
