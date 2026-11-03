# Backup and restore runbook

PostgreSQL is the source of truth for tasks, attempts, leases, Outbox, Team,
configuration, artifact metadata, Matrix Inbox and scheduler leases. Back it
up with a consistent dump and validate restoration before promoting it:

```bash
pg_dump --format=custom --no-owner --file=agentteams-$(date +%Y%m%d%H%M).dump "$SPRING_DATASOURCE_URL"
createdb agentteams-restore
pg_restore --no-owner --dbname=agentteams-restore agentteams-*.dump
```

Object content is stored in the configured S3-compatible bucket. Enable bucket
versioning/retention in the storage provider and record the bucket version or
snapshot identifier together with the PostgreSQL dump. After restore, verify
that every `artifacts.storage_key` and `config_files.storage_key` referenced by
the database exists and that its SHA-256 matches metadata before allowing new
configuration activation.

Recovery order is: PostgreSQL, NATS JetStream, object storage, Control Plane,
Gateway, then Operator/Workers. Pending Outbox rows and unexpired leases are
reconciled by the Control Plane after restart; Matrix delivery is replayable
from its Inbox/Outbox tables.

## Production RPO/RTO contract

The production baseline is an operator-owned PostgreSQL backup plus
versioned/retained object storage. Set the organization's targets explicitly;
the recommended starting point is RPO <= 15 minutes and RTO <= 60 minutes for
the Control Plane, with NATS rebuilt from the durable PostgreSQL Outbox rather
than treated as the source of truth. Record the PostgreSQL dump and object
storage snapshot/version as one recovery point, validate both before traffic
returns, and keep the previous application release available for rollback.

## Kind development cluster backup

For the local Kind cluster (installed by `deploy/install-kind-dev.sh`), use
the scripted backup that dumps PostgreSQL and mirrors the MinIO `agentteams`
bucket into `backups/` under the repository root:

```bash
# Requires kubectl and mc (MinIO client). Object mirroring needs a reachable
# MinIO, so run it while the Kind cluster is up.
./deploy/backup/backup-kind.sh

# Output layout: backups/recovery-<UTC stamp>/{agentteams.dump,minio/,
# OBJECT_INVENTORY.json,RECOVERY_POINT,SHA256SUMS,RECOVERY_COMPLETE}.
```

Both scripts require the active kubectl context to match the local Kind
context `kind-agentteams`, and verify that its API server uses a loopback
address. Every Kubernetes operation is then pinned to that context. If the
local cluster was created with another Kind context name, set
`AGENTTEAMS_KIND_CONTEXT` to that `kind-*` context and switch kubectl to it
before running backup or restore.

Restoration requires an explicit confirmation and never deletes existing
backups:

```bash
./deploy/backup/restore-kind.sh --confirm backups/recovery-20261015T120000Z
```

将示例中的目录替换为 `backup-kind.sh` 实际输出的单个 `recovery-<UTC 时间戳>` 目录；不能把整个 `backups/` 集合目录传给恢复脚本。恢复脚本只读取该目录中的固定文件名 `agentteams.dump`、`minio/`、`OBJECT_INVENTORY.json`、`RECOVERY_POINT`、`SHA256SUMS` 和 `RECOVERY_COMPLETE`，并在任何数据库写入前验证完整标记和校验和。`OBJECT_INVENTORY.json` 列出镜像中每个对象的相对路径、大小与 SHA-256，并包含在 bundle 校验和中。

`RECOVERY_POINT` 记录 PostgreSQL 导出和 MinIO 镜像各自的开始/结束时间。当前 Kind 脚本按顺序读取在线数据库和对象桶，没有暂停写入或使用共同快照 API，因此会把 `cross_store_consistency` 标为 `UNVERIFIED`；文件属于同一脚本运行并不代表跨存储时间点一致。恢复会覆盖数据库和 MinIO 桶，完成后仍须在隔离目标运行恢复后一致性检查。

恢复脚本会在 PostgreSQL 写入前向目标 MinIO 桶写入并删除一个唯一权限探针对象，以验证恢复身份具备 `mirror --remove` 所需的写入和删除权限。探针失败时数据库不会开始恢复；若清理遇到错误，脚本会报告探针对象路径。

生产恢复 hook 可将该恢复点的 `OBJECT_INVENTORY.json` 作为源对象基线传给 `deploy/production/recovery/export-object-references.py`；它会验证数据库引用在源镜像中的路径/大小和已有摘要，再供恢复后的 inventory 比对。源 inventory 改善了对象校验证据，但不会把在线 PostgreSQL dump 和 MinIO mirror 变成同一时间点快照，也不改变 `cross_store_consistency: UNVERIFIED`。

执行恢复前，先让外部 Ingress、DNS、网关或其它入口进入维护状态。Scheduler 与 Outbox Relay 在 Control Plane 进程内运行；Worker 由 Operator 根据自定义资源创建，因此需要同时缩容写入者 Deployment 和 Worker 自定义资源，并等待 Worker Pod 退出。示例：

```bash
kubectl -n agentteams scale deployment \
  -l 'app.kubernetes.io/name in (agentteams-control-plane,agentteams-gateway,agentteams-operator,agentteams-manager)' \
  --replicas=0
kubectl -n agentteams get workers.agentteams.io -o name \
  | while IFS= read -r worker; do
      [[ -n "$worker" ]] && kubectl -n agentteams patch "$worker" \
        --type=merge -p '{"spec":{"replicas":0}}'
    done
kubectl -n agentteams get pods \
  -l 'app.kubernetes.io/name in (agentteams-control-plane,agentteams-gateway,agentteams-operator,agentteams-manager)' -w
kubectl -n agentteams get pods -l app.kubernetes.io/name=agentteams-worker -w
```

确认外部入口已经阻断、Control Plane/Gateway/Operator/可选 Manager Deployment 副本为 0，所有应用写入 Pod（含终止中的 Pod）与 Worker Pod 均已退出，且 Worker CR 均为 0 后，再执行：

```bash
./deploy/backup/restore-kind.sh --confirm backups/recovery-20261015T120000Z --maintenance-confirmed
```

`--maintenance-confirmed` 是操作者对外部入口已进入维护状态的明确确认；脚本无法通用检查外部 Ingress、DNS 或网关。脚本还会独立查询 Kubernetes，验证必需 Deployment 存在且期望/实际副本均为 0、应用写入 Pod 列表为空、所有 Worker CR 显式设置 `spec.replicas: 0`、Worker Pod 列表为空。应用 Pod 检查会等待终止中的 Pod 完全退出。查询失败、状态不完整或仍有工作负载时，会在访问 MinIO 和 PostgreSQL 前拒绝恢复。对象恢复使用 `mc mirror --remove`，会删除目标桶中不在恢复点内的对象。

恢复脚本不会自动恢复应用副本。即使恢复成功，也要先在隔离目标完成数据库/对象引用一致性核验，再依据 Helm 配置恢复应用副本、Worker CR 与外部入口；脚本失败时也保持维护窗口，避免把部分恢复状态重新暴露给写入流量。

The Kind recovery CI also performs a non-destructive PostgreSQL validation:
it creates a custom-format dump, restores it into a temporary
`agentteams_restore` database, compares durable table signatures, and removes
the temporary database. The primary `agentteams` database is never overwritten
by this check.

The same recovery job validates MinIO object recovery using an isolated test
prefix: it mirrors the object to a temporary backup volume, deletes the live
object, restores it from the mirror, and verifies its SHA-256 checksum before
removing the test object and Pod.

The recovery job also scans every `artifacts.storage_key`,
`config_files.storage_key`, and `COMPLETED config_uploads.storage_key` reference
in PostgreSQL, downloads each referenced object, and compares its size and
SHA-256 checksum with the database metadata. `PENDING` and `DELETED`
`config_uploads` are intentionally excluded. The check deliberately deletes
one test object to verify that missing content fails, then restores and cleans
up the test data.

The backup script honors `AGENTTEAMS_NAMESPACE`, `AGENTTEAMS_BACKUP_DIR`, and
`MC_BIN`; the restore script honors `AGENTTEAMS_NAMESPACE` and `MC_BIN`, and
accepts the selected recovery-point directory as its positional argument. The
Kind scripts back up the same logical content as the production runbook above;
production still uses the cluster-provided PostgreSQL/MinIO endpoints and this
file's pg_dump/mc instructions.

存储密钥只通过标准输入导入权限为 `0700` 的临时 `mc` 配置目录，不进入 `mc` 的命令行参数，也不写入默认 `~/.mc` 配置。备份或恢复脚本退出时会删除临时配置目录。
