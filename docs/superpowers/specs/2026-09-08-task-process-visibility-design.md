# 任务过程可见性设计（一期：动作流水与调度语义化）

## 目标

对话创建的任务，用户能在 Console 任务详情页实时看到三类过程：

1. **创建过程**：任务由哪个会话的哪条消息创建（来源回链）
2. **分配过程**：排队 → 调度器分配给 worker → worker 确认接收 → 开始执行的语义化时间线
3. **执行过程**：AI 执行期间的结构化动作流水（工具调用、产出物），SSE 实时推送

## 已确认决策

| 决策点 | 结论 |
|---|---|
| 思考过程边界 | 仅动作流水：不上报 reasoning 文本与 message.delta，保留「不展示链路思维」既有安全约束 |
| 分配过程深度 | 一期做调度链路语义化展示；子任务指派（拆解/指派/独立调度）属二期，另行设计 |
| 上报通道 | 新增 gRPC `TaskEventReport` 消息（方案 A），不与 TaskProgress 混装，不经 HTTP 直连控制面 |
| 详情页布局 | 右栏面板式（方案 A）：主列执行过程时间线 + 右栏任务信息面板（DAG / 标签页 / 详情 / 来源回链） |
| 节奏 | 两期交付，一期验收后再启动二期 |

## 现状与约束

- 控制面已持久化任务过程事件（`task_process_events`，幂等插入 + sequence 排序 + visibility），并提供 process-events SSE（`Last-Event-ID` 断点续传）、progress、tree、decisions、result 五个只读端点。
- Console 任务详情页已消费事件流、执行总览、任务分解、决策记录、成果物，但为单列滚动布局。
- 缺口一：QwenPaw worker 执行任务期间仅上报一次初始进度，`QwenPawHttpRuntimePort` 的 SSE 解析只取终态，中间动作（工具调用）全部丢弃。
- 缺口二：调度语义事件（创建/排队/分配/接收）已存在但展示为状态变更，无 actor 语义。
- 缺口三：任务与来源会话无关联，详情页无法回链。
- 约束：不新增第二套任务状态机；不改变既有事件类型与终态可靠性语义；worker 只连 gateway（不直连控制面）。
- 部署假设：项目处于开发阶段，worker / gateway / control-plane / console 同步升级，不做跨版本兼容设计；proto 消息只保留一套最新语义，不为他日版本共存预留字段或降级分支。

## 方案

### 数据流（8 环节）

```
QwenPaw pod (SSE 中间事件)
  → runtime QwenPawHttpRuntimePort (新 RuntimeEventSink，白名单转换)
  → agent-worker GatewayRuntimeAdapter (新 reportEvent，限速)
  → gRPC AgentMessage.task_event_report (contracts 新消息)
  → agent-gateway ControlPlaneGatewayApplicationHandler (新 taskEventReport 校验)
  → NATS JetStream ExecutionEventEnvelope (type=TASK_EVENT 新分支)
  → control-plane NatsExecutionEventConsumer → ObservationAdapter.observed (新方法)
  → task_process_events 落库
  → console process-events SSE → 详情页时间线实时追加
```

### 事件类型白名单

| QwenPaw SSE 事件 | 上报 eventType | payload 示例 |
|---|---|---|
| `tool.started` | `tool.called` | `{"tool":"web_search"}` |
| `plugin_call_output` | `tool.finished` | `{"tool":"web_search","elapsedMs":2300,"ok":true}` |
| reasoning / message.delta | 丢弃（安全边界） | — |
| message.completed（object=message） | 不单独上报（终态路径已有） | — |

调度语义事件不新增事件源：既有生命周期投影映射到同一条时间线，actor 标注（对话 AI / 调度器 / worker）由前端 eventType 映射表完成。

### 组件设计

1. **contracts**：`TaskEventReport { EventMetadata metadata; uint32 sequence; string event_type; string payload; }`；`AgentMessage.oneof` 新增 `task_event_report`。metadata 复用既有字段（task_id/attempt_id/lease_id/agent_id/event_id/occurred_at）。
2. **runtime**：新 SPI 出口 `RuntimeEventSink { void accept(RuntimeEvent event); }`，`RuntimeEvent(taskId, eventType, payload, occurredAt)`；`AgentRuntimeContext` 增加可空 eventSink，无 sink 的实现行为不变。`QwenPawHttpRuntimePort` SSE 解析循环按白名单转换中间事件；未知/畸形事件吞掉并计 metric，永不中断流。
3. **agent-worker**：`GatewayRuntimeAdapter.reportEvent(taskId, sequence, eventType, payload)` 构造消息发送；每任务滑动窗口限速 `AGENTTEAMS_EVENT_RATE_LIMIT`（默认 30/s，超限丢弃 + metric）；seq 由每任务 AtomicLong 分配；`QwenPawWorker` 将 runtime sink 桥接到 adapter。
4. **agent-gateway**：`taskEventReport(connection, report)` 校验 attempt/lease/worker 归属（同 taskProgress 既有规则），转 `TaskEventReportCommand` 经 `ExecutionEventPort` 发布；envelope type 新增 `TASK_EVENT`。
5. **control-plane**：`NatsExecutionEventConsumer` 新增 TASK_EVENT 分支；`ControlPlaneTaskExecutionObservationAdapter.observed(taskId, runId, eventType, payload, at)` 二次校验白名单与 4KB 上限后经 `TaskProcessEventService.append` 落库（visibility=REQUESTER）。
6. **来源关联**：MCP `create_task` 新增可选 `source: {conversationId, messageId}` 参数，存入 `inputJson.source`；任务详情 API 已返回 inputJson，无需新端点。MCP server 侧若可从注入上下文读到会话则自动补全，读不到则字段为空。
7. **console**：`TaskDetailPage` 重排为 CSS grid 主列 + 右栏 320px；新组件 `TaskInfoPanel`（DAG 缩略图 + 事件/成果物/决策/详情标签 + 键值详情 + 来源会话回链）与 `TaskDag`（一期纯 div/SVG 层级渲染投影树，无图库依赖）；时间线增加 actor 映射与 `tool.finished` 耗时徽标。回链跳转 `/conversations/{id}`；source 缺失时不渲染回链区块。

### sequence 双轨制与幂等

- worker seq（TaskEventReport.sequence）：仅辅助排序，不参与去重。
- 去重键：EventMetadata.eventId（`(task_id, run_id, event_id)` 冲突即丢弃），worker 重启计数归零无影响。
- 存储 seq：控制面 `nextSequence(runId)` 统一分配，作为 SSE 回放游标。

## 错误处理

**公理一：中间事件是 best-effort，永不威胁终态。** SSE 畸形数据、sink 异常、限速、gRPC 断连、控制面校验拒绝一律丢弃 + metric，不缓存不重试；可靠性保障只属于终态事件（既有语义不变）。

**公理二：双层校验，不信任上游。** worker 出口（白名单 + 4KB 截断 + 限速）与控制面入口（白名单 + 4KB + scope/visibility）各校验一次。

### 边界情况

- worker 重启：seq 归零无害（去重靠 eventId）。
- SSE 中途断：中间事件随流丢失，终态由 runtime 既有重试保障。
- 长任务：工具调用频率远低于限速；前端 cursor 续传已有。
- 无鉴权部署：沿用 visibility=REQUESTER + scope 校验。
- 表增长：动作事件与既有 progress 同级体量，一期不加 TTL，观察后议。

## 测试策略

| 层 | 关键用例 |
|---|---|
| runtime | SSE→RuntimeEvent 白名单映射；reasoning/delta 不产生事件；畸形事件不中断流（终态正常）；payload 截断 |
| worker | reportEvent 消息构造；限速丢弃 + metric；sink 桥接 |
| gateway | taskEventReport 归属校验；无效消息拒绝 |
| control-plane | observed 落库（幂等/visibility）；NATS TASK_EVENT 分支；白名单二次校验；TaskPushInfrastructureIT 扩展全链路断言 |
| console | 右栏布局渲染；actor 标注与耗时徽标；回链空不渲染；SSE 重连在新布局不回归 |
| MCP 脚本 | create_task source 参数透传（契约测试扩展） |
| 端到端 | kind 冒烟 → L5 真模型：对话建任务，执行中详情页实时滚动动作事件，调度语义齐全，回链可跳转 |

## 范围外（二期预告）

子任务创建/指派工具、子任务独立调度与生命周期、子任务卡片与多节点 DAG 交互、agent 间协作语义事件。二期依赖一期的事件通道与右栏容器，接口已为其预留（eventType 命名空间、DAG 组件、标签页容器）。
