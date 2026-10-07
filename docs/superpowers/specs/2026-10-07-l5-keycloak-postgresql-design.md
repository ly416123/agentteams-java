# L5 Keycloak PostgreSQL 持久化设计

**日期：** 2026-10-07
**状态：** 已批准；实现记录见 [实现计划](../plans/2026-10-07-l5-keycloak-postgresql.md)
**范围：** 将 L5 K3s 上的 Keycloak 从临时 H2 切换到现有持久化 PostgreSQL。

## 背景与目标

2026-10-07 的 L5 子任务 PDF 验收期间，Keycloak Deployment 重启后丢失了原 realm 数据。当前 Pod 使用 `start-dev`，未配置数据库，也没有数据卷。恢复 Keycloak 用户后，Control Plane 中基于 OIDC `sub` 的成员关系仍需单独补齐，证明身份存储丢失会破坏应用授权关联。

L5 已有 `postgresql` StatefulSet 和绑定的 5Gi `local-path` PVC。目标是让 Keycloak realm、客户端、角色和用户持久化，并保留当前有效用户的 UUID（OIDC `sub`），使 Control Plane 成员记录继续指向同一主体。此次不升级 Keycloak 版本、不调整应用 OIDC issuer、不改 Kind 部署。

## 方案选择

### 方案 A：复用现有 PostgreSQL，创建独立 Keycloak 数据库（推荐）

在现有 PostgreSQL 实例创建独立 `keycloak` 数据库和专用最小权限账号；凭据存 Kubernetes Secret。Keycloak 25.0.6 使用 PostgreSQL 16 与生产 `start` 模式连接数据库。应用数据库仍使用既有 `agentteams` 库和账号，二者不共享 schema 或凭据。

优点是复用已绑定持久卷和现有运维路径；缺点是应用与身份服务共用一个 PostgreSQL 实例，实例故障会同时影响两者。

### 方案 B：新增独立 PostgreSQL StatefulSet

为 Keycloak 配置独立 PostgreSQL Pod、5Gi PVC、Service、Secret 和 NetworkPolicy。隔离边界更清楚，但增加 L5 单节点资源消耗、容量管理、备份和升级工作。

**选择：方案 A。** 目前 L5 是单节点验收环境，现有 PostgreSQL 已有持久卷；独立数据库和凭据提供足够的数据及权限边界。

## 架构与配置

- PostgreSQL 服务继续使用集群内 `postgresql:5432`；Keycloak 使用独立数据库名 `keycloak` 与专用数据库用户名。密码只存在 Secret，不写入 Git、ConfigMap、命令输出或日志。
- Keycloak Deployment 保持单副本、当前镜像 `quay.io/keycloak/keycloak:25.0.6`、Service/NodePort、健康探针和当前 `KC_HOSTNAME`。改用 `KC_DB=postgres`、数据库 URL/凭据以及 `start` 模式；不再在每次启动时依赖 ConfigMap realm 导入。
- 为 L5 增加独立且可审查的 Deployment 配置入口，避免把 L5 外部身份服务改造成 Helm 管理对象，也避免改变 Kind 仍使用的开发环境配置。
- Keycloak 到 PostgreSQL 的流量仅允许到同命名空间 PostgreSQL Service 的 TCP 5432。当前应用 Pod 到 Keycloak 的访问和浏览器 NodePort 规则保持不变。
- PostgreSQL 备份需覆盖 `agentteams` 与 `keycloak` 两个数据库；恢复演练先恢复数据库，再启动 Keycloak 与 AgentTeams 服务。

## 身份迁移与切换流程

1. 盘点当前 realm、用户、客户端、角色、已知 OIDC subject 以及 Control Plane 组织/租户/项目成员记录；确认导出内容和待保留身份范围。
2. 生成并保存受限访问的回滚备份；备份包含 Keycloak realm/用户数据，且不提交到仓库。备份、导入文件和临时 Secret 的权限按敏感数据处理。
3. 进入维护窗口，阻止身份配置变更并停止 Keycloak 写入。按 Keycloak 官方离线导出流程导出 realm 与用户，核对导出用户 ID 与当前 Admin API 用户 ID 集合一致。若不能得到一致、完整的离线导出，则停止切换并先解决数据导出路径。
4. 创建 Keycloak 专用数据库和账号，将导出文件导入 PostgreSQL 目标实例。导入前后比较 realm、用户 ID、角色映射、客户端配置与协议映射。
5. 更新 L5 Keycloak Secret 与 Deployment 配置，启动 PostgreSQL 模式 Keycloak。保持原 NodePort、hostname 和 issuer URL 不变。
6. 验证 Discovery issuer、JWK、Alice 和 MCP service user 的密码授权、realm 权限，以及 Control Plane 基于原 OIDC `sub` 的项目成员权限。将导入用户 ID 集合与切换前清单逐项比较；任何 UUID 不匹配或用户缺失都判失败，不通过重建用户或静默修改成员关系来掩盖。
7. 验证 PostgreSQL 重启后 Keycloak 能恢复原 realm 和用户，并重复 issuer、登录、授权检查。只有验证成功后，才结束维护窗口并保留备份作为恢复点。

JSON realm 导出不包含已登录会话等瞬态数据；切换后现有用户需要重新登录。数据库迁移成功的判据是业务身份主体与授权关联保留，不要求会话延续。

## 失败处理与回滚

- 在数据库、导出完整性、用户 UUID、角色映射或登录/API 验证任一环节失败时，停止后续切换。
- 保留原 Keycloak 配置、离线导出、切换前 subject 清单和新 PostgreSQL 数据库，不删除或覆盖证据。
- 回滚时停止新 Keycloak，恢复切换前 Deployment 配置。若离线导出失败或 UUID 集合不一致，不得切换；若 PostgreSQL 模式启动或运行验收失败，以已验证的离线导出导回 `keycloak` 数据库后再启动服务。由于当前 H2 未挂载持久卷，不能把切回 `start-dev` 当作可靠的数据恢复路径；保留导出文件作为数据回滚基线，不重新创建不同 UUID 的用户。
- `agentteams` 数据库中的 membership 不因该切换而更新。禁止在迁移脚本中将成员批量改为 ADMIN 或自动改写 `subject`。

## 验收

- PostgreSQL PVC 为 Bound，`keycloak` 数据库只由专用 Keycloak 凭据访问；Deployment 中不再出现 `start-dev`，Pod 重启后保持 Ready。
- `keycloak` 数据库与 `agentteams` 数据库分别可备份、可恢复；Keycloak Pod 重建后用户与 realm 仍存在。
- 用户 UUID 清单切换前后完全一致；客户端 redirect URI、web origins、realm roles 与用户 realm roles 映射一致。
- OIDC discovery 返回 `http://192.168.1.13:30082/realms/agentteams`；JWK、Alice 和 MCP service user 登录成功；现有 API 权限行为和项目成员关系不变。
- 执行一次 PostgreSQL StatefulSet 重启和一次 Keycloak Pod 重建，重复用户、issuer 和 API 权限检查。
- `git diff --check`、相关配置契约检查通过；保留 L5 rollout、PVC、subject 集合对比和验证结果作为验收记录。

## 约束与资料

- 不提交数据库密码、realm 用户导出或包含凭据的配置。
- 不升级 Keycloak 版本、不变更 OIDC issuer/客户端 ID/NodePort、不改变应用 membership。
- Keycloak 官方 PostgreSQL 配置与离线 realm 导出说明：
  - https://www.keycloak.org/server/db
  - https://www.keycloak.org/server/importExport
- 生产模式的启动行为与安全默认值：
  - https://www.keycloak.org/server/configuration
