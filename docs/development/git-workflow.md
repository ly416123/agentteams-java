# Git 开发工作流

本文档规定本仓库的唯一集成主线，目标是让代码、CI 和部署文档始终围绕同一个基线推进。

## 主线约定

- `main` 是唯一集成分支，也是发布和生产部署的基线。
- 所有开发分支必须从最新的 `origin/main` 创建，统一使用 `codex/<task-name>` 命名。
- 功能完成后先合并到 `main`，再从 `main` 创建下一项工作；不得把其他长期分支作为事实上的默认主线。
- 一个任务只保留一个活动集成分支。临时工作树完成后应及时移除，避免同一功能在多个目录继续演进。
- `main` 应设置为远程仓库的默认分支并启用保护规则，合并通过 CI 和代码审查后才允许进入主线。

## 开始任务

在开始新任务前，先确认工作区位于 `main`，并以快进方式同步：

```bash
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c codex/<task-name>
```

如果本地存在未提交改动，先确认这些改动属于当前任务；不应通过切换分支覆盖或丢弃它们。需要隔离任务时，使用 `git worktree`，并在创建前检查现有工作树：

```bash
git worktree list
git worktree add ../agentteams-java-<task-name> -b codex/<task-name> origin/main
```

## 合并与同步

提交会触发 GitHub Actions 前，必须先在本机 Docker 环境完成与项目范围匹配的验证。当前项目背景是 macOS + Colima；Maven 根 `pom.xml` 会在检测到 Colima socket 后自动为 Surefire/Failsafe 配置 Testcontainers endpoint，`deploy/dev-env.sh` 继续负责 Docker CLI、Kind 等显式命令：

```bash
source deploy/dev-env.sh
docker info
mvn -q -Pintegration-tests verify
```

上述 Docker 验证未通过时，不得把变更标记为本地验证完成，也不得为普通开发变更推送到 GitHub CI；应先修复本地环境或测试失败原因。纯 Java 测试可以用于定位问题，但不能替代 Docker-backed 验证。

合并前至少检查以下内容：

```bash
git fetch origin
git diff --stat origin/main...HEAD
git diff --check
source deploy/dev-env.sh
docker info
mvn -q -Pintegration-tests verify
python3 -m unittest discover -s scripts -p 'test_*.py'
```

合并后立即验证并同步远程主线：

```bash
git switch main
git pull --ff-only origin main
git push origin main
```

若开发分支与 `origin/main` 已经分叉，应先在该开发分支上基于最新主线完成 rebase 或按审查要求解决冲突，再发起合并。禁止直接把一个可能删除现有模块或部署能力的旧分支提升为默认主线。

## 分支治理检查清单

- 远程默认分支是否为 `main`。
- 本地 `main` 是否跟踪 `origin/main`，且工作区是否干净。
- 新分支是否基于最新 `origin/main`，而非基于另一个功能分支。
- 是否存在重复实现同一任务的活动分支或工作树。
- 合并前后的 Maven、脚本和 Helm 校验是否有可复现结果。
- 临时分支和工作树是否已归档或移除；需要保留的本地改动是否已明确记录。
