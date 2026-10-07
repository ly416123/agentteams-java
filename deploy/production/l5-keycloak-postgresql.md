# L5 Keycloak PostgreSQL 运维与恢复

L5 Keycloak 已使用现有 PostgreSQL 中独立的 `keycloak` 数据库作为唯一持久化数据源。本文说明当前部署检查、数据库恢复与持久性验收。历史 H2 迁移已于 2026-10-07 完成；本手册不把在线 H2 文件复制流程作为可重复或具备事务一致性保证的迁移方法。若未来需要从其他 H2 实例迁移，必须先建立并验证能停止全部写入、取得一致源快照的专项流程，再执行任何切换。

> Keycloak realm 导出包含用户凭据数据，按敏感备份管理。仅在 L5 主机操作，临时目录权限设为 `0700`，导出文件权限设为 `0600`，迁移完成后按组织备份策略保留或安全删除。不要提交导出文件、Token 或 Secret 内容。

## 前置检查

在 L5 主机 `ly@192.168.1.13` 上执行：

```bash
set -euo pipefail
NS=agentteams
KC_POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=keycloak \
  -o jsonpath='{.items[0].metadata.name}')
test -n "$KC_POD"
kubectl -n "$NS" get deploy/keycloak statefulset/postgresql pvc/data-postgresql-0
kubectl -n "$NS" rollout status deploy/keycloak --timeout=180s
kubectl -n "$NS" rollout status statefulset/postgresql --timeout=180s
kubectl -n "$NS" get pvc/data-postgresql-0 -o jsonpath='{.status.phase}{"\n"}'
kubectl -n "$NS" get svc/keycloak -o jsonpath='{.spec.ports[*].nodePort}{"\n"}'
kubectl -n "$NS" get deploy/keycloak -o jsonpath='{.spec.template.spec.containers[0].args}{"\n"}'
```

PVC 必须为 `Bound`，Keycloak 与 PostgreSQL 都必须 Ready，NodePort 必须仍是 `30082 30083`。记录当前 Pod、镜像、args、realm issuer、管理员 Secret 名称和已知用户数量。不得在输出或执行记录里保存数据库密码、管理员密码或 access token。

## 记录用户 subject 基线

通过当前管理员授权获取 realm 用户列表，使用分页读取全部用户，只把 `id` 集合保存在权限为 `0600` 的临时文件中。Admin REST API 需使用分页 `first`/`max` 参数，直到返回空列表；严禁只读第一页。`id` 就是 JWT `sub`。

同时从 Control Plane PostgreSQL 记录 `organization_memberships`、`tenant_memberships` 和 `project_memberships` 中当前 subject 集合。使用 `kubectl exec` 进入 PostgreSQL Pod 执行只读查询；不得把查询结果中的用户个人资料或密码材料写入仓库。基线至少记录 Alice 与 `agentteams-mcp-task-bot` 的 subject。

## 当前 PostgreSQL 部署检查

确认 Keycloak 已连接 PostgreSQL，PVC 仍 Bound，NodePort 和 issuer 保持既有值。身份数据的导出、备份和恢复应以 PostgreSQL 为源。创建仅当前操作用户可读写的目录，并保存 `pg_dump --format=custom --no-owner` 文件及 SHA-256：

   ```bash
   umask 077
   install -d -m 700 /home/ly/agentteams-backups
   kubectl -n agentteams exec statefulset/postgresql -- sh -ec \
     'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d keycloak -Fc --no-owner' \
     > /home/ly/agentteams-backups/keycloak-postgresql-$(date +%F).dump
   sha256sum /home/ly/agentteams-backups/keycloak-postgresql-$(date +%F).dump \
     > /home/ly/agentteams-backups/keycloak-postgresql-$(date +%F).dump.sha256
   chmod 600 /home/ly/agentteams-backups/keycloak-postgresql-$(date +%F).dump*
   ```

备份需在低写入窗口进行，并通过 `pg_restore --list` 检查归档目录。恢复前停止 Keycloak 写入，恢复到空的目标数据库，再启动 Keycloak 并核对 realm、用户 UUID、客户端和角色。

## 数据库引导与恢复

`bootstrap-l5-keycloak-db.sh` 是首次配置数据库时使用的幂等引导脚本。它创建或复用 Kubernetes Secret `keycloak-database`，以数据库管理员在现有 PostgreSQL 实例中创建独立 `keycloak` 数据库和专用账号。脚本重跑不会重置现有密码，并会拒绝不符合安全字符集的密码。

```bash
chmod 700 deploy/production/bootstrap-l5-keycloak-db.sh
./deploy/production/bootstrap-l5-keycloak-db.sh
```

恢复时在 PostgreSQL Pod 中使用 `pg_restore` 将归档写入空的 `keycloak` 数据库；不要在 Keycloak CLI 容器中运行数据库恢复命令。恢复后先检查数据库和 PostgreSQL 日志，再启动 Keycloak，并用 Admin API 对账 realm 用户 ID、角色与客户端配置。常规 realm 维护应使用 PostgreSQL 备份恢复，不从文件系统 H2 副本导出。

密码只通过 Kubernetes Secret 提供，不放进命令参数或命令历史。执行 `pg_restore --list <backup.dump>` 检查备份清单，再将其恢复到空的目标数据库。

## 切换 Keycloak

日常部署变更前预览差异：

```bash
kubectl diff -f deploy/production/l5-keycloak-postgresql.yaml
```

只接受 Keycloak Deployment、Service 和 `keycloak-postgresql-egress` NetworkPolicy 的预期变更。NodePort、hostname、镜像、资源边界和探针必须保持不变。应用后等待：

```bash
kubectl apply -f deploy/production/l5-keycloak-postgresql.yaml
kubectl -n agentteams rollout status deploy/keycloak --timeout=300s
kubectl -n agentteams get pod -l app.kubernetes.io/name=keycloak -o wide
```

部署后确认已有用户可重新登录；数据库恢复不会保留活动会话。

## 验收

- Pod 1/1 Ready，启动参数为 `start`，`KC_DB=postgres`，日志无数据库认证或 migration 错误。
- Discovery issuer 是 `http://192.168.1.13:30082/realms/agentteams`，JWK endpoint 可由 Control Plane Pod 访问。
- 用 Alice 和 `agentteams-mcp-task-bot` 凭据获取 token；检查 `iss`、`sub`、tenant/project/team、permissions 与切换前基线。access token 不得写入终端记录或日志。
- Alice 与 MCP service user 对既有项目接口的访问结果符合切换前预期；Control Plane 中 membership 行数与 subject 不因迁移改变。
- Keycloak Pod 重建一次、PostgreSQL StatefulSet 滚动重启一次；二者恢复后重复 Ready、用户 UUID、discovery、登录与项目 API 检查。
- 记录用户 ID 集合相等的结论、NodePort、PVC Bound、rollout 和 HTTP 状态；不要在报告中列出密码、token 或导出 JSON。

## 故障恢复

Keycloak 不可用时，先保留现场并从日志判定是否为数据库连接或配置问题。不要回滚到空 H2；从已验证的 PostgreSQL 恢复点恢复 realm 数据。若只是 Deployment 配置错误，修正 PostgreSQL 连接配置后重启服务。

恢复后核对用户 UUID、角色与客户端配置。始终保持 OIDC issuer 和应用 membership 不变；任何需要修改 membership subject 的操作都属于单独的数据迁移，不在本流程内。

## PostgreSQL 备份与恢复

现有 `deploy/backup/backup-kind.sh` 是 Kind 开发集群专用，默认只备份 `agentteams` 数据库。本次 L5 Keycloak 使用独立数据库，因此在 L5 备份流程中须为两个数据库分别执行 `pg_dump --format=custom --no-owner`，并记录 SHA-256。恢复先创建目标数据库并 `pg_restore`，验证数据库后再启动 Keycloak 和 AgentTeams Deployment。备份需存放在受限目录，不能放在 Git 工作区或共享公开文件夹。

本次切换产生的 Keycloak PostgreSQL 恢复点保存在 L5 主机 `/home/ly/agentteams-backups/keycloak-postgresql-20261007.dump`，目录权限为 `0700`、文件权限为 `0600`。保留 PostgreSQL dump 及校验文件，并按后续环境备份策略纳入定期备份。

## 官方文档

- [Keycloak 数据库配置](https://www.keycloak.org/server/db)
- [Keycloak realm 导入和导出](https://www.keycloak.org/server/importExport)
- [Keycloak 配置和生产启动](https://www.keycloak.org/server/configuration)
