# Kind Worker 启动配置恢复验收实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框跟踪进度。

**目标：** 将 Worker 重启后的当前配置快照恢复接入 GitHub CI 的 Kind 配置验收。

**架构：** 复用现有 `run-kind-resource-binding-ack.py` 的快照、绑定和 ACK fixture。在合法配置已应用后删除唯一 Worker Pod，等待替代 Pod Ready，并从替代 Pod 日志验证启动拉取以 `sequence=0` 成功应用配置；随后保留现有非法配置校验路径。

**技术栈：** Python 3 标准库、kubectl、GitHub Actions、现有 Control Plane 配置 API。

---

## 文件职责

- 修改 `scripts/run-kind-resource-binding-ack.py`：增加 Worker Pod 重启、替代 Pod 等待和启动恢复日志断言。
- 修改 `scripts/test_kind_config_acceptance_contract.py`：锁定重启顺序、启动恢复标记和非敏感诊断要求。
- 修改 `scripts/validate-kind-manifests.py`：要求验收脚本包含启动恢复断言。
- 修改 `.github/workflows/ci.yml`：将现有步骤名称明确为配置 ACK 与启动恢复验收。

### 任务 1：先写契约测试

- [ ] 在 `KindConfigAcceptanceContractTest` 增加测试，要求脚本包含 `worker_pod_names`、`run("delete", "pod"`、`replacement Worker Pod`、`sequence=0`、`applied=true` 和 `KIND_CONFIG_BOOTSTRAP_OK`。
- [ ] 增加顺序断言：合法配置成功标记必须在 Pod 删除前，启动恢复成功标记必须在 Pod 删除后，非法配置失败标记仍在启动恢复标记之后。
- [ ] 运行 `python3 -m unittest scripts.test_kind_config_acceptance_contract`，预期新增测试失败，因为脚本尚未有启动恢复路径。
- [ ] Commit：`test(验收): 锁定 Worker 启动配置恢复断言`。

### 任务 2：实现重启恢复验收

- [ ] 在 `scripts/run-kind-resource-binding-ack.py` 增加 `worker_pod_names`、`deployment_ready` 和 `pod_logs(namespace, pod)`：前两者读取 Deployment selector/status，后者使用 `kubectl logs` 读取单个替代 Pod 日志；失败诊断只保留最近 120 行并截断每行 512 字节。
- [ ] 增加 `wait_for_replacement_worker(namespace, deployment, old_pod, expected_replicas, timeout)`：轮询 `worker_pod_names`，要求出现不同于旧 Pod 的 Running Pod，并通过 `deployment_ready` 确认 Deployment Ready。
- [ ] 增加 `wait_for_bootstrap_log(namespace, pod, agent_id, timeout)`：轮询日志中的两条非敏感标记，要求同一 Agent 出现 `ConfigChanged received ... sequence=0` 和 `ConfigChanged completed ... sequence=0 applied=true`。
- [ ] 在合法配置 `APPLIED` 后记录旧 Pod、删除旧 Pod、等待替代 Pod，成功后输出：

```text
KIND_CONFIG_BOOTSTRAP_OK agent=<uuid> old_pod=<name> replacement_pod=<name> revision=<version>
```

- [ ] 保留现有 `KIND_RESOURCE_BINDING_ACK_OK` 和非法配置 `KIND_RESOURCE_BINDING_FAILURE_OK` 语义；异常时输出 Agent、旧 Pod、替代 Pod、binding 和 snapshot 版本，不输出 manifest 内容。
- [ ] 运行 `python3 -m py_compile scripts/run-kind-resource-binding-ack.py` 和契约测试，预期通过。
- [ ] Commit：`test(验收): 增加 Worker 启动配置恢复检查`。

### 任务 3：接入 CI 契约并验证

- [ ] 更新 `.github/workflows/ci.yml` 中对应步骤名称为 `Run Kind resource binding ACK and config bootstrap acceptance`，命令和环境变量继续复用现有脚本入口。
- [ ] 在 `scripts/validate-kind-manifests.py` 的资源绑定验收必备项中加入 `KIND_CONFIG_BOOTSTRAP_OK`、`sequence=0` 和 `applied=true`。
- [ ] 运行 `python3 -m unittest scripts.test_kind_config_acceptance_contract`、`python3 -m py_compile scripts/run-kind-resource-binding-ack.py scripts/test_kind_config_acceptance_contract.py scripts/validate-kind-manifests.py`、`python3 scripts/validate-kind-manifests.py`。
- [ ] 运行 `git diff --check`。
- [ ] Commit：`ci(验收): 接入 Worker 启动配置恢复检查`。

### 任务 4：最终回归

- [ ] 运行 `mvn -B -pl agent-worker,control-plane -am test`，确认 Java 行为没有回归。
- [ ] 运行 `python3 -m unittest scripts.test_batch_a_helm_contract scripts.test_kind_config_acceptance_contract`。
- [ ] 检查 `git status --short` 只包含预期提交历史，并推送分支。
