# Dashboard 告警投递闭环实现计划

> **面向 AI 代理的工作者：** 本计划已获自动实施授权，按步骤执行并在每个阶段运行验证。

**目标：** 将 Dashboard 告警从手工即时通知扩展为按项目定时评估、持久化去重、失败可重试的投递闭环。

**架构：** Usage 查询增加显式 tenant/project 边界，避免后台任务依赖登录态；告警投递服务为每条规则生成项目级时间窗口指纹，通过数据库事件表实现幂等、状态和重试。定时任务使用已有数据库 scheduler lease 保证多副本只由一个实例执行，通知继续复用现有 Webhook/日志端口。

**技术栈：** Java 17、Spring Scheduling、Spring JDBC、Flyway、JUnit 5、Mockito。

---

### 任务 1：锁定项目级 Usage 查询和告警事件行为

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageQueryService.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertEvent.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertEventRepository.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/InMemoryDashboardAlertEventRepository.java`
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/dashboard/DashboardAlertDeliveryServiceTest.java`

- [x] 编写测试：同一项目、同一窗口、同一规则只产生一次待投递事件；已发送事件再次评估被抑制；失败事件到期后可再次领取。
- [x] 运行定向测试，确认初始缺失实现会在测试编译阶段失败。
- [x] 实现显式作用域 Usage 查询和事件值对象/仓储接口。
- [x] 实现内存仓储，支持指纹去重、`PENDING/SENT/FAILED` 状态和到期领取。
- [x] 重跑定向测试，确认通过。

### 任务 2：增加 JDBC 告警事件持久化

**文件：**
- 创建：`control-plane/src/main/resources/db/migration/V40__dashboard_alert_events.sql`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/JdbcDashboardAlertEventRepository.java`
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/dashboard/JdbcDashboardAlertEventRepositoryTest.java`

- [x] 编写 JDBC 映射、唯一指纹、状态更新和失败重试 SQL 测试。
- [x] 运行定向测试，确认仓储实现完成后测试通过。
- [x] 增加带 tenant/project、窗口、规则、指纹、状态、尝试次数和脱敏错误字段的 Flyway 表。
- [x] 实现条件更新，确保多副本并发下同一指纹只有一个投递者。
- [x] 重跑定向测试，确认通过。

### 任务 3：实现告警投递服务和定时调度

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertDeliveryService.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertScheduler.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/dashboard/DashboardAlertController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`
- 修改：`control-plane/src/main/resources/application.yml`

- [x] 编写投递成功、通知异常进入 FAILED、到期重试和定时任务使用 scheduler lease 的测试。
- [x] 运行定向测试确认实现完成后测试通过。
- [x] 实现手工接口复用同一投递服务，避免已认证手工通知绕过幂等状态。
- [x] 增加按已认证项目作用域查询最近告警事件状态的接口。
- [x] 实现按项目枚举 Usage、固定窗口评估、指数退避重试和低基数日志。
- [x] 增加可配置的启用开关、轮询间隔、窗口长度和每轮项目上限，默认安全关闭外部 Webhook 自动投递。
- [x] 重跑定向测试，确认通过。

### 任务 4：回归、迁移和文档收口

**文件：**
- 修改：`docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md`

- [x] 运行 `mvn test`。
- [x] 运行 `git diff --check` 和敏感信息扫描。
- [x] 更新规格中的 Dashboard 告警闭环状态与外部 Webhook 默认行为。
- [x] 检查 Flyway 迁移、Spring Bean 装配和测试统计。
