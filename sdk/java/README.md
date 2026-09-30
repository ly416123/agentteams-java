# AgentTeams Java SDK

该 SDK 对应 `openapi/agentteams-public.yaml` 的 v1.0 公共 API 基线，提供
Project 创建、Task 查询、Task 取消和用户 Provisioning 的 Java 17 客户端。

```java
AgentTeamsClient client = new AgentTeamsClient(
        "https://agentteams.example",
        System.getenv("AGENTTEAMS_ACCESS_KEY_ID"),
        System.getenv("AGENTTEAMS_ACCESS_KEY_SECRET"),
        "external-org-1").asUser("external-user-1");
AgentTeamsClient.Task task = client.getTask(taskId);
```

统一命令入口可以创建带 Conversation 关联的 Task。`actorId` 必须与当前认证主体一致；返回的
`CommandResult.deduplicated()` 用于识别幂等重放。旧的 `createTask` 仍保留，用于兼容草稿创建接口。

```java
ObjectMapper mapper = new ObjectMapper();
AgentTeamsClient.CommandResult started = client.startTask(
        new AgentTeamsClient.StartTaskRequest(
                "tenant-a", "external-user-1", "生成周报", "汇总数据",
                mapper.readTree("{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}"),
                conversationId),
        "task-start-1", true);
```

管理端适配器还可以使用 OIDC Bearer Token 创建 Agent、创建/发布 AgentSpec，并把已发布
配置部署到 Agent：

```java
AgentTeamsClient admin = new AgentTeamsClient(
        "https://agentteams.example", () -> shortLivedToken);
AgentTeamsClient.Agent agent = admin.createAgent(
        new AgentTeamsClient.CreateAgentRequest("leader", "agentscope", "LEADER", null, null),
        "project-a", "corp-agent-agent-leader");
AgentTeamsClient.AgentSpec spec = admin.createAgentSpec(
        new AgentTeamsClient.CreateAgentSpecRequest("leader", "agentscope", "default",
                "qwen3.5-plus", "team-a", "RUNNING", "LEADER", specJson),
        "project-a", "corp-agent-spec-leader");
admin.publishAgentSpec(spec.id(), "project-a", "corp-agent-publish-leader");
admin.deployAgentSpec(spec.id(), agent.id(), "project-a", "corp-agent");
```

这些管理接口携带 `Authorization: Bearer`、项目作用域和幂等请求头；token 由调用方按需
刷新，SDK 不保存或打印 token。

客户端会为每个请求生成应用级 HMAC-SHA256 签名，并发送外部组织、外部用户、
时间戳、Nonce、Body SHA-256 和签名 Header。`asUser(externalUserId)` 用于明确
请求代表的外部用户；`provisioning()` 提供用户初始化、更新、禁用和成员查询。

写请求可显式传入稳定的 `Idempotency-Key` 和 `retrySafe=true`，以安全启用重试；
请求 Body Hash 在重试中保持不变。GET 请求默认最多重试 2 次，写请求默认不重试。
失败响应统一抛出 `ApiErrorException`，可通过 `error()` 读取结构化状态码、错误码、
消息和关联 ID。

安全要求：`accessKeySecret` 是长期服务端凭证，只能保存在服务端环境变量、Secret
Manager 或 Kubernetes External Secret 中，不能写入浏览器、移动端、源代码、日志、
异常信息或提交的配置文件。浏览器场景应使用 OIDC 或短期 Token；Secret 轮换时应
先配置新凭证，确认生效后再撤销旧凭证。
