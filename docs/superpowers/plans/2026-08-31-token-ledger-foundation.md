# Token Ledger 第一纵切实现计划

> **目标：** 在现有项目配额和模型调用审计之上，建立组织/租户/项目可归因的追加式 Token 账本，支持执行前预占、成功结算、失败释放和幂等重试。

## 范围

- 账本是追加式事实记录，不增加可被并发覆盖的单一余额字段；余额/消耗由账本聚合得到。
- 每笔操作必须绑定 `organizationId`、`tenantId`，项目和任务/运行可选；跨租户读取或写入拒绝。
- 预占、结算、释放使用不同的幂等键；同一幂等键复用不同请求必须拒绝。
- 结算只允许从 `RESERVED` 进入 `SETTLED`，释放只允许从 `RESERVED` 进入 `RELEASED`；重复相同操作返回原结果。
- 账本记录只保存 Token 数量、模型/来源标识和脱敏归因，不保存 Prompt、响应正文或 Secret。

## 任务

- [x] 增加 `TokenLedgerEntry`、`TokenReservation` 和 `TokenLedgerRepository` 契约。
- [x] 用失败测试冻结 scope、状态迁移、幂等和负数/超大值拒绝规则。
- [x] 增加 `V66__token_ledger.sql`，建立预占、结算、释放事实和租户索引；唯一约束由数据库保证。
- [x] 实现 JDBC Repository 和 `TokenLedgerService`，保证状态迁移及幂等在同一事务内完成。
- [x] 增加内部 Control Plane API，供 Manager/Worker 结算链路调用；不进入公共 SDK，不暴露账本明细中的敏感信息。
- [x] 运行定向测试、Maven 全量、Python/Helm/本地 Docker 门禁并提交。

## 非目标

本纵切不实现充值支付、最终账单、外部计费系统、多区域账本复制或 L6 生产长压测；这些能力继续由商业化后续批次承接。
