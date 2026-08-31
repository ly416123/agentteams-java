# 企业 Webhook 投递第一纵切实现计划

> **目标：** 为企业/租户建立安全的 Webhook 订阅和可靠投递基础，消费已冻结的任务过程/结果事件，不把 Webhook 调用放入任务主事务。

## 范围

- 订阅绑定 Organization/Tenant/Project，事件类型使用白名单；响应只返回订阅摘要。
- 投递记录追加保存事件 ID 和脱敏 JSON，按订阅+事件 ID 去重。
- HTTP 请求使用 HMAC 签名、时间戳和事件 ID，失败指数退避，超过上限进入 DEAD 状态。
- 投递由数据库 outbox 和 leader-only scheduler 驱动；网络调用不参与 Task 状态事务。

## 任务

- [x] 增加订阅/投递契约和失败测试。
- [x] 增加 V68 迁移、JDBC Repository 和投递状态机。
- [x] 增加订阅管理 API 与 HMAC HTTP 传输适配器。
- [x] 增加租约调度和重试/死信处理。
- [x] 运行定向测试、Maven 全量、Python/Helm/本地 Docker 门禁并提交。

## 非目标

本纵切不实现完整事件生产接线、客户 Connector、Webhook 管理后台、跨区域投递或 L6 验收；事件生产接线作为下一纵切接入现有 TaskProcess/ExecutionEvent 服务。
