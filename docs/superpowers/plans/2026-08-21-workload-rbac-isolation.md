# Workload ServiceAccount 隔离实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 消除 Gateway 与 Operator 复用高权限 ServiceAccount 的风险，并用 Helm 静态检查和 Kind 权限验证锁定最小权限边界。

**架构：** Control Plane、Gateway、Operator 各使用独立 ServiceAccount。Gateway 禁止自动挂载 Kubernetes API token；Operator 在安装命名空间内保留 Worker/Deployment/Service/Lease 管理权限，并只 watch 当前命名空间；Control Plane 只保留 Team CRD 的 `get/list/watch` 权限。多命名空间场景需按命名空间部署 Operator，后续再单独设计跨命名空间授权。

**技术栈：** Helm、Kubernetes RBAC、Kind、Python manifest validator。

---

### 任务 1：锁定失败验收

**文件：**

- 修改：`scripts/validate-kind-manifests.py`

- [x] **步骤 1：增加专用账号和无 Token 检查。** 校验 `_helpers.tpl` 提供 Operator/Gateway 专用账号 helper，Operator/Gateway Deployment 使用对应 helper，Gateway 声明 `automountServiceAccountToken: false`，Operator RBAC 绑定 Operator 专用账号。
- [x] **步骤 2：运行红灯。** 在 Helm 尚未拆分账号的基线代码上运行 `python3 scripts/validate-kind-manifests.py`，得到 `Dedicated service-account helper missing operatorServiceAccountName`。

### 任务 2：拆分 Helm ServiceAccount

**文件：**

- 修改：`deploy/helm/agentteams-java/values.yaml`
- 修改：`deploy/helm/agentteams-java/templates/_helpers.tpl`
- 修改：`deploy/helm/agentteams-java/templates/serviceaccount.yaml`
- 修改：`deploy/helm/agentteams-java/templates/gateway.yaml`
- 修改：`deploy/helm/agentteams-java/templates/operator.yaml`
- 修改：`deploy/helm/agentteams-java/templates/rbac.yaml`

- [x] **步骤 1：定义默认账号。** 使用 `agentteams-gateway` 和 `agentteams-operator`，保留 `agentteams-control-plane` 作为 Control Plane 账号。
- [x] **步骤 2：切换工作负载。** Gateway 使用专用账号且关闭 Token 自动挂载；Operator 使用专用账号；RBAC 绑定只指向 Operator 账号。
- [x] **步骤 3：运行绿灯。** 执行 `helm lint deploy/helm/agentteams-java` 和 `python3 scripts/validate-kind-manifests.py`，均通过。

### 任务 3：Kind 权限验收与交付

**文件：**

- 修改：`README.md`
- 修改：`docs/superpowers/plans/2026-08-18-kind-vertical-slice.md`

- [x] **步骤 1：应用 Helm。** 执行 `helm upgrade --install agentteams ... -f deploy/helm/kind-values.yaml`，Gateway 和 Operator rollout 成功。
- [x] **步骤 2：验证权限。** `agentteams-gateway` 对 Worker 和 Deployment 为 `no`；`agentteams-operator` 创建 Deployment 为 `yes`；`agentteams-control-plane` 读取 Team 为 `yes`、读取 Worker 为 `no`。
- [x] **步骤 3：验证业务链路。** 恢复 mTLS 参数后运行双 Agent Team 冒烟，输出 `TEAM_SCHEDULING_OK assigned=1 queued=2`。
- [x] **步骤 4：运行完整回归、提交并推送。** `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 test`、Kind 静态校验、Helm lint/template、脚本语法检查和敏感信息检查均已通过；提交与推送待本次任务收尾完成。

### 任务 4：收紧 Operator 的命名空间边界

**文件：**

- 修改：`deploy/helm/agentteams-java/templates/rbac.yaml`
- 修改：`deploy/helm/agentteams-java/templates/operator.yaml`
- 修改：`operator/src/main/java/io/agentteams/operator/AgentTeamsOperatorApplication.java`
- 修改：`scripts/validate-kind-manifests.py`
- 修改：`README.md`
- 修改：`docs/superpowers/plans/2026-08-18-kind-vertical-slice.md`

- [x] **步骤 1：先增加失败验收。** 校验 Operator RBAC 必须使用 `Role/RoleBinding`，并要求 Operator 注册控制器时读取 `AGENTTEAMS_OPERATOR_NAMESPACE`、调用 `settingNamespace`。
- [x] **步骤 2：实现命名空间范围。** 将 Operator 的 `ClusterRole/ClusterRoleBinding` 改为安装命名空间内的 `Role/RoleBinding`，Helm 通过 Downward API 注入当前命名空间，Operator 在该命名空间内 watch Worker 和 Team。
- [x] **步骤 3：Kind 验收。** 验证旧 Worker 继续 Ready；Gateway 无 Worker/Deployment 权限；Operator 仍可创建 Deployment；Control Plane 仍只读取 Team；双 Agent Team 调度冒烟输出 `TEAM_SCHEDULING_OK assigned=1 queued=2`。
- [x] **步骤 4：运行完整回归、提交并推送。** `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 test`、Kind 静态校验、Helm lint/template、脚本语法检查和敏感信息检查均已通过；提交与推送待本次任务收尾完成。
