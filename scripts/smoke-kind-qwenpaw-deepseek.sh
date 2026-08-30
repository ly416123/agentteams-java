#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${KIND_NAMESPACE:-agentteams}"
CONTROL_PLANE_SERVICE="${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}"
WORKER_NAME="${AGENTTEAMS_WORKER_NAME:-qwenpaw-worker}"
LOCAL_PORT="${AGENTTEAMS_CONTROL_PLANE_LOCAL_PORT:-18080}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-180}"
SUCCESS_MARKER="QWENPAW_DEEPSEEK_SMOKE_OK"
TMP_DIR="${TMPDIR:-/tmp}"
PORT_FORWARD_LOG="${TMP_DIR}/agentteams-control-plane-smoke.log"
PORT_FORWARD_PID=""
KEYCLOAK_PORT="${KIND_KEYCLOAK_LOCAL_PORT:-18082}"
KEYCLOAK_LOG="${TMP_DIR}/agentteams-qwenpaw-deepseek-keycloak.log"
KEYCLOAK_PID=""
API_AUTH_ARGS=()
SCOPE_TENANT="${AGENTTEAMS_SCOPE_TENANT:-tenant-a}"
SCOPE_PROJECT="${AGENTTEAMS_SCOPE_PROJECT:-project-a}"
SCOPE_TEAM="${AGENTTEAMS_SCOPE_TEAM:-team-a}"

curl_api() {
  if (( ${#API_AUTH_ARGS[@]} > 0 )); then
    curl "${API_AUTH_ARGS[@]}" "$@"
  else
    curl "$@"
  fi
}

cleanup() {
  if [[ -n "${KEYCLOAK_PID}" ]]; then
    kill "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
    wait "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  rm -f "${PORT_FORWARD_LOG}" "${KEYCLOAK_LOG}"
}
trap cleanup EXIT

for command_name in curl jq kubectl; do
  command -v "${command_name}" >/dev/null || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

if [[ -n "${AGENTTEAMS_API_TOKEN:-}" ]]; then
  API_AUTH_ARGS=(-H "Authorization: Bearer ${AGENTTEAMS_API_TOKEN}")
else
  kubectl -n "${NAMESPACE}" get service/keycloak >/dev/null 2>&1 || {
    echo "Keycloak is required when AGENTTEAMS_API_TOKEN is unset" >&2
    exit 1
  }
  kubectl -n "${NAMESPACE}" port-forward service/keycloak \
    "${KEYCLOAK_PORT}:8080" >"${KEYCLOAK_LOG}" 2>&1 &
  KEYCLOAK_PID=$!
  KEYCLOAK_URL="http://127.0.0.1:${KEYCLOAK_PORT}"
  for attempt in $(seq 1 60); do
    if curl --silent --fail \
      "${KEYCLOAK_URL}/realms/agentteams/.well-known/openid-configuration" >/dev/null 2>&1; then
      break
    fi
    if [[ "${attempt}" == 60 ]]; then
      echo "Keycloak did not become ready at ${KEYCLOAK_URL}" >&2
      exit 1
    fi
    sleep 1
  done
  token_response="$(curl --silent --fail --show-error -X POST \
    "${KEYCLOAK_URL}/realms/agentteams/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=agentteams-api' \
    --data-urlencode "username=${AGENTTEAMS_API_USERNAME:-alice}" \
    --data-urlencode "password=${AGENTTEAMS_API_PASSWORD:-alice-dev}")"
  TOKEN="$(jq -er '.access_token' <<<"${token_response}")"
  API_AUTH_ARGS=(-H "Authorization: Bearer ${TOKEN}")
fi

kubectl -n "${NAMESPACE}" wait --for=condition=available \
  "deployment/${WORKER_NAME}" --timeout="${TIMEOUT_SECONDS}s" >/dev/null
kubectl -n "${NAMESPACE}" port-forward "service/${CONTROL_PLANE_SERVICE}" \
  "${LOCAL_PORT}:8080" >"${PORT_FORWARD_LOG}" 2>&1 &
PORT_FORWARD_PID=$!
BASE_URL="http://127.0.0.1:${LOCAL_PORT}"

deadline=$((SECONDS + TIMEOUT_SECONDS))
until curl_api --fail --silent "${BASE_URL}/actuator/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Control Plane API did not become ready" >&2
    exit 1
  fi
  sleep 2
done

IDEMPOTENCY_KEY="kind-qwenpaw-deepseek-create-${RANDOM}-${SECONDS}"
TASK_BODY="$(jq -cn \
  --arg tenant "${SCOPE_TENANT}" \
  --arg project "${SCOPE_PROJECT}" \
  --arg team "${SCOPE_TEAM}" \
  '{title:"kind-qwenpaw-deepseek",description:"DeepSeek QwenPaw end-to-end smoke",spec:{scope:{tenant:$tenant,project:$project,team:$team},taskType:"qwenpaw",inputJson:{prompt:"Reply with exactly QWENPAW_DEEPSEEK_SMOKE_OK and nothing else."},requiredCapabilities:["qwenpaw"]}}')"
TASK_JSON="$(curl_api --fail --silent -X POST "${BASE_URL}/api/v1/tasks" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" -H 'Content-Type: application/json' \
  --data-binary "${TASK_BODY}")"
TASK_ID="$(jq -er '.id' <<<"${TASK_JSON}")"

curl_api --fail --silent -X POST "${BASE_URL}/api/v1/tasks/${TASK_ID}/queue" \
  -H "Idempotency-Key: kind-qwenpaw-deepseek-queue-${RANDOM}-${SECONDS}" \
  -H 'Content-Type: application/json' --data-binary '{}' >/dev/null

while :; do
  TASK_JSON="$(curl_api --fail --silent "${BASE_URL}/api/v1/tasks/${TASK_ID}")"
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
