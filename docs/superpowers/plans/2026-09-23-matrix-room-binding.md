# Matrix Room 绑定与 Matrix 命令适配实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans。步骤使用复选框（- [ ]）语法跟踪进度。

**目标：** 将 Matrix Room 从 project-only channel binding 扩展为唯一主上下文绑定，并把 Matrix 人类用户和 AppService 服务主体规范化为统一 Command Envelope。

**架构：** Matrix AppService 只负责入站去重、身份映射、房间上下文解析和事件投影；Control Plane 负责最终授权、幂等、版本校验、Task/Conversation 事实写入。现有 Matrix Inbox/Outbox 继续复用。

**技术栈：** Java 21、Spring Boot、JDBC、Flyway、Jackson、现有 Matrix AppService/Outbox、JUnit 5、AssertJ。

---

## 文件清单

- 创建：control-plane/src/main/resources/db/migration/V97__matrix_room_context_binding.sql
- 创建：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixRoomContextType.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixRoomBinding.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixRoomBindingRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/matrix/JdbcMatrixRoomBindingRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixRoomBindingService.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixAppServiceHttpAdapter.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixCommandParser.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixTaskCommandHandler.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixEventProjector.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/channel/MatrixChannelAdapter.java
- 修改：control-plane/src/main/java/io/agentteams/controlplane/matrix/MatrixChannelBinding.java、JdbcMatrixChannelBindingRepository.java
- 创建：control-plane/src/main/java/io/agentteams/controlplane/api/MatrixRoomBindingController.java
- 创建：control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixRoomBindingServiceTest.java
- 修改：control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixAppServiceHttpAdapterTest.java
- 修改：control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixCommandParserTest.java
- 修改：control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixTaskCommandHandlerTest.java
- 修改：control-plane/src/test/java/io/agentteams/controlplane/matrix/JdbcMatrixChannelBindingRepositoryTest.java
- 创建：scripts/test_matrix_room_binding_contract.py

### 任务 1：定义 Room 主上下文模型和迁移

**文件：** V97、MatrixRoomContextType、MatrixRoomBinding、Repository 及测试。

- [ ] **步骤 1：编写失败测试**

覆盖四种主上下文、Room 唯一、ACTIVE 绑定唯一、REVOKED 历史保留和 tenant 隔离。构造非法 binding 时断言 IllegalArgumentException。

~~~java
assertThat(new MatrixRoomBinding(
        id, "org", "tenant", "room", MatrixRoomContextType.CONVERSATION,
        conversationId, projectId, teamId, MatrixRoomBindingStatus.ACTIVE,
        actor, now, now, 0).primaryContextType())
        .isEqualTo(MatrixRoomContextType.CONVERSATION);
~~~

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='MatrixRoomBinding*Test' test
~~~

预期：FAIL，因为类型、表和 Repository 尚不存在。

- [ ] **步骤 3：实现类型和表**

固定类型：

~~~java
public enum MatrixRoomContextType { PROJECT, TEAM, CONVERSATION, TASK }
public enum MatrixRoomBindingStatus { ACTIVE, REVOKED, MIGRATING }
~~~

V97 创建：

~~~sql
CREATE TABLE matrix_room_bindings (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    room_id TEXT NOT NULL,
    primary_context_type TEXT NOT NULL CHECK (primary_context_type IN ('PROJECT','TEAM','CONVERSATION','TASK')),
    primary_context_id UUID NOT NULL,
    project_id UUID,
    team_id UUID,
    conversation_id UUID,
    task_id UUID,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','REVOKED','MIGRATING')),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE UNIQUE INDEX matrix_room_bindings_active_room_idx
    ON matrix_room_bindings (room_id) WHERE status = 'ACTIVE';
CREATE INDEX matrix_room_bindings_context_idx
    ON matrix_room_bindings (tenant_id, primary_context_type, primary_context_id);
~~~

服务构造函数校验 primary_context_id 与具体 context 字段一致，Conversation/Task 绑定校验同一 tenant/project/team。

- [ ] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期类型、Repository 和数据库约束测试 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/resources/db/migration/V97__matrix_room_context_binding.sql control-plane/src/main/java/io/agentteams/controlplane/matrix control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixRoomBindingServiceTest.java
git commit -m "feat(matrix): add room primary context binding"
~~~

### 任务 2：统一 Matrix 入站命令

**文件：** MatrixAppServiceHttpAdapter、MatrixCommandParser、MatrixTaskCommandHandler、统一命令适配器及测试。

- [ ] **步骤 1：编写失败测试**

覆盖：

1. Conversation Room 的 task.start 生成 conversationId、taskId、roomId 一致的 CommandEnvelope。
2. Task Room 的 task.pause 从当前 Task 读取 aggregateVersion。
3. 未绑定 Room 返回 ROOM_NOT_BOUND 且不调用 TaskService。
4. Matrix 人类身份映射 actor.type=USER。
5. AppService Bot 映射 actor.type=SERVICE。
6. 同一 transaction id + event id 重放不产生第二条命令。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='MatrixAppServiceHttpAdapterTest,MatrixCommandParserTest,MatrixTaskCommandHandlerTest' test
~~~

预期：新 Envelope、Room context 和去重断言 FAIL。

- [ ] **步骤 3：实现上下文解析和命令映射**

入站流程固定为：

~~~java
MatrixRoomBinding binding = roomBindings.requireActive(identity.tenantId(), event.roomId());
MatrixIdentity actor = identities.requireBound(event.sender());
CommandEnvelope command = parser.toCommand(actor, binding, event);
return commandDispatcher.dispatch(contextResolver.resolve(actor), command);
~~~

Parser 只转换 commandType 和 payload，不直接调用 TaskService。MatrixTaskCommandHandler 使用共享 CommandDispatcher，不再生成随机 source；幂等键使用 Matrix transaction id + event id。

- [ ] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期身份、Room、命令幂等和版本冲突测试 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/matrix control-plane/src/test/java/io/agentteams/controlplane/matrix control-plane/src/main/java/io/agentteams/controlplane/command
git commit -m "feat(matrix): normalize room commands through control plane"
~~~

### 任务 3：Room 绑定管理接口和权限继承

**文件：** MatrixRoomBindingController、MatrixRoomBindingService、ResourceAuthorizationMatrix 及测试。

- [ ] **步骤 1：编写失败测试**

MockMvc 覆盖创建、查询、迁移、撤销；非管理员拒绝；Task Room 只能继承同一 Conversation/Team/Project 的边界；解绑后只保留 Inbox 记录，不执行命令或投影。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='MatrixRoomBindingControllerTest,MatrixRoomBindingServiceTest' test
~~~

预期：FAIL，因为管理接口和迁移逻辑尚不存在。

- [ ] **步骤 3：实现 REST 端点**

固定端点：

~~~text
POST /api/v1/matrix/rooms/bindings
GET  /api/v1/matrix/rooms/bindings?contextType=&contextId=
POST /api/v1/matrix/rooms/bindings/{id}/migrate
POST /api/v1/matrix/rooms/bindings/{id}/revoke
~~~

端点通过 PrincipalContext、ExecutionContextResolver 和管理权限校验；绑定变更写入 audit/outbox，响应返回 binding version。

- [ ] **步骤 4：运行测试验证通过**

运行同一条 Maven 命令，预期绑定创建、迁移、撤销和权限测试 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/api/MatrixRoomBindingController.java control-plane/src/main/java/io/agentteams/controlplane/matrix control-plane/src/main/java/io/agentteams/controlplane/security control-plane/src/test/java/io/agentteams/controlplane/api
git commit -m "feat(matrix): manage room binding lifecycle"
~~~

### 任务 4：Matrix 事件投影和 Outbox 兼容

**文件：** MatrixEventProjector、MatrixChannelAdapter、MatrixChannelBinding、JdbcMatrixChannelBindingRepository 及测试。

- [ ] **步骤 1：编写失败测试**

覆盖 Conversation Event 到默认 Room、Task Event 到 Task/Conversation Room、eventId + roomId 去重、INTERNAL 不投影、撤销绑定不发送、Outbox 重试不回滚事实。

- [ ] **步骤 2：运行测试验证失败**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='MatrixChannelAdapterTest,MatrixEventProjectorTest,JdbcMatrixChannelBindingRepositoryTest' test
~~~

预期：投影路由和 visibility 测试 FAIL。

- [ ] **步骤 3：实现兼容适配**

MatrixChannelBinding 保留 V69 project/eventTypes 读取兼容，新增 roomBindingId 和 contextType；旧记录没有新绑定时继续使用 project binding。

MatrixEventProjector 以 eventId + roomId 作为 Outbox 幂等键，按 visibility、actor 授权和 binding status 过滤；房间消息只发送安全 summary 和 payloadRef，不发送原始 payload。

- [ ] **步骤 4：运行测试验证通过**

运行：

~~~bash
mvn -q -pl control-plane -Dtest='*Matrix*Test' test
python3 scripts/test_matrix_room_binding_contract.py
~~~

预期：Java Matrix 测试和 Python 合约检查 PASS。

- [ ] **步骤 5：提交**

~~~bash
git add control-plane/src/main/java/io/agentteams/controlplane/matrix control-plane/src/main/java/io/agentteams/controlplane/channel control-plane/src/test/java/io/agentteams/controlplane/matrix scripts/test_matrix_room_binding_contract.py
git commit -m "feat(matrix): project unified events with room authorization"
~~~

### 完成定义

- [ ] Matrix Room 四种主上下文均能创建、查询、迁移和撤销。
- [ ] Matrix 用户和 AppService 服务主体都能生成统一 CommandEnvelope。
- [ ] Room、tenant、project、team、conversation、task 权限边界有单元和 MockMvc 覆盖。
- [ ] Inbox/Outbox 重放和投影失败不改变 Control Plane 事实。
- [ ] 旧 V6/V69 数据迁移和现有 Matrix smoke/contract 测试通过。
