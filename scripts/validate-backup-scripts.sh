#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BACKUP="$ROOT/deploy/backup/backup-kind.sh"
RESTORE="$ROOT/deploy/backup/restore-kind.sh"

[[ -f "$BACKUP" ]] || { echo "BACKUP_SCRIPTS_FAIL: backup script missing" >&2; exit 1; }
[[ -f "$RESTORE" ]] || { echo "BACKUP_SCRIPTS_FAIL: restore script missing" >&2; exit 1; }
grep -q -- '--format=custom' "$BACKUP" || { echo "BACKUP_SCRIPTS_FAIL: custom pg_dump missing" >&2; exit 1; }
grep -q -- 'shasum -a 256' "$BACKUP" || { echo "BACKUP_SCRIPTS_FAIL: checksum missing" >&2; exit 1; }
grep -q -- '--confirm' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: restore confirmation missing" >&2; exit 1; }
grep -q -- '--maintenance-confirmed' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: restore maintenance confirmation missing" >&2; exit 1; }
grep -q -- 'get workers.agentteams.io' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: Worker maintenance gate missing" >&2; exit 1; }
grep -q -- 'get pods -l app.kubernetes.io/name=agentteams-worker' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: Worker Pod drain gate missing" >&2; exit 1; }
grep -q -- 'get deployments' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: application Deployment maintenance gate missing" >&2; exit 1; }
grep -q -- 'APP_PODS_JSON=' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: application Pod drain gate missing" >&2; exit 1; }
grep -qE -- 'RECOVERY_DIR=\$\(cd "\$2" && pwd -P\)' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: explicit recovery point directory missing" >&2; exit 1; }
grep -q -- 'RECOVERY_POINT' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: recovery point manifest validation missing" >&2; exit 1; }
grep -q -- 'RECOVERY_COMPLETE' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: recovery completion marker missing" >&2; exit 1; }
grep -q -- 'object-inventory.py' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: object inventory verification missing" >&2; exit 1; }
grep -q -- 'OBJECT_INVENTORY.json' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: sealed object inventory missing" >&2; exit 1; }
grep -qE -- 'restore-kind.sh --confirm backups/recovery-[^[:space:]]+ --maintenance-confirmed' "$ROOT/deploy/backup/README.md" || { echo "BACKUP_SCRIPTS_FAIL: restore example missing explicit recovery point and maintenance confirmation" >&2; exit 1; }
grep -q -- 'pg_restore' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: pg_restore missing" >&2; exit 1; }
grep -q -- 'mirror --overwrite agentteams-local/agentteams' "$BACKUP" || { echo "BACKUP_SCRIPTS_FAIL: MinIO backup missing" >&2; exit 1; }
bash -n "$BACKUP" "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: shell syntax invalid" >&2; exit 1; }
python3 "$ROOT/scripts/test_kind_backup_restore_pairing.py"
echo "BACKUP_SCRIPTS_OK"
