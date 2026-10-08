# L5 Keycloak PostgreSQL 持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `executing-plans` 按任务执行本计划，并在身份导出、数据库创建和服务切换处分别检查证据。每个任务使用复选框跟踪状态。

> **仓库约束：** 本仓库要求在 `main` 上直接完成工作，不创建 worktree。执行期间仅修改本计划文件清单中的文件；已有暂存和未暂存改动均保持原样，不混入本需求提交。

**目标：** 将 L5 Keycloak 的身份数据从临时 H2 迁移到现有持久化 PostgreSQL，并保持当前有效用户 UUID 与 Control Plane 成员关系不变。

**架构：** 复用 `agentteams` 命名空间中的 PostgreSQL StatefulSet 和 5Gi PVC，为 Keycloak 建独立数据库、账号及 Kubernetes Secret。添加 L5 专用 Deployment 清单和安全引导脚本；先从当前 H2 数据导出 realm/用户并比对 UUID，再导入新数据库后切换 Keycloak，Kind 开发配置保持不变。当前 L5 地址更新见设计规格顶部的环境变更记录；下方执行记录中的旧地址保留为当日历史事实。

**技术栈：** Keycloak 25.0.6 CLI、PostgreSQL 16、K3s/kubectl、Kubernetes Secret、Python unittest。

---

## 文件清单

- 新建 `deploy/production/l5-keycloak-postgresql.yaml`：L5 Keycloak Deployment 与 NodePort Service 的受控源文件，引用外部 Secret，不包含敏感值。
- 新建 `deploy/production/bootstrap-l5-keycloak-db.sh`：幂等创建数据库专用账号、数据库和 Secret；密码不回显、不写入仓库。
- 新建 `deploy/production/l5-keycloak-postgresql.md`：导出、迁移、验收、备份、恢复与回滚运行手册。
- 新建 `scripts/test_l5_keycloak_postgresql_contract.py`：检查部署清单和脚本的配置契约及 Secret 边界。
- 修改 `deploy/production/README.md`：链接 L5 Keycloak 数据库运维流程。
- 本计划 `docs/superpowers/plans/2026-10-07-l5-keycloak-postgresql.md`：记录步骤及 L5 证据。

## 任务 1：建立安全的数据库凭据和 Keycloak 数据库

**文件：**
- 新建：`deploy/production/bootstrap-l5-keycloak-db.sh`
- 测试：`scripts/test_l5_keycloak_postgresql_contract.py`

- [x] **步骤 1：编写配置契约检查**

增加 unittest，断言引导脚本：

```python
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class KeycloakDatabaseBootstrapContractTest(unittest.TestCase):
    def test_bootstrap_uses_separate_database_and_secret(self):
        script = (ROOT / "deploy/production/bootstrap-l5-keycloak-db.sh").read_text()
        self.assertIn("keycloak-database", script)
        self.assertIn("CREATE DATABASE", script)
        self.assertIn("kubectl create secret generic", script)
        self.assertNotIn("echo $KEYCLOAK_DB_PASSWORD", script)
        self.assertNotIn("agentteams-dev", script)

if __name__ == "__main__":
    unittest.main()
```

- [x] **步骤 2：运行检查，确认脚本缺失时失败**

运行：`python3 -m unittest scripts.test_l5_keycloak_postgresql_contract.KeycloakDatabaseBootstrapContractTest -v`
预期：FAIL，文件不存在或引导脚本尚未实现。

- [x] **步骤 3：实现幂等的 Secret 与数据库引导脚本**

脚本使用 `set -euo pipefail`，验证 `kubectl`/`openssl` 和命名空间、现有 PostgreSQL StatefulSet。若 `keycloak-database` Secret 已存在则保留其密码；否则用 `openssl rand -base64 36` 生成密码并创建 Secret，字段为 `username=keycloak`、`password=<随机值>`。使用 `agentteams-database` Secret 只在 PostgreSQL Pod 中认证管理账号，通过受控 SQL 创建最小权限 `keycloak` 登录角色与独立 `keycloak` 数据库。重复执行时不得重置既有密码或删除数据库。脚本只输出资源名和成功状态，不输出密码或 SQL 参数。

- [x] **步骤 4：运行配置契约检查和 Shell 语法检查**

运行：

```bash
python3 -m unittest scripts.test_l5_keycloak_postgresql_contract.KeycloakDatabaseBootstrapContractTest -v
bash -n deploy/production/bootstrap-l5-keycloak-db.sh
```

预期：测试通过、`bash -n` 退出码为 0。

## 任务 2：建立 L5 Keycloak 声明式运行配置

**文件：**
- 新建：`deploy/production/l5-keycloak-postgresql.yaml`
- 测试：`scripts/test_l5_keycloak_postgresql_contract.py`

- [x] **步骤 1：扩展测试覆盖数据库和现有外部契约**

测试 Deployment 容器声明 `KC_DB=postgres`、`KC_DB_URL=jdbc:postgresql://postgresql:5432/keycloak`、SecretKeyRef 凭据、`KC_HTTP_ENABLED=true` 和当前 hostname；断言参数不含 `start-dev` 或 `--import-realm`，镜像仍为 `quay.io/keycloak/keycloak:25.0.6`，Service 暴露 NodePort 30082/30083，且清单不含 `kind: Secret` 或密码字面值。

- [x] **步骤 2：运行新契约测试确认红灯**

运行：`python3 -m unittest scripts.test_l5_keycloak_postgresql_contract -v`
预期：L5 清单缺失时测试失败。

- [x] **步骤 3：创建 L5 专用 Deployment 与 Service 清单**

清单保留当前 `keycloak` 标签、单副本、镜像、资源边界、健康探针、Service NodePort 与 `KC_HOSTNAME=http://192.168.122.55:30082`。数据库配置只引用 `keycloak-database` Secret。启动参数使用 `start --http-port=8080`；启用 HTTP、health 和非严格 hostname；移除 `start-dev` 与 realm ConfigMap 挂载。清单仅管理 L5 Keycloak，不改 `deploy/kind-keycloak.yaml`。

- [x] **步骤 4：执行配置测试与 YAML 解析**

运行：

```bash
python3 -m unittest scripts.test_l5_keycloak_postgresql_contract -v
python3 -c 'import yaml; yaml.safe_load_all(open("deploy/production/l5-keycloak-postgresql.yaml"))'
```

预期：所有契约测试通过，YAML 解析成功。

## 任务 3：编写 L5 导出、迁移与恢复手册

**文件：**
- 新建：`deploy/production/l5-keycloak-postgresql.md`
- 修改：`deploy/production/README.md`

- [x] **步骤 1：记录当前 H2 文件与身份清单**

手册要求先读取 Deployment/Pod/Secret/PVC 状态，通过 Admin API 分页导出当前用户 ID 清单及 realm/client/role 配置；识别当前 Pod H2 文件位置。禁止先滚动重启 Keycloak。Realm 导出 JSON 与用户清单保存到受限的临时备份目录，不提交 Git。

- [x] **步骤 2：记录本次历史 H2 导出及其限制**

手册记录本次迁移曾通过维护策略阻断外部流量，并将 H2 文件复制到临时 PVC 后由同版本 CLI 导出。代码审查指出该操作没有停止 Keycloak 进程，不能证明复制时不存在后台写入，因此这不是可复用的一致性保障流程。当前数据源已切换为 PostgreSQL；运维手册现以 PostgreSQL 原生备份/恢复为准。任何未来 H2 迁移必须另行验证可停止写入并取得一致快照的方案。

- [x] **步骤 3：记录导入、切换、回滚和恢复验证**

依次执行数据库引导、向 PostgreSQL 导入离线 realm、检查用户 ID/角色/客户端、执行 `kubectl diff -f`、应用 L5 清单、等待 rollout。验收 issuer、JWK、Alice/MCP 服务账号 token、API 成员权限、PostgreSQL StatefulSet 重启与 Keycloak Pod 重建。失败时保留证据和 Secret；若需回滚，通过已验证 realm 导出恢复到 PostgreSQL，不依赖没有持久卷的旧 H2。

- [x] **步骤 4：补齐双库备份和恢复说明**

记录 AgentTeams 与 Keycloak 数据库分别执行 `pg_dump --format=custom` 的步骤；恢复时先恢复两个数据库，再启动 Keycloak，最后启动依赖 OIDC 的服务。备份文件权限设为仅当前操作用户可读写，文档命令不打印数据库密码。

- [x] **步骤 5：链接生产运维手册并自检**

在 `deploy/production/README.md` 增加 L5 手册链接。运行 `git diff --check` 并检查文档中无密码、token 或用户导出文件。

## 任务 4：L5 前置检查与数据迁移

**文件：**
- 使用：`deploy/production/bootstrap-l5-keycloak-db.sh`
- 使用：`deploy/production/l5-keycloak-postgresql.yaml`
- 记录：本计划执行记录和 L5 手册

- [x] **步骤 1：记录集群和身份基线**

检查当前 L5 主机 `192.168.122.55` 上 Keycloak/PostgreSQL Ready 状态、NodePort、PVC Bound 状态、当前 issuer、Secret 名称、realm 用户 UUID 集合和应用 membership subjects。输出不得包含任何密码或 access token。

- [x] **步骤 2：执行 H2 迁移导出并记录证据边界**

本次历史操作对导出用户数量和 UUID 集合完成了对账；但事后审查确认没有证明在线 H2 文件副本具有一致性。该风险已明确记录，当前持久化数据库已成为唯一运行数据源；不以重做 H2 导出作为修复，而以当前 PostgreSQL 持续运行、用户对账和可恢复备份作为后续保障。

- [x] **步骤 3：建立 PostgreSQL 目标并恢复 realm**

运行 `deploy/production/bootstrap-l5-keycloak-db.sh` 两次验证幂等；从容器内执行 `pg_isready`，验证仅 `keycloak` 凭据能连接 Keycloak 数据库。使用同版本 Keycloak CLI 把完整 realm/用户导入该数据库，重新导出并比较 UUID、角色映射、客户端和协议配置。

- [x] **步骤 4：预览并执行 Keycloak 切换**

运行 `kubectl diff -f deploy/production/l5-keycloak-postgresql.yaml`。核对差异仅涉及 Keycloak Deployment 和现有 Service；确认恢复证据在安全目录且新数据库含已验证 realm 后，运行 `kubectl apply -f ...` 并等待 300 秒 rollout。

- [x] **步骤 5：验收 OIDC 与应用授权**

从 Control Plane Pod 查询 discovery 与 JWK；从 L5 主机获取 Alice 与 MCP service user token，检查 issuer、sub、realm roles；使用 Alice 和 MCP token 读取已授权项目接口，确认既有 membership 可用且数据库没有因迁移被改写。

- [x] **步骤 6：验证持久性并记录结果**

分别重启 PostgreSQL StatefulSet 和删除一次 Keycloak Pod，等待恢复 Ready；重复 discovery、用户 UUID、两种登录及项目 API 权限检查。将 rollout 状态、PVC、导入前后 UUID 比较摘要与接口状态追加到本计划，不写入密钥或 token。

## 任务 5：运行自动化验证并完成审查

**文件：**
- 验证：`scripts/test_l5_keycloak_postgresql_contract.py`
- 验证：`deploy/production/l5-keycloak-postgresql.yaml`
- 验证：所有本需求文件

- [x] **步骤 1：运行配置合同测试与 shell/YAML 检查**

运行：

```bash
python3 -m unittest scripts.test_l5_keycloak_postgresql_contract -v
bash -n deploy/production/bootstrap-l5-keycloak-db.sh
python3 -c 'import yaml; list(yaml.safe_load_all(open("deploy/production/l5-keycloak-postgresql.yaml")))'
git diff --check
```

预期：所有测试通过、YAML 可解析且无 whitespace 错误。

- [x] **步骤 2：复核身份与 Secret 约束**

确认身份 UUID 前后集合完全一致，应用 membership 表未被本需求修改；对本需求 diff 扫描确认没有数据库密码、Keycloak 用户导出、token 或 Secret data。

- [x] **步骤 3：提交需求范围变更**

仅在本需求验证完成后，精确暂存本计划“文件清单”中的文件并按仓库中文提交规范提交；先检查 `git diff --cached --stat` 和 `git diff --cached --check`，确认没有混入工作区现存改动。


## 执行记录（2026-10-07）

- 配置合同测试 3 项通过；`bash -n`、YAML 解析和本需求文件 `git diff --check` 通过。
- L5 原 Keycloak 为 `start-dev --import-realm`，H2 文件位于未挂载卷的 Pod 文件系统。捕获 5 个 realm 用户 ID 基线及 22 个应用 membership subject 基线；将 H2 文件复制到隔离临时 PVC 后，用同版本 CLI 离线导出成功。首次试运行显式覆盖 H2 JDBC URL，因缺少 Keycloak 默认兼容选项失败；改用默认 `dev-file` URL 后导出完成。导出 5 个用户 ID 与 Admin API 基线完全一致。**审查后补充限制：**复制前只阻断了外部入口，没有停止 Keycloak 进程，因此不能证明复制快照期间没有后台写入；UUID/角色/客户端核验通过，但这不构成 H2 文件事务一致性的证明。现在 H2 副本已按原计划安全清理，无法回溯增强该快照保证。当前运行数据已在 PostgreSQL，已通过 Pod 重建、PostgreSQL 重启和数据库备份验证。
- 引导脚本连续执行两次成功，创建 `keycloak-database` Secret 和独立 `keycloak` 数据库。Keycloak 25.0.6 CLI 将 realm 导入 PostgreSQL；导入后再导出核对，用户 UUID、45 个 realm 角色、用户角色映射，以及 7 个客户端的 redirect URI/web origins 一致。三个内建客户端 `admin-cli`、`broker`、`realm-management` 由 Keycloak 自动补出默认 `post.logout.redirect.uris=+` 属性；未改变其 redirect URI/web origins。
- `kubectl diff` 显示只修改 Keycloak Deployment、保留现有 Service NodePort 并增加受限 egress NetworkPolicy。上线后 Keycloak `1/1 Ready`、0 次重启，`KC_DB=postgres`，Discovery issuer 为 `http://192.168.1.13:30082/realms/agentteams`。
- PostgreSQL StatefulSet 重启后 Keycloak 仍 Ready；随后删除并重建 Keycloak Pod，重新列出的 5 个用户 UUID 与基线一致，22 个 membership subject 清单未变。Alice 与 MCP service user 的 issuer/subject 正确，MCP service user 持有 `task:read` 并能读取原任务。
- 验收完成后删除临时迁移 Pod、PVC、维护 NetworkPolicy 和临时 H2/realm 导出文件。通过 `pg_dump --format=custom` 生成 Keycloak 数据库恢复点，`pg_restore --list` 校验通过；备份存于 L5 `/home/ly/agentteams-backups/keycloak-postgresql-20261007.dump`（目录 `0700`、文件 `0600`），校验文件同目录。
- 本需求已提交并推送至 `origin/main`，提交为 `38bddbdd`（`fix(身份): 持久化 L5 Keycloak 数据`）；按路径提交，仅包含本计划列出的 7 个文件。推送同时发布了本地原先领先远端的两个提交。工作区原有其他暂存/未暂存改动保持未触碰。

## 计划自检

- 规格目标和两个方案：任务 1、任务 2、任务 4覆盖。
- 身份完整导出与主体 UUID 保留：任务 3、任务 4第 1–3 步覆盖，并将 mismatch 定义为停止条件。
- 数据库隔离、凭据保护、NetworkPolicy 与现有外部 OIDC 接入：任务 1、任务 2、任务 4覆盖。
- PostgreSQL/Keycloak 持久性、备份恢复和回滚：任务 3、任务 4第 6 步覆盖。
- Kind 不受影响和 Keycloak 版本/issuer 不变：任务 2明确限制。
- 计划未承诺在 H2 离线导出或 subject 比对失败时继续切换。
