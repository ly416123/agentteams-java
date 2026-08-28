# Worker/Manager 远程配额生产组装实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将远程项目配额接入真实 Manager smoke 和 Kind Worker 任务，并验证持久化调用计数。

**架构：** 复用现有 `ManagerQuotaPortFactory`、`ProjectScopedModelCallAdmission`、`GrpcRuntimeQuotaPort` 和 Gateway Quota gRPC。Kind Worker 使用项目 scope 调用真实 QwenPaw mock，验收脚本通过 Control Plane quota API 比对前后计数。

**技术栈：** Java 17、Maven、gRPC、Python 3、Kind、kubectl、PostgreSQL、QwenPaw HTTP mock。

---

### 任务 1：Manager 真实调用接入远程 admission

**文件：**
- 修改：`manager/pom.xml`
- 修改：`manager/src/main/java/io/agentteams/manager/ManagerSmokeApplication.java`
- 创建：`manager/src/main/java/io/agentteams/manager/ManagerSmokeConfiguration.java`
- 测试：`manager/src/test/java/io/agentteams/manager/ManagerSmokeConfigurationTest.java`

- [x] 步骤 1：编写配置失败测试，验证远程 quota 开启但缺 tenant/project、Manager ID 或非法 Gateway port 时抛出稳定异常，并覆盖默认值和有效配置。
- [x] 步骤 2：运行 `mvn -q -pl manager -am -Dtest=ManagerSmokeConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认测试先因配置类缺失而失败。
- [x] 步骤 3：实现配置解析和 gRPC channel 组装；远程关闭返回 `QuotaPort.noop()`，远程开启返回 `ManagerQuotaPortFactory.from(...)`，并由 smoke 调用 `ProjectScopedModelCallAdmission` 包裹 Provider 请求。
- [x] 步骤 4：运行 Manager 配额配置、Factory、admission 和 SessionService 定向回归测试；已有 admission 测试覆盖拒绝短路与 release。

### 任务 2：Kind Worker 真实配额验收

**文件：**
- 修改：`.github/workflows/ci.yml`
- 创建：`scripts/run-kind-worker-quota-admission.py`
- 创建：`scripts/test_kind_worker_quota_admission_contract.py`

- [x] 步骤 1：编写脚本契约测试，要求 CI 在创建真实 Worker 时开启 `AGENTTEAMS_QUOTA_REMOTE_ENABLED` 并传入 `tenant-a/project-a`，且执行新的验收脚本。
- [x] 步骤 2：运行 `python3 -m unittest scripts.test_kind_worker_quota_admission_contract -v`，确认当前 CI 缺少该接线时先失败。
- [x] 步骤 3：实现验收脚本：通过 `/api/v1/usage/quota` 配置固定 Worker scope 对应的 project，创建临时 Team 并只加入目标 Agent，创建并 queue QwenPaw 任务，等待 `SUCCEEDED`，确认 `daily_calls` 增加、`daily_tokens` 增加、`current_concurrent_calls` 归零。
- [x] 步骤 4：在 CI 创建 Worker 后执行脚本，并通过脚本单测、Kind manifest/API validator、Python 语法检查和本地 Kind 端到端验收。

### 任务 3：回归与交付

**文件：**
- 修改：`docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md`

- [x] 步骤 1：运行 Maven `integration-tests verify`、Manager 定向回归、Python 脚本全量测试、Kind/API validator，并完成本地 Kind 真实 Worker 验收。
- [x] 步骤 2：更新本设计规格和商业差距规格中的远程配额接线状态，不改变未实现能力的状态。
- [x] 步骤 3：执行敏感扫描、`git diff --check`、提交中文 Conventional Commit 并推送当前分支。本轮敏感契约、配额契约、脚本全量测试、Manager 定向回归和 `mvn -q -Pintegration-tests verify` 均通过；变更提交并推送至自动执行功能分支。GitHub Actions `33173229336` 的 `verify` 成功，`kind-recovery`/`kind-oidc` 因功能分支条件跳过。

### 2026-08-24 本地验收补充

- 使用当前分支重新构建并加载 Control Plane、Gateway、Operator 和 Worker 镜像，避免复用旧 Kind 镜像。
- 真实 Worker 配额验收通过：`daily_calls_delta=1`、`daily_tokens_delta=1024`、`current_concurrent_calls=0`。
- `run-kind-quota-recovery.py --restart-control-plane` 通过 acquire/release 幂等、并发拒绝、超时、跨 tenant/project 隔离和 Control Plane 重启恢复检查。
- 修复 Windows 本地 Python 默认 GBK 导致的 UTF-8 Shell 契约测试失败：`test_kind_mtls_bootstrap_contract.py` 现在显式使用 UTF-8 读取脚本。
- 本轮已完成本地交付收口；远程 `verify` 已成功，Kind 作业保持“分支条件跳过”状态。
