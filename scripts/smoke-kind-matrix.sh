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

authorization_fixture_sql="
  INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
  VALUES ('00000000-0000-0000-0000-000000000025', 'tenant-a', 'project-a', 'ACTIVE', 'matrix-smoke', now(), now(), 0)
  ON CONFLICT (tenant_id, name) DO UPDATE SET status = 'ACTIVE', updated_at = now();
  INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
  SELECT 'tenant-a', id, 'matrix-smoke', 'ADMIN', 'ACTIVE', now(), now(), 0
    FROM projects WHERE tenant_id = 'tenant-a' AND name = 'project-a'
  ON CONFLICT (tenant_id, project_id, subject)
  DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = now();"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "${authorization_fixture_sql}" >/dev/null

mapping_id="00000000-0000-0000-0000-000000000042"
mapping_sql="INSERT INTO platform_identities(id, subject, tenant, project, team, permissions, created_at, updated_at, matrix_user_id) VALUES ('${mapping_id}', 'matrix-smoke', 'tenant-a', 'project-a', 'team-a', '[\"task:create\",\"task:read\",\"task:cancel\",\"task:retry\",\"task:pause\",\"task:approve\",\"task:reject\"]'::jsonb, now(), now(), '${user_id}') ON CONFLICT (subject) DO UPDATE SET tenant=EXCLUDED.tenant, project=EXCLUDED.project, team=EXCLUDED.team, permissions=EXCLUDED.permissions, updated_at=now(), matrix_user_id=EXCLUDED.matrix_user_id"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "${mapping_sql}" >/dev/null

room="$(curl --fail --silent --show-error --request POST \
  "${MATRIX_URL}/_matrix/client/v3/createRoom" \
  --header "Authorization: Bearer ${user_token}" \
  --header 'Content-Type: application/json' \
  --data "{\"name\":\"AgentTeams E2E ${run_id}\",\"preset\":\"private_chat\"}")"
room_id="$(printf '%s' "${room}" | json_string room_id)"
[[ -n "${room_id}" ]] || { echo "Matrix room creation failed: ${room}" >&2; exit 1; }

send_matrix_command() {
  local transaction="$1"
  local body="$2"
  local send_response
  send_response="$(curl --fail --silent --show-error --request PUT \
    "${MATRIX_URL}/_matrix/client/v3/rooms/${room_id}/send/m.room.message/${transaction}" \
    --header "Authorization: Bearer ${user_token}" \
    --header 'Content-Type: application/json' \
    --data "{\"msgtype\":\"m.text\",\"body\":\"${body}\"}")"
  grep -F 'event_id' <<<"${send_response}" >/dev/null || {
    echo "Matrix message send failed for ${body}: ${send_response}" >&2
    exit 1
  }
}

find_task_by_title() {
  local title="$1"
  kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c \
    "SELECT id FROM tasks WHERE actor='matrix-smoke' AND source='matrix' AND title='${title}' ORDER BY created_at DESC LIMIT 1" \
    | tr -d '\r' | head -n 1
}

wait_for_task() {
  local title="$1"
  local timeout_seconds="${2:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  local task_id=""
  while [[ -z "${task_id}" ]]; do
    task_id="$(find_task_by_title "${title}")"
    [[ -n "${task_id}" ]] && {
      printf '%s\n' "${task_id}"
      return 0
    }
    (( SECONDS >= deadline )) && return 1
    sleep 2
  done
}

matrix_event_seen() {
  local body="$1"
  kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c \
    "SELECT CASE WHEN EXISTS (SELECT 1 FROM matrix_inbox_events WHERE room_id='${room_id}' AND sender='${user_id}' AND body='${body}') THEN 1 ELSE 0 END" \
    | tr -d '\r' | head -n 1
}

wait_for_matrix_event() {
  local body="$1"
  local timeout_seconds="${2:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  local seen=0
  while [[ "${seen}" != "1" ]]; do
    seen="$(matrix_event_seen "${body}")"
    [[ "${seen}" == "1" ]] && return 0
    (( SECONDS >= deadline )) && return 1
    sleep 2
  done
}

print_matrix_delivery_diagnostics() {
  echo "Recent Tuwunel logs:" >&2
  kubectl -n "${NAMESPACE}" logs deployment/tuwunel --tail=120 >&2 || true
  echo "Recent Matrix inbox transactions:" >&2
  kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c \
    "SELECT transaction_id || ' processed=' || COALESCE(processed_at::text, 'null') FROM matrix_inbox_transactions ORDER BY received_at DESC LIMIT 20" >&2 || true
}

start_task() {
  local title="$1"
  local body="!agentteams start ${title}"
  local max_attempts=3
  local attempt=1
  local task_id=""
  while (( attempt <= max_attempts )); do
    task_id="$(find_task_by_title "${title}")"
    if [[ -n "${task_id}" ]]; then
      printf '%s\n' "${task_id}"
      return 0
    fi

    local transaction="kind-matrix-start-${RANDOM}-${SECONDS}-${attempt}"
    send_matrix_command "${transaction}" "${body}"

    # Do not resend after the Control Plane has recorded the event: the handler
    # may still be processing it, and a second event could create a duplicate task.
    if wait_for_matrix_event "${body}" 20; then
      if task_id="$(wait_for_task "${title}" 70)"; then
        printf '%s\n' "${task_id}"
        return 0
      fi
      echo "Matrix command reached Control Plane but did not create task ${title}" >&2
      print_matrix_delivery_diagnostics
      return 1
    fi

    echo "Matrix command was not delivered to Control Plane; retrying attempt ${attempt}/${max_attempts}" >&2
    attempt=$((attempt + 1))
  done

  echo "Matrix command was not delivered to Control Plane after ${max_attempts} attempts: ${body}" >&2
  print_matrix_delivery_diagnostics
  return 1
}

wait_task_phase() {
  local task_id="$1"
  local expected_phase="$2"
  local deadline=$((SECONDS + 90))
  local phase=""
  while [[ "${phase}" != "${expected_phase}" ]]; do
    phase="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
      psql -At -U agentteams -d agentteams -c "SELECT phase FROM tasks WHERE id='${task_id}'" \
      | tr -d '\r' | head -n 1)"
    [[ "${phase}" == "${expected_phase}" ]] && break
    if (( SECONDS >= deadline )); then
      echo "Task ${task_id} did not reach ${expected_phase}; current=${phase}" >&2
      exit 1
    fi
    sleep 2
  done
}

task_version() {
  local task_id="$1"
  kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
    psql -At -U agentteams -d agentteams -c "SELECT version FROM tasks WHERE id='${task_id}'" \
    | tr -d '\r' | head -n 1
}

wait_task_transition() {
  local task_id="$1"
  local minimum_version="$2"
  local deadline=$((SECONDS + 90))
  local seen=0
  while [[ "${seen}" != "1" ]]; do
    seen="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
      psql -At -U agentteams -d agentteams -c \
      "SELECT CASE WHEN EXISTS (SELECT 1 FROM domain_events WHERE aggregate_type='task' AND aggregate_id='${task_id}' AND event_type='TaskPhaseChanged' AND aggregate_version > ${minimum_version}) THEN 1 ELSE 0 END" \
      | tr -d '\r' | head -n 1)"
    [[ "${seen}" == "1" ]] && break
    if (( SECONDS >= deadline )); then
      echo "Task ${task_id} did not record a phase transition after version ${minimum_version}" >&2
      exit 1
    fi
    sleep 2
  done
}

wait_matrix_event() {
  local body="$1"
  if ! wait_for_matrix_event "${body}" 90; then
    echo "Matrix command was not delivered: ${body}" >&2
    print_matrix_delivery_diagnostics
    exit 1
  fi
}

# The homeserver may accept client requests before its first AppService
# delivery has completed. Establish that delivery path before creating tasks.
readiness_body="matrix-appservice-ready-${run_id}"
send_matrix_command "kind-matrix-readiness-${run_id}" "${readiness_body}"
wait_matrix_event "${readiness_body}"

title="matrix-e2e-${run_id}"
task_id="$(start_task "${title}")"

status_body="!agentteams status ${task_id}"
send_matrix_command "kind-matrix-status-${run_id}" "${status_body}"
wait_matrix_event "${status_body}"

approve_body="!agentteams approve ${task_id}"
send_matrix_command "kind-matrix-approve-${run_id}" "${approve_body}"
wait_matrix_event "${approve_body}"
approval_seen="$(kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -At -U agentteams -d agentteams -c "SELECT CASE WHEN spec->>'approvalGranted'='true' THEN 1 ELSE 0 END FROM tasks WHERE id='${task_id}'" \
  | tr -d '\r' | head -n 1)"
[[ "${approval_seen}" == "1" ]] || { echo "Matrix approve did not update task spec" >&2; exit 1; }

pause_body="!agentteams pause ${task_id}"
send_matrix_command "kind-matrix-pause-${run_id}" "${pause_body}"
wait_matrix_event "${pause_body}"
wait_task_phase "${task_id}" "PAUSED"
resume_version="$(task_version "${task_id}")"
send_matrix_command "kind-matrix-resume-${run_id}" "${pause_body}"
wait_matrix_event "${pause_body}"
wait_task_transition "${task_id}" "${resume_version}"

cancel_title="matrix-e2e-cancel-${run_id}"
cancel_task_id="$(start_task "${cancel_title}")"
cancel_body="!agentteams cancel ${cancel_task_id}"
send_matrix_command "kind-matrix-cancel-${run_id}" "${cancel_body}"
wait_matrix_event "${cancel_body}"
wait_task_phase "${cancel_task_id}" "CANCELLED"

reject_title="matrix-e2e-reject-${run_id}"
reject_task_id="$(start_task "${reject_title}")"
reject_body="!agentteams reject ${reject_task_id}"
send_matrix_command "kind-matrix-reject-${run_id}" "${reject_body}"
wait_matrix_event "${reject_body}"
wait_task_phase "${reject_task_id}" "REJECTED"

retry_title="matrix-e2e-retry-${run_id}"
retry_task_id="$(start_task "${retry_title}")"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c \
  "UPDATE tasks SET phase='FAILED', failure_code='SMOKE_FAILURE', redacted_failure_message='synthetic failure for Matrix retry smoke' WHERE id='${retry_task_id}'" >/dev/null
retry_body="!agentteams retry ${retry_task_id}"
retry_version="$(task_version "${retry_task_id}")"
send_matrix_command "kind-matrix-retry-${run_id}" "${retry_body}"
wait_matrix_event "${retry_body}"
wait_task_transition "${retry_task_id}" "${retry_version}"

denied_title="matrix-e2e-denied-${run_id}"
denied_task_id="$(start_task "${denied_title}")"
deny_sql="UPDATE platform_identities SET permissions='[\"task:create\",\"task:read\",\"task:cancel\",\"task:retry\",\"task:pause\",\"task:approve\"]'::jsonb, updated_at=now() WHERE subject='matrix-smoke'"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "${deny_sql}" >/dev/null
denied_transaction="kind-matrix-denied-${run_id}"
denied_event="\$${denied_transaction}"
denied_payload="{\"events\":[{\"event_id\":\"${denied_event}\",\"type\":\"m.room.message\",\"room_id\":\"${room_id}\",\"sender\":\"${user_id}\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"!agentteams reject ${denied_task_id}\"}}]}"
denied_status="$(curl --silent --show-error --output "${API_LOG}.denied" --write-out '%{http_code}' \
  --request PUT "${API_URL}/_matrix/app/v1/transactions/${denied_transaction}" \
  --header "Authorization: Bearer ${HS_TOKEN}" --header 'Content-Type: application/json' \
  --data "${denied_payload}")"
[[ "${denied_status}" == "403" ]] || { echo "Matrix permission denial expected 403, got ${denied_status}" >&2; exit 1; }
wait_task_phase "${denied_task_id}" "DRAFT"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "${mapping_sql}" >/dev/null
rm -f "${API_LOG}.denied"

duplicate_transaction="kind-matrix-duplicate-pause-${run_id}"
duplicate_event="\$${duplicate_transaction}"
duplicate_payload="{\"events\":[{\"event_id\":\"${duplicate_event}\",\"type\":\"m.room.message\",\"room_id\":\"${room_id}\",\"sender\":\"${user_id}\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"!agentteams pause ${denied_task_id}\"}}]}"
duplicate_first="$(curl --fail --silent --show-error --request PUT \
  "${API_URL}/_matrix/app/v1/transactions/${duplicate_transaction}" \
  --header "Authorization: Bearer ${HS_TOKEN}" --header 'Content-Type: application/json' \
  --data "${duplicate_payload}")"
grep -F '"status":"HANDLED"' <<<"${duplicate_first}" >/dev/null || {
  echo "Matrix direct pause was not handled: ${duplicate_first}" >&2
  exit 1
}
wait_task_phase "${denied_task_id}" "PAUSED"
duplicate_second="$(curl --fail --silent --show-error --request PUT \
  "${API_URL}/_matrix/app/v1/transactions/${duplicate_transaction}" \
  --header "Authorization: Bearer ${HS_TOKEN}" --header 'Content-Type: application/json' \
  --data "${duplicate_payload}")"
grep -F '"duplicate":true' <<<"${duplicate_second}" >/dev/null || {
  echo "Matrix duplicate mutation was not acknowledged: ${duplicate_second}" >&2
  exit 1
}
wait_task_phase "${denied_task_id}" "PAUSED"

echo "KIND_MATRIX_APPSERVICE_OK"
echo "KIND_MATRIX_E2E_OK task=${task_id} user=${user_id} room=${room_id}"
echo "KIND_MATRIX_LIFECYCLE_E2E_OK cancel=${cancel_task_id} reject=${reject_task_id} retry=${retry_task_id} denied=${denied_task_id}"
echo "KIND_MATRIX_DUPLICATE_MUTATION_OK task=${denied_task_id}"
