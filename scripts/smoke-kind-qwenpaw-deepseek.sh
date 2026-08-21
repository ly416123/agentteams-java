#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${KIND_NAMESPACE:-agentteams}"
CONTROL_PLANE_SERVICE="${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}"
WORKER_NAME="${AGENTTEAMS_WORKER_NAME:-qwenpaw-worker}"
LOCAL_PORT="${AGENTTEAMS_CONTROL_PLANE_LOCAL_PORT:-18080}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-180}"
SUCCESS_MARKER="QWENPAW_DEEPSEEK_SMOKE_OK"
PORT_FORWARD_LOG="${TMPDIR:-/private/tmp}/agentteams-control-plane-smoke.log"
PORT_FORWARD_PID=""

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  rm -f "${PORT_FORWARD_LOG}"
}
trap cleanup EXIT

for command_name in curl jq kubectl; do
  command -v "${command_name}" >/dev/null || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

kubectl -n "${NAMESPACE}" wait --for=condition=available \
  "deployment/${WORKER_NAME}" --timeout="${TIMEOUT_SECONDS}s" >/dev/null
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

IDEMPOTENCY_KEY="kind-qwenpaw-deepseek-create-${RANDOM}-${SECONDS}"
TASK_BODY='{"title":"kind-qwenpaw-deepseek","description":"DeepSeek QwenPaw end-to-end smoke","spec":{"taskType":"qwenpaw","inputJson":{"prompt":"Reply with exactly QWENPAW_DEEPSEEK_SMOKE_OK and nothing else."},"requiredCapabilities":["qwenpaw"]}}'
TASK_JSON="$(curl --fail --silent -X POST "${BASE_URL}/api/v1/tasks" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" -H 'Content-Type: application/json' \
  --data-binary "${TASK_BODY}")"
TASK_ID="$(jq -er '.id' <<<"${TASK_JSON}")"

curl --fail --silent -X POST "${BASE_URL}/api/v1/tasks/${TASK_ID}/queue" \
  -H "Idempotency-Key: kind-qwenpaw-deepseek-queue-${RANDOM}-${SECONDS}" \
  -H 'Content-Type: application/json' --data-binary '{}' >/dev/null

while :; do
  TASK_JSON="$(curl --fail --silent "${BASE_URL}/api/v1/tasks/${TASK_ID}")"
  PHASE="$(jq -r '.phase' <<<"${TASK_JSON}")"
  case "${PHASE}" in
    SUCCEEDED)
      if ! kubectl logs "deployment/${WORKER_NAME}" -n "${NAMESPACE}" \
          --since="${TIMEOUT_SECONDS}s" 2>/dev/null \
          | grep -F -- "Task result task=${TASK_ID} success=true" \
          | grep -F -- "${SUCCESS_MARKER}" >/dev/null; then
        echo "QwenPaw task reached SUCCEEDED but output marker was not observed: task=${TASK_ID}" >&2
        exit 1
      fi
      echo "QWENPAW_DEEPSEEK_TASK_OK task=${TASK_ID} phase=${PHASE} output=${SUCCESS_MARKER}"
      exit 0
      ;;
    FAILED|CANCELLED)
      echo "QwenPaw task ended in ${PHASE}: task=${TASK_ID}" >&2
      exit 1
      ;;
  esac
  if (( SECONDS >= deadline )); then
    echo "QwenPaw task timed out: task=${TASK_ID} phase=${PHASE}" >&2
    exit 1
  fi
  sleep 3
done
