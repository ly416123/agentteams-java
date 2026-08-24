#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${AGENTTEAMS_NAMESPACE:-agentteams}"
POSTGRES_POD="${AGENTTEAMS_POSTGRES_POD:-postgresql-0}"
BASE_URL="${AGENTTEAMS_CONTROL_PLANE_BASE_URL:-http://127.0.0.1:18080}"
API_AUTH_ARGS=()
KEYCLOAK_PID=""
KEYCLOAK_PORT="${KIND_KEYCLOAK_LOCAL_PORT:-18082}"

cleanup() {
  if [[ -n "${KEYCLOAK_PID}" ]]; then
    kill "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
    wait "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

curl_api() {
  if (( ${#API_AUTH_ARGS[@]} > 0 )); then
    curl "${API_AUTH_ARGS[@]}" "$@"
  else
    curl "$@"
  fi
}

configure_auth() {
  if [[ -n "${AGENTTEAMS_API_TOKEN:-}" ]]; then
    API_AUTH_ARGS=(-H "Authorization: Bearer ${AGENTTEAMS_API_TOKEN}")
    return
  fi

  local status
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}/api/v1/tasks" || true)"
  [[ "${status}" != "401" ]] && return

  kubectl -n "${NAMESPACE}" get service/keycloak >/dev/null 2>&1 || {
    echo "Control Plane requires a Bearer token; set AGENTTEAMS_API_TOKEN or deploy service/keycloak" >&2
    exit 1
  }
  kubectl -n "${NAMESPACE}" port-forward service/keycloak "${KEYCLOAK_PORT}:8080" \
    >/tmp/agentteams-task-api-keycloak-port-forward.log 2>&1 &
  KEYCLOAK_PID=$!

  local keycloak_url="http://127.0.0.1:${KEYCLOAK_PORT}"
  local deadline=$((SECONDS + 60))
  until curl --fail --silent "${keycloak_url}/realms/agentteams/.well-known/openid-configuration" >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Keycloak did not become ready for Task API smoke" >&2
      exit 1
    fi
    sleep 1
  done

  local response token
  response="$(curl --fail --silent --show-error -X POST \
    "${keycloak_url}/realms/agentteams/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=agentteams-api' \
    --data-urlencode "username=${AGENTTEAMS_API_USERNAME:-alice}" \
    --data-urlencode "password=${AGENTTEAMS_API_PASSWORD:-alice-dev}")"
  token="$(jq -er '.access_token' <<<"${response}")"
  API_AUTH_ARGS=(-H "Authorization: Bearer ${token}")
}

post_task() {
  local key="$1"
  local capability="${2:-kind-api-smoke}"
  local payload
  payload="$(jq -cn --arg capability "${capability}" '{title:"kind-task-api-smoke",description:"API contract smoke",spec:{scope:{tenant:"tenant-a",project:"project-a",team:"team-a"},taskType:"qwenpaw",inputJson:{prompt:"KIND_API_SMOKE"},requiredCapabilities:[$capability]}}')"
  curl_api --fail-with-body --silent --show-error \
    -X POST "${BASE_URL}/api/v1/tasks" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${key}" \
    -d "${payload}"
}

action_task() {
  local task_id="$1"
  local action="$2"
  local key="$3"
  local expected_version="$4"
  curl_api --fail-with-body --silent --show-error \
    -X POST "${BASE_URL}/api/v1/tasks/${task_id}/${action}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${key}" \
    -d "{\"expectedVersion\":${expected_version}}"
}

configure_auth

task_json="$(post_task "kind-api-smoke-$(date +%s)-lifecycle")"
task_id="$(jq -er '.id' <<<"${task_json}")"
version="$(jq -er '.version' <<<"${task_json}")"
echo "Task API smoke lifecycle task=${task_id} created" >&2

echo "Task API smoke action=approve" >&2
task_json="$(action_task "${task_id}" approve "kind-api-approve-${task_id}" "${version}")"
version="$(jq -er '.version' <<<"${task_json}")"
echo "Task API smoke action=queue" >&2
task_json="$(action_task "${task_id}" queue "kind-api-queue-${task_id}" "${version}")"
version="$(jq -er '.version' <<<"${task_json}")"
echo "Task API smoke action=pause" >&2
task_json="$(action_task "${task_id}" pause "kind-api-pause-${task_id}" "${version}")"
version="$(jq -er '.version' <<<"${task_json}")"
echo "Task API smoke action=resume" >&2
task_json="$(action_task "${task_id}" pause "kind-api-resume-${task_id}" "${version}")"
version="$(jq -er '.version' <<<"${task_json}")"
echo "Task API smoke action=cancel" >&2
task_json="$(action_task "${task_id}" cancel "kind-api-cancel-${task_id}" "${version}")"
[[ "$(jq -er '.phase' <<<"${task_json}")" == "CANCELLED" ]]

rejected_json="$(post_task "kind-api-smoke-$(date +%s)-reject")"
rejected_id="$(jq -er '.id' <<<"${rejected_json}")"
echo "Task API smoke action=reject" >&2
rejected_json="$(action_task "${rejected_id}" reject "kind-api-reject-${rejected_id}" "$(jq -er '.version' <<<"${rejected_json}")")"
[[ "$(jq -er '.phase' <<<"${rejected_json}")" == "REJECTED" ]]

attempt_task_json="$(post_task "kind-api-smoke-$(date +%s)-artifact" qwenpaw)"
attempt_task_id="$(jq -er '.id' <<<"${attempt_task_json}")"
echo "Task API smoke artifact task=${attempt_task_id} queued" >&2
action_task "${attempt_task_id}" queue "kind-api-artifact-queue-${attempt_task_id}" "$(jq -er '.version' <<<"${attempt_task_json}")" >/dev/null

attempt_id=""
for _ in $(seq 1 60); do
  attempt_id="$(kubectl -n "${NAMESPACE}" exec "${POSTGRES_POD}" -- psql -U agentteams -d agentteams -At -c \
    "select id::text from task_attempts where task_id = '${attempt_task_id}' order by created_at desc limit 1" 2>/dev/null || true)"
  if [[ -n "${attempt_id}" ]]; then
    break
  fi
  sleep 1
done
[[ -n "${attempt_id}" ]]

artifacts="$(curl_api --fail-with-body --silent --show-error \
  "${BASE_URL}/api/v1/tasks/${attempt_task_id}/attempts/${attempt_id}/artifacts")"
jq -e 'type == "array"' <<<"${artifacts}" >/dev/null

echo "KIND_TASK_API_OK lifecycle=${task_id} rejected=${rejected_id} attempt=${attempt_id}"
