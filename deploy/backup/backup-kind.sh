#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
BACKUP_DIR=${AGENTTEAMS_BACKUP_DIR:-"$ROOT/backups"}
STAMP=$(date +%Y%m%d%H%M%S)
PG_DUMP="$BACKUP_DIR/agentteams-$STAMP.dump"
OBJECT_DIR="$BACKUP_DIR/minio-$STAMP"
CHECKSUMS="$BACKUP_DIR/SHA256SUMS"
MC_BIN=${MC_BIN:-mc}

command -v kubectl >/dev/null || { echo "缺少 kubectl。" >&2; exit 1; }
kubectl get namespace "$NAMESPACE" >/dev/null
kubectl -n "$NAMESPACE" get statefulset/postgresql statefulset/minio >/dev/null
mkdir -p "$BACKUP_DIR"
[[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" ]] || { echo "备份目录必须是非符号链接目录。" >&2; exit 1; }

echo "导出 PostgreSQL：$PG_DUMP"
kubectl -n "$NAMESPACE" exec statefulset/postgresql -- sh -c \
  'pg_dump --format=custom --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$PG_DUMP"

if ! command -v "$MC_BIN" >/dev/null; then
  echo "缺少 mc；数据库已备份，但对象存储未备份。请安装 mc 或设置 MC_BIN 后重试。" >&2
  rm -f "$PG_DUMP"
  exit 1
fi

MINIO_ACCESS_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.access-key}' | base64 --decode)
MINIO_SECRET_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.secret-key}' | base64 --decode)
kubectl -n "$NAMESPACE" port-forward service/minio 19000:9000 >/tmp/agentteams-minio-port-forward.log 2>&1 &
PORT_FORWARD_PID=$!
trap 'kill "$PORT_FORWARD_PID" 2>/dev/null || true' EXIT
until curl -fsS http://127.0.0.1:19000/minio/health/ready >/dev/null; do sleep 1; done
mkdir -p "$OBJECT_DIR"
"$MC_BIN" alias set agentteams-local http://127.0.0.1:19000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
"$MC_BIN" mirror --overwrite agentteams-local/agentteams "$OBJECT_DIR"

(
  cd "$BACKUP_DIR"
  shasum -a 256 "$(basename "$PG_DUMP")" > "$CHECKSUMS"
  if find "$(basename "$OBJECT_DIR")" -type f -print -quit | grep -q .; then
    find "$(basename "$OBJECT_DIR")" -type f -print0 | xargs -0 shasum -a 256 >> "$CHECKSUMS"
  fi
)
echo "备份完成：$BACKUP_DIR"
