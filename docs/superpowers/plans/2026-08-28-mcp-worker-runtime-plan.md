# MCP Worker Runtime Port 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 将已解析的 MCP server revision 从 AgentSpec manifest 安全传递到 Worker，并通过独立 runtime Port 注册到 AgentScope Harness。

**架构：** Control Plane 只下发 MCP 的 serverId、revision、transport、endpoint 和 credentialRef 等非敏感运行元数据；Worker 将其解析为不可变 runtime binding。AgentScope 通过 Worker 侧 `McpRuntimePort` 创建受限 MCP 客户端并注册工具，凭证通过动态 `McpCredentialProvider` 按 credentialRef 获取，不写入 manifest、配置快照或日志。

**技术栈：** Java 17、Maven、Spring/AgentScope Harness 2.0.1、Jackson、JUnit 5、AssertJ、Mockito。

---

## 文件清单与职责

### 公共运行时模型

- 创建：`runtime/src/main/java/io/agentteams/runtime/RuntimeMcpServer.java`，定义校验后的 MCP runtime binding。
- 修改：`runtime/src/main/java/io/agentteams/runtime/RuntimeConfigSnapshot.java`，持有不可变 MCP binding 集合并保持旧构造函数兼容。
- 测试：`runtime/src/test/java/io/agentteams/runtime/RuntimeConfigSnapshotTest.java`，验证 binding 隔离和敏感字段不进入序列化值。

### Control Plane manifest 元数据

- 创建：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/McpRuntimeMetadata.java`，定义并校验 MCP 非敏感元数据。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecReferenceCatalog.java`，携带可选 MCP runtime metadata，保留兼容构造函数。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecReferenceBinding.java`，将 metadata 固化到 binding。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecMcpServiceReferenceCatalogAdapter.java`，从 MCP registry 生成元数据。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/agentspec/AgentSpecDeploymentService.java`，只向 manifest 写入非敏感 MCP 字段。
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/agentspec/AgentSpecDeploymentServiceTest.java` 和 `McpRuntimeMetadataTest.java`。

### Worker 解析与 AgentScope 适配

- 修改：`agent-worker/src/main/java/io/agentteams/worker/ResourceBindingLoader.java`，解析 MCP runtime 元数据并拒绝不安全字段。
- 修改：`agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`，将 MCP binding 转换到 RuntimeConfigSnapshot。
- 创建：`agent-worker/src/main/java/io/agentteams/worker/agentscope/McpCredentialProvider.java`，定义动态 credentialRef 解析端口。
- 创建：`agent-worker/src/main/java/io/agentteams/worker/agentscope/EnvironmentMcpCredentialProvider.java`，默认从环境变量读取凭证但不暴露值。
- 创建：`agent-worker/src/main/java/io/agentteams/worker/agentscope/McpRuntimePort.java`，定义 AgentScope 无关的配置边界。
- 创建：`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeMcpRuntimePort.java`，适配 AgentScope MCP client/toolkit 注册。
- 修改：`agent-worker/src/main/java/io/agentteams/worker/agentscope/AgentScopeHarnessFactory.java` 和 `ConfiguredAgentScopeHarnessFactory.java`，在配置激活和 Agent 创建时接入 Port。
- 测试：`agent-worker/src/test/java/io/agentteams/worker/ResourceBindingLoaderTest.java`、`QwenPawConfigSnapshotTest.java`、`agentscope/AgentScopeMcpRuntimePortTest.java` 和 `EnvironmentMcpCredentialProviderTest.java`。

### 文档与交付

- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md`，记录 Worker runtime Port 已完成和真实外部 MCP 长期运行边界。
- 修改：`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md`，同步批次 C 进度。

## 任务 1：建立 Runtime MCP binding 契约

- [x] **步骤 1：编写失败的测试**：覆盖 transport/endpoint/revision/credentialRef 校验、HTTP scheme、无 token 进入 `RuntimeConfigSnapshot.values`，以及 map 不可变。
- [x] **步骤 2：运行红灯**：执行 `mvn -q -pl runtime -am -Dtest=RuntimeConfigSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因 `RuntimeMcpServer` 和 snapshot MCP 字段不存在而失败。
- [x] **步骤 3：实现最小模型**：新增 `RuntimeMcpServer`，只允许 `SSE`/`STREAMABLE_HTTP`、`http/https` 无 userinfo/fragment/query endpoint；在 `RuntimeConfigSnapshot` 增加 `Map<String, RuntimeMcpServer> mcpServers` 和兼容构造函数。
- [x] **步骤 4：运行绿灯**：同一目标测试通过，确认旧构造函数仍可编译。
- [x] **步骤 5：提交**：`git add runtime && git commit -m "feat(MCP): 增加 Worker runtime binding 契约"`。

## 任务 2：下发 Control Plane MCP runtime 元数据

- [x] **步骤 1：编写失败的测试**：增加 metadata 边界测试，并在部署测试中断言 MCP manifest 包含 serverId/transport/endpoint/credentialRef，同时不包含 Authorization/token/header。
- [x] **步骤 2：运行红灯**：执行 `mvn -q -pl control-plane -am -Dtest=McpRuntimeMetadataTest,AgentSpecDeploymentServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新类型和 manifest 字段断言失败。
- [x] **步骤 3：实现最小传递链**：新增 `McpRuntimeMetadata`；扩展 `ReferenceMetadata`、`AgentSpecReferenceBinding` 的兼容字段；MCP catalog 从 `McpServerRecord` 提供元数据；manifest 仅写入非敏感字段。
- [x] **步骤 4：运行绿灯**：目标测试通过，并确认旧 model/skill metadata JSON 不发生变化。
- [x] **步骤 5：提交**：`git add control-plane && git commit -m "feat(MCP): 下发安全运行元数据"`。

## 任务 3：Worker 解析并装配 MCP binding

- [x] **步骤 1：编写失败的测试**：验证 ResourceBindingLoader 能读取 MCP 元数据，旧 manifest 保持兼容；验证 `buildConfigSnapshot` 生成按 binding key 隔离的 MCP map，缺失元数据时 fail-closed。
- [x] **步骤 2：运行红灯**：执行 `mvn -q -pl agent-worker -am -Dtest=ResourceBindingLoaderTest,QwenPawConfigSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因字段和装配逻辑不存在而失败。
- [x] **步骤 3：实现最小装配**：扩展 `ResourceBindingLoader.ResourceBinding` 的可选 MCP 字段；仅 MCP 类型转换为 `RuntimeMcpServer`；缺失 runtime metadata 或非数字 revision 时返回稳定 `RUNTIME_UNSUPPORTED`，不把资源绑定 JSON 写进普通 values。
- [x] **步骤 4：运行绿灯**：目标测试通过，旧无 metadata manifest 仍能被解析但激活 MCP 时 fail-closed。
- [x] **步骤 5：提交**：`git add agent-worker && git commit -m "feat(MCP): Worker 装配 runtime binding"`。

## 任务 4：AgentScope MCP Runtime Port

- [x] **步骤 1：编写失败的测试**：验证 Port 只注册 MCP binding、只支持允许的 HTTP transport、请求时按 credentialRef 动态取值、缺凭证时不发送空 Authorization，并且异常只返回固定分类不泄露 endpoint/secret。
- [x] **步骤 2：运行红灯**：执行 `mvn -q -pl agent-worker -am -Dtest=EnvironmentMcpCredentialProviderTest,AgentScopeMcpRuntimePortTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因 Port、provider 和 adapter 不存在而失败。
- [x] **步骤 3：实现最小适配**：定义 `McpCredentialProvider` 和 `McpRuntimePort`；用 AgentScope `McpClientBuilder` 的 SSE/Streamable HTTP transport 注册 toolkit；凭证通过 `httpRequestCustomizer` 动态注入；禁止 stdio、静态 secret 和日志输出；单个 binding 失败抛出固定 `MCP_RUNTIME_UNAVAILABLE`。
- [x] **步骤 4：接入 Harness**：`ConfiguredAgentScopeHarnessFactory.applyConfig` 保存当前 MCP snapshot，`create` 为每个新 Harness builder 调用 Port；无 MCP binding 时保持原有行为。
- [x] **步骤 5：运行绿灯**：目标测试和 agent-worker 全量测试通过。
- [x] **步骤 6：提交**：`git add agent-worker && git commit -m "feat(MCP): 接入 AgentScope runtime Port"`。

## 任务 5：完整验证、文档和远程同步

- [x] **步骤 1：同步规格状态**：更新 observability 和 remaining-capabilities 路线图，明确已完成 Worker runtime Port，保留集中告警、外部凭证真实注入和 L6 长期运行边界。
- [x] **步骤 2：运行模块验证**：`source deploy/dev-env.sh && mvn -q test`、`python3 -m unittest discover -s scripts -p 'test_*.py'`、`python3 -m py_compile scripts/*.py`、`bash scripts/validate-backup-scripts.sh`、`python3 scripts/validate-production-values.py`、`python3 scripts/validate-production-network.py`、`python3 scripts/validate-architecture-map.py`、`helm lint deploy/helm/agentteams-java`、`git diff --check`。
- [x] **步骤 3：运行本地 Docker 验证**：`source deploy/dev-env.sh && mvn -q -Pintegration-tests verify`，确认 Flyway/Control Plane/Agent Worker 集成启动成功。
- [x] **步骤 4：检查敏感信息与范围**：确认 diff 不含 token、Authorization、完整 MCP 响应、endpoint 标签或新分支/worktree。
- [x] **步骤 5：提交文档并推送**：`git add docs && git commit -m "docs(MCP): 更新运行时接入进度" && git push origin main`；因旧 Kind ACK 夹具包含无运行时元数据的伪 MCP 绑定，额外提交 `test(Kind): 修正资源绑定 ACK 烟测夹具` 并再次推送。
- [x] **步骤 6：确认 CI**：运行 `gh run watch 33139027751 --interval 15 --exit-status`，`verify`、`kind-recovery`、`kind-oidc` 全部成功，且 `HEAD` 与 `origin/main` 一致。

## 完成边界

本计划完成后，Worker 能够对 manifest 中已解析的 MCP HTTP/SSE binding 建立 AgentScope runtime client，并通过动态凭证端口访问；不代表真实第三方 MCP 服务、集中告警、50 客户端/30 分钟 L6 长压测或生产 Secret Manager 已验收。
