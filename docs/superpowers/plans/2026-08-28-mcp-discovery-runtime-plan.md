# MCP 跨实例发现观测实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 为 MCP `tools/list` 建立按 server revision 和实例隔离的持久化观测，并提供可恢复的跨实例聚合状态。

**架构：** Control Plane 通过 `McpDiscoveryObservationPort` 写入 PostgreSQL 快照；聚合服务只读取当前 server revision 的新鲜观测，按“任一健康则 AVAILABLE、全部明确失败则 UNAVAILABLE、无新鲜观测则 UNKNOWN”计算结果。仅保存工具列表 digest、状态和固定失败分类，不保存凭证、端点、完整工具响应或请求参数。

**技术栈：** Java 21 records、Spring JDBC、PostgreSQL/Flyway、JUnit 5、Mockito。

---

### 任务 1：发现观测领域模型与聚合服务

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryObservation.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryAggregate.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryStatus.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryObservationPort.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryAggregationService.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/mcp/McpDiscoveryAggregationServiceTest.java`

- [x] **步骤 1：编写失败测试**：覆盖健康实例为 `AVAILABLE`、全部失败为 `UNAVAILABLE`、无新鲜观测为 `UNKNOWN`、旧 revision 隔离、digest/计数稳定聚合，以及输入边界校验。
- [x] **步骤 2：运行红灯**：`mvn -q -pl control-plane -am -Dtest=McpDiscoveryAggregationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`；确认因类型尚不存在而失败。
- [x] **步骤 3：实现**：观测只保存 server/version/instance、工具 digest、健康状态、固定失败分类和观测/过期时间；聚合服务只依赖 Port，不执行网络调用。
- [x] **步骤 4：运行绿灯**：目标测试通过。

### 任务 2：PostgreSQL 快照持久化

**文件：**
- 创建：`control-plane/src/main/resources/db/migration/V51__mcp_discovery_snapshots.sql`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/JdbcMcpDiscoveryObservationRepository.java`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/mcp/JdbcMcpDiscoveryObservationRepositoryTest.java`

- [x] **步骤 1：编写失败测试**：验证 `JdbcTemplate` 使用 `(server_id, server_revision, instance_id)` 冲突键 upsert，并按 server/version 查询。
- [x] **步骤 2：运行红灯**：`mvn -q -pl control-plane -am -Dtest=JdbcMcpDiscoveryObservationRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`；确认因 Repository 尚不存在而失败。
- [x] **步骤 3：实现**：迁移创建 `mcp_discovery_snapshots`，主键为三元组，外键关联 `mcp_servers`，约束 revision、状态和字段长度；Repository 使用 upsert，不读取敏感字段。
- [x] **步骤 4：运行绿灯**：Repository 目标测试通过。

### 任务 3：健康探测写入实例观测

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpHealthProbeService.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpDiscoveryInstanceProperties.java`
- 修改：`control-plane/src/main/resources/application.yml`
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/mcp/McpHealthProbeServiceTest.java`

- [x] **步骤 1：编写失败测试**：验证成功/失败探测写入当前 server version、稳定实例 ID、固定分类和 bounded TTL，且不写入凭证、URL、异常正文。
- [x] **步骤 2：运行红灯**：`mvn -q -pl control-plane -am -Dtest=McpHealthProbeServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`；确认因观测接入尚不存在而失败。
- [x] **步骤 3：实现**：增加 `agentteams.mcp.discovery.instance-id` 与 `observation-ttl` 配置；观测写失败不改变探测返回结果；实例 ID 非空且限长。
- [x] **步骤 4：运行绿灯**：健康探测目标测试通过。

### 任务 4：集成验证、文档和交付

**文件：**
- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md`
- 修改：`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md`

- [x] **步骤 1：运行模块全量测试**：`source deploy/dev-env.sh && mvn -q -pl control-plane -am test` 已通过。
- [x] **步骤 2：运行本地 Docker/Colima 全量验证**：`source deploy/dev-env.sh && mvn -q -Pintegration-tests verify` 已通过；脚本、Helm lint、`git diff --check` 已通过。
- [x] **步骤 3：同步进度**：已标记跨实例发现观测持久化/聚合和健康探测写入，保留 Worker runtime Port、集中告警、预算预测和 L6 长压测。
- [x] **步骤 4：提交推送**：使用中文 Conventional Commit，直接提交 `main` 并执行 `git push origin main`。
- [x] **步骤 5：确认 CI**：此前运行 `33139839212` 已确认 `verify`、`kind-recovery`、`kind-oidc` 全部成功；本次仅收口计划状态，后续提交由主线 CI 继续验证。
