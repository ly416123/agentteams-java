# 收敛 AgentScope 唯一执行链路实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 当前架构只保留 AgentScope 执行链路，删除旧运行时的实现、迁移适配和兼容约束；DeepSeek 仅作为 L5 当前默认模型，模型 Provider 必须可配置替换。

**架构：** Control Plane 负责 Task/Team 调度，Worker 只通过 AgentScope Harness 执行模型调用。模型使用 `provider:model` 注册表键和通用配置注入；L5 默认 `deepseek:deepseek-chat`，替换模型不改变 Team、调度或任务状态机。当前代码、部署和测试不保留旧运行时迁移适配器；日期较早的设计文档仅作为历史背景。

**技术栈：** Java 21、Spring Boot、AgentScope、PostgreSQL/JDBC、Kubernetes/Helm、JUnit 5、Python 契约测试。

---

### 任务 1：锁定 AgentScope-only 默认行为

- [x] 默认运行时、空环境默认值和 Helm 默认值统一为 `AGENTSCOPE`。
- [x] 旧运行时值在应用契约、Worker CRD 和模板层直接拒绝。
- [x] 删除旧运行时路由和运行时工厂分支。
- [x] 运行 Java 定向测试和 Python 部署契约测试。

### 任务 2：同步当前项目约束和部署默认

- [x] 项目约束明确开发阶段不承担旧逻辑兼容，不保留迁移适配器。
- [x] 生产组合根只装配 AgentScope Harness；FakeRuntime 仅用于单元测试。
- [x] L5 默认模型为 `deepseek:deepseek-chat`，但模型 Provider 和模型标识可配置。
- [x] 使用通用 `AGENTTEAMS_MODEL_BASE_URL`、代理配置和 `provider:model` 注册表键，不绑定单一 Provider 的环境变量。

### 任务 3：删除旧运行时实现

- [x] 删除旧运行时类、兼容路由、旧部署脚本和旧运行时专项验收测试。
- [x] 删除旧运行时的 Worker/Manager/Operator 组合根入口。
- [x] 清理当前 README、Helm 和契约测试中的旧运行时说明。
- [x] 对日期较早的历史文档仅保留背景说明，不把其内容作为当前实现契约。

### 任务 4：合并回 main 并验证

- [x] 运行模块测试和全量回归。
- [ ] 提交当前隔离工作树全部已确认变更。
- [ ] 保护 main 未提交改动，合并提交并恢复 main 工作区改动。
- [ ] 在 main 上重新验证状态、测试和合并结果。
