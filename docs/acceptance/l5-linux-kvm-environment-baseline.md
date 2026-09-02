# L5 Linux/KVM 环境与 gVisor 故障基线

**用途：** 记录 L5 验收主机、`ly` 权限边界、gVisor/Kata 运行时配置，以及已确认的镜像拉取故障和修复方式。后续执行 L5 验收前必须先阅读本文，避免把镜像拉取问题误判为 RuntimeClass 或 handler 故障。

**最后核验：** 2026-09-02

**关联验收入口：** [`deploy/production/l5-linux-kvm-acceptance.md`](../../deploy/production/l5-linux-kvm-acceptance.md)

## 1. 已登记的 L5 环境

| 项目 | 已核验值 |
| --- | --- |
| SSH 目标 | `ly@192.168.122.55` |
| 主机名 | `ly-MacBookAir7-2` |
| 操作系统 | Ubuntu 26.04.1 LTS |
| 节点内核 | `7.0.0-30-generic`（`amd64`） |
| Kubernetes 发行版 | K3s |
| containerd | `2.3.4-k3s1.36` |
| Kubernetes 节点 | `ly-macbookair7-2`，状态为 `Ready` |
| gVisor | `/usr/local/bin/runsc`，`release-20260817.0` |
| runner 镜像 | `ghcr.io/ly416123/agentteams-task-sandbox:latest`，已预加载到 K3s containerd 镜像存储 |
| Operator | `agentteams-agentteams-java-operator`，滚动更新后 `1/1 Running` |

当前 RuntimeClass 与 containerd handler 的对应关系如下：

| RuntimeClass | containerd runtime type | handler |
| --- | --- | --- |
| `gvisor` | `io.containerd.runsc.v1` | `gvisor` |
| `kata-qemu` | `io.containerd.kata-qemu.v2` | `kata-qemu` |
| `runc` | 默认运行时 | `runc` |

截至最后核验，直接指定 `runtimeClassName: gvisor` 且使用
`imagePullPolicy: IfNotPresent` 的探针 Pod 已成功运行，并报告 gVisor guest kernel；因此该主机的 gVisor handler 已具备实际创建 sandbox 的能力。Kata profile 也已通过同一 L5 入口验证。

## 2. `ly` 权限边界

最后核验到的远程用户信息：

- UID/GID：`1000/1000`；
- 用户组包含 `sudo`、`kvm`、`lxd` 等；
- `sudo -n -l` 显示 `ly` 可执行普通 root 命令（普通命令可能要求 sudo 认证）；
- `/usr/local/bin/k3s` 和 `systemctl` 配置了 `NOPASSWD` sudo 规则。

因此，`ly` 不是“所有命令都免密执行”的特殊账号，但具备完成 L5 主机诊断和 K3s containerd 镜像导入所需的 root 能力。推荐使用以下方式确认权限，不要把密码写入命令、脚本或项目文档：

```bash
ssh -i ~/.ssh/agentteams-l5 ly@192.168.122.55
id
sudo -n -l
sudo -n /usr/local/bin/k3s crictl info
```

如果需要查看一般系统日志，可能需要交互式 `sudo` 和 TTY；这不表示 `ly` 没有 root 权限。权限不足时应记录具体命令和错误，再判断是否需要主机管理员协助。

## 3. 已确认的 gVisor 故障

### 3.1 现象

旧 Operator 镜像 `l5-worker-lifecycle-v11` 执行 L5 验收时：

- gVisor `TaskSandbox` 长时间停留在 `PROVISIONING`；
- 对应 Job 创建了 Pod，但 Pod 很快消失；
- Kubernetes 事件显示 runner 镜像拉取失败：GHCR 匿名 token 请求返回 HTTP 403；
- 由于验收脚本等待 gVisor 超时，Kata 任务随后被 Job deadline 回收。

### 3.2 根因

故障不是 gVisor handler、`runsc`、KVM 或 `RuntimeClass` 配置错误。原因是：

1. runner 使用固定的 `:latest` 标签；
2. 旧 Operator 生成的 Job 没有显式设置 `imagePullPolicy`；
3. Kubernetes 对带 `:latest` 标签且未显式设置策略的容器采用 `Always`；
4. 节点虽然已经预加载 runner 镜像，仍会尝试访问 GHCR；
5. GHCR 匿名拉取返回 403，导致 Pod 在进入 gVisor 运行时验证前就失败。

### 3.3 修复

修复已固化在 Operator 的 `TaskSandbox` Job 模板中：

```java
.withImage(TaskSandboxResourceFactory.SANDBOX_IMAGE)
.withImagePullPolicy("IfNotPresent")
```

同时，`TaskSandboxResourceFactoryTest` 已增加 `imagePullPolicy` 回归断言。2026-09-02 已构建并部署包含该逻辑的 Operator 镜像，随后重新执行 L5：

- `ISOLATED`：Job/Pod 使用 `runtimeClassName: gvisor`，状态 `READY`；
- `HARDENED`：Job/Pod 使用 `runtimeClassName: kata-qemu`，状态 `READY`；
- 两个 Pod 均取得 guest kernel 与宿主机 kernel 证据；
- 验收资源已清理完成。

## 4. 后续 L5 排查顺序

再次出现“Sandbox 不 READY”时，必须按以下顺序排查：

1. 先查看 Job、Pod 和事件，确认是否为 `ErrImagePull`、`ImagePullBackOff` 或匿名 registry 403；
2. 检查生成的 Job 容器是否为 `imagePullPolicy: IfNotPresent`，以及节点是否存在准确的镜像引用；
3. 在确认镜像已存在且不会触发远程拉取后，再检查 Job/Pod 的 `runtimeClassName`；
4. 再使用 `sudo -n /usr/local/bin/k3s crictl info` 检查 `gvisor` 是否映射到 `io.containerd.runsc.v1`；
5. 最后使用显式 `imagePullPolicy: IfNotPresent` 的最小探针验证 `runsc`，并检查 guest kernel；
6. 不得仅凭 Pod 消失或 `PROVISIONING` 状态认定 gVisor handler 损坏，必须保留 `kubectl describe` 和 Events 证据。

推荐的最小只读检查：

```bash
kubectl -n agentteams get runtimeclass gvisor kata-qemu -o wide
kubectl -n agentteams get jobs,pods -l app.kubernetes.io/name=agentteams-task-sandbox
kubectl -n agentteams get events --sort-by=.lastTimestamp
sudo -n /usr/local/bin/k3s crictl info
```

## 5. 安全边界

- L5 验收只使用开发/受控环境，不安装或读取生产 Secret、Token、密码或供应商凭据；
- runner 镜像必须由平台预置或通过受控镜像导入流程加载，禁止把匿名 registry 拉取成功作为 L5 前置条件；
- `ly` 的 SSH 私钥不写入仓库；本文只记录 SSH 用户、主机和权限类型；
- 每次验收结束必须确认临时 `TaskSandbox`、Job、Service 和 Pod 已清理；
- L6 外部供应商、生产 Secret Manager 和长期运行验收仍按项目约束保留至最终阶段。
