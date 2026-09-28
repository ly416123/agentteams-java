# Console、SDK 与 Matrix 统一 task.start 适配设计

## 背景与目标

当前 Matrix 在绑定上下文模式下已经通过 `/api/v1/commands` 提交 `task.start`，由 Control Plane 统一完成授权、幂等、命令审计和 Task 创建。Console 与 SDK 仍主要调用旧的 `/api/v1/tasks` 创建接口，因此同一语义的任务创建不会产生统一命令审计。

本次补齐跨渠道的显式 `task.start` 适配：Console、Java SDK、TypeScript SDK 和 Matrix 都使用同一份 `CommandEnvelope` 语义，由 Control Plane 产生一致的命令审计和 Task 事实。旧的 `/api/v1/tasks` 创建接口继续保留，避免破坏已有草稿创建流程。

## 方案比较

### 方案 A：客户端新增统一命令适配（采用）

在 Console、Java SDK 和 TypeScript SDK 增加 `task.start` 方法，统一 POST `/api/v1/commands`；Matrix 继续复用现有解析器。旧 Task API 保持不变。

优点是边界清晰、兼容性最好，调用方可以明确区分“创建草稿”和“提交统一命令”；缺点是需要维护各语言的轻量协议类型，并为 Console 增加显式入口。

### 方案 B：旧 Task API 在服务端内部桥接统一命令

让 `/api/v1/tasks` 的创建逻辑内部构造 `CommandEnvelope`，调用方不变。

优点是客户端改动小；缺点是旧接口的草稿语义与 `task.start` 语义混在一起，无法让调用方明确选择命令渠道，且会扩大旧接口的行为变更风险。

### 方案 C：只补集成测试和文档

保留现状，仅证明 Matrix 的统一入口和其他渠道的旧入口最终都能创建 Task。

该方案不能满足“统一命令审计”的验收要求，因此不采用。

## 设计

### 1. 统一命令请求

客户端新增 `startTask` 能力，提交以下稳定字段：

- `commandType` 固定为 `task.start`；
- `aggregateType` 固定为 `TASK`，新建任务时 `taskId` 为空、`aggregateVersion` 为 `0`；
- `tenantId`、`actor.id` 必须与当前认证主体匹配，`actor.channel` 分别为 `CONSOLE`、`SDK` 或 `MATRIX`；
- `conversationId` 可选；存在时由 Control Plane 写入 Task 的 `sourceConversationId`；
- `payload` 只包含标题、说明和非敏感 Task Spec 字段；
- `idempotencyKey`、`traceId` 和 `occurredAt` 保持调用方可追踪且可重放。

响应统一解码为 `CommandResult`，包含 `commandId`、`status`、`duplicate`、`taskId`、`aggregateVersion` 和 `traceId`。客户端不复制 Control Plane 的审计逻辑。

### 2. Console 适配

新增 `console/src/api/commands.ts`，提供 `startTask` API 和请求/响应类型。任务创建页面保留现有“创建草稿”流程，并增加显式“统一入口创建”操作；该操作只调用 `/api/v1/commands`，成功后按返回的 `taskId` 跳转任务详情。两条流程共享表单校验和错误展示。

### 3. Java 与 TypeScript SDK 适配

两个 SDK 均新增 `startTask` 方法、请求类型和 `CommandResult` 类型。方法支持显式幂等键和安全重试，鉴权、结构化错误和重试策略复用现有客户端实现。原有 `createTask` 保持原路径和返回类型不变。

### 4. Matrix 与服务端边界

不改变已通过验收的 Matrix `CommandEnvelope` 生成逻辑。仅补充跨渠道契约测试，确认 Matrix、Console 和 SDK 的业务字段映射一致，差异只允许出现在 `actor.channel`、命令标识、幂等键和追踪字段。

Control Plane 继续由现有 `UnifiedCommandController` 与 `CommandDispatcher` 完成授权、敏感字段拒绝、幂等冲突处理、命令审计和 Task 创建；不新增第二套 Task 创建服务。

## 错误处理与兼容性

- 未提供或不匹配 `tenantId`、`actor.id` 时由服务端返回现有授权错误。
- 命令重复且请求哈希一致时返回 `DUPLICATE`；同一幂等键对应不同请求时保持现有冲突错误。
- `payload` 命中敏感字段时保持现有拒绝行为。
- 旧 `/api/v1/tasks`、Manager Conversation API 和 Matrix 旧兼容构造函数继续保留。

## 验证范围

- Control Plane：统一命令入口、Dispatcher、Task source Conversation 和 Matrix 适配回归测试。
- Java SDK：请求路径、统一 JSON 字段、鉴权、幂等键、响应解码和结构化错误测试。
- TypeScript SDK：同上，并验证安全重试只对显式安全写请求生效。
- Console：API 合同测试、统一入口创建页面操作和错误状态测试。
- 项目级回归：Maven、Python、Console 测试与构建，最后更新整体集成验收清单。

## 非目标

- 不删除或重命名旧 Task API。
- 不把所有生命周期操作一次性迁移到统一命令入口。
- 不引入新的审计表、消息总线或客户端自定义权限判断。
