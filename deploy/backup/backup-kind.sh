#!/usr/bin/env bash
set -euo pipefail
umask 077

NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
BACKUP_DIR=${AGENTTEAMS_BACKUP_DIR:-"$(cd "$(dirname "$0")/../.." && pwd)/backups"}
MC_BIN=${MC_BIN:-mc}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RECOVERY_POINT_ID=$STAMP
RECOVERY_DIR="$BACKUP_DIR/recovery-$RECOVERY_POINT_ID"
PG_DUMP="$RECOVERY_DIR/agentteams.dump"
OBJECT_DIR="$RECOVERY_DIR/minio"
OBJECT_INVENTORY="$RECOVERY_DIR/OBJECT_INVENTORY.json"
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

source "$SCRIPT_DIR/require-kind-context.sh"
command -v "$MC_BIN" >/dev/null || { echo "缺少 mc；为避免生成不完整恢复点，请先安装 mc 或设置 MC_BIN。" >&2; exit 1; }
kubectl get namespace "$NAMESPACE" >/dev/null
kubectl -n "$NAMESPACE" get statefulset/postgresql statefulset/minio >/dev/null
mkdir -p "$BACKUP_DIR"
[[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" ]] || { echo "备份目录必须是非符号链接目录。" >&2; exit 1; }
mkdir "$RECOVERY_DIR"
mkdir "$OBJECT_DIR"

BACKUP_STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PG_DUMP_STARTED_AT=$BACKUP_STARTED_AT
echo "导出 PostgreSQL 到恢复点 ${RECOVERY_POINT_ID}。"
kubectl -n "$NAMESPACE" exec statefulset/postgresql -- sh -c \
  'pg_dump --format=custom --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$PG_DUMP"
PG_DUMP_COMPLETED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

MINIO_MIRROR_STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
MINIO_ACCESS_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.access-key}' | python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.buffer.read()))')
MINIO_SECRET_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.secret-key}' | python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.buffer.read()))')
MC_CONFIG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agentteams-backup-mc.XXXXXX")
PORT_FORWARD_LOG=$(mktemp "${TMPDIR:-/tmp}/agentteams-minio-port-forward.XXXXXX")
MINIO_LOCAL_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
PORT_FORWARD_PID=""
cleanup() {
  if [[ -n "$PORT_FORWARD_PID" ]]; then
    kill "$PORT_FORWARD_PID" 2>/dev/null || true
  fi
  rm -rf "$MC_CONFIG_DIR"
  rm -f "$PORT_FORWARD_LOG"
}
trap cleanup EXIT
chmod 700 "$MC_CONFIG_DIR"
kubectl -n "$NAMESPACE" port-forward service/minio "$MINIO_LOCAL_PORT:9000" >"$PORT_FORWARD_LOG" 2>&1 &
PORT_FORWARD_PID=$!
READY=false
for attempt in $(seq 1 60); do
  if ! kill -0 "$PORT_FORWARD_PID" 2>/dev/null; then
    break
  fi
  if grep -Fq "Forwarding from 127.0.0.1:${MINIO_LOCAL_PORT} -> 9000" "$PORT_FORWARD_LOG"; then
    if curl -fsS "http://127.0.0.1:${MINIO_LOCAL_PORT}/minio/health/ready" >/dev/null; then
      READY=true
      break
    fi
  fi
  sleep 1
done
[[ "$READY" == true ]] || { echo "MinIO port-forward 未就绪；保留未完成目录但不生成恢复完成标记。" >&2; exit 1; }
export MINIO_ACCESS_KEY MINIO_SECRET_KEY MINIO_LOCAL_PORT
python3 - <<'PY' | "$MC_BIN" --config-dir "$MC_CONFIG_DIR" alias import agentteams-local >/dev/null
import json
import os
import sys

json.dump({
    "url": f"http://127.0.0.1:{os.environ['MINIO_LOCAL_PORT']}",
    "accessKey": os.environ["MINIO_ACCESS_KEY"],
    "secretKey": os.environ["MINIO_SECRET_KEY"],
    "api": "s3v4",
    "path": "auto",
}, sys.stdout)
PY
unset MINIO_ACCESS_KEY MINIO_SECRET_KEY
"$MC_BIN" --config-dir "$MC_CONFIG_DIR" mirror --overwrite agentteams-local/agentteams "$OBJECT_DIR"
python3 "$SCRIPT_DIR/../production/recovery/object-inventory.py" \
  --root "$OBJECT_DIR" --output "$OBJECT_INVENTORY"
MINIO_MIRROR_COMPLETED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

python3 - "$RECOVERY_POINT_ID" "$BACKUP_STARTED_AT" "$PG_DUMP_STARTED_AT" \
  "$PG_DUMP_COMPLETED_AT" "$MINIO_MIRROR_STARTED_AT" "$MINIO_MIRROR_COMPLETED_AT" \
  > "$RECOVERY_DIR/RECOVERY_POINT" <<'PY'
import json
import sys

point_id, started, pg_started, pg_completed, minio_started, minio_completed = sys.argv[1:]
record = {
    "schema_version": 1,
    "recovery_point_id": point_id,
    "backup_started_at_utc": started,
    "postgres": {
        "path": "agentteams.dump",
        "started_at_utc": pg_started,
        "completed_at_utc": pg_completed,
    },
    "object_storage": {
        "path": "minio",
        "inventory_path": "OBJECT_INVENTORY.json",
        "started_at_utc": minio_started,
        "completed_at_utc": minio_completed,
    },
    "cross_store_consistency": "UNVERIFIED",
}
print(json.dumps(record, sort_keys=True, separators=(",", ":")))
PY

(
  cd "$RECOVERY_DIR"
  shasum -a 256 agentteams.dump RECOVERY_POINT OBJECT_INVENTORY.json > SHA256SUMS
  if find minio -type f -print -quit | grep -q .; then
    find minio -type f -print0 | xargs -0 shasum -a 256 >> SHA256SUMS
  fi
  printf '%s\n' "$RECOVERY_POINT_ID" > RECOVERY_COMPLETE
)
echo "恢复点已封存：${RECOVERY_DIR}（跨存储一致性仍未验证）。"
