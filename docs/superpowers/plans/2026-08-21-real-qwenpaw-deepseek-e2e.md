# 真实 QwenPaw + DeepSeek Kind 端到端验证实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `executing-plans` 逐任务执行。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在本地 Kind 集群中使用真实 QwenPaw、真实 DeepSeek API Key 和当前 Java Worker，验证从模型配置、Agent 注册、任务创建、QwenPaw 执行到 `SUCCEEDED` 的完整链路。

**架构：** QwenPaw 运行在 `agentteams` 命名空间的 `qwenpaw` Service 中；Worker 由 Operator 根据 Worker CR 创建，使用 QwenPaw HTTP/SSE Runtime；Control Plane 负责任务状态和调度。DeepSeek Key 只从本机 `apikey` 文件或环境变量读取，不进入 YAML、命令行参数、日志、镜像和 Git。

**技术栈：** Kind、Colima/Docker、Kubernetes、Helm、QwenPaw HTTP API、DeepSeek OpenAI-compatible API、Java 17、Bash、curl、jq、kubectl。

## 验收门槛

- `qwenpaw`、Control Plane、Gateway、Operator、Worker Deployment 均 Ready。
- QwenPaw active model 返回 `deepseek/deepseek-v4-flash`。
- QwenPaw Provider test 成功，Manager smoke 返回脱敏成功标记。
- 任务 smoke 创建并排队任务后，任务最终进入 `SUCCEEDED`。
- 任务输出包含 `QWENPAW_DEEPSEEK_SMOKE_OK`，并且没有 API Key 出现在输出或 Git 工作区。
- 失败时保留 Pod 状态、事件和脱敏日志，禁止通过关闭校验或跳过真实 QwenPaw 来宣称通过。

## 文件职责

- 修改：`scripts/configure-local-qwenpaw-deepseek.sh`，修复真实 QwenPaw API 响应兼容性、就绪等待和敏感信息边界。
- 修改：`scripts/smoke-deepseek-manager.sh`，保证真实 Manager 请求使用本机 Key 且只输出脱敏结果。
- 修改：`scripts/smoke-kind-qwenpaw-deepseek.sh`，增强 Worker/Runtime/QwenPaw readiness 和任务结果诊断。
- 修改：`deploy/bootstrap-kind-qwenpaw-worker.sh`，确保 Worker 使用当前本地镜像和实际 Agent UUID，避免复用错误占位资源。
- 新增或修改：`scripts/validate-kind-manifests.py` 及相关静态检查，覆盖真实 smoke 所需的 Service、环境变量和镜像契约。
- 修改：`README.md`、`docs/superpowers/plans/2026-08-21-deepseek-local-integration.md`，记录真实验证命令、结果和剩余阻塞。

### 任务 1：建立真实环境前置检查

- [x] **步骤 1：检查本机凭据边界。** 使用 `git check-ignore -v apikey`、`git ls-files apikey` 和文件权限检查；禁止读取或打印 Key 内容。
- [x] **步骤 2：检查 Kind 和 Docker。** 执行 `source deploy/dev-env.sh`、`docker info`、`kind get clusters`、`kubectl config current-context`；若当前上下文不可用，按 `deploy/install-kind-dev.sh` 的既有流程恢复，不删除已有集群或数据。
- [x] **步骤 3：检查资源状态。** 确认 `qwenpaw`、PostgreSQL、NATS、MinIO、Control Plane、Gateway 和 Operator 的 Deployment/StatefulSet Ready，确认 Worker CRD 已安装。
- [x] **步骤 4：运行静态脚本检查。** 执行 `bash -n`、`python3 scripts/validate-kind-manifests.py`、`python3 scripts/validate-kind-infra.py`、`helm lint deploy/helm/agentteams-java`。

### 任务 2：配置真实 QwenPaw DeepSeek Provider

- [x] **步骤 1：运行 QwenPaw 配置脚本。** 执行 `./scripts/configure-local-qwenpaw-deepseek.sh`，仅接受输出 `QWENPAW_DEEPSEEK_OK provider=deepseek model=deepseek-v4-flash`。
- [x] **步骤 2：独立检查 active model。** 通过临时 port-forward 查询 `/api/models/active`，只打印 provider 和 model 字段，确认值为 `deepseek` 与 `deepseek-v4-flash`。
- [x] **步骤 3：检查 QwenPaw 运行日志。** 仅提取 HTTP 状态、错误类别和就绪信息；若出现 API schema 不兼容，修改脚本并先用脱敏响应结构测试，再重新运行真实配置。

### 任务 3：连接真实 Worker

- [x] **步骤 1：构建并加载 Worker 镜像。** 执行 `./deploy/build-images.sh`，确认 `ghcr.io/ly416123/agentteams-agent-worker:latest` 已加载到 Kind 节点。
- [x] **步骤 2：执行 Worker bootstrap。** 执行 `./deploy/bootstrap-kind-qwenpaw-worker.sh`，确认 Agent UUID 来自 Control Plane，Worker CR、Deployment、Service 和 status 均使用同一身份。
- [x] **步骤 3：检查 Worker 连接状态。** 确认 Worker 日志出现 `READY`，Control Plane `agents.phase=READY`，Gateway 无身份或协议错误；检查 QwenPaw endpoint 为集群内 `http://qwenpaw:8088`。

### 任务 4：执行真实任务闭环

- [x] **步骤 1：执行 Manager smoke。** 运行 `./scripts/smoke-deepseek-manager.sh`，只接受脱敏成功标记和模型名，不保存输出中的响应正文。
- [x] **步骤 2：执行 Kind 任务 smoke。** 运行 `./scripts/smoke-kind-qwenpaw-deepseek.sh`，要求任务最终为 `SUCCEEDED`，并从 Worker 日志确认目标标记。
- [x] **步骤 3：保留失败诊断。** 如果超时或失败，记录任务 phase、Worker/QwenPaw/Control Plane Pod 状态、最近事件和脱敏日志；按“配置→连接→调度→HTTP/SSE→回传”顺序修复，不直接扩大超时掩盖问题。
- [x] **步骤 4：重复验证幂等性。** 使用新的任务 Idempotency-Key 重跑一次，确认两个任务均可独立成功；对同一创建请求重复发送时不新增任务。

### 任务 5：固化自动化检查和文档

- [x] **步骤 1：补充脚本契约检查。** 静态检查必须覆盖模型名、Service 名、Worker endpoint、成功标记、Key 文件权限和禁止输出完整响应。
- [x] **步骤 2：更新 DeepSeek 计划状态。** 仅在真实命令输出满足验收门槛后勾选对应步骤；外部资源不可用时记录实际错误和复现命令。
- [x] **步骤 3：更新 README。** 记录从现有 Kind 集群开始的最短命令、成功输出格式、失败诊断入口和 Key 安全边界，不写入真实 Key 或响应正文。

### 任务 6：回归、提交和同步

- [x] **步骤 1：运行回归。** 执行 `mvn -q -Dmaven.repo.local=/private/tmp/agentteams-java-m2 clean test`、真实 `TaskPushInfrastructureIT`、所有脚本/Helm/Kind 静态检查和 `git diff --check`。
- [x] **步骤 2：安全检查。** 确认 `git ls-files apikey` 无输出，diff 和新增日志中无 API Key、Authorization 值或完整 QwenPaw/DeepSeek 响应。
- [x] **步骤 3：提交。** 使用 `test(集成): 验证真实 QwenPaw DeepSeek Kind 闭环`，提交只包含脚本、文档和必要修复。
- [ ] **步骤 4：同步远程。** 验证工作区干净后推送当前功能分支；网络失败时保留本地提交并报告准确远程状态。
