# Kind Worker 启动配置恢复验收设计

## 目标

把 Worker 重启后的配置恢复纳入 GitHub CI 的 Kind 验收，验证 Worker 在丢失内存状态后能够从 Control Plane 当前配置快照恢复，而不是依赖历史 `ConfigChanged` 事件重新投递。

## 方案

复用 `scripts/run-kind-resource-binding-ack.py` 已有的配置快照与绑定部署流程：

1. 创建并部署 legacy 配置，等待 `APPLIED`。
2. 创建并部署合法 `resourceBindings` 配置，等待 `APPLIED`。
3. 记录唯一 Worker Pod，删除该 Pod，等待替代 Pod 运行且 Deployment 恢复 Ready。
4. 读取替代 Pod 日志，等待同一 Agent 出现：
   - `ConfigChanged received ... sequence=0`
   - `ConfigChanged completed ... sequence=0 applied=true`
5. 输出独立的 `KIND_CONFIG_BOOTSTRAP_OK` 标记。
6. 继续现有非法配置路径，确认配置校验失败仍返回 `VALIDATION`。

验收只读取日志和 Control Plane 持久化状态，不调用 Worker 私有 HTTP 接口；不会记录 manifest、凭据或其他敏感内容。启动恢复路径不发送 ACK，因此不改变现有绑定状态模型。

## CI 接入

在现有 Kind resource binding ACK step 中直接执行增强后的脚本。脚本失败时输出 Agent、旧 Pod、替代 Pod、绑定和快照版本等非敏感诊断，保留当前失败路径的返回码。

## 错误处理

- 删除失败、替代 Pod 未出现、Deployment 未 Ready 或启动日志未出现成功应用标记时验收失败。
- 日志等待超时输出最近的 Pod 日志片段，但截断长度并过滤 manifest 内容。
- 原有 ACK `APPLIED` 和非法配置 `VALIDATION` 断言保持不变，避免只验证启动日志而遗漏实时配置链路。

## 测试

- 扩展 `scripts/test_kind_resource_binding_ack.py`，锁定重启、替代 Pod、`sequence=0` 和 `applied=true` 断言。
- 运行该契约测试、Python 编译检查以及现有 Kind/Java 测试入口。
