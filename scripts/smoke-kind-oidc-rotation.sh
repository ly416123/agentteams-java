#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${KIND_NAMESPACE:-agentteams}
KEYCLOAK_PORT=${KIND_KEYCLOAK_LOCAL_PORT:-18080}
API_PORT=${KIND_CONTROL_PLANE_LOCAL_PORT:-18081}
KEYCLOAK_SERVICE=${KIND_KEYCLOAK_SERVICE:-keycloak}
CONTROL_PLANE_SERVICE=${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}
KEYCLOAK_ADMIN_USERNAME=${KEYCLOAK_ADMIN_USERNAME:-admin}
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin-dev}
LOG_DIR=${TMPDIR:-/tmp}
KEYCLOAK_LOG="${LOG_DIR}/agentteams-keycloak-rotation-port-forward.log"
API_LOG="${LOG_DIR}/agentteams-oidc-rotation-api-port-forward.log"
KEYCLOAK_PID=""
API_PID=""
COMPONENT_ID=""
COMPONENT_NAME=""
ADMIN_TOKEN=""

cleanup() {
  if [[ -z "${COMPONENT_ID}" && -n "${COMPONENT_NAME}" && -n "${ADMIN_TOKEN}" ]]; then
    COMPONENT_ID="$(curl --silent --show-error \
      --header "Authorization: Bearer ${ADMIN_TOKEN}" \
      "${KEYCLOAK_URL}/admin/realms/agentteams/components?type=org.keycloak.keys.KeyProvider" \
      | tr '{' '\n' | grep -F "\"name\":\"${COMPONENT_NAME}\"" \
      | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1 || true)"
  fi
  if [[ -n "${COMPONENT_ID}" && -n "${ADMIN_TOKEN}" ]]; then
    curl --silent --show-error --request DELETE \
      "${KEYCLOAK_URL}/admin/realms/agentteams/components/${COMPONENT_ID}" \
      --header "Authorization: Bearer ${ADMIN_TOKEN}" >/dev/null 2>&1 || true
  fi
  [[ -z "${KEYCLOAK_PID}" ]] || kill "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  [[ -z "${API_PID}" ]] || kill "${API_PID}" >/dev/null 2>&1 || true
  [[ -z "${KEYCLOAK_PID}" ]] || wait "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  [[ -z "${API_PID}" ]] || wait "${API_PID}" >/dev/null 2>&1 || true
  rm -f "${KEYCLOAK_LOG}" "${API_LOG}"
}
trap cleanup EXIT

for command_name in curl kubectl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

kubectl -n "${NAMESPACE}" port-forward "service/${KEYCLOAK_SERVICE}" \
  "${KEYCLOAK_PORT}:8080" >"${KEYCLOAK_LOG}" 2>&1 &
KEYCLOAK_PID=$!
kubectl -n "${NAMESPACE}" port-forward "service/${CONTROL_PLANE_SERVICE}" \
  "${API_PORT}:8080" >"${API_LOG}" 2>&1 &
API_PID=$!

KEYCLOAK_URL="http://127.0.0.1:${KEYCLOAK_PORT}"
API_URL="http://127.0.0.1:${API_PORT}"
deadline=$((SECONDS + 120))
until curl --fail --silent "${KEYCLOAK_URL}/realms/agentteams/.well-known/openid-configuration" >/dev/null \
  && curl --fail --silent "${API_URL}/actuator/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Keycloak 或 Control Plane 未就绪" >&2
    sed -n '1,20p' "${KEYCLOAK_LOG}" >&2 || true
    sed -n '1,20p' "${API_LOG}" >&2 || true
    exit 1
  fi
  sleep 2
done

json_string() {
  local field="$1"
  sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

json_first_string() {
  local field="$1"
  # Consume the complete response: grep -m/head can close the pipe early and
  # GNU tr then reports SIGPIPE as an error under `set -o pipefail`.
  tr ',' '\n' | awk -v field="${field}" '
    !found && index($0, "\"" field "\"") { print; found = 1 }
  ' | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

token_for() {
  local username="$1"
  local password="$2"
  local response
  local token
  response="$(curl --fail --silent --show-error --request POST \
    "${KEYCLOAK_URL}/realms/agentteams/protocol/openid-connect/token" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=agentteams-api' \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}")"
  token="$(printf '%s' "${response}" | json_string access_token)"
  [[ -n "${token}" ]] || { echo "Keycloak 未返回 access_token" >&2; exit 1; }
  printf '%s\n' "${token}"
}

admin_response="$(curl --fail --silent --show-error --request POST \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' \
  --data-urlencode "username=${KEYCLOAK_ADMIN_USERNAME}" \
  --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}")"
ADMIN_TOKEN="$(printf '%s' "${admin_response}" | json_string access_token)"
[[ -n "${ADMIN_TOKEN}" ]] || { echo "Keycloak admin token 获取失败" >&2; exit 1; }

token_kid() {
  local encoded_header="${1%%.*}"
  encoded_header="${encoded_header//-/+}"
  encoded_header="${encoded_header//_/\/}"
  while (( ${#encoded_header} % 4 )); do
    encoded_header="${encoded_header}="
  done
  printf '%s' "${encoded_header}" | base64 --decode 2>/dev/null | json_string kid
}

jwks_contains_kid() {
  curl --fail --silent --show-error \
    --header 'Cache-Control: no-cache' \
    --header 'Pragma: no-cache' \
    "${KEYCLOAK_URL}/realms/agentteams/protocol/openid-connect/certs?rotation_nonce=${RANDOM}-${SECONDS}" \
    | grep -F "${1}" >/dev/null
}

post_task() {
  local label="$1"
  local token="$2"
  local key="oidc-rotation-${label}-${RANDOM}-${SECONDS}"
  local status
  status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --request POST "${API_URL}/api/v1/tasks" \
    --header "Authorization: Bearer ${token}" \
    --header "Idempotency-Key: ${key}" \
    --header 'Content-Type: application/json' \
    --data '{"title":"oidc-rotation","spec":{"scope":{"tenant":"tenant-a","project":"project-a","team":"team-a"}}}')"
  [[ "${status}" == "201" ]] || {
    echo "${label}: expected HTTP 201, got ${status}" >&2
    exit 1
  }
  echo "${label}: HTTP ${status}"
}

find_component_id() {
  curl --fail --silent --show-error \
    --header "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${KEYCLOAK_URL}/admin/realms/agentteams/components?type=org.keycloak.keys.KeyProvider" \
    | tr '{' '\n' \
    | sed -n "/\"name\":\"${COMPONENT_NAME}\"/s/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

TOKEN_BEFORE="$(token_for alice alice-dev)"
KID_BEFORE="$(token_kid "${TOKEN_BEFORE}")"
[[ -n "${KID_BEFORE}" ]] || { echo "轮换前 token 未找到 kid" >&2; exit 1; }
post_task before-rotation "${TOKEN_BEFORE}"

RUN_ID="$(date +%s)-$$"
COMPONENT_NAME="agentteams-oidc-rotation-${RUN_ID}"
REALM_ID="$(curl --fail --silent --show-error \
  --header "Authorization: Bearer ${ADMIN_TOKEN}" \
  "${KEYCLOAK_URL}/admin/realms/agentteams" | json_first_string id)"
[[ -n "${REALM_ID}" ]] || { echo "无法读取 Keycloak realm id" >&2; exit 1; }

COMPONENT_BODY="$(printf '{"name":"%s","providerId":"rsa-generated","providerType":"org.keycloak.keys.KeyProvider","parentId":"%s","config":{"priority":["200"],"active":["true"],"enabled":["true"]}}' "${COMPONENT_NAME}" "${REALM_ID}")"
COMPONENT_HEADERS="$(curl --fail --silent --show-error --dump-header - --output /dev/null --request POST \
  "${KEYCLOAK_URL}/admin/realms/agentteams/components" \
  --header "Authorization: Bearer ${ADMIN_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data "${COMPONENT_BODY}")"

COMPONENT_ID="$(printf '%s' "${COMPONENT_HEADERS}" \
  | sed -n 's/[Ll]ocation:.*\/components\/\([^[:space:]]*\).*/\1/p' | tr -d '\r' | head -n 1)"

if [[ -z "${COMPONENT_ID}" ]]; then
  for attempt in 1 2 3 4 5; do
    COMPONENT_ID="$(find_component_id || true)"
    [[ -n "${COMPONENT_ID}" ]] && break
    sleep 1
  done
fi
[[ -n "${COMPONENT_ID}" ]] || { echo "未找到新 Keycloak key provider" >&2; exit 1; }

COMPONENT_UPDATE_BODY="$(printf '{"id":"%s","name":"%s","providerId":"rsa-generated","providerType":"org.keycloak.keys.KeyProvider","parentId":"%s","config":{"priority":["200"],"active":["true"],"enabled":["true"]}}' "${COMPONENT_ID}" "${COMPONENT_NAME}" "${REALM_ID}")"
curl --fail --silent --show-error --request PUT \
  "${KEYCLOAK_URL}/admin/realms/agentteams/components/${COMPONENT_ID}" \
  --header "Authorization: Bearer ${ADMIN_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data "${COMPONENT_UPDATE_BODY}" >/dev/null

KID_AFTER=""
# Keycloak persists the newly generated key synchronously, but token signing and
# the JWKS endpoint can observe that change on different cache/refresh cycles.
# Keep this smoke test strict about the actual kid change while allowing the
# local Kind deployment enough time to converge under a busy CI runner.
kid_deadline=$((SECONDS + 180))
until [[ -n "${KID_AFTER}" && "${KID_AFTER}" != "${KID_BEFORE}" ]]; do
  TOKEN_AFTER="$(token_for alice alice-dev)"
  KID_AFTER="$(token_kid "${TOKEN_AFTER}")"
  if (( SECONDS >= kid_deadline )); then
    echo "JWKS kid 未轮换: before=${KID_BEFORE} after=${KID_AFTER}" >&2
    exit 1
  fi
  [[ "${KID_AFTER}" == "${KID_BEFORE}" ]] && sleep 2
done

jwks_deadline=$((SECONDS + 180))
until jwks_contains_kid "${KID_AFTER}"; do
  if (( SECONDS >= jwks_deadline )); then
    echo "新 kid 未出现在 JWKS: ${KID_AFTER}" >&2
    exit 1
  fi
  sleep 2
done

post_task after-rotation "${TOKEN_AFTER}"
post_task old-token-overlap "${TOKEN_BEFORE}"
echo "OIDC_JWKS_ROTATION_OK before=${KID_BEFORE} after=${KID_AFTER}"
