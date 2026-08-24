# Kind 验收收口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `executing-plans` 逐任务执行此计划。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 在真实 Kind 集群中验收当前已实现的项目配额持久化、Worker resourceBindings ACK 和配置回滚链路，并把可复现的验证纳入交付状态。

**架构：** 使用现有 `kind-recovery` 部署链，先通过 Gateway gRPC 验证 Control Plane 持久化配额 reservation/release 的幂等和隔离，再验证 AgentSpec 绑定 revision/digest 的 Worker ACK，最后运行已有配置回滚验收。只补齐本机验收依赖，不改变业务契约。

**技术栈：** Java 17、Maven、Docker、Kind、kubectl、Helm、Python 3、grpcurl、PostgreSQL、NATS、MinIO。

---

### 任务 1：准备验收环境并确认静态契约

**文件：**
- 读取：`deploy/kind-config.yaml`
- 读取：`deploy/helm/kind-values.yaml`
- 读取：`scripts/run-kind-quota-recovery.py`
- 读取：`scripts/run-kind-resource-binding-ack.py`
- 读取：`scripts/run-kind-config-rollback.py`

- [x] **步骤 1：确认工作区和本地工具链。** 检查 Git 工作区、Java 17、Maven、Docker、Kind、kubectl、Helm、Python 3 和 grpcurl；缺少 grpcurl 时安装固定版本 1.9.3。

- [x] **步骤 2：运行静态验证。** 执行 `bash -n`、Kind/Helm manifest validator、Helm lint 和 `git diff --check`，确认验收脚本顺序与部署契约一致。

### 任务 2：执行真实 Kind 配额验收

**文件：**
- 执行：`scripts/run-kind-quota-recovery.py`
- 观察：Control Plane、Gateway、PostgreSQL、Worker 日志与资源状态

- [ ] **步骤 1：确认依赖就绪。** 确认 `agentteams` 集群、三个核心 Deployment、PostgreSQL、NATS、MinIO 和至少一个 READY Worker 可用。

- [x] **步骤 2：运行配额验收。** 执行 `python3 scripts/run-kind-quota-recovery.py`，必须看到 acquire/release 重试幂等、拒绝、超时、跨 tenant/project 隔离和最终清理成功标记。

- [x] **步骤 3：验证重启语义。** 使用脚本的 Control Plane 重启选项重复 acquire/release，确认 reservation 和 release 状态由数据库保留，重试不会增加计数。

### 任务 3：执行真实 Worker 绑定与回滚验收

**文件：**
- 执行：`scripts/run-kind-resource-binding-ack.py`
- 执行：`scripts/run-kind-config-rollback.py`

- [x] **步骤 1：运行 resourceBindings ACK 验收。** 验证 legacy manifest、合法 model/skill/MCP 绑定、错误 revision/digest 和缺失资源分别得到成功 ACK 或稳定失败分类。

- [x] **步骤 2：运行配置回滚验收。** 验证新 revision 成功应用、失败 revision 不替换稳定版本、rollback 请求产生新的幂等事件并最终被真实 Worker 观察到。

- [ ] **步骤 3：收集脱敏诊断。** 失败时只保留 Pod 状态、任务 phase、错误分类、revision/digest 和最近事件，不输出 API Key、JWT、完整 Prompt/Response 或完整 Secret。

### 任务 4：回归与交付状态更新

**文件：**
- 修改：`docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md`
- 修改：`README.md`（仅在本地验收命令或结果需要同步时）

- [ ] **步骤 1：运行 Java 回归。** 执行 `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 clean test`，确认失败数和错误数均为 0。

- [ ] **步骤 2：更新剩余任务状态。** 只根据本轮真实命令结果更新规格中的验收状态；外部 GitHub Actions 未运行的项目保持“待 CI 确认”。

- [x] **步骤 3：检查并交付。** 执行敏感信息扫描、`git diff --check`、`git status`，形成独立中文 Conventional Commit；仅在验证通过后推送当前分支。
