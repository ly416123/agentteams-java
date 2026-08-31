# AgentTeams Java SDK

该 SDK 对应 `openapi/agentteams-public.yaml` 的 v1.0 公共 API 基线，当前提供
Project 创建、Task 查询和 Task 取消的 Java 17 客户端。

```java
AgentTeamsClient client = new AgentTeamsClient(
        "https://agentteams.example",
        () -> System.getenv("AGENTTEAMS_TOKEN"));
AgentTeamsClient.Task task = client.getTask(taskId);
```

客户端只负责认证 Header、幂等键、结构化错误和安全重试提示，不隐藏服务端
Task 状态机。GET 请求默认最多重试 2 次；写请求只有显式传入 `retrySafe=true`
时才会重试。
