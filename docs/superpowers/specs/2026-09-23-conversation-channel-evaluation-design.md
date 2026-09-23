# Conversation、统一渠道与 Agent 评测设计

- 日期：2026-09-23
- 状态：待用户审阅
- 关联决策：[ADR-0001：采用混合通信模式](../../adr/0001-hybrid-communication-model.md)

## 1. 背景与目标

前序设计已经确认：系统采用混合通信模式，Control Plane 仍然是任务事实源；Conversation 与 Task Event 持久化；Console、SDK、Matrix 是三种展示和操作渠道。

本规格进一步固化四组边界：

1. Conversation 与 Task 的领域关系及统一时间线。
2. Console、SDK、Matrix 的统一命令、事件、幂等、授权和审计协议。
3. Matrix Room 与 Project、Team、Task、Conversation 的绑定模型。
4. Agent 执行结果、成功物、分析过程摘要、任务拆分和 Agent 评测在整体架构中的归属。

本规格不改变已经存在的任务执行、结果审查和 Matrix 适配实现，而是将它们提升为统一架构中的明确能力和约束。

## 2. 架构结论

系统采用以下分层：

~~~text
外部系统 ── SDK ─┐
管理端   ── REST ├──> Control Plane ──> Execution Plane ──> External Dependencies
Matrix   ── AppService ─┘          │
                                   ├── Conversation / Task Event Persistence
                                   ├── Result & Artifact Manifest
                                   ├── Audit & Outbox
                                   └── Evaluation / Quality Plane（只读消费证据）
~~~

### 2.1 事实源

Control Plane 是以下事实的唯一权威来源：

- Task 的生命周期、状态和版本；
- Task 与 Project、Team、Conversation 的关系；
- 命令是否被接受、拒绝或去重；
- Task Event、Process Event、Progress、Task Tree、Decision；
- 结果版本、结果审查状态和 Artifact Manifest；
- 统一授权、幂等和审计记录。

Matrix、Console 和 SDK 都不是任务事实源。它们只能提交经过统一协议封装的命令，或读取 Control Plane 投影出的状态和事件。

### 2.2 评测平面

Agent 评测作为第五个横切平面纳入架构：

- 不直接改变 Task 的业务状态；
- 不读取或生成隐藏 Chain-of-Thought；
- 只消费已持久化的执行证据；
- 产出独立的 Evaluation Run、Case Result、Score、Finding 和 Report；
- 可以关联 Task、Execution Run、Artifact、Task Event，但不替代这些领域对象；
- 评测失败不会回写为 Task 失败，除非未来显式增加质量门禁策略。

## 3. 当前能力与缺口

### 3.1 已有能力

当前项目已经具备以下基础能力：

- Task 生命周期、任务执行和重试；
- Task Tree / Subtask 任务拆分；
- Process Event、Progress、Decision 和结果展示；
- 结果 Manifest 与 Artifact 引用；
- 业务结果审查和拒绝后重试；
- Manager Session 与消息持久化；
- Matrix AppService 入站、命令解析、事件投影和房间映射基础；
- 离线 Agent Evaluation Runner、评测用例、规则和 DeepEval 适配器基础。

### 3.2 架构缺口

本规格需要补齐或明确以下缺口：

- Conversation 尚未作为跨渠道统一领域对象完整建模；
- Conversation Message 与 Task Event 的关联、排序和可见性尚未统一；
- 三种渠道的命令和事件协议需要收敛到同一 Envelope；
- Matrix Room 的主绑定上下文、迁移和权限继承规则需要固定；
- 评测目前主要是离线工具链，尚未形成与生产执行证据对接的 Evaluation / Quality Plane；
- 评测报告、质量门禁、校准和权限审计尚未成为一等产品能力。

## 4. 领域模型

### 4.1 Conversation

Conversation 表示围绕一个或多个 Task 的持续协作上下文。

核心字段：

~~~text
Conversation
- conversationId
- tenantId
- projectId
- teamId（可空）
- title
- status: ACTIVE | ARCHIVED
- createdBy
- createdAt
- updatedAt
- version
~~~

约束：

1. 一个 Conversation 可以创建多个 Task。
2. 一个 Task 最多有一个 sourceConversationId。
3. Conversation 不负责取代 Task 的执行状态。
4. Conversation 可以在 Task 完成后继续存在，用于追问、复盘和创建后续 Task。
5. Conversation 的生命周期与单个 Task 的生命周期解耦。

### 4.2 Conversation Message

Conversation Message 表示渠道中围绕 Conversation 的用户、系统或 Agent 可见消息。

核心字段：

~~~text
ConversationMessage
- messageId
- conversationId
- tenantId
- senderType: USER | SYSTEM | AGENT | CHANNEL
- senderId
- channel: CONSOLE | SDK | MATRIX | INTERNAL
- messageType: TEXT | COMMAND | STATUS | RESULT_REFERENCE | SYSTEM_NOTICE
- content
- contentRef（可选）
- visibility: PUBLIC | TEAM | PRIVATE | INTERNAL
- occurredAt
- sequence
- idempotencyKey（命令消息可选）
- traceId
~~~

约束：

- Message 是沟通事实，不承担 Task 状态变更；
- 命令型 Message 必须经过统一 Command Envelope 处理；
- 敏感内容按租户、团队、任务和消息可见性过滤；
- 原始凭据、未脱敏工具输出和隐藏推理不得进入 PUBLIC 消息；
- 消息正文可与大型内容、文件和 Artifact Manifest 分离存储。

### 4.3 Task

Task 表示一个可执行、可审查、可追踪的业务任务。

核心关系：

~~~text
Conversation 1 ───── creates ───── N Task
Task N ─────────── source of ───── 0..1 Conversation
Task 1 ─────────── contains ───── N Subtask
Task 1 ─────────── emits ──────── N Task Event
Task 1 ─────────── produces ───── N Result / Artifact
Task 1 ─────────── evaluated by ── N Evaluation Run
~~~

Task 的现有执行、结果 Manifest、业务结果审查和 Task Tree 能力继续保留。新增要求是：Task 必须可以被统一关联到 Conversation、Execution Run、Artifact 和 Evaluation Run。

### 4.4 Task Event

Task Event 表示 Task 的领域和执行事实，包括但不限于：

- TaskCreated、TaskAccepted、TaskStarted；
- TaskPaused、TaskResumed、TaskCancelled、TaskRetried；
- SubtaskPlanned、SubtaskStarted、SubtaskCompleted、SubtaskFailed；
- ProcessEvent、ProgressUpdated、DecisionRecorded；
- ResultProduced、ResultSubmitted、ResultAccepted、RevisionRequired；
- ArtifactRegistered、ApprovalRequested、ApprovalGranted、ApprovalRejected；
- TaskSucceeded、TaskFailed。

Task Event 与 Conversation Message 分开持久化。两者通过统一时间线查询模型进行合并，而不是写入同一张事实表。

### 4.5 统一时间线

统一时间线是查询投影，不是新的事实源。

查询结果至少包含：

~~~text
TimelineItem
- itemId
- itemType: CONVERSATION_MESSAGE | TASK_EVENT | PROCESS_EVENT | RESULT | ARTIFACT
- conversationId
- taskId（可空）
- eventType
- actor
- occurredAt
- sequence
- visibility
- payloadRef
- summary
- sourceVersion
~~~

排序规则：

1. 同一聚合内优先使用 Control Plane 分配的 sequence；
2. 跨聚合合并使用 occurredAt + itemId 稳定排序；
3. 当事件补偿或延迟到达时，以 sourceVersion 和 itemId 去重；
4. 展示层允许显示“已补偿”标记，但不得重写原始事实。

## 5. 统一命令协议

### 5.1 Command Envelope

Console、SDK、Matrix 都必须将入口请求规范化为同一 Command Envelope：

~~~json
{
  "commandId": "cmd_xxx",
  "commandType": "task.start",
  "tenantId": "tenant_xxx",
  "actor": {
    "type": "USER",
    "id": "user_xxx",
    "channel": "CONSOLE"
  },
  "conversationId": "conv_xxx",
  "taskId": "task_xxx",
  "aggregateType": "TASK",
  "aggregateVersion": 12,
  "payload": {},
  "idempotencyKey": "tenant_xxx:client_xxx:req_xxx",
  "traceId": "trace_xxx",
  "occurredAt": "2026-09-23T10:00:00Z"
}
~~~

必选处理步骤：

1. 入口适配器完成身份绑定、格式校验和渠道级限流；
2. Control Plane 校验租户、资源关系、角色和命令权限；
3. 以 idempotencyKey + commandType + tenantId 执行幂等去重；
4. 校验 aggregateVersion，冲突时返回版本冲突；
5. 持久化命令接收审计和领域事件；
6. 通过 Outbox 或等价可靠投递机制触发异步执行；
7. 返回统一命令结果。

统一命令结果：

~~~json
{
  "commandId": "cmd_xxx",
  "status": "ACCEPTED",
  "deduplicated": false,
  "taskId": "task_xxx",
  "aggregateVersion": 13,
  "traceId": "trace_xxx"
}
~~~

允许的状态：

- ACCEPTED：命令已被接受并产生后续事实；
- DUPLICATE：命令已处理过，返回原处理结果；
- REJECTED：协议、权限、版本或业务规则不允许；
- FAILED：命令处理过程中发生可重试或不可重试错误。

### 5.2 初始命令集合

第一阶段统一以下命令：

- conversation.create
- conversation.send_message
- task.create
- task.start
- task.pause
- task.resume
- task.cancel
- task.retry
- task.approve
- task.reject
- task.request_revision
- task.create_subtasks
- task.update_subtask_status
- artifact.attach
- evaluation.run（只允许授权的评测主体调用）

各渠道可以有不同的交互形式，但不得定义语义不同的同名命令。

## 6. 统一事件协议

### 6.1 Event Envelope

所有需要跨渠道投影的事件使用统一事件封装：

~~~json
{
  "eventId": "evt_xxx",
  "eventType": "task.result.produced",
  "eventVersion": 1,
  "tenantId": "tenant_xxx",
  "aggregateType": "TASK",
  "aggregateId": "task_xxx",
  "aggregateVersion": 18,
  "conversationId": "conv_xxx",
  "taskId": "task_xxx",
  "occurredAt": "2026-09-23T10:01:00Z",
  "actor": {
    "type": "AGENT",
    "id": "agent_xxx"
  },
  "payload": {
    "summary": "结果已生成",
    "resultId": "result_xxx",
    "artifactIds": ["artifact_xxx"]
  },
  "visibility": "TEAM",
  "traceId": "trace_xxx"
}
~~~

事件要求：

- 事件不可变；
- payload 中的大对象使用 payloadRef 或 Artifact 引用；
- 事件版本必须显式携带；
- 投影器必须按 eventId 幂等；
- 渠道不可根据展示文本反推任务状态；
- Control Plane 事件和渠道投影事件必须能够追溯到同一 eventId。

### 6.2 事件投影

- Console：REST 查询初始状态，SSE 或等价订阅接收增量；
- SDK：REST/Webhook/回调或客户端订阅接收状态和结果；
- Matrix：由 Matrix Event Projector 将授权范围内的事件转换为房间消息；
- Evaluation Plane：订阅或批量读取执行证据，不向 Matrix 或 Console 直接写入未经授权的事实。

渠道投影失败不回滚 Control Plane 事实。投影器应支持重试、死信、补偿和按游标重放。

## 7. Matrix Room 绑定模型

### 7.1 主绑定原则

一个 Matrix Room 只能有一个主业务上下文：

~~~text
Room 1 ── primaryContext ── PROJECT | TEAM | CONVERSATION | TASK
~~~

默认推荐：

- 以 Conversation Room 作为日常协作入口；
- Task Room 作为高噪声执行、审批或排障场景的可选房间；
- Project / Team Room 作为聚合浏览、通知和运营场景；
- 不允许一个 Room 同时作为多个 Task 的主控制面。

### 7.2 绑定记录

~~~text
MatrixRoomBinding
- bindingId
- tenantId
- matrixServer
- roomId
- primaryContextType
- primaryContextId
- projectId（可空）
- teamId（可空）
- conversationId（可空）
- taskId（可空）
- bindingStatus: ACTIVE | REVOKED | MIGRATING
- createdBy
- createdAt
- updatedAt
- version
~~~

约束：

1. 一个 Room 只能有一个 ACTIVE 主绑定；
2. 一个 Conversation 可以有多个投影 Room，但只能有一个默认控制 Room；
3. 一个 Task 可以没有 Task Room；
4. Task Room 必须继承或显式声明 Conversation、Team 和 Project 的访问边界；
5. Room 迁移必须记录绑定变更事件；
6. Matrix Room 不拥有 Task、Conversation 或 Artifact 的生命周期。

### 7.3 Matrix 调用方

Matrix 的调用方不是普通匿名用户，而是两类主体：

- 人类用户：通过 Matrix 身份绑定到平台 User，并携带房间成员关系；
- 服务主体：Matrix AppService / Bot / Bridge，以受控 Integration Identity 调用 Control Plane。

最终权限由 Control Plane 决定。房间成员资格只是上下文信号，不是平台授权的唯一依据。

### 7.4 Matrix 交互边界

允许：

- 在绑定的 Conversation Room 中发送消息；
- 创建或启动授权范围内的 Task；
- 查询状态、进度、结果摘要和审批请求；
- 执行批准、拒绝、暂停、恢复、取消和重试；
- 引用或查看授权范围内的 Artifact。

不允许：

- 直接写 Task 状态存储；
- 绕过 Control Plane 创建没有租户归属的任务；
- 通过 Room 成员关系越权读取其他 Project 或 Team；
- 将隐藏推理、原始凭据和未脱敏工具输出广播到房间；
- 用 Matrix 消息文本作为唯一审计依据。

## 8. 三种渠道权限边界

### 8.1 Console

- 身份：OIDC 登录用户；
- 接口：REST 查询和命令接口，SSE 或等价实时订阅；
- 适合：管理、配置、审查、运维、全量结果查看；
- 权限：按租户、Project、Team、Task、Artifact 和管理角色校验；
- 特权操作：密钥配置、连接器管理、评测配置和质量门禁管理仅允许管理角色。

### 8.2 SDK

- 身份：Integration Credential、服务主体或委托用户身份；
- 接口：稳定的 REST/SDK Command API、查询 API、Webhook/回调；
- 适合：外部系统提交任务、查询状态、接收结果和自动化编排；
- 权限：默认服务级最小权限，委托用户操作必须携带 delegated actor；
- 约束：SDK 不得直接访问内部数据库、事件表或执行面私有接口。

### 8.3 Matrix

- 身份：Matrix User Binding 或 Matrix AppService Service Principal；
- 接口：Matrix AppService 入站、事件投影和命令适配；
- 适合：协作沟通、通知、轻量审批和任务操作；
- 权限：以绑定后的平台身份和 Control Plane 授权为最终准则；
- 约束：Matrix 是协作渠道，不是配置中心、密钥中心或事实存储。

## 9. Agent 执行证据与结果模型

### 9.1 结果和成功物

Agent 的成功物不能只保留在最终文本中，必须形成可引用的 Result / Artifact 结构：

~~~text
Result
- resultId
- taskId
- executionRunId
- version
- summary
- status: PRODUCED | SUBMITTED | ACCEPTED | REVISION_REQUIRED
- artifactManifestId
- createdAt
- createdBy

Artifact
- artifactId
- taskId
- executionRunId
- type
- name
- uri or contentRef
- checksum
- mimeType
- size
- classification
- createdAt
~~~

Result 负责业务意义和审查状态，Artifact 负责可交付的文件、结构化数据、链接或其他成功物。两者都必须可追溯到 Task 和 Execution Run。

### 9.2 分析思路和过程证据

系统需要保留可审计的过程摘要，但不要求保存隐藏 Chain-of-Thought。

可持久化：

- Task decomposition 结构；
- 子任务依赖和状态；
- Agent 选择的工具、连接器和外部调用摘要；
- 关键 Decision 及其理由摘要；
- Process Event、Progress 和错误分类；
- 输入、输出和 Artifact 的引用；
- 可公开的分析摘要、风险和假设。

默认不持久化或不向普通用户展示：

- 隐藏 Chain-of-Thought；
- 原始系统提示词和凭据；
- 未脱敏外部响应；
- 超出授权边界的内部安全信号。

## 10. Evaluation / Quality Plane

### 10.1 目标

Evaluation / Quality Plane 用于回答：

- Agent 是否完成了任务；
- 输出是否满足结构、格式、业务规则和验收标准；
- 任务拆分是否完整、合理且可执行；
- 工具调用是否成功、是否越权或出现异常重试；
- 最终结果和成功物是否可复现、可引用；
- 不同 Agent、模型、Prompt、工具版本之间是否发生质量回归。

### 10.2 评测输入

评测平面读取规范化 Evidence Bundle：

~~~text
EvidenceBundle
- taskId
- executionRunId
- taskSnapshot
- taskEvents
- processEvents
- taskTree
- decisions
- toolCallSummaries
- resultManifest
- artifactRefs
- approvalHistory
- modelAndAgentMetadata
- policyAndPermissionOutcomes
~~~

Evidence Bundle 只暴露脱敏、授权的执行证据。评测器不应通过读取隐藏推理来替代可验证证据。

### 10.3 评测领域模型

~~~text
EvaluationSuite
- suiteId
- name
- version
- scope
- rubricRefs
- enabled

EvaluationCase
- caseId
- suiteId
- inputRef
- expectedAssertions
- expectedArtifactSchema
- tags

EvaluationRun
- evaluationRunId
- suiteId
- caseId
- taskId
- executionRunId
- evaluatorVersion
- status: QUEUED | RUNNING | PASSED | FAILED | INCONCLUSIVE
- startedAt
- completedAt

EvaluationResult
- resultId
- evaluationRunId
- metric
- score
- threshold
- status
- evidenceRefs
- findingSummary

EvaluationReport
- reportId
- evaluationRunId
- aggregateScore
- dimensionScores
- findings
- regressionCompareRef
- createdAt
~~~

### 10.4 评测维度

初始维度固定为：

1. 任务完成度：是否满足 Task 的成功条件；
2. 结果质量：结果内容是否满足业务规则；
3. 成功物完整性：Artifact 是否齐全、可访问、格式正确；
4. 任务拆分质量：子任务覆盖、依赖、状态和收敛情况；
5. 工具与外部依赖：调用成功率、错误处理和权限边界；
6. 稳定性与效率：重试、耗时、资源消耗和重复调用；
7. 安全与合规：敏感信息、越权、策略违规和审计完整性。

### 10.5 评测方法

采用分层评测：

- 确定性断言：Schema、状态、字段、Artifact、权限和事件序列；
- 规则评分：基于 Rubric 的可解释规则；
- 语义评测：可选 DeepEval 或等价模型评审；
- 人工校准：对关键样本进行人工复核并校准阈值；
- 回归对比：按 Agent、模型、Prompt、工具版本和数据集比较。

默认顺序是确定性断言优先，语义评测补充，不允许仅凭一个 LLM Judge 分数判定生产任务成功。

### 10.6 评测生命周期

~~~text
Execution evidence ready
        ↓
Evidence normalization
        ↓
Evaluation Run queued
        ↓
Deterministic checks
        ↓
Optional semantic checks
        ↓
Score / Findings / Report
        ↓
Regression comparison and quality gate
~~~

第一阶段：

- 保持当前离线 Runner、用例、Rubric 和 DeepEval 适配能力；
- 统一读取 Evidence Bundle；
- 评测结果写入报告目录或独立存储；
- 支持 CLI、CI 和人工查看。

后续阶段：

- Evaluation Run、Evaluation Result 和 Report 进入持久化领域；
- Console 增加评测详情、趋势、回归和人工校准；
- 支持按策略将评测结果作为发布或路由门禁；
- 支持线上抽样评测，但必须有成本、隐私和延迟控制。

### 10.7 评测与业务状态的关系

默认情况下：

~~~text
Task success ≠ Evaluation pass
Evaluation pass ≠ Task success
~~~

Task 成功表示执行流程满足业务生命周期条件；Evaluation Pass 表示按某个 Suite、Case 和版本评测通过。两者必须独立记录。

只有在显式配置 Quality Gate 后，评测结果才可影响：

- Agent / Prompt / Model 版本发布；
- 自动路由或降级；
- 批量任务准入；
- 结果是否自动提交审查。

即使启用 Quality Gate，也必须通过 Control Plane 的正式命令或策略处理，不允许评测器直接修改 Task 状态。

## 11. 审计、幂等与一致性

### 11.1 幂等键

命令幂等键至少包含：

~~~text
tenantId + callerId + idempotencyKey + commandType
~~~

事件投影以 eventId 幂等；评测以 evaluationRunId 或稳定的 evaluation fingerprint 幂等。

### 11.2 审计要求

以下动作必须审计：

- 身份绑定和权限决策；
- 命令接收、拒绝、去重和执行结果；
- Task、Conversation、Room Binding 的变更；
- Result、Artifact 和审查状态变更；
- Evaluation Suite、Rubric、阈值和质量门禁变更；
- Matrix 投影、失败、补偿和重放；
- 评测报告的生成、人工校准和覆盖。

### 11.3 一致性策略

- 事实写入与 Outbox 记录必须在同一可靠事务边界内；
- 渠道投影采用最终一致；
- Evaluation Plane 采用异步最终一致；
- 事件迟到、重复和乱序必须可处理；
- 任一渠道不可通过本地缓存覆盖 Control Plane 的最终状态；
- 外部依赖失败必须记录为可检索的执行证据和错误分类。

## 12. 数据安全与可见性

数据分为以下级别：

- PUBLIC：可在普通 Conversation / Matrix 房间展示；
- TEAM：仅 Project / Team 成员可见；
- TASK：仅 Task 授权参与者可见；
- PRIVATE：仅提交者、管理角色或指定主体可见；
- INTERNAL：仅 Control Plane、执行面和受控评测器可见。

规则：

1. 渠道投影必须依据 visibility 和当前 actor 重新授权；
2. Room 成员不是绕过平台权限的依据；
3. Artifact 下载必须短期授权或服务端代理；
4. Evaluation Evidence Bundle 必须脱敏和最小化；
5. 日志、事件、结果和评测报告中的凭据必须禁止明文落盘；
6. Matrix 房间历史中的敏感消息无法可靠撤回，因此发送前必须完成脱敏和可见性检查。

## 13. 验收标准

满足以下条件，视为本设计完成：

### 领域模型

- [ ] Conversation、Conversation Message、Task、Task Event 的边界和关系已固化；
- [ ] 一个 Conversation 可关联多个 Task，一个 Task 最多一个 source Conversation；
- [ ] Message 与 Task Event 分开持久化，可通过统一时间线查询；
- [ ] Result、Artifact、Task Tree、Decision、Process Event 可追溯到 Task / Execution Run。

### 统一协议

- [ ] Console、SDK、Matrix 都可生成统一 Command Envelope；
- [ ] Control Plane 统一执行授权、版本、幂等和审计；
- [ ] 统一 Event Envelope 可被三种渠道投影；
- [ ] 渠道投影失败可重试、补偿和重放。

### Matrix

- [ ] Room 只有一个主绑定上下文；
- [ ] Conversation Room 是默认协作入口，Task Room 可选；
- [ ] Matrix 身份和服务主体都能映射到平台授权模型；
- [ ] Matrix 无法绕过 Control Plane 直接修改事实状态。

### Agent 评测

- [ ] Evidence Bundle 能覆盖任务、过程、拆分、工具、结果和成功物；
- [ ] 评测结果是独立领域对象，可链接 Task / Execution Run / Artifact；
- [ ] 具备确定性断言、规则评分和可选语义评测；
- [ ] 支持评测报告、证据引用、回归比较和人工校准；
- [ ] Evaluation Pass 与 Task success 分离；
- [ ] Quality Gate 只有在显式配置后才能影响发布、路由或结果处理。

## 14. 非目标

本规格暂不决定：

- 具体数据库表结构、索引和分库分表；
- Matrix homeserver、AppService SDK 或桥接产品选型；
- Console 的具体页面视觉设计；
- Agent 内部提示词和隐藏推理格式；
- 某个具体 LLM Judge 的模型、价格和供应商；
- 线上评测的最终采样比例和成本预算；
- 多租户跨域共享 Room 的产品策略。

这些内容应在本规格批准后，拆分为实现计划和各领域的专项设计。
