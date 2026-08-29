# AgentTeams 对话运行时与 E2E 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 先用 Fake Worker/Mock QwenPaw 验证对话 UI 所需的 SSE、断线重连、取消和错误态，再接入已验证的真实 Worker、QwenPaw 和 DeepSeek 完成端到端验收。

**架构：** Conversation 服务持有会话和事件游标，运行时适配器负责与 Fake、Mock 或 QwenPaw 通信。浏览器只访问 Conversation API；真实 Worker 的 HTTP/SSE 协议、模型凭据和 Gateway 连接留在服务端/Worker 侧。Mock 能够确定性注入增量、延迟、断线、取消和失败。

**技术栈：** Java 17、Spring Boot、SSE、NATS JetStream、QwenPaw HTTP/SSE、JUnit 5、WireMock 或内置 HTTP Mock、TypeScript、Vitest、Playwright、Kind。

**执行顺序：** 本计划在管理 API 和 Console SPA 计划完成后执行；Mock 验收必须先通过，真实 Worker、QwenPaw 和 DeepSeek 验收只在本地凭据与 Worker Ready 时运行。

---

## 文件清单

- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationRuntimePort.java`、`ConversationService.java`、`ConversationRuntimeException.java`、`FakeConversationRuntime.java`、`QwenPawConversationRuntime.java`。
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationEvent.java`、`ConversationRuntimeConfiguration.java`。
- 修改：`manager/src/main/java/io/agentteams/manager/api/ManagerSessionController.java`、`ManagerApplication.java`。
- 创建：`manager/src/test/java/io/agentteams/manager/conversation/FakeConversationRuntimeTest.java`、`QwenPawConversationRuntimeTest.java`。
- 创建：`scripts/qwenpaw-conversation-mock.py`、`scripts/test_qwenpaw_conversation_mock.py`。
- 创建：`console/src/streams/SseEvent.ts`、`SseConnection.ts`、`useConversationStream.ts`、`console/tests/streams/SseConnection.test.ts`。
- 创建：`console/src/features/conversations/ConversationPage.tsx`、`ConversationSidebar.tsx`、`MessageComposer.tsx`、`RuntimeContextPanel.tsx`。
- 创建：`console/tests/features/conversations/ConversationPage.test.tsx`、`console/tests/e2e/conversation.spec.ts`。
- 修改：`manager/src/main/java/io/agentteams/manager/api/ManagerSessionController.java`、`manager/src/test/java/io/agentteams/manager/api/ManagerSessionControllerTest.java`，增加统一 Conversation 路由。
- 创建：`scripts/run-kind-console-conversation.py`、`scripts/test_run_kind_console_conversation.py`。
- 修改：`deploy/helm/agentteams-java/values.yaml`、`deploy/helm/agentteams-java/templates/manager.yaml`、`deploy/kind-qwenpaw-openai-mock.yaml`、`.github/workflows/ci.yml`。

### 任务 1：定义 Conversation 事件模型和运行时端口

**文件：**

- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationEvent.java`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationRuntimePort.java`
- 创建：`manager/src/test/java/io/agentteams/manager/conversation/ConversationEventTest.java`

- [ ] **步骤 1：编写失败测试。** 验证事件游标必须单调递增，事件类型只允许 `conversation.started`、`message.delta`、`message.completed`、`task.updated`、`tool.started`、`tool.completed`、`conversation.cancelled`、`conversation.failed`。

```java
@Test
void rejectsUnknownEventType() {
    assertThatThrownBy(() -> ConversationEvent.of(1, "unknown", "{}"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **步骤 2：运行测试验证失败。** `mvn -q -pl manager -Dtest=ConversationEventTest test`，预期 FAIL。
- [ ] **步骤 3：实现事件模型和端口。** 端口签名固定为 `start(Context)`, `send(Message)`, `cancel(UUID)`, `events(UUID,long)`；Context 只包含 Project、Team、Worker、Task 和 sessionId。
- [ ] **步骤 4：运行测试验证通过。** `mvn -q -pl manager -Dtest=ConversationEventTest test`，预期 PASS。
- [ ] **步骤 5：Commit。** `git add manager/src/main/java manager/src/test/java && git commit -m "feat(对话): 定义 Conversation 事件与运行时端口"`

### 任务 2：实现 Fake Runtime 和服务端取消

**文件：**

- 创建：`manager/src/main/java/io/agentteams/manager/conversation/FakeConversationRuntime.java`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationService.java`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationRuntimeException.java`
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/FakeConversationRuntimeTest.java`、`ConversationServiceTest.java`

- [ ] **步骤 1：编写失败测试。** 验证 Fake Runtime 按固定顺序产生 started、delta、completed；发送相同幂等键返回相同消息；取消后不再产生新的 delta。
- [ ] **步骤 2：运行测试验证失败。** `mvn -q -pl manager -Dtest=FakeConversationRuntimeTest,ConversationServiceTest test`，预期 FAIL。
- [ ] **步骤 3：实现 Fake Runtime。** 使用内存事件队列仅服务测试和本地开发；每条消息通过 `ConversationEvent` 发布，取消使用原子状态并追加 cancelled 事件。
- [ ] **步骤 4：运行测试验证通过。** `mvn -q -pl manager -Dtest=FakeConversationRuntimeTest,ConversationServiceTest test`，预期 PASS。
- [ ] **步骤 5：Commit。** `git add manager/src/main/java manager/src/test/java && git commit -m "feat(对话): 增加 Fake Conversation Runtime"`

### 任务 3：实现 Mock QwenPaw HTTP/SSE 服务

**文件：**

- 创建：`scripts/qwenpaw-conversation-mock.py`
- 创建：`scripts/test_qwenpaw_conversation_mock.py`
- 修改：`deploy/kind-qwenpaw-openai-mock.yaml`

- [ ] **步骤 1：编写失败测试。** 验证 Mock 具备 `/health`、会话发送、SSE delta、可配置延迟、断线和取消；测试必须验证断线后请求可重新建立，不需要重启 Pod 改变延迟。
- [ ] **步骤 2：运行测试验证失败。** `python3 -m unittest scripts/test_qwenpaw_conversation_mock.py`，预期 FAIL。
- [ ] **步骤 3：实现 Mock。** 延迟和故障模式保存在进程内受锁保护的状态；SSE 每个事件带 cursor；取消将会话标记为 cancelled 并返回确定性结果；不得记录请求中的凭据或完整消息。
- [ ] **步骤 4：运行测试验证通过。** `python3 -m unittest scripts/test_qwenpaw_conversation_mock.py`，预期 PASS。
- [ ] **步骤 5：Commit。** `git add scripts/qwenpaw-conversation-mock.py scripts/test_qwenpaw_conversation_mock.py deploy/kind-qwenpaw-openai-mock.yaml && git commit -m "test(对话): 增加可控 QwenPaw SSE Mock"`

### 任务 4：实现真实 QwenPaw Runtime 适配器

**文件：**

- 创建：`manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationRuntimeConfiguration.java`
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/QwenPawConversationRuntimeTest.java`
- 修改：`manager/src/main/java/io/agentteams/manager/ManagerApplication.java`

- [ ] **步骤 1：编写失败测试。** 使用本地 HTTP Server 验证 `POST /api/console/chat`、`X-Agent-Id`、可选 Bearer Token、completed/failed SSE、HTTP 错误分类和本地取消。
- [ ] **步骤 2：运行测试验证失败。** `mvn -q -pl manager -Dtest=QwenPawConversationRuntimeTest test`，预期 FAIL。
- [ ] **步骤 3：实现适配器。** 复用当前 QwenPaw HTTP/SSE 约定；解析增量和终态事件，限制响应大小和连接时间，禁止自动重定向；取消只关闭本地流并追加服务端 cancelled 状态。
- [ ] **步骤 4：运行测试验证通过。** `mvn -q -pl manager -Dtest=QwenPawConversationRuntimeTest test`，预期 PASS。
- [ ] **步骤 5：Commit。** `git add manager/src/main/java manager/src/test/java && git commit -m "feat(对话): 接入 QwenPaw Conversation Runtime"`

### 任务 5：实现前端 SSE 游标、重连和对话页面

**文件：**

- 创建：`console/src/streams/SseEvent.ts`、`SseConnection.ts`、`useConversationStream.ts`
- 创建：`console/src/features/conversations/ConversationPage.tsx`、`ConversationSidebar.tsx`、`MessageComposer.tsx`、`RuntimeContextPanel.tsx`
- 修改：`manager/src/main/java/io/agentteams/manager/api/ManagerSessionController.java`
- 测试：`console/tests/streams/SseConnection.test.ts`、`console/tests/features/conversations/ConversationPage.test.tsx`
- 测试：`manager/src/test/java/io/agentteams/manager/api/ManagerSessionControllerTest.java`

- [ ] **步骤 1：编写失败测试。** 覆盖 delta 拼接、Last-Event-ID 重连、指数退避上限、取消后停止发送、failed 事件和 Worker unavailable 展示。
- [ ] **步骤 2：运行测试验证失败。** `cd console && npm test -- --run tests/streams tests/features/conversations`，预期 FAIL。
- [ ] **步骤 3：实现 Conversation Controller 路由。** 在现有 Manager Session 权限、幂等和事件持久化约束下增加 `POST /api/v1/conversations`、`GET /api/v1/conversations/{id}`、`POST /api/v1/conversations/{id}/messages`、`GET /api/v1/conversations/{id}/events` 和 `POST /api/v1/conversations/{id}/cancel`；统一映射 `WORKER_UNAVAILABLE`、`MODEL_PROVIDER_UNAVAILABLE`、`CONVERSATION_CANCELLED` 和版本冲突。
- [ ] **步骤 4：实现 SseConnection。** 连接 URL 始终带 `after`，重连时传递最后 cursor；退避为 1、2、4、8、16 秒并封顶 16 秒；超过 5 次显示手动重试。
- [ ] **步骤 5：实现对话页。** 左栏显示会话，中央显示增量消息和取消/重试，右栏显示 Project、Team、Worker、Task、Runtime；事件驱动 Query 精确失效，不全局刷新。
- [ ] **步骤 6：运行测试验证通过。** `mvn -q -pl manager -Dtest=ManagerSessionControllerTest,ConversationServiceTest test && cd console && npm test -- --run tests/streams tests/features/conversations`，预期 PASS。
- [ ] **步骤 7：Commit。** `git add manager/src/main/java manager/src/test/java console/src/streams console/src/features/conversations console/tests && git commit -m "feat(console): 增加 SSE 对话工作台"`

### 任务 6：实现 Mock Kind 验收

**文件：**

- 创建：`scripts/run-kind-console-conversation.py`
- 创建：`scripts/test_run_kind_console_conversation.py`
- 修改：`.github/workflows/ci.yml`

- [ ] **步骤 1：编写失败契约测试。** 验证验收脚本包含登录、创建 Conversation、发送消息、SSE 断线重连、取消、Worker unavailable 和结果校验步骤。
- [ ] **步骤 2：运行测试验证失败。** `python3 -m unittest scripts/test_run_kind_console_conversation.py`，预期 FAIL。
- [ ] **步骤 3：实现验收脚本。** 通过 Keycloak 获取测试 Token，调用公开 API；Mock 故障模式通过运行时 debug API 改变，不重启 Mock Pod；脚本不得打印 Token、Key 或完整消息。
- [ ] **步骤 4：运行测试验证通过。** `python3 -m unittest scripts/test_run_kind_console_conversation.py && python3 scripts/run-kind-console-conversation.py`，预期契约测试和 Kind 验收均通过。
- [ ] **步骤 5：Commit。** `git add scripts .github/workflows/ci.yml && git commit -m "test(对话): 增加 Kind Mock 对话验收"`

### 任务 7：接入真实 Worker、QwenPaw 和 DeepSeek E2E

**文件：**

- 修改：`deploy/helm/agentteams-java/values.yaml`、`templates/manager.yaml`
- 修改：`scripts/run-kind-console-conversation.py`
- 创建：`scripts/smoke-kind-console-real-conversation.sh`
- 修改：`.github/workflows/ci.yml` 或受保护的手动验收 Workflow

- [ ] **步骤 1：编写失败验收。** 在真实 Worker 未 Ready 或真实模型未配置时，验收必须明确失败为 `WORKER_UNAVAILABLE` 或 `MODEL_PROVIDER_UNAVAILABLE`，不能报告为 UI 错误。
- [ ] **步骤 2：运行验收确认边界。** `kubectl -n agentteams get worker` 和 `./scripts/smoke-kind-console-real-conversation.sh`，预期在依赖未满足时给出对应分类。
- [ ] **步骤 3：配置真实运行时。** 使用已验证的本地 Worker 镜像、QwenPaw DeepSeek Provider 和 `deepseek-v4-flash`；模型 Key 只从本机 `apikey` 或外部 Secret 读取。
- [ ] **步骤 4：实现真实 E2E。** 验证登录、创建会话、发送消息、Task 回链、SSE 终态、取消和 Worker 重连；检查输出标记和服务端事件，不只检查 HTTP `200`。
- [ ] **步骤 5：运行真实验收通过。** `./scripts/smoke-kind-console-real-conversation.sh`，预期输出 `CONSOLE_REAL_CONVERSATION_OK`。
- [ ] **步骤 6：Commit。** `git add deploy scripts .github/workflows/ci.yml && git commit -m "test(对话): 完成真实 Worker Conversation 验收"`

### 任务 8：对话全量验证

- [ ] **步骤 1：运行 Java 测试。** `mvn -q -pl manager -am test`，预期全部通过。
- [ ] **步骤 2：运行脚本测试。** `python3 -m unittest discover -s scripts -p 'test_*.py'`，预期全部通过。
- [ ] **步骤 3：运行前端测试。** `cd console && npm test -- --run`，预期全部通过。
- [ ] **步骤 4：运行 Playwright。** `cd console && npm run e2e -- tests/e2e/conversation.spec.ts`，预期 Mock 对话、重连和取消通过。
- [ ] **步骤 5：运行完整 Docker/Kind 验收。** `source deploy/dev-env.sh && mvn -q -Pintegration-tests verify`，再运行 Mock 和真实对话脚本，预期全部通过。
- [ ] **步骤 6：Commit。** `git commit --allow-empty -m "test(对话): 完成对话运行时与 E2E 验证"`
