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
