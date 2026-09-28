# Console、SDK 与 Matrix 统一 task.start 适配实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Console、Java SDK、TypeScript SDK 和 Matrix 使用同一语义的 `task.start` 统一命令，同时保留旧 Task API 的草稿创建兼容性。

**架构：** Control Plane 继续作为唯一命令事实源，复用现有 `/api/v1/commands`、`UnifiedCommandController` 和 `CommandDispatcher`。各客户端只负责构造并发送 `CommandEnvelope`，不复制授权、幂等或审计逻辑。Console 同时保留“创建草稿”和新增的“统一入口创建”两条明确流程。

**技术栈：** Java 21、JUnit 5、TypeScript、React、Vitest、现有 HTTP 客户端和 OIDC 用户上下文。

---

## 文件职责

- 修改：`sdk/java/src/main/java/io/agentteams/sdk/AgentTeamsClient.java`，增加 Java SDK 的统一命令请求类型、响应类型和 `startTask` 方法。
- 修改：`sdk/java/src/test/java/io/agentteams/sdk/AgentTeamsClientTest.java`，验证统一命令 JSON、鉴权、幂等键、响应和结构化错误。
- 修改：`sdk/typescript/src/client.ts`，增加 TypeScript SDK 的统一命令类型和 `startTask` 方法。
- 修改：`sdk/typescript/test/client.test.ts`，验证请求路径、请求体、重试和响应解码。
- 创建：`console/src/api/commands.ts`，封装 Console 的 `task.start` API。
- 修改：`console/src/queries/useTaskQueries.ts`，提供 Console 的 `useStartTask` mutation。
- 修改：`console/src/features/tasks/TaskCreatePage.tsx`，保留草稿创建并增加统一入口创建操作。
- 修改：`console/tests/api/contracts.test.ts`，增加 Console 命令 API 合同测试。
- 创建：`console/tests/features/tasks/TaskCreatePage.test.tsx`，验证两种创建模式的调用和错误状态。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/command/CommandDispatcherTest.java`，补充跨渠道稳定字段和 `sourceConversationId` 断言。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixCommandParserTest.java`，固定 Matrix 与客户端命令在业务字段上的同构约束。
- 修改：`docs/superpowers/plans/2026-09-23-conversation-evaluation-rollout.md`，在全部集成验收通过后更新复选框和证据。

### 任务 1：先为 Java SDK 编写失败测试

**文件：** 修改 `sdk/java/src/test/java/io/agentteams/sdk/AgentTeamsClientTest.java`。

- [ ] **步骤 1：编写失败测试**

在现有本地 `HttpServer` 测试中增加命令端点，断言 SDK 发送以下业务字段：

```java
assertThat(exchange.getRequestMethod()).isEqualTo("POST");
assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/v1/commands");
assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("start-1");
String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
assertThat(body).contains("\"commandType\":\"task.start\"")
        .contains("\"aggregateType\":\"TASK\"")
        .contains("\"tenantId\":\"tenant-a\"")
        .contains("\"channel\":\"SDK\"")
        .contains("\"sourceConversationId\":\"00000000-0000-0000-0000-000000000031\"");
respond(exchange, 202, "{\"commandId\":\"00000000-0000-0000-0000-000000000032\",\"status\":\"ACCEPTED\",\"duplicate\":false,\"taskId\":\"00000000-0000-0000-0000-000000000033\",\"aggregateVersion\":0,\"traceId\":\"trace-1\"}");
```

调用目标为：

```java
AgentTeamsClient.CommandResult result = client.startTask(
        new AgentTeamsClient.StartTaskRequest("tenant-a", "user-1", "生成周报",
                "汇总数据", json("{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}"),
                UUID.fromString("00000000-0000-0000-0000-000000000031")),
        "start-1", false);
assertThat(result.status()).isEqualTo("ACCEPTED");
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl sdk/java -Dtest=AgentTeamsClientTest test
```

预期：编译失败，提示 `StartTaskRequest`、`CommandResult` 或 `startTask` 尚不存在。

- [ ] **步骤 3：实现最小 Java SDK 适配**

在 `AgentTeamsClient` 中新增：

```java
public CommandResult startTask(StartTaskRequest request, String idempotencyKey, boolean retrySafe) {
    ObjectNode command = JSON.createObjectNode();
    command.put("commandId", UUID.randomUUID().toString());
    command.put("commandType", "task.start");
    command.put("tenantId", request.tenantId());
    ObjectNode actor = command.putObject("actor");
    actor.put("type", "USER").put("id", request.actorId()).put("channel", "SDK");
    command.putNull("taskId").put("aggregateType", "TASK").put("aggregateVersion", 0);
    if (request.conversationId() == null) command.putNull("conversationId");
    else command.put("conversationId", request.conversationId().toString());
    ObjectNode payload = command.putObject("payload");
    payload.put("title", request.title()).put("description", request.description());
    payload.set("spec", request.spec().deepCopy());
    command.put("idempotencyKey", idempotencyKey).put("traceId", UUID.randomUUID().toString());
    command.put("occurredAt", Instant.now().toString());
    return send("POST", "/api/v1/commands", command, CommandResult.class, retrySafe, idempotencyKey);
}
```

`StartTaskRequest` 校验租户、主体、标题、说明和 Spec 非空；`CommandResult` 只描述统一命令响应。旧 `createTask` 方法不改动。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
mvn -q -pl sdk/java -Dtest=AgentTeamsClientTest test
```

预期：全部 Java SDK 测试 PASS。

- [ ] **步骤 5：提交**

```bash
git add sdk/java/src/main/java/io/agentteams/sdk/AgentTeamsClient.java sdk/java/src/test/java/io/agentteams/sdk/AgentTeamsClientTest.java
git commit -m "feat(SDK): 增加统一 task.start 客户端"
```

### 任务 2：增加 TypeScript SDK 统一命令适配

**文件：** 修改 `sdk/typescript/src/client.ts`、`sdk/typescript/test/client.test.ts`。

- [ ] **步骤 1：编写失败测试**

新增 `startTask` 测试，使用现有 fetch mock 断言路径、`Idempotency-Key`、`commandType`、`actor.channel`、`conversationId` 和 `payload.spec`，并返回 `CommandResult`。

```ts
const result = await client.startTask(
  {
    tenantId: 'tenant-a',
    actorId: 'user-1',
    title: '生成周报',
    description: '汇总数据',
    spec: { scope: { tenant: 'tenant-a', project: 'project-a', team: 'team-a' } },
    conversationId: 'conversation-1',
  },
  { idempotencyKey: 'start-1' },
);
expect(result.status).toBe('ACCEPTED');
expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/api/v1/commands'), expect.objectContaining({
  method: 'POST',
  headers: expect.objectContaining({ 'Idempotency-Key': 'start-1' }),
}));
```

- [ ] **步骤 2：运行测试确认失败**

运行：`npm --prefix sdk/typescript test -- --run client.test.ts`。

预期：TypeScript 编译失败，提示 `startTask` 或相关类型不存在。

- [ ] **步骤 3：实现最小 TypeScript SDK 适配**

新增类型和方法：

```ts
export type StartTaskRequest = {
  tenantId: string;
  actorId: string;
  title: string;
  description: string;
  spec: Record<string, unknown>;
  conversationId?: string;
};

export type CommandResult = {
  commandId: string;
  status: string;
  duplicate: boolean;
  taskId?: string | null;
  aggregateVersion: number;
  traceId: string;
};

startTask(body: StartTaskRequest, options: { idempotencyKey?: string; retrySafe?: boolean } = {}) {
  return this.request<CommandResult>('/api/v1/commands', {
    method: 'POST',
    body: {
      commandId: crypto.randomUUID(), commandType: 'task.start', tenantId: body.tenantId,
      actor: { type: 'USER', id: body.actorId, channel: 'SDK' }, conversationId: body.conversationId ?? null,
      taskId: null, aggregateType: 'TASK', aggregateVersion: 0,
      payload: { title: body.title, description: body.description, spec: body.spec },
      idempotencyKey: options.idempotencyKey ?? `sdk-${crypto.randomUUID()}`,
      traceId: crypto.randomUUID(), occurredAt: new Date().toISOString(),
    },
    idempotencyKey: options.idempotencyKey, retrySafe: options.retrySafe,
  });
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`npm --prefix sdk/typescript test -- --run client.test.ts`。

预期：SDK 测试全部 PASS，且现有 `createTask` 测试保持 PASS。

- [ ] **步骤 5：提交**

```bash
git add sdk/typescript/src/client.ts sdk/typescript/test/client.test.ts
git commit -m "feat(SDK): 增加 TypeScript 统一 task.start"
```

### 任务 3：增加 Console API 和显式统一入口创建

**文件：** 创建 `console/src/api/commands.ts`；修改 `console/src/queries/useTaskQueries.ts`、`console/src/features/tasks/TaskCreatePage.tsx`、`console/tests/api/contracts.test.ts`；创建 `console/tests/features/tasks/TaskCreatePage.test.tsx`。

- [ ] **步骤 1：编写失败测试**

API 合同测试验证：

```ts
await startTask({
  tenantId: 'tenant-a', actorId: 'user-1', title: '生成周报', description: '汇总数据',
  spec: { scope: { tenant: 'tenant-a', project: 'project-a', team: 'team-a' }, teamId: 'team-1' },
  conversationId: 'conversation-1',
}, client);
expect(client.request).toHaveBeenCalledWith('/api/v1/commands', {
  method: 'POST',
  body: expect.objectContaining({
    commandType: 'task.start', tenantId: 'tenant-a',
    actor: { type: 'USER', id: 'user-1', channel: 'CONSOLE' },
    conversationId: 'conversation-1', aggregateType: 'TASK', aggregateVersion: 0,
  }),
});
```

页面测试验证：默认按钮仍调用 `createTask`；新增“统一入口创建”按钮读取 OIDC `user.profile.sub`，调用 `startTask`，成功后跳转 `taskId`，失败显示结构化错误。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
npm --prefix console test -- --run tests/api/contracts.test.ts tests/features/tasks/TaskCreatePage.test.tsx
```

预期：编译或测试失败，提示 `startTask` API、mutation 或页面操作尚不存在。

- [ ] **步骤 3：实现最小 Console 适配**

`commands.ts` 构造与 SDK 相同的请求，仅将 `actor.channel` 设为 `CONSOLE`；`useStartTask` 复用现有 QueryClient 缓存失效逻辑。页面新增第二个按钮，不改变现有草稿创建按钮：

```tsx
const auth = useAuth();
const start = useStartTask(projectId);

start.mutate({
  tenantId: project.tenantId,
  actorId: String(auth.user?.profile.sub || ''),
  title, description,
  spec: { scope: { tenant: project.tenantId, project: project.name, team: selectedTeam.name }, teamId: selectedTeam.id },
}, { onSuccess: (result) => navigate(`/${projectId}/tasks/${result.taskId}`) });
```

按钮在未获取 OIDC `sub`、未选择项目/团队或请求进行中时禁用；失败沿用 `ErrorState`，不展示原始响应体。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
npm --prefix console test -- --run tests/api/contracts.test.ts tests/features/tasks/TaskCreatePage.test.tsx
npm --prefix console run build
```

预期：目标测试和前端构建 PASS。

- [ ] **步骤 5：提交**

```bash
git add console/src/api/commands.ts console/src/queries/useTaskQueries.ts console/src/features/tasks/TaskCreatePage.tsx console/tests/api/contracts.test.ts console/tests/features/tasks/TaskCreatePage.test.tsx
git commit -m "feat(Console): 增加统一 task.start 入口"
```

### 任务 4：补齐 Control Plane 跨渠道契约测试

**文件：** 修改 `control-plane/src/test/java/io/agentteams/controlplane/command/CommandDispatcherTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixCommandParserTest.java`。

- [ ] **步骤 1：编写失败测试**

增加断言：SDK/Console 风格的 `CommandEnvelope` 与 Matrix `CommandEnvelope` 在 `commandType`、`aggregateType`、租户、Conversation 关联、Task Spec 和幂等语义上相同；只允许渠道、命令标识、幂等键和追踪字段不同。验证 Dispatcher 将 Conversation ID 写入 Task Spec，并记录 `COMMAND_ACCEPTED`。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl control-plane -Dtest='CommandDispatcherTest,MatrixCommandParserTest' test
```

预期：新增断言先失败，暴露跨渠道字段差异。

- [ ] **步骤 3：实现必要的服务端兼容修正**

仅修正导致同构断言失败的字段映射；不得新增第二套 Task 创建逻辑，也不得改变旧 `/api/v1/tasks` 的响应语义。优先复用 `CommandDispatcher.taskSpec` 和现有 Matrix Parser。

- [ ] **步骤 4：运行测试确认通过**

运行同一条 Maven 命令，预期目标测试全部 PASS。

- [ ] **步骤 5：提交**

```bash
git add control-plane/src/test/java/io/agentteams/controlplane/command/CommandDispatcherTest.java control-plane/src/test/java/io/agentteams/controlplane/matrix/MatrixCommandParserTest.java control-plane/src/main/java
git commit -m "test(协议): 验证跨渠道 task.start 语义一致"
```

### 任务 5：执行项目级集成验收并更新计划

**文件：** 修改 `docs/superpowers/plans/2026-09-23-conversation-evaluation-rollout.md`。

- [ ] **步骤 1：运行定向回归**

运行：

```bash
mvn -q -pl control-plane,manager,sdk/java -am -Dtest='CommandDispatcherTest,MatrixCommandParserTest,AgentTeamsClientTest,ConversationServiceTest,ConversationTimelineServiceTest,EvidenceBundleAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix sdk/typescript test -- --run
npm --prefix console test -- --run
```

预期：Java、TypeScript SDK 和 Console 目标测试全部 PASS。

- [ ] **步骤 2：运行项目级回归**

运行：

```bash
mvn -q test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
npm --prefix console run build
git diff --check
```

预期：所有命令退出码为 `0`，且 Surefire 没有失败报告。

- [ ] **步骤 3：更新集成验收清单**

只有在测试证据齐全后，将整体计划中的 7 条集成验收逐项标记为 `[x]`，并记录统一命令入口、旧 API 兼容性和回归命令；若任一渠道仍未产生统一审计，不得勾选第一条。

- [ ] **步骤 4：提交**

```bash
git add docs/superpowers/plans/2026-09-23-conversation-evaluation-rollout.md
git commit -m "test(集成): 完成跨渠道 task.start 验收"
```

## 完成定义

- [ ] Console、Java SDK、TypeScript SDK 和 Matrix 都能提交同构的 `task.start` 命令。
- [ ] Control Plane 统一产生命令审计、Task Event 和 `sourceConversationId`。
- [ ] 旧 `/api/v1/tasks` 创建、Manager Conversation API 和 Matrix 兼容路径不回归。
- [ ] Java、TypeScript、Console、Control Plane 和项目级回归全部通过。
