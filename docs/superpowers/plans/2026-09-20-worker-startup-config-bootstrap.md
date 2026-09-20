# Worker 启动配置快照恢复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Worker 启动时从 Control Plane 拉取该 Agent 的当前配置绑定，并复用现有 `ConfigChanged` 路径恢复 MCP/模型/技能配置。

**架构：** Control Plane 暴露按 Agent 查询当前绑定的只读接口，返回快照校验信息与已完成文件元数据。Worker 使用独立 bootstrap fetcher 将响应转换为现有 `ConfigChanged` protobuf，再调用现有配置应用流程；失败只影响启动恢复，不影响 Gateway 注册。

**技术栈：** Java 21、Spring MVC、Spring JDBC、Jackson、protobuf、JUnit 5、AssertJ、Maven。

---

## 文件清单

- 修改 `control-plane/src/main/java/io/agentteams/controlplane/config/ConfigLifecycleRepository.java`：增加按 Agent 查询当前绑定的方法。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/config/ConfigDeploymentService.java`：组合绑定、快照和文件，提供启动恢复所需的只读数据。
- 修改 `control-plane/src/main/java/io/agentteams/controlplane/api/ConfigController.java`：增加 `GET /api/v1/config/agents/{agentId}/current` 响应 DTO 和作用域校验。
- 修改 `agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`：创建 fetcher，并在 Gateway 连接前执行一次启动恢复。
- 创建 `agent-worker/src/main/java/io/agentteams/worker/ConfigBootstrapFetcher.java`：请求、限制响应大小、解析并验证当前绑定响应，转换为 `ConfigChanged`。
- 修改 `control-plane/src/test/java/io/agentteams/controlplane/config/ConfigLifecycleRepositoryTest.java`（若当前仓库没有该测试文件则创建）：覆盖按 Agent 查询。
- 创建 `control-plane/src/test/java/io/agentteams/controlplane/api/ConfigControllerBootstrapTest.java`：覆盖空结果、绑定响应和作用域校验。
- 创建 `agent-worker/src/test/java/io/agentteams/worker/ConfigBootstrapFetcherTest.java`：覆盖成功、HTTP 错误、超限、字段校验和文件元数据校验。
- 修改 `agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerTest.java`：覆盖启动恢复失败仍连接 Gateway 的降级行为。
- 修改 `docs/superpowers/plans/2026-09-20-l5-mcp-worker-integration.md`：补充启动恢复验收结果和已知限制。

### 任务 1：Control Plane 当前绑定读取

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/config/ConfigLifecycleRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/config/ConfigDeploymentService.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/config/ConfigLifecycleRepositoryTest.java`

- [ ] **步骤 1：编写失败测试**

在仓储测试中插入两个不同 subject 的 `config_bindings`，为同一 Agent 指向两个快照，再调用 `findBindingsForAgent(agentId)`，断言返回两个绑定并按 subject、binding id 稳定排序；另插入其他 Agent，断言不返回。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl control-plane -Dtest=ConfigLifecycleRepositoryTest test
```

预期：编译失败，提示 `findBindingsForAgent` 不存在。

- [ ] **步骤 3：实现仓储查询**

在 `ConfigLifecycleRepository` 增加：

```java
public List<ConfigBindingRecord> findBindingsForAgent(UUID agentId) {
    return jdbc.query("""
        SELECT id, subject, agent_id, snapshot_id, desired_at
          FROM config_bindings
         WHERE agent_id = ?
         ORDER BY subject, id
        """, mapper, agentId);
}
```

- [ ] **步骤 4：实现服务组合模型**

在 `ConfigDeploymentService` 增加公开只读方法和嵌套 record：

```java
public List<CurrentConfigBinding> findCurrentForAgent(UUID agentId) {
    return persistence.inTransaction(tx -> tx.configLifecycle().findBindingsForAgent(agentId).stream()
        .map(binding -> new CurrentConfigBinding(binding,
            snapshots.findById(binding.snapshotId()).orElseThrow(),
            tx.configLifecycle().findFiles(binding.snapshotId())))
        .toList());
}

public record CurrentConfigBinding(ConfigBindingRecord binding,
        ConfigSnapshot snapshot, List<ConfigFileRecord> files) { }
```

缺失快照必须抛出明确的 `IllegalStateException`，避免返回不完整启动数据。

- [ ] **步骤 5：运行测试确认通过**

运行：

```bash
mvn -q -pl control-plane -Dtest=ConfigLifecycleRepositoryTest,ConfigDeploymentServiceTest test
```

预期：PASS。

- [ ] **步骤 6：提交**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/config control-plane/src/test/java/io/agentteams/controlplane/config/ConfigLifecycleRepositoryTest.java
git commit -m "feat(Control Plane): 查询 Agent 当前配置绑定"
```

### 任务 2：Control Plane 启动恢复接口

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/ConfigController.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/api/ConfigControllerBootstrapTest.java`

- [ ] **步骤 1：编写失败测试**

使用现有 MockMvc/Controller 测试风格，验证 `GET /api/v1/config/agents/{agentId}/current`：

- 无绑定返回 `200 []`。
- 一个绑定返回 `bindingId`、`agentId`、`snapshotId`、`configVersion`、`manifestUri`、`manifestSha256`、`sizeBytes` 和文件数组。
- 返回的 `manifestJson` 仅在 64 KiB 内联限制内出现，文件 URI 使用现有 `urn:agentteams:config-file:{snapshotId}:{path}` 格式。
- 已认证请求调用 `PrincipalContext.requireScope(snapshot.manifestJson())`。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl control-plane -Dtest=ConfigControllerBootstrapTest test
```

预期：接口映射不存在或响应字段未定义。

- [ ] **步骤 3：实现响应 DTO 和接口**

在 `ConfigController` 注入 `ConfigDeploymentService` 已有实例，增加：

```java
@GetMapping("/agents/{agentId}/current")
public List<CurrentConfigResponse> current(@PathVariable UUID agentId) {
    return deployments.findCurrentForAgent(agentId).stream().map(CurrentConfigResponse::from).toList();
}
```

`CurrentConfigResponse.from` 计算 manifest UTF-8 字节数，超过 64 KiB 时将 `manifestJson` 置为空字符串；其余字段必须与 `ConfigChanged` 的下载校验字段一致。对每个快照执行既有 `PrincipalContext.requireScope`。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
mvn -q -pl control-plane -Dtest=ConfigControllerBootstrapTest,ConfigControllerTest test
```

预期：PASS。

- [ ] **步骤 5：提交**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/api/ConfigController.java control-plane/src/test/java/io/agentteams/controlplane/api/ConfigControllerBootstrapTest.java
git commit -m "feat(Control Plane): 暴露 Agent 当前配置快照"
```

### 任务 3：Worker 启动快照 Fetcher

**文件：**
- 创建：`agent-worker/src/main/java/io/agentteams/worker/ConfigBootstrapFetcher.java`
- 创建：`agent-worker/src/test/java/io/agentteams/worker/ConfigBootstrapFetcherTest.java`

- [ ] **步骤 1：编写失败测试**

使用 `HttpServer` 返回固定 JSON，验证 `fetch(agentId)` 生成一个 `ConfigChanged`：

```java
assertThat(changed.getBindingId()).isEqualTo(bindingId.toString());
assertThat(changed.getConfigVersion()).isEqualTo(7);
assertThat(changed.getManifestSha256()).hasSize(64);
assertThat(changed.getFiles(0).getUri()).startsWith("urn:agentteams:config-file:");
assertThat(changed.getMetadata().getSequence()).isZero();
```

另外验证：HTTP 401/500、响应超过 `maxBytes`、顶层不是数组、agentId 不匹配、缺少 UUID/摘要/版本、文件路径为空或大小超过文件上限均抛出带稳定前缀的异常；正常空数组返回空列表。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl agent-worker -Dtest=ConfigBootstrapFetcherTest test
```

预期：编译失败，类不存在。

- [ ] **步骤 3：实现最小 Fetcher**

Fetcher 使用 `HttpClient` 和 `BodyHandlers.ofInputStream()`：

- URL 固定为 `{base}/api/v1/config/agents/{agentId}/current`，对 UUID 做路径编码/严格格式校验。
- 响应体最多读取 `maxBytes`，非 2xx 只保留最多 4 KiB 错误摘要。
- Jackson 逐项解析并校验 agentId、bindingId、snapshotId、版本、摘要、大小和文件字段。
- 将每项构造成 `ConfigChanged`，`metadata.agent_id` 设置为 Worker agentId，`metadata.sequence` 保持 0，`rollback=false`。
- 不记录响应正文；InterruptedException 恢复中断标记并转换为失败。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
mvn -q -pl agent-worker -Dtest=ConfigBootstrapFetcherTest,ConfigManifestFetcherTest,ConfigFileFetcherTest test
```

预期：PASS。

- [ ] **步骤 5：提交**

```bash
git add agent-worker/src/main/java/io/agentteams/worker/ConfigBootstrapFetcher.java agent-worker/src/test/java/io/agentteams/worker/ConfigBootstrapFetcherTest.java
git commit -m "feat(Worker): 增加启动配置快照拉取"
```

### 任务 4：接入 Worker 启动流程

**文件：**
- 修改：`agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`
- 修改：`agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerTest.java`

- [ ] **步骤 1：编写失败测试**

在 Worker 测试中提供不可用的配置基础 URL，调用 `start()`，断言 Worker 仍进入 Gateway 连接路径且只产生脱敏启动恢复日志；另用成功 HTTP 响应断言启动阶段调用现有配置应用逻辑，并且不向 Gateway 发送 `ConfigApplied` ACK。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
mvn -q -pl agent-worker -Dtest=QwenPawWorkerTest test
```

预期：启动流程尚未调用 bootstrap fetcher，新增断言失败。

- [ ] **步骤 3：接入启动恢复**

在 Worker 构造函数创建 `ConfigBootstrapFetcher`；在 `start()` 中按顺序执行：

```java
healthServer.start();
bootstrapCurrentConfig();
channelPort.connect();
channelClient.connect(hello);
```

`bootstrapCurrentConfig()` 对每个 `ConfigChanged` 调用现有 `onConfigChanged`，但通过参数或独立入口跳过 ACK；捕获所有恢复异常，日志只包含 agent、binding、snapshot、version 和 `rootMessage`，然后继续连接 Gateway。为保持现有实时路径行为，将 ACK 判断封装成 `applyConfigChanged(changed, acknowledge)`，实时事件传 `true`，启动恢复传 `false`。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
mvn -q -pl agent-worker -Dtest=QwenPawWorkerTest,ConfigBootstrapFetcherTest test
```

预期：PASS。

- [ ] **步骤 5：提交**

```bash
git add agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerTest.java
git commit -m "feat(Worker): 启动时恢复当前配置"
```

### 任务 5：全量验证、部署与 L5 重启验收

**文件：**
- 修改：`docs/superpowers/plans/2026-09-20-l5-mcp-worker-integration.md`
- 可能修改：`deploy/helm/l5-values.yaml`（仅更新最终 Worker 镜像标签）

- [ ] **步骤 1：运行单元和契约测试**

运行：

```bash
mvn -B -pl agent-worker,control-plane -am test
python3 -m unittest scripts.test_batch_a_helm_contract
 git diff --check
```

预期：所有测试通过且无 diff whitespace 错误。

- [ ] **步骤 2：构建并导入 L5 镜像**

使用现有 Colima Docker context 构建 amd64 Worker 镜像，导入远端 K3s，更新 Helm values 中 Worker image tag，执行 Helm upgrade；确认 Control Plane、Operator、MCP 和 Worker Pod 均 Ready。

- [ ] **步骤 3：重启已配置 AgentScope Worker**

只重启已有 AgentScope Worker Pod，不重新创建 AgentSpec、不重新发布配置。检查 Worker 日志出现启动恢复请求/应用结果，且没有输出 manifest 或 Secret 值。

- [ ] **步骤 4：执行真实 MCP 验收**

通过现有 Keycloak/API 流程创建要求 `runtime-agentscope-v1` 的任务，要求调用 `web_search`。断言任务 `SUCCEEDED`、输出为中国人民银行官网域名，并检查 MCP 服务端出现 AgentScope initialize 日志。

- [ ] **步骤 5：记录结果并提交**

将镜像摘要、任务 ID、Worker 重启时间、MCP initialize 日志证据和任何限制写入现有 L5 集成计划；提交并推送分支。

```bash
git add docs/superpowers/plans/2026-09-20-l5-mcp-worker-integration.md deploy/helm/l5-values.yaml
git commit -m "test(L5): 验证 Worker 启动恢复配置"
git push origin codex/l5-mcp-integration
```

## 规格覆盖自检

- Control Plane 当前绑定查询：任务 1、2。
- Worker 启动拉取与复用现有 `ConfigChanged` 路径：任务 3、4。
- 失败继续注册、无 ACK、脱敏日志：任务 3、4。
- 不暴露凭据、沿用现有下载和作用域边界：任务 2、3。
- 单元、接口、回归和 L5 重启验收：任务 1、2、3、4、5。
