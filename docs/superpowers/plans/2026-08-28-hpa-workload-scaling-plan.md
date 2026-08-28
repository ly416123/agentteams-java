# 工作负载 HPA 与规模化安全基线实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 为 Control Plane、Gateway 和可选 Manager 增加默认关闭、CPU request 约束明确且可被 Helm 契约验证的 HorizontalPodAutoscaler。

**架构：** HPA 只扩缩无状态 HTTP/gRPC 工作负载，分别引用对应 Deployment，并使用 `autoscaling/v2` 的 CPU 利用率指标。默认不渲染 HPA；启用任一组件 HPA 时，Helm values schema 和模板共同要求正数 CPU request，避免指标没有基准。Operator 保持单副本和 leader election，不纳入 HPA。

**技术栈：** Helm、Kubernetes `autoscaling/v2`、JSON Schema、Python unittest、GitHub Actions。

---

## 任务 1：建立 HPA 失败契约

**文件：**
- 创建：`scripts/test_hpa_contract.py`
- 修改：无

- [x] **步骤 1：编写失败的契约测试**

测试必须覆盖：默认 values 不渲染 HPA；使用 Helm override 开启 Control Plane、Gateway 和 Manager HPA 时渲染三个 HPA；每个 HPA 的 `scaleTargetRef` 指向对应 Deployment，`minReplicas/maxReplicas` 与 override 一致，CPU utilization 指标存在；启用 HPA 但没有 CPU request 时 Helm 命令失败。

```python
def test_enabled_hpa_requires_cpu_request(self):
    result = subprocess.run(
        ["helm", "template", "agentteams", str(CHART), "--set", "controlPlane.autoscaling.enabled=true"],
        capture_output=True, text=True,
    )
    self.assertNotEqual(result.returncode, 0)
    self.assertIn("CPU request", result.stderr)
```

- [x] **步骤 2：运行测试确认失败**

运行：`python3 -m unittest scripts/test_hpa_contract.py -v`

预期：测试因 HPA 模板和契约测试文件尚不存在而失败，失败原因必须是缺少待验证的 HPA 行为，不得是 Python 语法错误或 Helm 环境缺失。

## 任务 2：增加 values/schema 和 HPA 模板

**文件：**
- 修改：`deploy/helm/agentteams-java/values.yaml`
- 修改：`deploy/helm/agentteams-java/values.schema.json`
- 修改：`deploy/helm/agentteams-java/templates/control-plane.yaml`
- 修改：`deploy/helm/agentteams-java/templates/gateway.yaml`
- 修改：`deploy/helm/agentteams-java/templates/manager.yaml`
- 创建：`deploy/helm/agentteams-java/templates/hpa.yaml`

- [x] **步骤 1：增加组件级配置**

在 `controlPlane`、`gateway` 和 `manager` 下增加同形状配置，默认值为：

```yaml
autoscaling:
  enabled: false
  minReplicas: 2
  maxReplicas: 4
  targetCPUUtilizationPercentage: 70
```

Manager 的默认 `minReplicas` 使用 1，保持现有 Manager 单副本默认行为。Schema 要求 enabled 为 boolean，副本和 CPU 百分比为正整数；`maxReplicas` 不小于 `minReplicas` 由 Helm 渲染前置校验保证；`manager.autoscaling` 位于现有 manager 配置对象内。

- [x] **步骤 2：在 Deployment 中建立 CPU request 前置条件**

当对应 autoscaling enabled 且 `.Values.resources.requests.cpu` 为空时，模板使用 Helm `fail` 终止渲染，并包含稳定文本 `CPU request is required when ... autoscaling is enabled`。不改变 HPA 关闭时 `resources: {}` 的兼容行为。

- [x] **步骤 3：实现 `autoscaling/v2` 模板**

`hpa.yaml` 只在对应 enabled 时渲染，分别生成 `...-control-plane-hpa`、`...-gateway-hpa` 和 `...-manager-hpa`。每个资源使用：

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

HPA 不携带镜像、Secret、endpoint 或业务标签；Manager HPA 只在 Manager Deployment 已启用时渲染。

## 任务 3：验证模板、Schema 和回归

**文件：**
- 修改：`scripts/test_hpa_contract.py`
- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md`
- 修改：`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md`

- [x] **步骤 1：运行契约测试确认通过**

运行：`python3 -m unittest scripts/test_hpa_contract.py -v`。

预期：默认关闭、三组件 enabled render、Deployment target、CPU metric 和缺失 CPU request fail-closed 测试全部通过。

- [x] **步骤 2：运行本机 Helm/脚本验证**

运行：`helm lint deploy/helm/agentteams-java`、`helm template agentteams deploy/helm/agentteams-java`、`python3 -m unittest discover -s scripts -p 'test_*.py'`、`python3 -m py_compile scripts/*.py`、`git diff --check`。

预期：现有默认 values 不新增 HPA，全部脚本测试通过，渲染结果不含敏感值。

- [x] **步骤 3：使用本机 Docker/Colima 做 Maven 回归**

运行：`source deploy/dev-env.sh && mvn -q -pl control-plane -am test`。

预期：Control Plane 测试通过；该变更不修改 Java 业务代码和数据库迁移。

- [x] **步骤 4：同步文档边界**

只记录 HPA 模板、CPU request fail-closed 和默认关闭作为仓库侧 L1-L3 交付；不把本地 Helm render 宣称为 Kubernetes Metrics Server、Prometheus Adapter 或生产扩缩容验收。

## 任务 4：提交和远程 CI

**文件：**
- 修改：本计划文件及任务 2/3 列出的文件

- [x] **步骤 1：提交单一职责变更**

运行：

```bash
git add deploy/helm/agentteams-java scripts/test_hpa_contract.py docs/superpowers/plans/2026-08-28-hpa-workload-scaling-plan.md docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md
git diff --cached --check
git commit -m "feat(扩缩容): 增加工作负载 HPA 契约"
```

- [x] **步骤 2：推送 main 并确认 CI**

运行：`git push origin main`、`gh run list --branch main --limit 1`，再跟踪该提交对应的 `verify`、`kind-oidc` 和 `kind-recovery`。只有三个作业均为 success 才将本计划标记完成。

验证结果（2026-08-28）：提交 `9393bf0` 已推送到 `main`；GitHub Actions 运行 `33161634266` 的 `verify`、`kind-oidc`、`kind-recovery` 三个作业均为 `success`。

## 完成边界

本计划完成表示 Chart 已具备可选 HPA 和安全的 CPU request 契约；不表示已经在生产集群安装 Metrics Server/Prometheus Adapter、完成 HPA 实际扩缩、拓扑故障或 L6 长压测。Operator、数据库、有状态依赖和基于队列深度的自定义指标不在本计划范围内。
