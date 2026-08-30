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
SMOKE_MEMBERSHIP_SUBJECT=""
SMOKE_MEMBERSHIP_PREVIOUS_ROLE=""
SMOKE_MEMBERSHIP_CHANGED="0"
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
  if [[ "${SMOKE_MEMBERSHIP_CHANGED}" == "1" && -n "${SMOKE_MEMBERSHIP_SUBJECT}" ]]; then
    DB_PASSWORD="$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
      -o jsonpath='{.data.password}' | base64 --decode 2>/dev/null || true)"
    if [[ -n "${DB_PASSWORD}" ]]; then
      if [[ -n "${SMOKE_MEMBERSHIP_PREVIOUS_ROLE}" ]]; then
        kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
          psql -U agentteams -d agentteams -v ON_ERROR_STOP=1 -c \
          "UPDATE project_memberships SET role='${SMOKE_MEMBERSHIP_PREVIOUS_ROLE}', updated_at=now() WHERE subject='${SMOKE_MEMBERSHIP_SUBJECT}' AND tenant_id='${SCOPE_TENANT}' AND project_id=(SELECT id FROM projects WHERE tenant_id='${SCOPE_TENANT}' AND name='${SCOPE_PROJECT}');" \
          >/dev/null 2>&1 || true
      else
        kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
          psql -U agentteams -d agentteams -v ON_ERROR_STOP=1 -c \
          "DELETE FROM project_memberships WHERE subject='${SMOKE_MEMBERSHIP_SUBJECT}' AND tenant_id='${SCOPE_TENANT}' AND project_id=(SELECT id FROM projects WHERE tenant_id='${SCOPE_TENANT}' AND name='${SCOPE_PROJECT}');" \
          >/dev/null 2>&1 || true
      fi
    fi
  fi
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

for command_name in curl jq kubectl python3; do
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

  SMOKE_MEMBERSHIP_SUBJECT="$(python3 - "${TOKEN}" <<'PY'
import base64
import json
import sys

payload = sys.argv[1].split('.')[1]
payload += '=' * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload))['sub'])
PY
)"
  [[ "${SMOKE_MEMBERSHIP_SUBJECT}" =~ ^[A-Za-z0-9._:-]{1,255}$ ]] || {
    echo "OIDC token subject has an unexpected format" >&2
    exit 1
  }
  DB_PASSWORD="$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
    -o jsonpath='{.data.password}' | base64 --decode)"
  SMOKE_MEMBERSHIP_PREVIOUS_ROLE="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -U agentteams -d agentteams -At -c \
    "SELECT role FROM project_memberships WHERE subject='${SMOKE_MEMBERSHIP_SUBJECT}' AND tenant_id='${SCOPE_TENANT}' AND project_id=(SELECT id FROM projects WHERE tenant_id='${SCOPE_TENANT}' AND name='${SCOPE_PROJECT}') LIMIT 1;" \
    2>/dev/null || true)"
  kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -U agentteams -d agentteams -v ON_ERROR_STOP=1 -c \
    "INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version) SELECT '${SCOPE_TENANT}', id, '${SMOKE_MEMBERSHIP_SUBJECT}', 'ADMIN', 'ACTIVE', now(), now(), 0 FROM projects WHERE tenant_id='${SCOPE_TENANT}' AND name='${SCOPE_PROJECT}' ON CONFLICT (tenant_id, project_id, subject) DO UPDATE SET role='ADMIN', status='ACTIVE', updated_at=now();" \
    >/dev/null
  SMOKE_MEMBERSHIP_CHANGED="1"
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
