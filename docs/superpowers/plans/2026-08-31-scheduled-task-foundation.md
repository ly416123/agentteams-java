# 定时任务第一纵切实现计划

> **目标：** 为企业/租户提供可持久化、可暂停恢复、可重启安全的定时任务定义，并将每次到期触发转换为普通 Task，复用现有任务状态机、权限和幂等链路。

## 范围

- 定时定义必须绑定 `organizationId`、`tenantId`，项目可选；定义和执行实例分离。
- 使用 Spring `CronExpression` 校验 6 段 Cron 与时区；不引入外部调度服务。
- 每个到期窗口使用确定性幂等键，只创建一次 Task；多副本通过数据库 scheduler lease 选主。
- 暂停后不补发历史窗口；恢复时从当前时间计算下一次执行时间。
- 任务模板只保存标题、描述、spec 摘要和来源，不保存凭据、Prompt 或响应正文。

## 任务

- [x] 增加计划定义/执行记录契约和失败测试。
- [x] 增加 V67 迁移、JDBC Repository 和 scope/幂等约束。
- [x] 增加创建、查询、暂停、恢复 API。
- [x] 增加 leader-only 到期触发器，复用 `TaskService` 创建任务。
- [x] 运行定向测试、Maven 全量、Python/Helm/本地 Docker 门禁并提交。

## 非目标

本纵切不实现日历规则、补偿策略、Webhook、重试编排、外部 Quartz/Temporal 集成或 L6 验收。
