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

## Kind development cluster backup

For the local Kind cluster (installed by `deploy/install-kind-dev.sh`), use
the scripted backup that dumps PostgreSQL and mirrors the MinIO `agentteams`
bucket into `backups/` under the repository root:

```bash
# Requires kubectl and mc (MinIO client). Object mirroring needs a reachable
# MinIO, so run it while the Kind cluster is up.
./deploy/backup/backup-kind.sh

# Output layout: backups/agentteams-<stamp>.dump, backups/minio-<stamp>/,
# and backups/SHA256SUMS with SHA-256 checksums for every file.
```

Restoration requires an explicit confirmation and never deletes existing
backups:

```bash
./deploy/backup/restore-kind.sh --confirm
```

The Kind recovery CI also performs a non-destructive PostgreSQL validation:
it creates a custom-format dump, restores it into a temporary
`agentteams_restore` database, compares durable table signatures, and removes
the temporary database. The primary `agentteams` database is never overwritten
by this check.

Both scripts honor `AGENTTEAMS_NAMESPACE`, `AGENTTEAMS_BACKUP_DIR`, and
`MC_BIN` overrides. The Kind scripts back up the same logical content as the
production runbook above; production still uses the cluster-provided
PostgreSQL/MinIO endpoints and this file's pg_dump/mc instructions.
