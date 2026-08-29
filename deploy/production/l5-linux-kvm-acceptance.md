# L5 Linux/KVM TaskSandbox 验收

该入口用于已部署的 Ubuntu/K3s 集群，验证 AgentTeams `TaskSandbox` 的真实 Linux/KVM 运行时路径：

- `ISOLATED` 生成的 Job/Pod 使用 `gvisor`；
- `HARDENED` 生成的 Job/Pod 使用 `kata-qemu`；
- 两个临时 `TaskSandbox` 都能进入 `status.phase=READY`，并在结束时被 Operator 清理。

脚本只做 L5 runtime acceptance，不会安装 sandbox 镜像，不会安装业务 Control Plane、Helm Release 或 Operator，也不会创建、读取或注入生产凭证。运行前请先完成受控环境部署，并确保 Operator 使用与本次验收相同的 RuntimeClass 配置。

## 前置条件

在 Ubuntu/K3s 节点上确认：

1. `kubectl` 已安装并指向目标 K3s 集群，当前身份可访问目标命名空间、`RuntimeClass`、`TaskSandbox`、Job 和 Pod；若要收集完整证据，还需要读取 Node metadata，并允许对 sandbox Pod 执行 `uname -a`。
2. 目标命名空间（默认 `agentteams`）已存在。
3. `RuntimeClass/gvisor` 和 `RuntimeClass/kata-qemu` 均已存在，并已在节点上配置对应的 gVisor 与 Kata/QEMU handler。
4. `tasksandboxes.agentteams.io` CRD 已安装。
5. Operator/controller 已就绪，且其 Deployment 带有标签 `app.kubernetes.io/name=agentteams-operator`。脚本按此标签发现控制器，不依赖固定 Deployment 名称。
6. Operator 所配置的 sandbox runner 镜像已经由平台预置并可拉取。脚本不会安装或推送镜像。

如果是在本项目的 Intel 开发机上做一次受控验收，可先在仓库根目录构建
linux/amd64 的 Operator 和 runner 镜像，再通过 K3s 的 containerd 导入；这一步
需要 Ubuntu 上的 root 权限，但不需要任何生产凭证：

```bash
docker build --platform linux/amd64 \
  -f deploy/docker/operator.Dockerfile \
  -t ghcr.io/ly416123/agentteams-operator:l5 .
docker build --platform linux/amd64 \
  -f deploy/docker/task-sandbox.Dockerfile \
  -t ghcr.io/ly416123/agentteams-task-sandbox:latest .
docker save -o /tmp/agentteams-l5-images.tar \
  ghcr.io/ly416123/agentteams-operator:l5 \
  ghcr.io/ly416123/agentteams-task-sandbox:latest
gzip /tmp/agentteams-l5-images.tar
scp /tmp/agentteams-l5-images.tar.gz ly@192.168.1.16:/tmp/
ssh -tt ly@192.168.1.16 \
  'sudo k3s ctr -n k8s.io images import /tmp/agentteams-l5-images.tar.gz'
```

Operator Deployment 必须将镜像配置为对应的 `:l5` 标签并使用
`imagePullPolicy: Never` 或 `IfNotPresent`；runner 镜像名称必须保持为
`ghcr.io/ly416123/agentteams-task-sandbox:latest`，因为 Operator 只接受仓库内的
受控镜像。脚本自身仍只负责验收和清理。

仓库中的 `deploy/examples/qwenpaw-worker.yaml` 是业务 Worker 示例，不是本次 L5 入口的依赖；脚本只应用 `deploy/examples/task-sandbox-isolated.yaml` 与 `deploy/examples/task-sandbox-hardened.yaml` 两个短生命周期 `TaskSandbox` examples，不触碰业务 Worker 或 Control Plane。

## 本机 SSH 执行

以下命令从本机 SSH 到 Ubuntu/K3s 主机执行。`ly` 和 `192.168.1.16` 仅为示例，不包含密钥或密码：

```bash
ssh ly@192.168.1.16 \
  'cd /opt/agentteams-java && \
   KUBECTL=kubectl NAMESPACE=agentteams TIMEOUT=10m \
   ./scripts/run-l5-task-sandbox-acceptance.sh'
```

如果仓库位于其他目录，请替换 `cd` 路径。也可以在远端直接执行：

```bash
KUBECTL=kubectl NAMESPACE=agentteams TIMEOUT=10m \
  ./scripts/run-l5-task-sandbox-acceptance.sh
```

支持的环境变量如下：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KUBECTL` | `kubectl` | `kubectl` 可执行文件路径 |
| `NAMESPACE` | `agentteams` | 验收 CR 和 Operator 所在命名空间 |
| `TIMEOUT` | `300s` | 每个前置检查、READY 等待和清理等待使用的 `kubectl` 时长 |

脚本不会从环境变量读取 Token、密码、私钥或生产 Secret，也不会把这些内容写入验收 CR。请通过主机已有的 kubeconfig、K3s 配置和 Kubernetes RBAC 管理访问权限。

## 验收过程

脚本按以下顺序执行：

1. fail-fast 检查 `kubectl`、目标命名空间、`gvisor`、`kata-qemu`、TaskSandbox CRD，以及带 Operator 标签的 controller Deployment readiness。
2. 应用仓库 `deploy/examples/task-sandbox-isolated.yaml` 和 `deploy/examples/task-sandbox-hardened.yaml` 两个临时 examples；它们分别使用 `profile: ISOLATED` / `gvisor` 与 `profile: HARDENED` / `kata-qemu`，TTL 均为 300 秒。脚本会拒绝修改已存在的同名 CR。
3. 等待两个 CR 的 `status.phase` 变为 `READY`。超时会输出当前 phase/message，并进入清理。
4. 按 Operator 标签发现生成的 Job 和 Pod，输出 Job template 与 Pod 的 `runtimeClassName`，并要求它们分别等于 `gvisor` 和 `kata-qemu`。
5. 若权限和 runner 条件允许，通过 Pod 内 `uname -a` 输出 guest kernel；通过 Pod 所在 Node 的 `status.nodeInfo.kernelVersion` 输出 host kernel。若 `exec` 或 Node metadata 因 RBAC 不可用，脚本明确输出 `unavailable`，不会用另一侧的版本、固定字符串或猜测值伪造证据。
6. 正常结束、失败或收到中断信号时，删除两个临时 CR，并等待 CR、Operator 生成的 Job/Service/Pod 清理完成。

## 判据

成功必须同时满足：

- 输出 `TaskSandbox/... status.phase=READY` 两次；
- 两个 profile 的 Job 和 Pod `runtimeClassName` 分别为 `gvisor` 与 `kata-qemu`；
- 输出 `L5_LINUX_KVM_ACCEPTANCE_OK`；
- 最后的清理输出为 `L5 cleanup: complete`，进程退出码为 `0`。

guest/host kernel 行是证据增强项。由于非特权 Pod 或最小 RBAC 可能不允许 `kubectl exec`、Node metadata 读取，相关行可以是 `unavailable`；这不代表脚本伪造了内核结果，也不替代 runtimeClass 校验。

失败包括但不限于：缺少 RuntimeClass 或 CRD、Operator/controller 未就绪、任一 CR 未进入 `READY`、Job/Pod runtimeClassName 与预期不符、无法读取生成的 Job/Pod，或中断后的资源清理未确认完成。失败时请保留脚本输出以及集群侧 Operator/Job/Pod 事件供平台团队诊断；不要把生产凭证粘贴到日志中。
