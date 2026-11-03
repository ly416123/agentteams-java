#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 3 || "$1" != "--confirm" || "$3" != "--maintenance-confirmed" ]]; then
  echo "用法：$0 --confirm <recovery-point-directory> --maintenance-confirmed" >&2
  exit 2
fi

NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
MC_BIN=${MC_BIN:-mc}
[[ -d "$2" && ! -L "$2" ]] || { echo "恢复点必须是非符号链接目录。" >&2; exit 1; }
RECOVERY_DIR=$(cd "$2" && pwd -P)
RECOVERY_POINT="$RECOVERY_DIR/RECOVERY_POINT"
RECOVERY_COMPLETE="$RECOVERY_DIR/RECOVERY_COMPLETE"
CHECKSUMS="$RECOVERY_DIR/SHA256SUMS"
PG_DUMP="$RECOVERY_DIR/agentteams.dump"
OBJECT_DIR="$RECOVERY_DIR/minio"
OBJECT_INVENTORY="$RECOVERY_DIR/OBJECT_INVENTORY.json"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
INVENTORY_TOOL="$SCRIPT_DIR/../production/recovery/object-inventory.py"

source "$SCRIPT_DIR/require-kind-context.sh"
command -v "$MC_BIN" >/dev/null || { echo "缺少 mc，拒绝在 PostgreSQL 恢复后才发现无法恢复对象存储。" >&2; exit 1; }
[[ -f "$RECOVERY_POINT" && ! -L "$RECOVERY_POINT" ]] || { echo "缺少 RECOVERY_POINT 元数据。" >&2; exit 1; }
[[ -f "$RECOVERY_COMPLETE" && ! -L "$RECOVERY_COMPLETE" ]] || { echo "恢复点未完整封存。" >&2; exit 1; }
[[ -f "$CHECKSUMS" && ! -L "$CHECKSUMS" ]] || { echo "缺少 SHA256SUMS。" >&2; exit 1; }
[[ -f "$OBJECT_INVENTORY" && ! -L "$OBJECT_INVENTORY" ]] || { echo "缺少对象存储 inventory。" >&2; exit 1; }
[[ -f "$PG_DUMP" && ! -L "$PG_DUMP" ]] || { echo "恢复点缺少 agentteams.dump。" >&2; exit 1; }
[[ -d "$OBJECT_DIR" && ! -L "$OBJECT_DIR" ]] || { echo "恢复点缺少 minio 对象目录。" >&2; exit 1; }

RECOVERY_POINT_ID=$(python3 - "$RECOVERY_POINT" "$RECOVERY_DIR" "$RECOVERY_COMPLETE" <<'PY'
import json
import pathlib
import re
import sys
from datetime import datetime

manifest_path, directory, complete_path = map(pathlib.Path, sys.argv[1:])
try:
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    point_id = data["recovery_point_id"]
    timestamps = [
        data["backup_started_at_utc"],
        data["postgres"]["started_at_utc"],
        data["postgres"]["completed_at_utc"],
        data["object_storage"]["started_at_utc"],
        data["object_storage"]["completed_at_utc"],
    ]
    if not all(isinstance(value, str) and value.endswith("Z") for value in timestamps):
        raise ValueError("invalid timestamps")
    parsed_timestamps = [datetime.fromisoformat(value.replace("Z", "+00:00"))
                         for value in timestamps]
    valid = (
        data.get("schema_version") == 1
        and re.fullmatch(r"\d{8}T\d{6}Z", point_id) is not None
        and parsed_timestamps == sorted(parsed_timestamps)
        and directory.name == f"recovery-{point_id}"
        and data.get("postgres", {}).get("path") == "agentteams.dump"
        and data.get("object_storage", {}).get("path") == "minio"
        and data.get("object_storage", {}).get("inventory_path") == "OBJECT_INVENTORY.json"
        and data.get("cross_store_consistency") == "UNVERIFIED"
        and complete_path.read_text(encoding="utf-8") == f"{point_id}\n"
    )
except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
    valid = False
if not valid:
    raise SystemExit(1)
print(point_id)
PY
) || { echo "RECOVERY_POINT 元数据或完成标记无效。" >&2; exit 1; }

[[ -s "$PG_DUMP" ]] || { echo "PostgreSQL dump 为空。" >&2; exit 1; }
ROOT_FILE_CHECKSUMS=$(cd "$RECOVERY_DIR" && shasum -a 256 agentteams.dump RECOVERY_POINT OBJECT_INVENTORY.json)
while IFS= read -r expected_checksum; do
  checksum_matches=$(grep -Fxc -- "$expected_checksum" "$CHECKSUMS" || true)
  [[ "$checksum_matches" == 1 ]] || {
    echo "SHA256SUMS 缺少或重复关键恢复文件的校验项；恢复已拒绝。" >&2
    exit 1
  }
done <<< "$ROOT_FILE_CHECKSUMS"
(cd "$RECOVERY_DIR" && shasum -a 256 -c SHA256SUMS >/dev/null)
INVENTORY_CHECK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agentteams-restore-inventory.XXXXXX")
cleanup_inventory_check() { rm -rf "$INVENTORY_CHECK_DIR"; }
trap cleanup_inventory_check EXIT
python3 "$INVENTORY_TOOL" --root "$OBJECT_DIR" --output "$INVENTORY_CHECK_DIR/actual.json" >/dev/null || {
  echo "对象目录包含不受支持的条目或无法生成 inventory；恢复已拒绝。" >&2
  exit 1
}
if ! python3 - "$OBJECT_INVENTORY" "$INVENTORY_CHECK_DIR/actual.json" <<'PY'
import json
import pathlib
import sys

def read_inventory(path):
    def reject_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result
    with pathlib.Path(path).open(encoding="utf-8") as stream:
        value = json.load(stream, object_pairs_hook=reject_duplicates)
    if not isinstance(value, dict) or not isinstance(value.get("objects"), list):
        raise ValueError("invalid inventory shape")
    return value

try:
    recorded, actual = map(read_inventory, sys.argv[1:])
    if recorded != actual:
        raise ValueError("inventory mismatch")
except (OSError, ValueError, TypeError, json.JSONDecodeError):
    raise SystemExit(1)
PY
then
  echo "对象目录与已封存 inventory 不一致；恢复已拒绝。" >&2
  exit 1
fi
cleanup_inventory_check
trap - EXIT
echo "恢复点文件校验和验证通过。"

kubectl get namespace "$NAMESPACE" >/dev/null
DEPLOYMENTS_JSON=$(kubectl -n "$NAMESPACE" get deployments \
  -l 'app.kubernetes.io/name in (agentteams-control-plane,agentteams-gateway,agentteams-operator,agentteams-manager)' \
  -o json) || { echo "无法确认应用 Deployment 已暂停；恢复已拒绝。" >&2; exit 1; }
if ! printf '%s' "$DEPLOYMENTS_JSON" | python3 -c '
import json
import sys

required = {"agentteams-control-plane", "agentteams-gateway", "agentteams-operator"}
try:
    value = json.load(sys.stdin)
    items = value["items"]
    if not isinstance(items, list):
        raise ValueError("items must be a list")
    found = set()
    for item in items:
        name = item["metadata"]["labels"]["app.kubernetes.io/name"]
        if name not in required | {"agentteams-manager"}:
            continue
        found.add(name)
        desired = item["spec"].get("replicas")
        actual = item.get("status", {}).get("replicas", 0)
        if type(desired) is not int or desired != 0 or type(actual) is not int or actual != 0:
            raise ValueError(f"{name} is not fully scaled to zero")
    if not required.issubset(found):
        raise ValueError("required application Deployment is missing")
except (AttributeError, KeyError, TypeError, ValueError, json.JSONDecodeError):
    raise SystemExit(1)
'; then
  echo "Control Plane、Gateway、Operator 与可选 Manager 必须全部缩容至 0；恢复已拒绝。" >&2
  exit 1
fi

APP_PODS_JSON=$(kubectl -n "$NAMESPACE" get pods \
  -l 'app.kubernetes.io/name in (agentteams-control-plane,agentteams-gateway,agentteams-operator,agentteams-manager)' \
  -o json) || { echo "无法确认应用写入 Pod 已退出；恢复已拒绝。" >&2; exit 1; }
if ! printf '%s' "$APP_PODS_JSON" | python3 -c '
import json
import sys

try:
    items = json.load(sys.stdin)["items"]
    if not isinstance(items, list) or items:
        raise ValueError("application writer Pods remain")
except (KeyError, TypeError, ValueError, json.JSONDecodeError):
    raise SystemExit(1)
'; then
  echo "仍有 Control Plane、Gateway、Operator 或 Manager Pod（包括终止中的 Pod）；恢复已拒绝。" >&2
  exit 1
fi

WORKERS_JSON=$(kubectl -n "$NAMESPACE" get workers.agentteams.io -o json) || {
  echo "无法确认 Worker 自定义资源已暂停；恢复已拒绝。" >&2
  exit 1
}
if ! printf '%s' "$WORKERS_JSON" | python3 -c '
import json
import sys

try:
    items = json.load(sys.stdin)["items"]
    if not isinstance(items, list):
        raise ValueError("items must be a list")
    if any(type(item["spec"]["replicas"]) is not int or item["spec"]["replicas"] != 0
           for item in items):
        raise ValueError("Worker replicas must be zero")
except (KeyError, TypeError, ValueError, json.JSONDecodeError):
    raise SystemExit(1)
'; then
  echo "所有 Worker 自定义资源必须显式设置 replicas: 0；恢复已拒绝。" >&2
  exit 1
fi

WORKER_PODS_JSON=$(kubectl -n "$NAMESPACE" get pods -l app.kubernetes.io/name=agentteams-worker -o json) || {
  echo "无法确认 Worker Pod 已退出；恢复已拒绝。" >&2
  exit 1
}
if ! printf '%s' "$WORKER_PODS_JSON" | python3 -c '
import json
import sys

try:
    items = json.load(sys.stdin)["items"]
    if not isinstance(items, list) or items:
        raise ValueError("Worker Pods remain")
except (KeyError, TypeError, ValueError, json.JSONDecodeError):
    raise SystemExit(1)
'; then
  echo "仍有 Worker Pod 运行；恢复已拒绝。" >&2
  exit 1
fi
echo "维护门禁通过：应用写入者副本为 0，且 Worker Pod 已退出。"

kubectl -n "$NAMESPACE" get statefulset/postgresql statefulset/minio >/dev/null
MINIO_ACCESS_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.access-key}' | python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.buffer.read()))')
MINIO_SECRET_KEY=$(kubectl -n "$NAMESPACE" get secret agentteams-storage -o jsonpath='{.data.secret-key}' | python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.buffer.read()))')
MC_CONFIG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agentteams-restore-mc.XXXXXX")
PORT_FORWARD_LOG=$(mktemp "${TMPDIR:-/tmp}/agentteams-minio-port-forward.XXXXXX")
MINIO_LOCAL_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
chmod 700 "$MC_CONFIG_DIR"
kubectl -n "$NAMESPACE" port-forward service/minio "$MINIO_LOCAL_PORT:9000" >"$PORT_FORWARD_LOG" 2>&1 &
PORT_FORWARD_PID=$!
PREFLIGHT_OBJECT=""
cleanup() {
  if [[ -n "$PREFLIGHT_OBJECT" ]]; then
    if ! "$MC_BIN" --config-dir "$MC_CONFIG_DIR" rm "$PREFLIGHT_OBJECT" >/dev/null 2>&1; then
      echo "MinIO 权限探针对象未能自动清理：$PREFLIGHT_OBJECT" >&2
    fi
  fi
  kill "$PORT_FORWARD_PID" 2>/dev/null || true
  rm -rf "$MC_CONFIG_DIR"
  rm -f "$PORT_FORWARD_LOG"
}
trap cleanup EXIT
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
[[ "$READY" == true ]] || { echo "MinIO port-forward 未就绪；数据库尚未恢复。" >&2; exit 1; }
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
"$MC_BIN" --config-dir "$MC_CONFIG_DIR" ls agentteams-local/agentteams >/dev/null
PREFLIGHT_OBJECT="agentteams-local/agentteams/.agentteams-restore-preflight-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
if ! printf 'agentteams restore permission check\n' \
  | "$MC_BIN" --config-dir "$MC_CONFIG_DIR" pipe "$PREFLIGHT_OBJECT" >/dev/null; then
  echo "MinIO 写入权限检查失败；PostgreSQL 尚未恢复。" >&2
  exit 1
fi
if ! "$MC_BIN" --config-dir "$MC_CONFIG_DIR" rm "$PREFLIGHT_OBJECT" >/dev/null; then
  echo "MinIO 删除权限检查失败；PostgreSQL 尚未恢复。" >&2
  exit 1
fi
PREFLIGHT_OBJECT=""

echo "警告：恢复点 $RECOVERY_POINT_ID 的 PostgreSQL 与 MinIO 只记录了成对文件和校验和，跨存储一致性未验证。"
echo "警告：恢复会覆盖当前 agentteams 数据库和 MinIO agentteams bucket。"
kubectl -n "$NAMESPACE" exec -i statefulset/postgresql -- sh -c \
  'pg_restore --clean --if-exists --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$PG_DUMP"
"$MC_BIN" --config-dir "$MC_CONFIG_DIR" mirror --overwrite --remove "$OBJECT_DIR" agentteams-local/agentteams
echo "恢复完成：${RECOVERY_DIR}（须另行执行恢复后一致性校验）。"
