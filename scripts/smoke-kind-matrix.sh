#!/usr/bin/env bash
set -euo pipefail

NAMESPACE=${KIND_NAMESPACE:-agentteams}
MATRIX_PORT=${KIND_MATRIX_LOCAL_PORT:-18082}
API_PORT=${KIND_CONTROL_PLANE_LOCAL_PORT:-18081}
MATRIX_SERVICE=${KIND_MATRIX_SERVICE:-tuwunel}
CONTROL_PLANE_SERVICE=${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}
LOG_DIR=${TMPDIR:-/tmp}
MATRIX_LOG="${LOG_DIR}/agentteams-tuwunel-port-forward.log"
API_LOG="${LOG_DIR}/agentteams-matrix-api-port-forward.log"
MATRIX_PID=""
API_PID=""

cleanup() {
  [[ -z "${MATRIX_PID}" ]] || kill "${MATRIX_PID}" >/dev/null 2>&1 || true
  [[ -z "${API_PID}" ]] || kill "${API_PID}" >/dev/null 2>&1 || true
  [[ -z "${MATRIX_PID}" ]] || wait "${MATRIX_PID}" >/dev/null 2>&1 || true
  [[ -z "${API_PID}" ]] || wait "${API_PID}" >/dev/null 2>&1 || true
  rm -f "${MATRIX_LOG}" "${API_LOG}"
}
trap cleanup EXIT

for command_name in curl kubectl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

HS_TOKEN=${MATRIX_HS_TOKEN:-$(kubectl -n "${NAMESPACE}" get secret matrix-appservice \
  -o jsonpath='{.data.hs-token}' | base64 --decode)}
[[ -n "${HS_TOKEN}" ]] || { echo "Matrix hs_token is empty" >&2; exit 1; }

kubectl -n "${NAMESPACE}" port-forward "service/${MATRIX_SERVICE}" \
  "${MATRIX_PORT}:8008" >"${MATRIX_LOG}" 2>&1 &
MATRIX_PID=$!
kubectl -n "${NAMESPACE}" port-forward "service/${CONTROL_PLANE_SERVICE}" \
  "${API_PORT}:8080" >"${API_LOG}" 2>&1 &
API_PID=$!

MATRIX_URL="http://127.0.0.1:${MATRIX_PORT}"
API_URL="http://127.0.0.1:${API_PORT}"
deadline=$((SECONDS + 120))
until curl --fail --silent "${MATRIX_URL}/_matrix/client/versions" >/dev/null \
  && curl --fail --silent "${API_URL}/actuator/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Tuwunel 或 Control Plane 未就绪" >&2
    sed -n '1,40p' "${MATRIX_LOG}" >&2 || true
    sed -n '1,40p' "${API_LOG}" >&2 || true
    exit 1
  fi
  sleep 2
done

transaction_id="kind-matrix-$(date +%s)-${RANDOM}"
event_id="\$kind-matrix-${RANDOM}-${SECONDS}"
payload="{\"events\":[{\"event_id\":\"${event_id}\",\"type\":\"m.room.message\",\"room_id\":\"!agentteams:agentteams.test\",\"sender\":\"@alice:agentteams.test\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"hello from tuwunel\"}}]}"

unauthorized_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request PUT "${API_URL}/_matrix/app/v1/transactions/${transaction_id}-unauthorized" \
  --header 'Content-Type: application/json' --data "${payload}")"
[[ "${unauthorized_status}" == "401" ]] || {
  echo "missing Matrix hs_token: expected HTTP 401, got ${unauthorized_status}" >&2
  exit 1
}

response="$(curl --fail --silent --show-error --request PUT \
  "${API_URL}/_matrix/app/v1/transactions/${transaction_id}" \
  --header "Authorization: Bearer ${HS_TOKEN}" \
  --header 'Content-Type: application/json' --data "${payload}")"
grep -F '"accepted":true' <<<"${response}" >/dev/null || {
  echo "Matrix AppService transaction was not accepted: ${response}" >&2
  exit 1
}

duplicate="$(curl --fail --silent --show-error --request PUT \
  "${API_URL}/_matrix/app/v1/transactions/${transaction_id}" \
  --header "Authorization: Bearer ${HS_TOKEN}" \
  --header 'Content-Type: application/json' --data "${payload}")"
grep -F '"duplicate":true' <<<"${duplicate}" >/dev/null || {
  echo "Matrix duplicate transaction was not acknowledged: ${duplicate}" >&2
  exit 1
}

json_string() {
  local field="$1"
  sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

DB_PASSWORD=$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
  -o jsonpath='{.data.password}' | base64 --decode)
run_id="${RANDOM}-${SECONDS}"
username="matrixsmoke${RANDOM}${SECONDS}"
password="agentteams-matrix-dev"
registration="$(curl --fail --silent --show-error --request POST \
  "${MATRIX_URL}/_matrix/client/v3/register" \
  --header 'Content-Type: application/json' \
  --data "{\"username\":\"${username}\",\"password\":\"${password}\",\"auth\":{\"type\":\"m.login.registration_token\",\"token\":\"agentteams-registration-dev\"}}")"
user_token="$(printf '%s' "${registration}" | json_string access_token)"
user_id="$(printf '%s' "${registration}" | json_string user_id)"
[[ -n "${user_token}" && -n "${user_id}" ]] || {
  echo "Tuwunel user registration failed: ${registration}" >&2
  exit 1
}
[[ "${user_id}" =~ ^@[A-Za-z0-9._=-]+:agentteams\.test$ ]] || {
  echo "unexpected Matrix user id: ${user_id}" >&2
  exit 1
}

mapping_id="00000000-0000-0000-0000-000000000042"
mapping_sql="INSERT INTO platform_identities(id, subject, tenant, project, team, permissions, created_at, updated_at, matrix_user_id) VALUES ('${mapping_id}', 'matrix-smoke', 'tenant-a', 'project-a', 'team-a', '[\"task:create\",\"task:read\",\"task:cancel\"]'::jsonb, now(), now(), '${user_id}') ON CONFLICT (subject) DO UPDATE SET tenant=EXCLUDED.tenant, project=EXCLUDED.project, team=EXCLUDED.team, permissions=EXCLUDED.permissions, updated_at=now(), matrix_user_id=EXCLUDED.matrix_user_id"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "${mapping_sql}" >/dev/null

room="$(curl --fail --silent --show-error --request POST \
  "${MATRIX_URL}/_matrix/client/v3/createRoom" \
  --header "Authorization: Bearer ${user_token}" \
  --header 'Content-Type: application/json' \
  --data "{\"name\":\"AgentTeams E2E ${run_id}\",\"preset\":\"private_chat\"}")"
room_id="$(printf '%s' "${room}" | json_string room_id)"
[[ -n "${room_id}" ]] || { echo "Matrix room creation failed: ${room}" >&2; exit 1; }

title="matrix-e2e-${run_id}"
send_response="$(curl --fail --silent --show-error --request PUT \
  "${MATRIX_URL}/_matrix/client/v3/rooms/${room_id}/send/m.room.message/${run_id}" \
  --header "Authorization: Bearer ${user_token}" \
  --header 'Content-Type: application/json' \
  --data "{\"msgtype\":\"m.text\",\"body\":\"!agentteams start ${title}\"}")"
grep -F 'event_id' <<<"${send_response}" >/dev/null || {
  echo "Matrix message send failed: ${send_response}" >&2
  exit 1
}

task_id=""
deadline=$((SECONDS + 90))
while [[ -z "${task_id}" ]]; do
  task_id="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c \
    "SELECT id FROM tasks WHERE actor='matrix-smoke' AND source='matrix' AND title='${title}' ORDER BY created_at DESC LIMIT 1" \
    | tr -d '\r' | head -n 1)"
  [[ -n "${task_id}" ]] && break
  if (( SECONDS >= deadline )); then
    echo "Matrix command did not create a task" >&2
    kubectl -n "${NAMESPACE}" logs deployment/tuwunel --tail=120 >&2 || true
    exit 1
  fi
  sleep 2
done

status_txn="kind-matrix-status-${run_id}"
status_body="!agentteams status ${task_id}"
status_response="$(curl --fail --silent --show-error --request PUT \
  "${MATRIX_URL}/_matrix/client/v3/rooms/${room_id}/send/m.room.message/${status_txn}" \
  --header "Authorization: Bearer ${user_token}" \
  --header 'Content-Type: application/json' \
  --data "{\"msgtype\":\"m.text\",\"body\":\"${status_body}\"}")"
grep -F 'event_id' <<<"${status_response}" >/dev/null || {
  echo "Matrix status message send failed: ${status_response}" >&2
  exit 1
}

status_seen=0
deadline=$((SECONDS + 90))
while [[ "${status_seen}" != "1" ]]; do
  status_seen="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c \
    "SELECT CASE WHEN EXISTS (SELECT 1 FROM matrix_inbox_events WHERE room_id='${room_id}' AND sender='${user_id}' AND body='${status_body}') THEN 1 ELSE 0 END" \
    | tr -d '\r' | head -n 1)"
  [[ "${status_seen}" == "1" ]] && break
  if (( SECONDS >= deadline )); then
    echo "Matrix status command was not delivered to Control Plane" >&2
    exit 1
  fi
  sleep 2
done

echo "KIND_MATRIX_APPSERVICE_OK"
echo "KIND_MATRIX_E2E_OK task=${task_id} user=${user_id} room=${room_id}"
echo "KIND_MATRIX_STATUS_E2E_OK task=${task_id}"
