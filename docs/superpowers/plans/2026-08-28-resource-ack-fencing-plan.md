# 批次 C 资源级 ACK 与 revision 栅栏实现计划

## 目标

为 Skill/MCP/Model 资源绑定增加结构化 Worker ACK 和 Control Plane 持久化，保证迟到的旧配置结果不能覆盖当前 revision。此批次只建立可信的观测链路，不把资源引用校验误报为 Skill 下载或 MCP 工具发现已经完成。

## 范围

- `ConfigApplied` 新增重复的资源结果字段，保持旧 Worker 的向后兼容；
- Gateway/NATS/Application Contract 透传资源结果；
- Control Plane 新增 V50 `runtime_resource_apply_records` 表和版本条件写入；
- Worker 将现有资源绑定校验结果转成受控状态和失败分类；
- L1/L2 测试覆盖协议、字段约束、ACK 接入和 Flyway 迁移。

## 验收

- [x] 资源结果字段只允许 `APPLIED`、`REJECTED`、`FAILED`；
- [x] 失败分类只允许规格中的固定枚举，供应商正文不进入分类字段；
- [x] 旧配置 ACK 无法通过当前 binding/snapshot/version 校验；
- [x] 数据库写入使用当前 revision 条件和版本保护；
- [x] 本机 Colima Docker 相关 Maven 测试通过，迁移验证到 V50；
- [ ] Skill 包下载、digest 校验和 MCP 工具发现的真实运行时实现；
- [x] Kind 端到端资源 ACK 与 MCP 跨实例聚合验收（快照聚合、旧 revision 隔离、过期 `UNKNOWN`）。

## 边界

本批次的 `APPLIED` 表示 Worker 已接受并完成当前配置应用流程中的该绑定结果；Skill 下载器、MCP Transport 和工具发现尚未接入时，不以此结果替代真实资源运行时健康状态。后续任务必须在实际运行时 Port 接入后才扩展相应状态。
