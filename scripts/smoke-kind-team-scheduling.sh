#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${KIND_NAMESPACE:-agentteams}"
CONTROL_PLANE_SERVICE="${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}"
LOCAL_PORT="${AGENTTEAMS_TEAM_SMOKE_LOCAL_PORT:-18081}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-120}"
AGENT_IDS_RAW="${AGENTTEAMS_TEAM_AGENT_IDS:-}"
TEAM_NAME="${AGENTTEAMS_TEAM_NAME:-team-scheduling-smoke-${RANDOM}-${SECONDS}}"
TEAM_ID=""
TEAM_APPLIED=0
PORT_FORWARD_PID=""
PORT_FORWARD_LOG="${TMPDIR:-/private/tmp}/agentteams-team-scheduling-smoke.log"

cleanup() {
  if [[ "${TEAM_APPLIED}" == "1" ]]; then
    kubectl -n "${NAMESPACE}" delete team "${TEAM_NAME}" --ignore-not-found >/dev/null 2>&1 || true
  fi
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  rm -f "${PORT_FORWARD_LOG}"
}
trap cleanup EXIT

for command_name in curl jq kubectl python3; do
  command -v "${command_name}" >/dev/null || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

if [[ -z "${AGENT_IDS_RAW}" ]]; then
  echo "AGENTTEAMS_TEAM_AGENT_IDS must contain two comma-separated READY Agent UUIDs" >&2
  exit 1
fi
IFS=',' read -r -a AGENT_IDS <<<"${AGENT_IDS_RAW}"
if [[ "${#AGENT_IDS[@]}" -lt 2 ]]; then
  echo "AGENTTEAMS_TEAM_AGENT_IDS must contain two comma-separated READY Agent UUIDs" >&2
  exit 1
fi
LEADER_ID="${AGENT_IDS[0]}"
WORKER_ID="${AGENT_IDS[1]}"
if [[ "${LEADER_ID}" == "${WORKER_ID}" ]]; then
  echo "Team smoke requires two distinct Agent UUIDs" >&2
  exit 1
fi

kubectl get crd teams.agentteams.io >/dev/null
kubectl -n "${NAMESPACE}" wait --for=condition=available \
  "deployment/${AGENTTEAMS_CONTROL_PLANE_DEPLOYMENT:-agentteams-agentteams-java-control-plane}" \
  --timeout="${TIMEOUT_SECONDS}s" >/dev/null

TEAM_ID="$(python3 - "${NAMESPACE}" "${TEAM_NAME}" <<'PY'
import hashlib
import sys
import uuid

raw = f"agentteams.io/v1alpha1/{sys.argv[1]}/{sys.argv[2]}".encode("utf-8")
digest = bytearray(hashlib.md5(raw).digest())
digest[6] = (digest[6] & 0x0F) | 0x30
digest[8] = (digest[8] & 0x3F) | 0x80
print(uuid.UUID(bytes=bytes(digest)))
PY
)"

kubectl apply -f - >/dev/null <<EOF
apiVersion: agentteams.io/v1alpha1
kind: Team
metadata:
  name: ${TEAM_NAME}
  namespace: ${NAMESPACE}
spec:
  leaderRef: ${LEADER_ID}
  workspaceRef: team-scheduling-smoke
  channelBindingRef: team-scheduling-smoke
  members:
    - agentRef: ${LEADER_ID}
      role: leader
      capabilities: [qwenpaw]
    - agentRef: ${WORKER_ID}
      role: worker
      capabilities: [qwenpaw]
  policy:
    maxConcurrentTasks: 1
    requireApproval: false
    allowedRuntimes: [qwenpaw]
    requiredCapabilities: [qwenpaw]
EOF
TEAM_APPLIED=1

kubectl -n "${NAMESPACE}" port-forward "service/${CONTROL_PLANE_SERVICE}" \
  "${LOCAL_PORT}:8080" >"${PORT_FORWARD_LOG}" 2>&1 &
PORT_FORWARD_PID=$!
BASE_URL="http://127.0.0.1:${LOCAL_PORT}"
deadline=$((SECONDS + TIMEOUT_SECONDS))
until curl --fail --silent "${BASE_URL}/actuator/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Control Plane API did not become ready" >&2
    exit 1
  fi
  sleep 2
done

for agent_id in "${LEADER_ID}" "${WORKER_ID}"; do
  agent_json="$(curl --fail --silent "${BASE_URL}/api/v1/agents/${agent_id}" 2>/dev/null || true)"
  if [[ -z "${agent_json}" ]] || [[ "$(jq -r '.phase' <<<"${agent_json}")" != "READY" ]]; then
    echo "Agent ${agent_id} must be READY before Team smoke" >&2
    exit 1
  fi
done

create_task() {
  local suffix="$1"
  local task_json
  task_json="$(curl --fail --silent -X POST "${BASE_URL}/api/v1/tasks" \
    -H "Idempotency-Key: team-smoke-create-${TEAM_NAME}-${suffix}" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"title\":\"team scheduling ${suffix}\",\"description\":\"Kind Team scheduling smoke\",\"spec\":{\"taskType\":\"qwenpaw\",\"teamId\":\"${TEAM_ID}\",\"requiredCapabilities\":[\"qwenpaw\"],\"inputJson\":{\"prompt\":\"Team scheduling smoke ${suffix}\"}}}")"
  local task_id
  task_id="$(jq -er '.id' <<<"${task_json}")"
  curl --fail --silent -X POST "${BASE_URL}/api/v1/tasks/${task_id}/queue" \
    -H "Idempotency-Key: team-smoke-queue-${TEAM_NAME}-${suffix}" \
    -H 'Content-Type: application/json' --data-binary '{}' >/dev/null
  printf '%s\n' "${task_id}"
}

TASK_ONE="$(create_task one)"
TASK_TWO="$(create_task two)"
TASK_THREE="$(create_task three)"

while :; do
  PHASE_ONE="$(curl --fail --silent "${BASE_URL}/api/v1/tasks/${TASK_ONE}" | jq -r '.phase')"
  PHASE_TWO="$(curl --fail --silent "${BASE_URL}/api/v1/tasks/${TASK_TWO}" | jq -r '.phase')"
  PHASE_THREE="$(curl --fail --silent "${BASE_URL}/api/v1/tasks/${TASK_THREE}" | jq -r '.phase')"
  if [[ ("${PHASE_ONE}" == "ASSIGNED" || "${PHASE_ONE}" == "RUNNING") \
      && "${PHASE_TWO}" == "QUEUED" && "${PHASE_THREE}" == "QUEUED" ]]; then
    echo "TEAM_SCHEDULING_OK team=${TEAM_ID} assigned=1 queued=2"
    exit 0
  fi
  if [[ "${PHASE_ONE}" == "FAILED" || "${PHASE_TWO}" == "FAILED" || "${PHASE_THREE}" == "FAILED" ]]; then
    echo "Team scheduling smoke observed FAILED task phases: ${PHASE_ONE},${PHASE_TWO},${PHASE_THREE}" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "Team scheduling smoke timed out: phases=${PHASE_ONE},${PHASE_TWO},${PHASE_THREE}" >&2
    exit 1
  fi
  sleep 0.25
done
