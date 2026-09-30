# Git 开发工作流

本文档规定本仓库的唯一集成主线，目标是让代码、CI 和部署文档始终围绕同一个基线推进。

## 主线约定

- `main` 是唯一集成分支，也是发布和生产部署的基线。
- 所有新需求必须直接在 `main` 上完成，包括需求规格、计划、实现、测试和修复；本项目不创建功能分支、临时集成分支或 Git worktree。
- 一个需求完成并验证后，按需求范围提交并同步到 `origin/main`，再开始下一项需求；不得把未完成工作留在其他分支，也不得把其他长期分支作为事实上的默认主线。
- 历史分支只用于只读审查和差异盘点。需要取回其中的功能时，只能将确认缺失且适配当前架构的最小变更移植到 `main`，完成回归后提交；禁止整体合并历史分支。
- `main` 应设置为远程仓库默认分支。直接在 `main` 上开发不代表免除质量门禁：提交前必须完成与范围匹配的代码审查、自动化测试和本地验证，验证通过后才可推送；远程保护规则应与本项目的 main-only 工作方式一致。

## 开始任务

在开始新需求前，先确认当前分支为 `main`，检查工作区改动归属，并以快进方式同步远端。已有未提交改动必须保留，不得为切换或同步分支而覆盖、丢弃或混入无关需求：

```bash
git fetch origin
git switch main
git pull --ff-only origin main
```

如果当前不在 `main`，或本地 `main` 无法安全快进同步，应先停止开发并明确处理现有状态；不得通过新建分支或 worktree 绕过该约束。多个需求不得并行写入同一工作区；先完成、验证并同步当前需求，再开始下一项。

## 验证、提交与同步

提交会触发 GitHub Actions 前，必须先在本机 Docker 环境完成与项目范围匹配的验证。当前项目背景是 macOS + Colima；Maven 根 `pom.xml` 会在检测到 Colima socket 后自动为 Surefire/Failsafe 配置 Testcontainers endpoint，`deploy/dev-env.sh` 继续负责 Docker CLI、Kind 等显式命令：

```bash
source deploy/dev-env.sh
docker info
mvn -q -Pintegration-tests verify
```

上述 Docker 验证未通过时，不得把变更标记为本地验证完成，也不得为普通开发变更推送到 GitHub CI；应先修复本地环境或测试失败原因。纯 Java 测试可以用于定位问题，但不能替代 Docker-backed 验证。

## 批量功能验收门禁

后续批量功能开发必须同时满足本地 Docker-backed 验证和与变更范围匹配的真实环境验收。凡是涉及 Kubernetes、Operator、Worker、TaskSandbox、RuntimeClass、镜像、Helm、运行时路由、生命周期或部署链路的功能，在本地 Docker/Kind 验证通过后，还必须在受控 Ubuntu/KVM L5 主机 `ly-MacBookAir7-2`（`192.168.122.55`）执行真实验收脚本，并保留成功标记、运行时证据和清理结果。

涉及 L5、Ubuntu/K3s 或其他 Linux 部署环境的镜像，必须按 `linux/amd64` 构建并在导入前用 `docker image inspect <image>:<tag> --format '{{.Os}}/{{.Architecture}}'` 校验；具体约束以[Worker Pod 与记忆隔离需求基线](../superpowers/specs/2026-09-01-work-pod-and-memory-isolation-requirements.md#11-linux-容器镜像平台约束)第 11 节为准。Apple Silicon 构建机不得将原生 `arm64` 镜像传输到 L5。

L5 不得用本地 Kind、Fake Provider、静态模板或“环境不可用”替代。任一本地 Docker 或 L5 门禁未通过时，变更只能标记为“开发完成、待验收”，不得标记批次完成、不得进入主线集成；后续修改镜像、Operator、Helm 或运行时行为后必须重新执行受影响的 L5 场景。L6 仍按路线图使用独立的受控环境，不因本条约束自动纳入普通批量开发。

### 执行平面并行开发边界

执行平面的职责边界包括 MCP Connector 协议、Token Ledger、对话转任务、实时事件消费和 SDK 适配；这些功能必须直接在 `main` 上按需求顺序推进，并通过已冻结的 Organization/Tenant、Sandbox Policy、MCP Connection 和任务过程契约接入，不得借“并行开发”名义创建独立分支或工作树。

以下内容属于共享模型的高风险变更：Organization/Tenant 主键与作用域、SandboxPolicy 字段和合并语义、MCP Connection 唯一键、任务过程事件 sequence 规则、事件可见性枚举。修改这些边界前，必须先更新架构规格、实现计划和对应迁移；开发期间暂停其他需求写入 `main`，完成全量相关回归后再继续。

本地统一门禁入口为 `bash scripts/enterprise-execution-contract.sh`。它覆盖应用契约、Control Plane、真实 Testcontainers 迁移、源码/发布契约、API 契约、Helm 和 diff 检查；涉及 Kubernetes、Operator、Worker、TaskSandbox 或 RuntimeClass 的变更，仍需追加 Ubuntu/KVM L5 真实验收。

在 `main` 提交和推送前至少检查以下内容：

```bash
git fetch origin
git status --short
git diff --stat
git diff --check
source deploy/dev-env.sh
docker info
mvn -q -Pintegration-tests verify
python3 -m unittest discover -s scripts -p 'test_*.py'
```

验证通过并按需求范围提交后，直接同步远程 `main`：

```bash
git fetch origin
git rev-list --left-right --count origin/main...main
git push origin main
```

若发现历史分支与 `origin/main` 分叉，只进行只读差异审查；确认缺失功能后，将最小变更移植到 `main` 并验证。禁止 rebase/整体合并历史分支，尤其不得将可能删除现有模块或部署能力的旧分支提升为默认主线。

## 分支治理检查清单

- 远程默认分支是否为 `main`。
- 本地 `main` 是否跟踪 `origin/main`，且工作区是否干净。
- 当前需求是否只在 `main` 工作，且没有另起分支或工作树继续实现。
- 是否存在与当前需求并行写入的工作区；存在时先暂停并收敛到单一主线。
- 提交前后的 Maven、脚本和 Helm 校验是否有可复现结果。
- 历史分支是否仅用于只读盘点；需要保留的本地改动是否已明确记录。
