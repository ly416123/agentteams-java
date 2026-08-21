# ConfigSnapshot 与 Artifact lifecycle 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans（当前会话内联执行）。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 完成配置快照文件的协议下发、Worker 校验/暂存/应用确认，以及可恢复的快照和对象清理。

**架构：** Control Plane 仍持有 PostgreSQL 配置状态并从 MinIO 提供受控文件读取；Outbox/NATS 传递不含凭据的 URN、checksum 和大小。Agent Worker 通过 Control Plane 基址下载并校验文件，再将本地文件引用交给 Runtime SPI；官方 QwenPaw HTTP runtime 只应用其支持的 JSON 配置，JSON Lines runtime 可使用文件引用。

**技术栈：** Java 17、protobuf/gRPC、Spring JDBC、MinIO、JUnit 5、Testcontainers、Maven。

---

## 文件职责清单

- 修改：`contracts/src/main/proto/agent_channel.proto`，增加向后兼容的配置文件描述。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/config/ConfigDeploymentService.java`、`ConfigLifecycleRepository.java`、`ConfigUploadService.java`，生成文件元数据、受控读取和生命周期查询。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/ConfigFileController.java`，增加已确认文件下载接口。
- 新增：`control-plane/src/main/java/io/agentteams/controlplane/config/ConfigSnapshotCleanupService.java`、`ConfigSnapshotCleanupJob.java`，实现保留窗口和可恢复清理。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`、`deploy/helm/agentteams-java/templates/control-plane.yaml`，注册清理服务并暴露策略配置。
- 修改：`agent-gateway/src/main/java/io/agentteams/gateway/ConfigChangedCommandHandler.java`，把 JSON 文件清单映射到 protobuf。
- 修改：`runtime/src/main/java/io/agentteams/runtime/RuntimeConfigSnapshot.java`、`JsonLinesQwenPawProcessPort.java`，传递本地文件引用。
- 新增：`agent-worker/src/main/java/io/agentteams/worker/ConfigFileFetcher.java`，按 URN 下载并校验配置文件。
- 修改：`agent-worker/src/main/java/io/agentteams/worker/QwenPawWorker.java`、`agent-worker/src/test/java/io/agentteams/worker/QwenPawWorkerTest.java`，完成文件配置阶段和应用确认。
- 新增/修改：对应 contracts、control-plane、gateway、runtime、agent-worker 单元测试及 `integration-tests/src/test/java/io/agentteams/it/TaskPushInfrastructureIT.java`。

## 任务 1：扩展配置文件协议并保持旧消息兼容

- [x] **步骤 1：编写失败的协议测试。** 在 `AgentChannelContractTest` 构造带一个文件描述的 `ConfigChanged`，断言 protobuf 序列化后 path、URI、checksum、size、content type 全部保留；保留现有无文件消息断言。
- [x] **步骤 2：运行聚焦测试确认失败。** 运行 `mvn -q -pl contracts -am -Dtest=AgentChannelContractTest test`；预期编译失败，因为 `ConfigFile` 和 `files` 字段尚不存在。
- [x] **步骤 3：实现最小 protobuf 变更。** 在 `ConfigChanged` 后新增 `ConfigFile` message，并在 `ConfigChanged` 使用新的字段号 `9` 添加 `repeated ConfigFile files`；不修改既有字段编号。
- [x] **步骤 4：运行聚焦测试确认通过。** 重跑上述 Maven 命令，预期协议测试通过。

## 任务 2：让 Control Plane 生成并受控读取文件清单

- [x] **步骤 1：编写失败的配置部署测试。** 扩展 `ConfigDeploymentServiceTest`：事务返回一个已确认 `ConfigFileRecord` 时，断言 `ConfigChanged` payload 含 `files` 数组和五个文件字段；未确认文件不出现在 payload。新增 `ConfigUploadServiceTest` 断言只有 `COMPLETED` 文件可以下载。
- [x] **步骤 2：运行聚焦测试确认失败。** 运行 `mvn -q -pl control-plane -am -Dtest=ConfigDeploymentServiceTest,ConfigUploadServiceTest test`；预期因缺少文件查询、payload 字段和下载方法而失败。
- [x] **步骤 3：实现最小数据库/服务接口。** 给 `ConfigLifecycleRepository` 增加按 snapshot 查询文件、按 snapshot/path 查询文件和删除文件记录的方法；给 `ConfigUploadService` 增加读取已完成文件的受控方法，校验 snapshot、path 和 `COMPLETED` 状态后调用 `ObjectStorage.download`；在 `ConfigDeploymentService` 读取文件记录并将 `path`、`urn:agentteams:config-file:<snapshotId>:<path>`、checksum、size、contentType 写入 JSON payload。
- [x] **步骤 4：增加 HTTP 下载端点。** 在 `ConfigFileController` 增加 `GET /api/v1/config/snapshots/{snapshotId}/files/content?path=...`，按文件的 content type 和 size 返回流；禁止通过请求直接指定 storage key。
- [x] **步骤 5：运行聚焦测试确认通过。** 重跑任务 2 的 Maven 命令，并执行 `git diff --check`。

## 任务 3：把 Outbox JSON 文件清单映射到 Gateway protobuf

- [x] **步骤 1：编写失败的 Gateway 测试。** 扩展 `ConfigChangedCommandHandlerTest`，输入含 `files` 数组的 payload，断言发送的 `ConfigChanged` 有一个等值文件；输入无 `files` 的旧 payload 仍发送成功。
- [x] **步骤 2：运行测试确认失败。** 运行 `mvn -q -pl agent-gateway -am -Dtest=ConfigChangedCommandHandlerTest test`；预期文件字段断言失败。
- [x] **步骤 3：实现 JSON 到 protobuf 映射。** 在 handler 中校验 files 为数组，每项 path/URI/checksum/contentType 非空、size 非负，然后构造 `ConfigFile`；缺失数组按空列表处理。
- [x] **步骤 4：运行聚焦测试确认通过。** 重跑上述命令，预期旧/新 payload 测试均通过。

## 任务 4：Worker 下载、校验并向 Runtime 提供文件引用

- [x] **步骤 1：编写失败的文件下载测试。** 新增 `ConfigFileFetcherTest`，通过内嵌 HTTP server 覆盖 URN 解析、checksum/size 校验、超限拒绝和 HTTP 错误；扩展 `QwenPawWorkerTest`，验证配置事件中的文件会被传给 runtime 配置快照。
- [x] **步骤 2：运行测试确认失败。** 运行 `mvn -q -pl agent-worker -am -Dtest=ConfigFileFetcherTest,QwenPawWorkerTest test`；预期新类/新快照字段尚不存在。
- [x] **步骤 3：扩展 Runtime 配置快照。** 为 `RuntimeConfigSnapshot` 增加不可变的 `Map<String, Path> files`，保留现有三参数构造器并默认空文件映射；让 `JsonLinesQwenPawProcessPort` 把文件映射作为 `files` 对象传给外部进程，QwenPaw HTTP 继续忽略该字段。
- [x] **步骤 4：实现 Worker 文件阶段。** 新增 `ConfigFileFetcher`，仅接受 HTTP/HTTPS 或 `urn:agentteams:config-file:`，解析 snapshot/path 到 Control Plane 文件端点，限制最大文件大小，写入版本目录并校验 checksum/size；`QwenPawWorker.onConfigChanged` 先完成 manifest 和文件校验，再调用 coordinator，失败时发送 `ConfigApplied(applied=false)`，成功时发送 `applied=true`。
- [x] **步骤 5：运行聚焦测试确认通过。** 重跑任务 4 命令，确认重复同版本同 checksum 返回 `ALREADY_ACTIVE`，同版本不同 checksum 被拒绝。

## 任务 5：实现快照和对象清理的保留/恢复策略

- [x] **步骤 1：编写失败的清理测试。** 新增 `ConfigSnapshotCleanupServiceTest`：当前 binding 引用的 snapshot、最近保留数量内的 snapshot 不可删除；孤立旧 snapshot 返回待删对象；对象存储删除异常时数据库记录保持不变，重试可再次执行。
- [x] **步骤 2：运行测试确认失败。** 运行 `mvn -q -pl control-plane -am -Dtest=ConfigSnapshotCleanupServiceTest test`；预期因清理服务不存在而失败。
- [x] **步骤 3：实现候选查询与幂等清理。** 在 repository 中增加按 subject 计算保留窗口、排除 binding 引用并分页返回 snapshot/file 的查询；新增 cleanup service，先逐个删除对象，全部成功后在事务中删除 file/snapshot 记录，任何失败保留数据库记录。
- [x] **步骤 4：注册定时任务并配置策略。** 新增 cleanup job，使用 `agentteams.config.snapshot-retention-count` 默认 5、`agentteams.config.snapshot-cleanup-batch-size` 默认 25、`agentteams.config.snapshot-cleanup-interval-ms` 默认 300000；Helm ConfigMap 暴露同名环境变量。清理不能删除仍被 binding 或未完成上传引用的 snapshot。
- [x] **步骤 5：运行聚焦测试确认通过。** 重跑任务 5 测试，并执行 `mvn -q -pl control-plane -am test`。

## 任务 6：真实集成验证与交付

- [x] **步骤 1：扩展基础设施集成测试。** 在 `TaskPushInfrastructureIT` 增加配置文件上传、完成、部署、FakeAgent 接收文件描述并 ACK 的断言，同时检查 `config_apply_records.phase=APPLIED`；增加重复部署不生成重复命令的断言。
- [x] **步骤 2：运行真实容器测试。** 执行 `source deploy/dev-env.sh && export TESTCONTAINERS_RYUK_DISABLED=true && mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 -Pintegration-tests -Dit.test=TaskPushInfrastructureIT verify`；预期配置文件闭环通过，Docker 不可用时记录准确跳过原因。
- [x] **步骤 3：运行全量回归和静态检查。** 执行完整 `mvn clean test`、`bash -n`、`python3 scripts/validate-kind-manifests.py`、`python3 scripts/validate-kind-infra.py`、`helm lint deploy/helm/agentteams-java`、`git diff --check`。
- [x] **步骤 4：安全检查。** 确认 `git ls-files apikey` 无输出、`git check-ignore -v apikey` 命中规则，差异中没有凭据、预签名 URL 或对象存储密钥。
- [ ] **步骤 5：提交并同步。** 使用 `feat(配置): 完成快照文件下发与生命周期治理` 提交，验证工作区干净后推送当前功能分支。
