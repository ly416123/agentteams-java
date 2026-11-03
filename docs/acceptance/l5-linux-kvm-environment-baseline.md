# L5 Linux/KVM 环境与 gVisor 故障基线

**用途：** 记录 L5 验收主机、`ly` 权限边界、gVisor/Kata 运行时配置，以及已确认的镜像拉取故障和修复方式。后续执行 L5 验收前必须先阅读本文，避免把镜像拉取问题误判为 RuntimeClass 或 handler 故障。

**最后核验：** 2026-10-15

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
| Operator | `agentteams-agentteams-java-operator`，滚动更新后 `2/2 Running` |

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

### 3.4 资源耗尽与验收残留（2026-09-03）

本次 L5 受控排查发现，主机只有约 7.2 GiB 内存，历史浏览器验收产生的 20 个
`worker-*` Worker CR 各维持 1 个 JVM Pod，导致主机可用内存降至约 200 MiB，Swap
也基本耗尽。期间出现以下连锁现象：

- 节点短暂进入 `NotReady`，k3s API 请求出现超时；
- Control Plane 被 `OOMKilled` 并重启；
- Operator informer 无法在 10 秒内连接 Kubernetes API，进程以退出码 1 重启；
- Operator 轮询 Control Plane 时出现连接超时和 Vert.x event-loop blocked 警告。

处理方式是保留 Worker CR、任务和数据库数据，仅将这 20 个明确属于开发验收残留的
Worker CR 的 `spec.replicas` 置为 `0`，随后滚动重启 Operator。处理后节点、Control
Plane、Gateway、Operator 均恢复 Ready，稳定观察 35 秒无新的 Operator 异常；保留的
`l5-demo-qwenpaw-worker` 仍为 `1/1 Ready`。这不是生产清理，也不应作为常规验收清理
手段，若资源归属不明确必须先确认。

后续验收必须在结束阶段清理 Worker CR 的副本数或删除明确创建的临时 Worker；只删除
Deployment/Pod 不够，因为 Operator 会依据 Worker CR 的期望副本数重新创建它们。
执行前应记录创建的资源名称，执行后确认 `worker-*` 数量、`spec.replicas` 和主机内存。

### 3.5 Worker cgroup OOM（2026-10-14）

2026-10-15 通过 L5 只读 Kubernetes 身份和主机 `adm` 日志权限复核了本次 OOM：

- Worker CR `worker-6cecfa6bad9442b1ae3915547f998539` 的 `spec.agentId` 为 `6cecfa6b-ad94-42b1-ae39-15547f998539`，标签指向 `tenant-a/project-a/team-a`。
- 对应 Pod `worker-6cecfa6bad9442b1ae3915547f998539-65c7bf4fbd-gx8t2` 位于节点 `ly-macbookair7-2`。Pod UID 与主机内核日志中的 cgroup UID 一致；旧 container ID 也与内核日志一致。
- Pod 资源配置为 `request=128Mi`、`limit=512Mi`。`JAVA_TOOL_OPTIONS` 配置 `InitialRAMPercentage=15` 和 `MaxRAMPercentage=55`。
- 2026-10-14 14:04:31 +08，内核记录该 cgroup 用量达到 `524288kB/524288kB`，`failcnt=322`，并以 `CONSTRAINT_MEMCG` 杀死 Java 进程；进程退出码为 137，anon RSS 为 `517284kB`，file RSS 为 `23580kB`。Kubernetes 状态记录该容器随后重启 1 次。
- 这证明直接原因是单个 Worker 容器达到自身内存上限；该记录不是节点全局 OOM。现有证据没有 GC 日志或 Native Memory Tracking 数据，无法判断超限内存中 Java heap 与 native 分配各占多少。
- 该 Worker 的上一次容器日志在 14:04:15.439–14:04:15.636 +08 命中子任务 ID `3f1c9a52-7d4e-4b21-9a86-1c0e5b7d2a01`，距 OOM 约 16 秒；评测重试报告的任务树将该子任务列为父任务 `9b770e84-b750-4fea-aee3-01ffc442c884` 的子任务。结合 Worker CR 的 `spec.agentId`、Pod 与旧 container ID 的内核 cgroup 记录，Worker → Agent → 子任务 → 父任务的关联有直接日志和任务树支持，可信度高。首次超时快照没有保存该任务的运行时间，因此时间线只作交叉核验，不单独作为关联依据。
- 本次只读取已有指标，没有启动新评测。2026-10-15 检查时，节点 `MemAvailable` 约 `800 MiB`，`4 GiB` swap 已使用约 `3.1 GiB`；`agentteams` 命名空间有 12 个运行中的 Worker Pod。资源恢复且任务绑定审计记录补齐前，不进行并发压测，也不擅自缩容其他 Worker。

证据位置：L5 主机 `/var/log/kern.log*` 中与 Pod UID/旧 container ID 匹配的 OOM 记录；Worker 上一次容器日志中的子任务 ID 命中（仅核对 ID 和时间戳，未复制日志正文）；Kubernetes Worker/Pod 状态；`eval/reports/2026-10-14-l5-dual-mcp-10-isolated-team-timeout-snapshot.json` 与 `eval/reports/2026-10-14-l5-dual-mcp-10-isolated-team-rerun2.json` 中的任务树关系。

2026-10-15 后续只读复核：L5 节点 `ly-macbookair7-2` 当前 `Ready=True`、`MemoryPressure=False`，按该节点名称查询 Kubernetes Events 未找到事件；集群节点容量约 `7.2 GiB`。主机先后读取到 `MemAvailable=824,582,144` 与 `783,810,560` 字节（后者约 `748 MiB`），swap 后一次读取已用 `3,291,471,872` 字节（约 `3.06 GiB`），磁盘可用约 `120 GiB`。`agentteams` 仍有 18 个 Worker CR，其中 12 个运行中 Worker Pod；上述 OOM Worker CR 最新状态为 `Progressing`、期望副本数为 1，其 Pod `restartCount=1`，最后一次终止状态仍为 `OOMKilled/137`（结束时间 `2026-10-14T06:04:31Z`）。本次从主机内核日志再次读到其历史 cgroup 峰值 `524288/524288 kB`、`failcnt=322`。节点总体压力状态正常不改变单容器 512 MiB 限额已被触顶的事实；鉴于可用内存和 swap 状况，不启动 L5 评测、恢复演练或会创建 Worker 的身份轮换冒烟。

2026-10-15 再次只读复核：同一 Pod 仍为 `1/1 Running`、`restartCount=1`，上次终止原因仍为 `OOMKilled/137`；`kubectl top pod` 当前读数为 `254Mi`，节点读数为 `5846Mi/79%`。主机 `MemAvailable` 约 `725 MiB`、swap 仍已使用约 `3.06 GiB`。当前用量低于 Worker 的 512 MiB 限额，但只是单次采样，不能替代峰值观测或 GC/native 内存归因；不据此恢复并发评测。

2026-10-15 16:35 +08 后续只读复核：Pod 仍为 `1/1 Running`，最近一次指标采样为 `254Mi`；节点仍 `Ready=True`、`MemoryPressure=False`。主机 `MemAvailable=746,688 kB`，`SwapFree=957,268 kB`。归档目录检查进程 PID `1582736`（`sudo`）与 `1582738`（`crictl`）仍存活，运行约 34 分钟。Kubernetes 再次确认 Worker `spec.agentId=6cecfa6b-ad94-42b1-ae39-15547f998539`、期望副本数为 1、状态为 `Ready`；对应 Pod 的 `request=128Mi`、`limit=512Mi`、`restartCount=1`，当前容器自 `2026-10-14T06:04:32Z` 启动，上次终止仍为 `OOMKilled/137`。这些状态与前述映射一致，但仍无 GC 或 Native Memory Tracking 证据解释 Java heap 与 native 内存占比。未结束远端进程、重跑归档检查或创建新负载。

2026-10-15 16:52 +08 只读复核上一容器日志：通过 `kubectl logs --previous --timestamps --limit-bytes=262144` 读取到 135 行，只统计、不输出正文。子任务 ID `3f1c9a52-7d4e-4b21-9a86-1c0e5b7d2a01` 命中 2 次，时间为 `2026-10-14T14:04:15.439993364+08:00` 至 `2026-10-14T14:04:15.635941666+08:00`；在这段返回内容中未发现 GC、heap-after-GC、NMT 或 Java `OutOfMemory` 诊断线索。该结果确认任务关联日志仍可读取，但不能归因 heap/native 内存占比，也不能排除未落入截取范围的诊断记录。

### 3.6 OIDC 与 Secret Manager 接入核验（2026-10-15）

- L5 Keycloak 的 OIDC discovery 与 JWKS 端点均返回 HTTP 200，issuer 为 `http://192.168.122.55:30082/realms/agentteams`；JWKS 当前发布 1 个 RS256 签名密钥。此次只读取公开元数据，没有执行登录或密钥轮换，因此不能据此认定轮换流程已验收。
- L5 Kubernetes API 当前没有 `external-secrets.io` API/CRD，`SecretStore` 与 `ExternalSecret` 资源不可用，故 L5 尚未接入 External Secrets Operator，也没有验证外部 Secret Manager 读取或轮换。
- 当前本地 Kind 环境已安装 External Secrets Operator。`scripts/run-kind-external-secrets.py --namespace agentteams --run-id codex-20261015` 在该集群成功，Kubernetes-backed `SecretStore`/`ExternalSecret` 收敛为 Ready，脚本 finally 清理了测试对象。该结果只证明本地 Kind 到 Kubernetes Secret 的 provider 路径，不代表云端/外部 Secret Manager 集成。
- 因 L5 Worker 的 512 MiB cgroup OOM 和当前主机余量，本轮没有运行会创建任务/Worker 的 OIDC 轮换 smoke，也没有安装 L5 ESO 或修改 L5 身份配置。

2026-10-15 16:54 +08 只读复核：L5 OIDC discovery 和 JWKS 均可读取，issuer 仍为 `http://192.168.122.55:30082/realms/agentteams`；JWKS 当前有 2 个公钥用途项，算法/用途组合为 `RS256/sig` 与 `RSA-OAEP/enc`。未读取或输出公钥材料。L5 `external-secrets.io` CRD 数量仍为 0。该检查只证明发现文档/JWKS 端点可用，不证明 IdP 签名密钥轮换或任何外部 Secret Manager 已接入。

2026-10-15 17:01 +08 只读复核：OOM Worker Pod 的 requests/limits 仍为 `128Mi/512Mi`，`restartCount=1`，上次终止为 `OOMKilled/137`（`2026-10-14T06:04:31Z`）；对应 Worker CR 的 `spec.agentId=6cecfa6b-ad94-42b1-ae39-15547f998539`。再次只读读取上一次容器日志，仅匹配任务 ID 与时间戳：子任务 `3f1c9a52-7d4e-4b21-9a86-1c0e5b7d2a01` 的分配接受日志出现在 OOM 前约 16 秒，和既有任务树映射相符。没有复制日志正文、读取 Secret 值或发现 heap/native 归因证据。同次主机采样 `MemAvailable=745,544 kB`、`SwapFree=955,488 kB`；卡住的归档目录诊断进程 PID `1582736`（sudo）和 `1582738`（crictl）仍存活，已运行约 1 小时 2 分钟。未结束进程、未重试归档检查、未启动恢复或压力负载。

2026-10-15 17:03 +08 只读复核 Worker Pod 容器规格：运行镜像为 `agentteams-agent-worker:l5-aggregate-task-context-20261009-amd64`，实际注入的 `JAVA_TOOL_OPTIONS` 为 `-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15`，资源限制仍为 `512Mi`。这说明 JVM 受容器可用内存比例参数约束，但没有 heap dump、GC after-log 或 NMT 数据，不能据此断定 OOM 是 Java heap 耗尽或 native 开销导致；镜像标签也表明 L5 运行的是历史构建，仓库当前源码/默认值不能自动代表该镜像内部实现。

2026-10-15 17:04 +08 只读检查 L5 全集群 Pod、Service 与 ServiceMonitor，未发现 Prometheus、VictoriaMetrics、Mimir 或 Thanos 监控组件/目标。因此目前没有 L5 历史容器内存曲线或 JVM heap 指标可用于回溯 2026-10-14 的 OOM；根因仍只能确认到触及 512Mi cgroup 上限，不能细分 heap 与 native。该检查未安装监控组件或修改集群。

## 4. 后续 L5 排查顺序

再次出现“Sandbox 不 READY”时，必须按以下顺序排查：

1. 先查看 Job、Pod 和事件，确认是否为 `ErrImagePull`、`ImagePullBackOff` 或匿名 registry 403；
2. 检查生成的 Job 容器是否为 `imagePullPolicy: IfNotPresent`，以及节点是否存在准确的镜像引用；
3. 在确认镜像已存在且不会触发远程拉取后，再检查 Job/Pod 的 `runtimeClassName`；
4. 再使用 `sudo -n /usr/local/bin/k3s crictl info` 检查 `gvisor` 是否映射到 `io.containerd.runsc.v1`；
5. 最后使用显式 `imagePullPolicy: IfNotPresent` 的最小探针验证 `runsc`，并检查 guest kernel；
6. 不得仅凭 Pod 消失或 `PROVISIONING` 状态认定 gVisor handler 损坏，必须保留 `kubectl describe` 和 Events 证据。
7. 先检查节点资源和 Worker 数量；L5 单节点环境不得让历史验收 Worker 无限累积。若发现
   节点可用内存不足，应先停用已确认的开发验收残留 Worker，再继续 Sandbox 诊断。

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
- 每次验收结束还必须确认由验收创建的 Worker CR 已停用或删除；仅检查 Pod/Deployment
  不足以证明 Worker 已清理；
- L6 外部供应商、生产 Secret Manager 和长期运行验收仍按项目约束保留至最终阶段。

## 6. 无图形界面运行建议

TaskSandbox 的 L5 验收脚本只依赖 `kubectl`、K3s/containerd、RuntimeClass、Operator、SSH、网络和日志，不需要桌面环境、显示管理器、窗口管理器或 X11/Wayland。因此，L5 主机可以按无图形界面服务器运行，以减少常驻内存和 CPU 消耗。

需要保留的能力包括：

- K3s server/agent、containerd、gVisor `runsc`、Kata/QEMU 和 `/dev/kvm`；
- SSH、DNS、时间同步、网络访问、journald 和 Kubernetes 事件日志；
- 运行验收所需的 `kubectl`、镜像导入工具和必要的诊断命令。

Console 或 Playwright 验收属于另一类场景。它们需要浏览器，但可以使用 headless Chromium，不需要启动完整桌面。关闭图形界面本身只能减少基础开销，L5 主机的主要资源控制仍是限制 Worker 副本数、清理历史 Worker CR、设置合理的 Pod requests/limits，并在验收前检查可用内存和 Swap。
