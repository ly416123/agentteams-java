#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
NAMESPACE=${KIND_NAMESPACE:-agentteams}
KEYCLOAK_PORT=${KIND_KEYCLOAK_LOCAL_PORT:-18082}
KEYCLOAK_SERVICE=${KIND_KEYCLOAK_SERVICE:-keycloak}
CONTROL_PLANE_SERVICE=${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}
WORKER_NAME=${AGENTTEAMS_WORKER_NAME:-qwenpaw-worker}
TENANT=${AGENTTEAMS_API_TENANT:-tenant-a}
PROJECT=${AGENTTEAMS_API_PROJECT:-project-a}
TEAM=${AGENTTEAMS_API_TEAM:-team-a}
KEYCLOAK_PID=""

cleanup() {
  if [[ -n "${KEYCLOAK_PID}" ]]; then
    kill "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
    wait "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

for command_name in curl jq kubectl python3; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

if [[ -z "${AGENTTEAMS_API_BEARER_TOKEN:-}" ]]; then
  kubectl -n "${NAMESPACE}" get service/"${KEYCLOAK_SERVICE}" >/dev/null
  kubectl -n "${NAMESPACE}" port-forward "service/${KEYCLOAK_SERVICE}" \
    "${KEYCLOAK_PORT}:8080" >/dev/null 2>&1 &
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
    --data-urlencode "username=${AGENTTEAMS_API_USERNAME:-quota-admin}" \
    --data-urlencode "password=${AGENTTEAMS_API_PASSWORD:-quota-admin-dev}")"
  TOKEN="$(jq -er '.access_token' <<<"${token_response}")"
else
  TOKEN="${AGENTTEAMS_API_BEARER_TOKEN}"
fi

TOKEN_SUBJECT="$(python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; print(json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["sub"])' "${TOKEN}")"
AGENT_ID="${AGENTTEAMS_AGENT_ID:?AGENTTEAMS_AGENT_ID is required}"
for scope_value in "${TENANT}" "${PROJECT}" "${TEAM}" "${TOKEN_SUBJECT}"; do
  [[ "${scope_value}" =~ ^[A-Za-z0-9._:@/-]{1,255}$ ]] || {
    echo "scope contains unsupported characters" >&2
    exit 1
  }
done
WORKER_AGENT_ID=$(kubectl -n "${NAMESPACE}" get worker "${WORKER_NAME}" -o jsonpath='{.spec.agentId}')
[[ "${AGENT_ID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || {
  echo "AGENTTEAMS_AGENT_ID must be a UUID" >&2
  exit 1
}
[[ "${WORKER_AGENT_ID}" == "${AGENT_ID}" ]] || {
  echo "Worker ${WORKER_NAME} is registered as ${WORKER_AGENT_ID}, expected ${AGENT_ID}" >&2
  exit 1
}
kubectl -n "${NAMESPACE}" patch worker "${WORKER_NAME}" --type merge -p \
  "{\"spec\":{\"env\":{\"AGENTTEAMS_QUOTA_REMOTE_ENABLED\":\"true\",\"AGENTTEAMS_SCOPE_TENANT\":\"${TENANT}\",\"AGENTTEAMS_SCOPE_PROJECT\":\"${PROJECT}\"}}}" >/dev/null
kubectl -n "${NAMESPACE}" rollout status "deployment/${WORKER_NAME}" --timeout=180s >/dev/null
kubectl -n "${NAMESPACE}" wait --for=jsonpath='{.status.phase}'=Ready \
  "worker/${WORKER_NAME}" --timeout=180s >/dev/null
DB_PASSWORD="$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
  -o jsonpath='{.data.password}' | base64 --decode)"
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 \
  -U agentteams -d agentteams -c "
    INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
    SELECT '${TENANT}', id, '${TOKEN_SUBJECT}', 'ADMIN', 'ACTIVE', now(), now(), 0
      FROM projects WHERE tenant_id = '${TENANT}' AND name = '${PROJECT}'
    ON CONFLICT (tenant_id, project_id, subject)
    DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = now();
    INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team, created_at, updated_at)
    SELECT 'WORKER', id, '${TENANT}', '${PROJECT}', '${TEAM}', now(), now()
      FROM agents WHERE id = '${AGENT_ID}'
    ON CONFLICT (resource_type, resource_id)
    DO UPDATE SET tenant_id = EXCLUDED.tenant_id, project_id = EXCLUDED.project_id,
                  team = EXCLUDED.team, updated_at = EXCLUDED.updated_at;
  " >/dev/null

AGENTTEAMS_API_BEARER_TOKEN="${TOKEN}" \
  python3 "${ROOT}/scripts/run-kind-worker-quota-admission.py" \
    --namespace "${NAMESPACE}" \
    --control-plane-service "${CONTROL_PLANE_SERVICE}" \
    --tenant "${TENANT}" --project "${PROJECT}" --team "${TEAM}" \
    --agent-id "${AGENT_ID}"
echo "KIND_OIDC_WORKER_QUOTA_ADMISSION_OK"
