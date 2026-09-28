# Conversation 与统一渠道协议实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans。步骤使用复选框（- [ ]）语法跟踪进度。

**目标：** 将 Conversation、Conversation Message、Task source Conversation、统一 Command/Event Envelope 和统一时间线落到 Control Plane，同时保留 Manager Conversation API 兼容行为。

**架构：** application-contracts 提供跨模块不可变协议类型；Control Plane 持久化 Conversation/Message、Task 关联和领域事件，并在同一授权边界提供查询与命令入口；Manager、Console、SDK 通过适配器消费 Control Plane。

**技术栈：** Java 21 records、Jackson、Spring MVC、Spring JDBC、Flyway PostgreSQL、JUnit 5、AssertJ、TypeScript、Java HttpClient。

---

## 文件清单

- 创建：application-contracts/src/main/java/io/agentteams/application/api/CommandEnvelope.java
- 创建：application-contracts/src/main/java/io/agentteams/application/api/CommandResult.java
- 创建：application-contracts/src/main/java/io/agentteams/application/api/UnifiedEventEnvelope.java
- 创建：application-contracts/src/main/java/io/agentteams/application/api/TimelineItem.java
- 创建：control-plane/src/main/resources/db/migration/V96__conversation_unified_protocol.sql
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationMessageRecord.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/JdbcConversationRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationMessageRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/JdbcConversationMessageRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationService.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/conversation/ConversationTimelineService.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/api/ConversationController.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/api/UnifiedCommandController.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/persistence/TaskRecord.java、TaskListRecord.java、TaskRepository.java
- 修改：manager/src/main/java/io/agentteams/manager/api/ConversationController.java
- 修改：manager/src/main/java/io/agentteams/manager/conversation/ConversationService.java
- 修改：console/src/api/conversations.ts、console/src/api/types.ts、console/src/features/conversations/ConversationPage.tsx
- 修改：sdk/java/src/main/java/io/agentteams/sdk/AgentTeamsClient.java
- 创建：application-contracts/src/test/java/io/agentteams/application/api/UnifiedProtocolTest.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/conversation/ConversationServiceTest.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/conversation/ConversationTimelineServiceTest.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/api/UnifiedCommandControllerTest.java
- 修改：manager/src/test/java/io/agentteams/manager/api/ConversationControllerTest.java
- 修改：console/tests/api/conversations.test.ts
- 修改：sdk/java/src/test/java/io/agentteams/sdk/AgentTeamsClientTest.java

### 任务 1：定义跨模块协议类型

**文件：** application-contracts 四个新 record 文件及 UnifiedProtocolTest.java。

- [x] **步骤 1：编写失败测试**

覆盖 actor、租户、幂等键、版本号、occurredAt 的校验，以及 Jackson 字段名和命令状态。

~~~java
assertThat(new CommandResult(commandId, CommandStatus.DUPLICATE, true, taskId, 5L, "trace"))
        .deduplicated().isTrue();

assertThatThrownBy(() -> new CommandEnvelope(
        commandId, "", "tenant-1", actor, null, taskId, "TASK",
        4L, objectNode, "key", "trace", instant))
        .isInstanceOf(IllegalArgumentException.class);
~~~

- [x] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl application-contracts -am -Dtest=UnifiedProtocolTest test
~~~

预期：FAIL，原因是协议类型尚不存在。

- [x] **步骤 3：实现最小协议类型**

保持以下稳定签名：

~~~java
public record CommandEnvelope(
        UUID commandId, String commandType, String tenantId,
        CommandActor actor, UUID conversationId, UUID taskId,
        String aggregateType, long aggregateVersion, JsonNode payload,
        String idempotencyKey, String traceId, Instant occurredAt) {}

public record CommandActor(String type, String id, String channel) {}

public enum CommandStatus { ACCEPTED, DUPLICATE, REJECTED, FAILED }

public record CommandResult(
        UUID commandId, CommandStatus status, boolean deduplicated,
        UUID taskId, long aggregateVersion, String traceId) {}

public record UnifiedEventEnvelope(
        UUID eventId, String eventType, int eventVersion, String tenantId,
        String aggregateType, UUID aggregateId, long aggregateVersion,
        UUID conversationId, UUID taskId, Instant occurredAt,
        CommandActor actor, JsonNode payload, String visibility, String traceId) {}

public record TimelineItem(
        String itemId, String itemType, UUID conversationId, UUID taskId,
        String eventType, String actorId, Instant occurredAt, long sequence,
        String visibility, String payloadRef, String summary, long sourceVersion) {}
~~~

用 Jackson 注解固定公共 JSON 字段名，不把 Spring、JDBC 或数据库记录类型放进 application-contracts。

- [x] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期 UnifiedProtocolTest 全部 PASS。

- [x] **步骤 5：提交**

~~~bash
git add application-contracts/src/main/java/io/agentteams/application/api application-contracts/src/test/java/io/agentteams/application/api/UnifiedProtocolTest.java
git commit -m "feat(协议): 增加统一命令事件和时间线合同"
~~~

### 任务 2：建立 Conversation 和 Task 关联持久化

**文件：** V96、ConversationRecord、ConversationMessageRecord、两个 Repository 及 JDBC 测试。

- [x] **步骤 1：编写失败测试**

覆盖：

1. 创建 Conversation 后能读回 tenant、project、team、title、status、version。
2. 同一 Conversation 可写入不同渠道 Message，sequence 严格递增。
3. 相同 conversation_id + idempotency_key 重放返回原 Message。
4. Task source conversation 可为空，写入后可反查。
5. 不同 tenant 不能读到对方 Conversation。

- [x] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='Conversation*RepositoryTest' test
~~~

预期：FAIL，原因是迁移、记录类型和 Repository 尚不存在。

- [x] **步骤 3：编写迁移**

V96 创建 Conversation、Message 和 Task 关联；Message 表若与 manager 同库，使用不冲突的物理名 conversation_messages_cp。

~~~sql
CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT,
    title TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE conversation_messages_cp (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    tenant_id TEXT NOT NULL,
    sender_type TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    message_type TEXT NOT NULL,
    content TEXT,
    content_ref TEXT,
    visibility TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    sequence BIGINT NOT NULL,
    idempotency_key TEXT,
    trace_id TEXT NOT NULL,
    UNIQUE (conversation_id, idempotency_key),
    UNIQUE (conversation_id, sequence)
);

ALTER TABLE tasks ADD COLUMN source_conversation_id UUID REFERENCES conversations(id);
CREATE INDEX conversation_messages_cp_timeline_idx
    ON conversation_messages_cp (conversation_id, sequence);
CREATE INDEX tasks_source_conversation_idx ON tasks (source_conversation_id);
~~~

- [x] **步骤 4：实现 Repository 和 tenant 过滤**

固定接口：

~~~java
ConversationRecord create(ExecutionContext context, CreateConversation command);
Optional<ConversationRecord> find(ExecutionContext context, UUID id);
ConversationMessageRecord appendMessage(ExecutionContext context, AppendConversationMessage command);
List<ConversationMessageRecord> messages(ExecutionContext context, UUID conversationId, long after, int limit);
~~~

appendMessage 使用数据库唯一约束实现幂等；重放只返回原记录，不生成新 sequence。

- [x] **步骤 5：运行测试验证通过**

运行同一条 Maven 命令，预期 Repository、tenant 隔离和幂等测试 PASS。

- [x] **步骤 6：提交**

~~~bash
git add control-plane/src/main/resources/db/migration/V96__conversation_unified_protocol.sql control-plane/src/main/java/io/agentteams/controlplane/conversation
git commit -m "feat(conversation): persist conversations and messages"
~~~

### 任务 3：实现 Conversation 服务和统一时间线

**文件：** ConversationService.java、ConversationTimelineService.java、Task 持久层映射及服务测试。

- [x] **步骤 1：编写失败测试**

测试一个 Conversation 创建两个 Task；一个 Task 第二次绑定 Conversation 被拒绝；时间线包含 Message 和 Task Event；事件乱序时稳定排序；跨 tenant 查询被拒绝。

~~~java
ConversationRecord conversation = service.create(context, create("conv-title"));
TaskRecord first = taskService.create(context, taskCreate(conversation.id()));
TaskRecord second = taskService.create(context, taskCreate(conversation.id()));

assertThat(first.sourceConversationId()).isEqualTo(conversation.id());
assertThat(second.sourceConversationId()).isEqualTo(conversation.id());
assertThat(timelineService.list(context, conversation.id(), 0, 100))
        .extracting(TimelineItem::itemType)
        .contains("CONVERSATION_MESSAGE", "TASK_EVENT");
~~~

- [x] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='ConversationServiceTest,ConversationTimelineServiceTest' test
~~~

预期：FAIL，原因是服务、Task 字段和 Timeline 查询尚未实现。

- [x] **步骤 3：实现服务和查询投影**

ConversationService 负责创建、归档、追加 Message 和校验 Task 关联；不得改变 Task phase。

ConversationTimelineService 读取 conversation_messages_cp、DomainEventRepository、TaskProcessEventRepository、TaskResultManifestRepository，映射为 TimelineItem。过滤 visibility 后按 occurredAt、sequence、itemId 稳定排序；payload 只返回 payloadRef 或安全 summary。

- [x] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期关联、授权、排序和可见性测试 PASS。

- [x] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/conversation control-plane/src/main/java/io/agentteams/controlplane/persistence
git commit -m "feat(conversation): add service and unified timeline"
~~~

### 任务 4：统一命令入口和审计

**文件：** UnifiedCommandController.java、幂等存储、现有 audit/outbox 组件和 UnifiedCommandControllerTest.java。

- [x] **步骤 1：编写失败测试**

MockMvc 覆盖合法 task.start、重复命令返回 DUPLICATE、aggregateVersion 冲突 409、无权限 403、命令接收/拒绝审计和敏感 payload 拒绝。

- [x] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest=UnifiedCommandControllerTest test
~~~

预期：FAIL，原因是统一入口和幂等存储尚不存在。

- [x] **步骤 3：实现入口**

新增 POST /api/v1/commands：

~~~java
@PostMapping("/api/v1/commands")
public CommandResult execute(@Valid @RequestBody CommandEnvelope command) {
    return commandDispatcher.dispatch(resolveContext(command), command);
}
~~~

CommandDispatcher 先校验 actor/tenant/scope，再按 tenant_id + command_type + idempotency_key 去重，最后调用现有 TaskService/ConversationService，并写入 DomainEventRepository、OutboxEventRepository 和审计记录。

- [x] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期命令、授权、幂等和审计测试 PASS。

- [x] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/api control-plane/src/main/java/io/agentteams/controlplane/command control-plane/src/test/java/io/agentteams/controlplane/api/UnifiedCommandControllerTest.java
git commit -m "feat(command): unify channel commands and idempotency"
~~~

### 任务 5：保留 Manager API 并接入 Control Plane Conversation

**文件：** Manager ConversationController、ConversationService、兼容层及测试。

- [x] **步骤 1：扩展现有测试**

验证旧 POST /api/v1/conversations 仍返回旧字段；创建结果保留 sessionId/taskId；旧消息转换为 channel=INTERNAL、messageType=TEXT；Task 带 sourceConversationId；runtime 重放不重复创建 Task。

- [x] **步骤 2：运行基线测试**

运行：

~~~bash
mvn -q -pl manager -Dtest='ConversationControllerTest,ConversationServiceTest' test
~~~

预期：基线通过；基线失败时先记录根因，不将无关修复混入兼容桥接。

- [x] **步骤 3：实现兼容桥接**

Manager 保留 conversation_sessions 和旧游标；通过 ControlPlaneConversationPort 写入新 Conversation、Message 和 sourceConversationId。旧 DTO 继续输出旧字段，新领域 ID 由映射保存。

桥接使用原请求 idempotency key，并将 manager session version 映射到 CommandEnvelope.aggregateVersion，防止 runtime 重放重复创建 Task。

- [x] **步骤 4：运行测试验证通过**

运行：

~~~bash
mvn -q -pl manager -Dtest='ConversationControllerTest,ConversationServiceTest,ConversationEventTest' test
~~~

预期：旧 API 和桥接测试 PASS。

- [x] **步骤 5：提交**

~~~bash
git add manager/src/main/java/io/agentteams/manager manager/src/test/java/io/agentteams/manager
git commit -m "feat(manager): bridge legacy conversation to control plane"
~~~

### 任务 6：同步 SDK、Console 和公共时间线查询

**文件：** AgentTeamsClient.java、Conversation TypeScript API、ConversationPage.tsx、对应测试。

- [x] **步骤 1：编写失败测试**

SDK 测试验证 createConversation、sendConversationMessage、getConversationTimeline 的路径、Idempotency-Key、Authorization 和响应解码。Console 测试验证 TimelineItem、visibility、payloadRef 和 409 版本冲突。

- [x] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl sdk/java -Dtest=AgentTeamsClientTest test
npm --prefix console test -- --run console/tests/api/conversations.test.ts
~~~

预期：新 API 测试 FAIL。

- [x] **步骤 3：实现 SDK 和 Console API**

SDK 增加：

~~~java
public Conversation createConversation(CreateConversationRequest request, String idempotencyKey);
public ConversationMessage sendConversationMessage(UUID conversationId, SendMessageRequest request, String idempotencyKey);
public List<TimelineItem> getConversationTimeline(UUID conversationId, long after, int limit);
~~~

Console 增加 create/send/timeline API；ConversationPage 用统一 TimelineItem 渲染 Message、Task Event、Result 和 Artifact，并复用现有错误与版本冲突组件。

- [x] **步骤 4：运行测试验证通过**

运行：

~~~bash
mvn -q -pl sdk/java -Dtest=AgentTeamsClientTest test
npm --prefix console test -- --run
~~~

预期：SDK、Console API 和页面测试 PASS。

- [x] **步骤 5：提交**

~~~bash
git add sdk/java console/src/api console/src/features/conversations console/tests
git commit -m "feat(channel): expose conversation timeline through sdk and console"
~~~

### 完成定义

- [ ] application-contracts、control-plane、manager、sdk/java、console 定向测试通过。
- [ ] Flyway V96 在空库和现有库成功执行。
- [ ] 旧 Manager Conversation API 的字段和幂等语义不回归。
- [ ] unified timeline 不暴露 INTERNAL visibility 和敏感 payload。
- [ ] 计划 B 可只依赖本计划已提交的协议类型和 Conversation 查询接口。
