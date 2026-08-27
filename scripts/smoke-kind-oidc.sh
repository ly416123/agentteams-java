#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${KIND_NAMESPACE:-agentteams}
KEYCLOAK_PORT=${KIND_KEYCLOAK_LOCAL_PORT:-18080}
API_PORT=${KIND_CONTROL_PLANE_LOCAL_PORT:-18081}
KEYCLOAK_SERVICE=${KIND_KEYCLOAK_SERVICE:-keycloak}
CONTROL_PLANE_SERVICE=${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}
LOG_DIR=${TMPDIR:-/tmp}
KEYCLOAK_LOG="${LOG_DIR}/agentteams-keycloak-port-forward.log"
API_LOG="${LOG_DIR}/agentteams-oidc-api-port-forward.log"
KEYCLOAK_PID=""
API_PID=""

cleanup() {
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

token_for() {
  local username="$1"
  local password="$2"
  local response
  local token
  response="$(curl --fail --silent --show-error -X POST \
    "${KEYCLOAK_URL}/realms/agentteams/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=agentteams-api' \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}")"
  token="$(printf '%s' "${response}" | tr -d '\r\n' | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
  [[ -n "${token}" ]] || {
    echo "Keycloak 未返回 access_token（用户 ${username}）" >&2
    exit 1
  }
  printf '%s\n' "${token}"
}

export API_URL
export TOKEN="$(token_for alice alice-dev)"
export TOKEN_NO_PERMISSION="$(token_for reader reader-dev)"
export TOKEN_CROSS_SCOPE="$(token_for tenant-b-user tenant-b-dev)"
export SCOPE_TENANT=tenant-a
export SCOPE_PROJECT=project-a
export SCOPE_TEAM=team-a

# Keycloak's JWT subject is an implementation-generated stable identifier, not
# necessarily the username. Seed the membership using the exact verified
# subject that the Control Plane will receive, never a caller-supplied name.
TOKEN_SUBJECT="$(python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; print(json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["sub"])' "${TOKEN}")"
[[ "${TOKEN_SUBJECT}" =~ ^[A-Za-z0-9._:-]{1,255}$ ]] || {
  echo "OIDC token subject has an unexpected format" >&2
  exit 1
}
DB_PASSWORD=$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
  -o jsonpath='{.data.password}' | base64 --decode)
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "
    INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
    SELECT 'tenant-a', id, '${TOKEN_SUBJECT}', 'DEVELOPER', 'ACTIVE', now(), now(), 0
      FROM projects WHERE tenant_id = 'tenant-a' AND name = 'project-a'
    ON CONFLICT (tenant_id, project_id, subject)
    DO UPDATE SET role = 'DEVELOPER', status = 'ACTIVE', updated_at = now();
  " >/dev/null
echo "OIDC authorization membership fixture ready: tenant-a/project-a subject=${TOKEN_SUBJECT} role=DEVELOPER"

bash "${ROOT}/scripts/validate-oidc-acceptance.sh"
echo "KIND_OIDC_SMOKE_OK"
