# 预算评估调度与集中通知闭环实现计划

## 目标

在现有预算策略、线性预测和评估查询基础上，补齐周期评估与集中通知闭环：预算评估始终更新当前窗口读模型，仅对软/硬阈值告警创建幂等事件；通知失败可重试；多副本部署通过数据库 lease 保证单活调度。默认关闭调度，不改变现有 CI 与部署行为。

## 范围

1. 拆分预算评估写入与通知事件写入，修复同一窗口后续评估被旧指纹阻断的问题。
2. 为预算事件增加重试状态和时间字段，提供 active policy、待投递事件、claim、成功和失败更新能力。
3. 增加预算专用通知端口和安全的日志实现，不传递 prompt、response、token 或授权信息。
4. 增加基于 `SchedulerLeaseService` 的预算评估调度器，周期评估 active policy 并投递待处理事件。
5. 增加 Spring 配置、默认关闭的 application/Helm 配置和迁移/单元/集成测试。
6. 更新路线图与闭环设计的进度记录。

## 实现顺序

- [x] 先添加预算通知服务、调度器和 repository contract 的失败测试。
- [x] 添加预算事件领域对象、V54 迁移和 JDBC repository 实现。
- [x] 修改预算服务以始终 upsert evaluation，并仅为可通知状态写入状态指纹事件。
- [x] 实现通知投递、指数退避重试和 lease 调度。
- [x] 接入 Spring 与 Helm 配置，保持调度默认关闭。
- [x] 补齐 PostgreSQL/Testcontainers 集成覆盖事件生命周期。
- [x] 运行 Maven、脚本、Helm 和 Docker/Colima 验证，更新文档并提交推送 `main`。

## 验收标准

- 同一 policy/window 的 `INSUFFICIENT_DATA` 后续变为 `SOFT_LIMIT` 或 `HARD_LIMIT` 时，evaluation 更新且产生新的状态事件。
- 相同 policy/window/status 重复调度不会重复通知。
- 通知失败记录 `FAILED`、错误摘要和下一次尝试时间；到期重试成功后变为 `SENT`。
- 只有一个持有 `usage-budget` lease 的实例执行评估；未持 lease 的实例不访问评估工作集。
- 通知载荷只包含策略范围、窗口、币种、实际/预测成本和状态，不包含敏感请求内容。
- 默认配置不会启用新的周期调度。
