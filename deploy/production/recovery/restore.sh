#!/usr/bin/env bash

# Orchestrate a production recovery using environment-owned, non-secret hooks.
# Every hook receives metadata through environment variables and its output is
# discarded so credentials supplied by the platform cannot enter the report.

set -Eeuo pipefail
umask 077

fail() {
  if [[ "${recovery_started:-false}" == true && -n "${RECOVERY_CLOSE_ENTRYPOINT_COMMAND:-}" ]]; then
    RECOVERY_PHASE=close-entrypoint "$RECOVERY_CLOSE_ENTRYPOINT_COMMAND" >/dev/null 2>&1 || true
  fi
  printf '%s\n' 'RECOVERY_RESTORE_FAIL' >&2
  exit 1
}

trap fail ERR

environment=''
backup_id=''
restore_point=''
endpoint=''
manifest_digest=''
metadata_file=''
object_inventory_file=''
execute=false
approval_id=''

while (($# > 0)); do
  case "$1" in
    --environment)
      (($# >= 2)) || fail
      environment="$2"
      shift 2
      ;;
    --backup-id)
      (($# >= 2)) || fail
      backup_id="$2"
      shift 2
      ;;
    --restore-point)
      (($# >= 2)) || fail
      restore_point="$2"
      shift 2
      ;;
    --endpoint)
      (($# >= 2)) || fail
      endpoint="$2"
      shift 2
      ;;
    --manifest-digest)
      (($# >= 2)) || fail
      manifest_digest="$2"
      shift 2
      ;;
    --metadata)
      (($# >= 2)) || fail
      metadata_file="$2"
      shift 2
      ;;
    --object-inventory)
      (($# >= 2)) || fail
      object_inventory_file="$2"
      shift 2
      ;;
    --approval-id)
      (($# >= 2)) || fail
      approval_id="$2"
      shift 2
      ;;
    --execute)
      execute=true
      shift
      ;;
    *)
      fail
      ;;
  esac
done

[[ "$environment" == production ]] || fail
[[ -n "$metadata_file" && -n "$object_inventory_file" && "$metadata_file" != "$object_inventory_file" ]] || fail
[[ "$approval_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || fail

if [[ "$execute" == true ]]; then
  for output_file in "$metadata_file" "$object_inventory_file"; do
    [[ ! -e "$output_file" && ! -L "$output_file" ]] || fail
    output_dir="$(dirname -- "$output_file")"
    [[ -d "$output_dir" && -w "$output_dir" ]] || fail
  done
else
  [[ -f "$metadata_file" && ! -L "$metadata_file" ]] || fail
  [[ -f "$object_inventory_file" && ! -L "$object_inventory_file" ]] || fail
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$script_dir/preflight.sh" \
  --environment "$environment" \
  --backup-id "$backup_id" \
  --restore-point "$restore_point" \
  --endpoint "$endpoint" \
  --manifest-digest "$manifest_digest" >/dev/null
if [[ "$execute" != true ]]; then
  python3 "$script_dir/consistency-check.py" --input "$metadata_file" \
    --object-inventory "$object_inventory_file" >/dev/null
fi

if [[ "$execute" != true ]]; then
  printf '%s\n' 'RECOVERY_PLAN_OK'
  exit 0
fi

[[ "${RECOVERY_APPROVAL_ID:-}" == "$approval_id" ]] || fail

hook_variable() {
  case "$1" in
    pause-entrypoint) printf '%s' RECOVERY_PAUSE_ENTRYPOINT_COMMAND ;;
    pause-scheduler) printf '%s' RECOVERY_PAUSE_SCHEDULER_COMMAND ;;
    restore-postgres) printf '%s' RECOVERY_RESTORE_POSTGRES_COMMAND ;;
    restore-object-storage) printf '%s' RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND ;;
    restore-nats) printf '%s' RECOVERY_RESTORE_NATS_COMMAND ;;
    flyway-validate) printf '%s' RECOVERY_FLYWAY_VALIDATE_COMMAND ;;
    consistency-check) printf '%s' RECOVERY_CONSISTENCY_CHECK_COMMAND ;;
    start-control-plane) printf '%s' RECOVERY_START_CONTROL_PLANE_COMMAND ;;
    start-gateway) printf '%s' RECOVERY_START_GATEWAY_COMMAND ;;
    start-operator) printf '%s' RECOVERY_START_OPERATOR_COMMAND ;;
    start-worker) printf '%s' RECOVERY_START_WORKER_COMMAND ;;
    replay-outbox) printf '%s' RECOVERY_REPLAY_OUTBOX_COMMAND ;;
    smoke) printf '%s' RECOVERY_SMOKE_COMMAND ;;
    open-entrypoint) printf '%s' RECOVERY_OPEN_ENTRYPOINT_COMMAND ;;
    *) fail ;;
  esac
}

required_hook_variables=(
  RECOVERY_PAUSE_ENTRYPOINT_COMMAND RECOVERY_PAUSE_SCHEDULER_COMMAND
  RECOVERY_RESTORE_POSTGRES_COMMAND RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND
  RECOVERY_RESTORE_NATS_COMMAND RECOVERY_FLYWAY_VALIDATE_COMMAND
  RECOVERY_CONSISTENCY_CHECK_COMMAND RECOVERY_START_CONTROL_PLANE_COMMAND
  RECOVERY_START_GATEWAY_COMMAND RECOVERY_START_OPERATOR_COMMAND
  RECOVERY_START_WORKER_COMMAND RECOVERY_REPLAY_OUTBOX_COMMAND
  RECOVERY_SMOKE_COMMAND RECOVERY_OPEN_ENTRYPOINT_COMMAND
)

for variable in "${required_hook_variables[@]}" RECOVERY_CLOSE_ENTRYPOINT_COMMAND; do
  hook="${!variable:-}"
  [[ -n "$hook" && -x "$hook" ]] || fail
done

run_hook() {
  local phase="$1"
  local variable="$(hook_variable "$phase")"
  local hook="${!variable}"
  if [[ "$phase" == consistency-check ]]; then
    RECOVERY_METADATA_FILE="$metadata_file" \
      RECOVERY_OBJECT_INVENTORY_FILE="$object_inventory_file" \
      RECOVERY_PHASE="$phase" \
      RECOVERY_APPROVAL_ID="$approval_id" \
      RECOVERY_BACKUP_ID="$backup_id" \
      RECOVERY_RESTORE_POINT="$restore_point" \
      RECOVERY_ENDPOINT="$endpoint" \
      RECOVERY_MANIFEST_DIGEST="$manifest_digest" \
      "$hook" >/dev/null 2>&1
    [[ -f "$metadata_file" && ! -L "$metadata_file" ]] || fail
    [[ -f "$object_inventory_file" && ! -L "$object_inventory_file" ]] || fail
    metadata_chmod_path="$metadata_file"
    inventory_chmod_path="$object_inventory_file"
    [[ "$metadata_chmod_path" != -* ]] || metadata_chmod_path="./$metadata_chmod_path"
    [[ "$inventory_chmod_path" != -* ]] || inventory_chmod_path="./$inventory_chmod_path"
    chmod 600 "$metadata_chmod_path" "$inventory_chmod_path"
    python3 "$script_dir/consistency-check.py" --input "$metadata_file" \
      --object-inventory "$object_inventory_file" >/dev/null
  else
    RECOVERY_PHASE="$phase" \
      RECOVERY_APPROVAL_ID="$approval_id" \
      RECOVERY_BACKUP_ID="$backup_id" \
      RECOVERY_RESTORE_POINT="$restore_point" \
      RECOVERY_ENDPOINT="$endpoint" \
      RECOVERY_MANIFEST_DIGEST="$manifest_digest" \
      "$hook" >/dev/null 2>&1
  fi
}

recovery_started=true
for phase in \
  pause-entrypoint pause-scheduler restore-postgres restore-object-storage \
  restore-nats flyway-validate consistency-check start-control-plane \
  start-gateway start-operator start-worker replay-outbox smoke; do
  run_hook "$phase"
done

run_hook open-entrypoint
recovery_started=false
printf '%s\n' 'RECOVERY_RESTORE_OK'
