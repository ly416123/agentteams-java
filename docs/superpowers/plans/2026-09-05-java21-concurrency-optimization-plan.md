# Java 21 与并发链路优化实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（\`- [ ]\`）语法来跟踪进度。

**目标：** 修复当前集成发布阻断，校正架构文档，统一管理界面中文展示，升级项目 Java 21 基线，并在阻塞 I/O 链路中以可观测、可回滚的方式引入虚拟线程。

**架构：** 保持 Control Plane 以 PostgreSQL 为事实源、通过 Outbox + NATS JetStream 投递、经 Agent Gateway/gRPC 到 Worker/Runtime 的总体架构不变。管理界面增加独立的状态码到中文文案展示层，不改变 API、数据库和领域状态；虚拟线程只用于阻塞 I/O，数据库连接池、上游模型限流、NATS backpressure 和租户配额继续作为资源闸门。

**技术栈：** Java 21、Maven \`--release 21\`、Spring Boot 3.4.5、JDK HttpClient、NATS JetStream、PostgreSQL/HikariCP、Testcontainers、Helm/Kubernetes、JFR、Console 现有前端技术栈。

---

## 当前基线与完成标准

- 根 POM、服务镜像和 Task Sandbox 当前仍使用 Java 17。
- \`mvn -q test\` 当前通过，约 1444 个测试用例失败/错误/跳过均为 0。
- \`mvn -q -Pintegration-tests verify\` 当前为 13 个测试中 12 个通过、1 个错误；失败点是双 Gateway/Control Plane 启动时 \`correlationIdFilter\` Bean 重名。
- \`python3 scripts/validate-architecture-map.py\` 当前失败，架构地图缺少 \`observability\`、\`storage\`、\`sdk/java\`。
- 管理界面当前需要把英文枚举、内部状态码、错误码和机器字段转换为稳定、统一、可测试的中文文案。
- Java 21 基线完成定义：本地 JDK、Maven、所有 Java Docker 镜像、Task Sandbox、Helm/构建脚本、契约测试和当前文档一致，所有验证命令通过。
- 虚拟线程试点完成定义：Manager/Runtime SSE 读取可由配置开关控制；关闭时行为与现状一致；开启时完成并发、取消、超时、异常、上下文传播和资源上限测试，并有压测对比数据。
- 中文展示完成定义：相同状态在列表、详情、筛选、弹窗、告警和错误提示中使用同一中文文案；未知状态有安全兜底；API 和数据库仍使用英文稳定码。
- 不修改历史计划和历史验收报告中的旧基线，只更新当前 README、架构地图、部署契约和新增验收文档。

## 文件清单

### 发布阻断和架构文档

- 修改：\`observability/src/main/java/io/agentteams/observability/ObservabilityAutoConfiguration.java\`
- 删除或迁移：\`agent-gateway/src/main/java/io/agentteams/gateway/CorrelationIdFilter.java\`
- 测试：\`observability/src/test/java/io/agentteams/observability/ObservabilityAutoConfigurationTest.java\`
- 回归：\`integration-tests/src/test/java/io/agentteams/it/TaskPushInfrastructureIT.java\`
- 修改：\`docs/architecture-map.html\`

### 消息并发和资源边界

- 修改：\`agent-gateway/src/main/java/io/agentteams/gateway/NatsGatewayEventConsumer.java\`
- 修改：\`control-plane/src/main/java/io/agentteams/controlplane/outbox/NatsExecutionEventConsumer.java\`
- 修改：\`control-plane/src/main/java/io/agentteams/controlplane/outbox/OutboxRelay.java\`
- 修改：\`control-plane/src/main/java/io/agentteams/controlplane/skill/ConfiguredExternalSkillSandboxScanner.java\`
- 修改：\`control-plane/src/main/java/io/agentteams/controlplane/security/KubernetesSecretResolver.java\`
- 测试：对应 NATS、Outbox、Skill Scanner、Secret Resolver 测试文件

### Java 21 基线

- 修改：\`pom.xml\`
- 修改：\`deploy/docker/control-plane.Dockerfile\`
- 修改：\`deploy/docker/gateway.Dockerfile\`
- 修改：\`deploy/docker/manager.Dockerfile\`
- 修改：\`deploy/docker/operator.Dockerfile\`
- 修改：\`deploy/docker/worker.Dockerfile\`
- 修改：\`deploy/docker/task-sandbox.Dockerfile\`
- 修改：\`deploy/build-images.sh\`
- 修改：\`scripts/validate-kind-manifests.py\`
- 修改：\`scripts/test_l5_task_sandbox_image_contract.py\`
- 重命名并修改：\`domain/src/test/java/io/agentteams/domain/Java17SmokeTest.java\`
- 修改：\`README.md\`、\`sdk/java/README.md\`、\`docs/architecture-map.html\`
- 修改：\`deploy/helm/agentteams-java/values.yaml\`
- 修改：\`deploy/helm/agentteams-java/values-production.example.yaml\`
- 修改：\`deploy/helm/agentteams-java/templates/manager.yaml\`
- 修改：\`deploy/helm/agentteams-java/templates/gateway.yaml\`
- 修改：\`deploy/helm/agentteams-java/templates/control-plane.yaml\`

### 管理界面中文展示

- 定位并修改：Console 中的状态、错误、告警、用量、Worker、配置和审批展示组件
- 优先搜索：\`console/src\` 下的 \`status\`、\`state\`、\`error\`、\`alert\`、\`usage\`、\`worker\`、\`approval\`、\`label\`、\`format\` 文件
- 新增：统一中文展示字典模块，例如 \`console/src/i18n/statusLabels.ts\` 或当前 Console 等价目录
- 测试：状态字典单元测试、页面渲染测试、未知状态兜底测试
- 修改：\`openapi/agentteams-public.yaml\` 仅在需要补充错误码/状态码说明时修改，不能把中文文案作为协议值

### 虚拟线程与验收

- 修改：\`manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java\`
- 修改：\`manager/src/main/java/io/agentteams/manager/ManagerApplication.java\`
- 修改：\`runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java\`
- 审查：\`manager/src/main/java/io/agentteams/manager/security/ManagerRequestContext.java\`
- 审查：\`control-plane/src/main/java/io/agentteams/controlplane/security/PrincipalContext.java\`
- 新增：\`integration-tests/src/test/java/io/agentteams/it/VirtualThreadConversationIT.java\`
- 新增：\`scripts/benchmark-io-concurrency.py\`
- 新增：\`docs/acceptance/2026-09-05-java21-virtual-thread-verification.md\`

## 任务 1：修复共享 Observability Bean 冲突

**目标：** 所有 Spring 应用只注册一个共享的 \`CorrelationIdFilter\`。

- [ ] **步骤 1：复现当前失败**

运行：

\`\`\`bash
mvn -q -Pintegration-tests -Dit.test=TaskPushInfrastructureIT verify
\`\`\`

预期：当前版本在 \`startInfrastructureApplications\` 处因 Bean \`correlationIdFilter\` 重名失败。

- [ ] **步骤 2：补充装配回归测试**

在 \`ObservabilityAutoConfigurationTest\` 中验证自动配置上下文只存在一个 \`CorrelationIdFilter\` Bean，并验证该 Bean 可以作为 Servlet Filter 使用；保留 \`TaskPushInfrastructureIT\` 作为多应用回归测试。

- [ ] **步骤 3：统一实现归属**

将 Gateway 中的 Filter 实现迁移为 observability 中的共享实现，删除 Gateway 中重复的 \`@Component\` 类。继续使用 \`@ConditionalOnMissingBean(CorrelationIdFilter.class)\`，不要开启全局 Bean 覆盖。

- [ ] **步骤 4：验证并提交**

\`\`\`bash
mvn -q -pl observability,agent-gateway -am test
mvn -q -Pintegration-tests -Dit.test=TaskPushInfrastructureIT verify
git add observability agent-gateway integration-tests
git commit -m "fix: unify correlation id filter wiring"
\`\`\`

预期：无 \`BeanDefinitionOverrideException\`。

## 任务 2：修复架构地图漂移

**目标：** 架构地图覆盖根 POM 的 13 个模块，并反映当前 89 条 migration。

- [ ] **步骤 1：补齐模块卡片**

在 \`docs/architecture-map.html\` 中增加 \`observability\`、\`storage\`、\`sdk/java\`，更新模块总数、源码/测试统计、Flyway 迁移数、页头 Java 基线和 footer 日期。

- [ ] **步骤 2：运行门禁**

\`\`\`bash
python3 scripts/validate-architecture-map.py
\`\`\`

预期：输出 \`ARCHITECTURE_MAP_OK modules=13\`。

- [ ] **步骤 3：提交**

\`\`\`bash
git add docs/architecture-map.html
git commit -m "docs: refresh architecture map module coverage"
\`\`\`

## 任务 3：建立 NATS 消费并发和顺序契约

**目标：** 消除 NATS 消费者的全局单线程串行，同时保持同一 Agent/Task 的消息顺序、失败重投和 ACK 语义。

- [ ] **步骤 1：补充测试**

在 \`NatsGatewayEventConsumerTest\` 和 \`NatsExecutionEventConsumerTest\` 中覆盖：不同 Agent 消息可并行；同一 \`aggregate_id\` 严格有序；异常不 ACK；成功处理后才 ACK；关闭和重连不重复消费。

- [ ] **步骤 2：定义配置**

增加有界并发配置，默认并发度为 8；顺序键为 \`aggregate_id\`；\`maxAckPending\` 不低于并发度；并发度为 1 时保持旧行为。

- [ ] **步骤 3：拆分接收和处理**

接收循环只读取消息并提交 Dispatcher；Dispatcher 使用按 \`aggregate_id\` 分片的串行队列，使不同聚合并行、同一聚合有序。ACK 发生在处理成功任务中，解析和业务异常继续重投。

- [ ] **步骤 4：处理关闭和重连**

关闭时停止接收、拒绝新任务、等待已提交任务在超时内结束；重连只恢复订阅，不重复创建 Dispatcher；记录 inflight、拒绝和重投指标。

- [ ] **步骤 5：验证并提交**

\`\`\`bash
mvn -q -pl agent-gateway,control-plane -am test
mvn -q -Pintegration-tests -Dit.test=TaskPushInfrastructureIT verify
git add agent-gateway control-plane
git commit -m "perf: add bounded ordered NATS dispatch"
\`\`\`

## 任务 4：整理 Outbox 和外部资源边界

**目标：** 线程并发不超过数据库、NATS、扫描器和 Kubernetes API 的实际承载能力。

- [ ] **步骤 1：补充资源上限测试**

为 \`OutboxRelayTest\` 增加阻塞发布、批次并发和关闭超时测试；为 Skill Scanner 和 Secret Resolver 增加并发上限、超时、取消测试。

- [ ] **步骤 2：调整 Outbox**

保留 \`claimDue\` 租约和 \`FOR UPDATE SKIP LOCKED\`。将同步逐个 \`Future.get()\` 改成受控批次完成统计；下一轮不能无限提交任务，使用 inflight 上限或 Semaphore 提供 backpressure；区分 publish、retry、dead-letter 和 shutdown 指标。

- [ ] **步骤 3：调整外部扫描器**

使用“可选虚拟线程 + Semaphore”的模型，默认扫描并发度为 4、单次超时 15 秒；超限返回 \`REVIEW_REQUIRED\`，不能阻塞其他租户。

- [ ] **步骤 4：调整 Secret Resolver**

取消无界 \`newCachedThreadPool\` 作为资源上限，使用明确并发上限和超时。只返回存在性状态，不读取或记录凭据内容。

- [ ] **步骤 5：验证并提交**

\`\`\`bash
mvn -q -pl control-plane -am test
mvn -q -Pintegration-tests -Dit.test=StorageWiringIT,TeamSchedulingInfrastructureIT verify
git add control-plane
git commit -m "perf: enforce external resource concurrency limits"
\`\`\`

## 任务 5：统一升级 Java 21 构建和运行基线

**目标：** 升级 Java 版本，但不在同一提交中引入大范围业务并发行为变化。

- [ ] **步骤 1：升级 Maven**

将根 \`pom.xml\` 的 \`java.version\` 和 \`maven.compiler.release\` 改为 21；暂不升级 Spring Boot、gRPC、Fabric8、Operator SDK、Flyway 版本。

- [ ] **步骤 2：升级镜像**

六个 Dockerfile 的构建镜像改为 \`maven:3.9.16-eclipse-temurin-21\`，运行镜像改为 \`eclipse-temurin:21-jre\`；Task Sandbox 的 \`javac --release 17\` 改为 \`javac --release 21\`；同步更新 \`deploy/build-images.sh\`。

- [ ] **步骤 3：更新测试和文档**

将 \`Java17SmokeTest\` 重命名为 \`Java21SmokeTest\` 并改断言；更新当前 README、SDK README、架构地图和 Java 21 契约脚本；历史计划保留原始 Java 17 记录。

- [ ] **步骤 4：增加部署开关**

在 Helm values 和 Manager/Gateway/Control Plane 模板中增加 \`AGENTTEAMS_VIRTUAL_THREADS_ENABLED: "false"\`。Java 21 升级默认不改变并发行为。

- [ ] **步骤 5：验证并提交**

\`\`\`bash
java -version
mvn -q test
python3 scripts/validate-architecture-map.py
python3 scripts/validate-api-contract.py
python3 scripts/validate-production-values.py
python3 scripts/validate-observability.py
python3 scripts/test_l5_task_sandbox_image_contract.py
git add pom.xml deploy/docker deploy/build-images.sh scripts README.md sdk/java domain/src/test deploy/helm docs/architecture-map.html
git commit -m "build: upgrade Java baseline to 21"
\`\`\`

## 任务 6：统一管理界面中文展示

**目标：** 用户看到的状态、错误、告警和运营信息统一显示为中文，内部稳定码不改变。

- [ ] **步骤 1：盘点所有直接展示内部值的地方**

搜索 Console 中直接渲染 \`status\`、\`state\`、\`type\`、\`errorCode\`、\`severity\`、\`source\`、\`phase\`、\`decision\` 的组件，建立状态清单。至少覆盖 Task、Conversation、Worker、Team、配置发布、Artifact、Sandbox、告警、用量、审批和审计。

- [ ] **步骤 2：建立单一中文字典**

新增统一展示字典，所有字典函数接收稳定英文码并返回中文文案；字典同时提供状态类型、颜色、排序优先级和未知值兜底。示例语义为：\`PENDING=待处理\`、\`RUNNING=运行中\`、\`COMPLETED=已完成\`、\`FAILED=失败\`、\`CANCELLED=已取消\`、\`RECOVERY_REQUIRED=需要恢复\`。实际枚举以 OpenAPI、后端 DTO 和当前 Console 代码盘点结果为准，不自行新增协议状态。

- [ ] **步骤 3：替换组件渲染**

列表、详情、筛选器、Badge、Tooltip、Toast、弹窗和表格统一调用字典函数；禁止在 JSX/模板中直接输出内部枚举；错误信息优先按错误码映射中文安全文案，未知错误显示“未知错误”，同时保留 trace/correlation id 供排查。

- [ ] **步骤 4：保留机器值和可排查性**

详情页可在“技术信息”折叠区域展示稳定英文码，默认用户界面展示中文；API 响应、URL、数据库、事件和日志字段不改成中文；不能把中文文案写入状态机或持久化字段。

- [ ] **步骤 5：补充测试**

新增状态字典全量映射测试、未知状态兜底测试、错误码映射测试和关键页面渲染测试；测试同一个状态在列表和详情中使用同一文案。

- [ ] **步骤 6：验证并提交**

\`\`\`bash
cd console
npm test
npm run build
cd ..
python3 scripts/validate-api-contract.py
git add console openapi/agentteams-public.yaml
git commit -m "feat: localize management status and error labels"
\`\`\`

## 任务 7：在 Manager/Runtime SSE 链路试点虚拟线程

**目标：** 用虚拟线程承载长时间阻塞的 SSE \`InputStream\` 读取，同时保留所有资源限制。

- [ ] **步骤 1：增加开关测试**

测试 \`agentteams.concurrency.virtual-threads.enabled\` 和环境变量 \`AGENTTEAMS_VIRTUAL_THREADS_ENABLED\`；关闭使用平台线程执行器，开启使用 \`Executors.newVirtualThreadPerTaskExecutor()\`；timeout scheduler 继续使用 \`ScheduledExecutorService\`。

- [ ] **步骤 2：改造 Manager SSE reader**

仅替换响应处理 Executor；每个响应读取任务使用一个虚拟线程，不建立虚拟线程池；保留 \`requestSlots\`、\`sessionSlots\`、每会话单请求、取消和超时逻辑。不得在 \`synchronized\` 临界区执行网络读取。

- [ ] **步骤 3：改造 Runtime SSE reader**

使用同一开关选择响应处理 Executor；保持 Task 幂等键、attempt、取消和终态事件语义。JsonLines 进程 Runtime 继续使用每个进程的固定 stdout/stderr 读取线程。

- [ ] **步骤 4：验证上下文传播**

审查 \`ManagerRequestContext\`、\`PrincipalContext\`、MDC 和 tracing 在 \`sendAsync\`、\`thenAcceptAsync\`、NATS Dispatcher、scheduler 和虚拟线程之间的传递；跨线程任务显式携带不可变租户/用户/Trace 上下文，结束时清理 MDC。

- [ ] **步骤 5：补充测试并提交**

覆盖长 SSE、Semaphore 上限、取消、超时、非 2xx、非 SSE、连接断开、超大响应、Executor 关闭和不同用户上下文不串线。

\`\`\`bash
mvn -q -pl manager,runtime,observability -am test
mvn -q -Pintegration-tests -Dit.test=VirtualThreadConversationIT verify
git add manager runtime observability integration-tests
git commit -m "perf: pilot virtual threads for blocking SSE readers"
\`\`\`

## 任务 8：建立性能基线、压测和 JFR 验收

**目标：** 用数据确认虚拟线程是否改善吞吐和尾延迟。

- [ ] **步骤 1：实现压测脚本**

新增 \`scripts/benchmark-io-concurrency.py\`，固定并发档位 16、64、128、256，分别执行平台线程和虚拟线程配置；记录成功率、吞吐、P50/P95/P99、取消延迟、RSS、GC、数据库连接池等待、NATS backlog、Outbox oldest pending age 和上游错误率。

- [ ] **步骤 2：执行对照实验**

使用同一 QwenPaw SSE mock、响应大小、延迟分布和数据库配置；每档预热 60 秒、采样 5 分钟、重复 3 次，保存原始数据和环境信息。

- [ ] **步骤 3：执行 JFR 检查**

检查 \`jdk.VirtualThreadPinned\`、虚拟线程提交失败、GC 和线程启动/结束事件；发现频繁且超过 20ms 的 pinning 时，先移出 \`synchronized\` 临界区，再扩大并发。

- [ ] **步骤 4：执行故障注入**

验证 NATS 断连、PostgreSQL 重启、QwenPaw 长时间无事件、上游 429/5xx、Worker 重连、Manager 滚动重启和应用关闭；确认没有重复 ACK、任务状态回退、凭据泄漏、上下文串线或连接泄漏。

- [ ] **步骤 5：形成验收报告**

将对照数据、JFR 结论、资源上限、故障注入结果和灰度决定写入 \`docs/acceptance/2026-09-05-java21-virtual-thread-verification.md\`。若受限并发下无吞吐或尾延迟改善，保留 Java 21 基线但关闭虚拟线程开关。

## 任务 9：生产灰度和完成门禁

**目标：** Java 21、虚拟线程、中文展示和其他生产能力可以独立发布和回滚。

- [ ] **步骤 1：灰度顺序**

按 Manager SSE、Runtime SSE、外部 Skill Scanner、Secret Resolver、Outbox publisher 顺序灰度；Gateway NATS 先完成有界并发和顺序验证，再独立评估虚拟线程。

- [ ] **步骤 2：生产初始值**

生产环境先只对一个 Manager 副本开启虚拟线程，其他副本关闭；观察一个完整业务高峰周期，确认 P99、连接池等待、NATS lag、Outbox backlog、错误率、CPU 和内存未恶化。

- [ ] **步骤 3：回滚**

将 \`AGENTTEAMS_VIRTUAL_THREADS_ENABLED\` 设为 \`false\` 并滚动对应 Deployment；Java 21 镜像基线不回退，避免把 JDK 回滚和并发行为回滚混在一起。中文展示回滚只能回退文案层，不能回退 API/数据库状态码。

- [ ] **步骤 4：最终验证**

\`\`\`bash
mvn -q test
mvn -q -Pintegration-tests verify
python3 scripts/validate-architecture-map.py
python3 scripts/validate-api-contract.py
python3 scripts/validate-production-values.py
python3 scripts/validate-observability.py
python3 scripts/validate-kind-manifests.py
./scripts/run-l5-task-sandbox-acceptance.sh
\`\`\`

预期：所有命令成功；集成测试不再有 Bean 装配错误；管理界面关键状态和错误均展示中文；L5 结果独立记录，不能用本地 Kind/Fake Provider 代替。

## 任务 0：L5 环境前置检查

**目标：** 在进入涉及镜像、Helm、Worker、Operator、Task Sandbox 或 RuntimeClass 的任务前，确认真实 Ubuntu/KVM 验收环境可用；L5 不阻塞纯 Java/Control Plane/Manager/Console 的前置任务，但必须在任务 5 和最终发布门禁前准备完成。

- [ ] **步骤 1：阅读并确认环境基线**

先阅读 `docs/acceptance/l5-linux-kvm-environment-baseline.md` 和 `deploy/production/l5-linux-kvm-acceptance.md`。当前登记主机为 `ly@192.168.125.55`，主机身份和 RuntimeClass 基线仍需按本次验收前检查确认。TaskSandbox 验收按无图形界面服务器运行；Console/Playwright 场景使用 headless Chromium，不启动完整桌面。

- [ ] **步骤 2：确认远端访问和 Kubernetes 依赖**

确认 SSH key、网络和非交互权限可用，并在远端确认 namespace `agentteams`、`gvisor`/`kata-qemu` RuntimeClass、Team/Worker/TaskSandbox CRD、带 `app.kubernetes.io/name=agentteams-operator` 标签且 Available 的 Operator Deployment 均存在。

- [ ] **步骤 3：清理或登记残留资源**

执行前记录 Worker CR、Deployment、Pod 数量和主机可用内存。L5 主机只有约 7.2 GiB 内存，不能遗留历史 Worker CR；只处理本次验收明确创建的资源，归属不明的资源不得删除。保留的 Worker CR 必须核对 `spec.replicas`，不能只删除 Pod。

- [ ] **步骤 4：准备 Java 21 验收镜像**

任务 5 完成后，将 Control Plane、Gateway、Manager、Worker、Operator 和 Task Sandbox 的 Java 21 镜像构建并导入远端 K3s/containerd；Task Sandbox runner 必须使用准确镜像引用和 `imagePullPolicy: IfNotPresent`，避免把 registry 拉取失败误判为 RuntimeClass 故障。

- [ ] **步骤 5：执行 L5 脚本并保留证据**

在远端可用的 kubectl 上运行：

\`\`\`bash
./scripts/run-l5-task-sandbox-acceptance.sh
\`\`\`

预期：输出 `L5_LINUX_KVM_ACCEPTANCE_OK`，两个 profile 均达到 `READY`，Job/Pod 的 RuntimeClass、guest/host kernel 和清理结果均有记录。环境不可用、超时或清理失败必须作为验收失败处理，不能标记为通过。

- [ ] **步骤 6：设置执行门禁**

任务 1 至任务 4、任务 6 的纯本地代码和测试可以在 L5 准备完成前开始；任务 5 修改镜像/Task Sandbox/Helm 后必须追加 L5；任务 9 在本地 Docker/Kind、集成测试和 L5 全部通过前不得标记优化完成或进入主线集成。

## 风险控制和明确不做的事情

- 不把虚拟线程用于 CPU 密集型 JSON、哈希、策略扫描或进程管道读取。
- 不取消 Semaphore、租户配额、HikariCP 最大连接数、NATS \`maxAckPending\`、Outbox 租约和上游 QPS 限制。
- 不在同一批次升级 Spring Boot、gRPC、Fabric8、Operator SDK 或 Flyway 大版本。
- 不使用 \`spring.main.allow-bean-definition-overriding=true\` 绕过集成失败。
- 不把减少平台线程直接等同于吞吐提升，必须以压测数据为准。
- 不把中文展示文案写入领域状态、数据库、事件或 API 协议。
- AgentScope 生产接线、Manager in-flight 真正 resume、节点故障恢复和 L6 生产验收仍作为独立发布门禁。
