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
grep -qE -- 'BACKUP_DIR=\$\(cd "\$2" && pwd\)' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: restore directory argument missing" >&2; exit 1; }
grep -qE -- 'restore-kind.sh --confirm backups([[:space:]]|$)' "$ROOT/deploy/backup/README.md" || { echo "BACKUP_SCRIPTS_FAIL: restore example missing directory" >&2; exit 1; }
grep -q -- 'pg_restore' "$RESTORE" || { echo "BACKUP_SCRIPTS_FAIL: pg_restore missing" >&2; exit 1; }
grep -q -- 'mc ' "$BACKUP" || { echo "BACKUP_SCRIPTS_FAIL: MinIO backup missing" >&2; exit 1; }
echo "BACKUP_SCRIPTS_OK"
