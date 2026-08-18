#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || "$1" != "--confirm" ]]; then
  echo "用法：$0 --confirm <backup-directory>" >&2
  exit 2
fi

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
BACKUP_DIR=$(cd "$2" && pwd)
MC_BIN=${MC_BIN:-mc}

command -v kubectl >/dev/null || { echo "缺少 kubectl。" >&2; exit 1; }
kubectl get namespace "$NAMESPACE" >/dev/null
[[ -f "$BACKUP_DIR/SHA256SUMS" ]] || { echo "缺少 SHA256SUMS。" >&2; exit 1; }
(cd "$BACKUP_DIR" && shasum -a 256 -c SHA256SUMS)
PG_DUMP=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'agentteams-*.dump' -print -quit)
[[ -n "$PG_DUMP" ]] || { echo "找不到 PostgreSQL dump。" >&2; exit 1; }

echo "警告：恢复会覆盖当前 agentteams 数据库。"
kubectl -n "$NAMESPACE" exec -i statefulset/postgresql -- sh -c \
  'pg_restore --clean --if-exists --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$PG_DUMP"

OBJECT_DIR=$(find "$BACKUP_DIR" -maxdepth 1 -type d -name 'minio-*' -print -quit || true)
if [[ -n "$OBJECT_DIR" ]]; then
  command -v "$MC_BIN" >/dev/null || { echo "缺少 mc，无法恢复对象存储。" >&2; exit 1; }
  MINIO_ACCESS_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.access-key}' | base64 --decode)
  MINIO_SECRET_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.secret-key}' | base64 --decode)
  kubectl -n "$NAMESPACE" port-forward service/minio 19000:9000 >/tmp/agentteams-minio-port-forward.log 2>&1 &
  PORT_FORWARD_PID=$!
  trap 'kill "$PORT_FORWARD_PID" 2>/dev/null || true' EXIT
  until curl -fsS http://127.0.0.1:19000/minio/health/ready >/dev/null; do sleep 1; done
  "$MC_BIN" alias set agentteams-local http://127.0.0.1:19000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
  "$MC_BIN" mirror --overwrite "$OBJECT_DIR" agentteams-local/agentteams
fi
echo "恢复完成：$BACKUP_DIR"
