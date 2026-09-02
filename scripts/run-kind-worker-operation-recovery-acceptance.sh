#!/usr/bin/env bash
set -Eeuo pipefail

# Real OIDC Worker rollout recovery acceptance for the development Kind cluster.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
KEYCLOAK_PORT=${AGENTTEAMS_E2E_OIDC_PORT:-18082}
API_PORT=${AGENTTEAMS_E2E_API_PORT:-18081}
PROJECT_ID=${AGENTTEAMS_E2E_PROJECT_ID:-00000000-0000-0000-0000-000000000025}
EXPECTED_REPLICAS=${AGENTTEAMS_WORKER_REPLICAS:-1}
KEYCLOAK_SERVICE=${KIND_KEYCLOAK_SERVICE:-keycloak}
CONTROL_PLANE_SERVICE=${AGENTTEAMS_CONTROL_PLANE_SERVICE:-agentteams-agentteams-java-control-plane}
: "${AGENTTEAMS_E2E_USERNAME:?set a non-production OIDC username}"
: "${AGENTTEAMS_E2E_PASSWORD:?set a non-production OIDC password}"

[[ "${EXPECTED_REPLICAS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "AGENTTEAMS_WORKER_REPLICAS must be a positive integer" >&2
  exit 1
}

for command_name in curl jq kubectl; do
  command -v "${command_name}" >/dev/null 2>&1 || { echo "${command_name} is required" >&2; exit 1; }
done

kubectl -n "${NAMESPACE}" port-forward "service/${KEYCLOAK_SERVICE}" \
  "${KEYCLOAK_PORT}:8080" >/tmp/agentteams-worker-recovery-keycloak.log 2>&1 &
KEYCLOAK_PID=$!
kubectl -n "${NAMESPACE}" port-forward "service/${CONTROL_PLANE_SERVICE}" \
  "${API_PORT}:8080" >/tmp/agentteams-worker-recovery-control-plane.log 2>&1 &
CONTROL_PLANE_PID=$!
CR_NAME=""
ORIGINAL_REPLICAS=""
cleanup() {
  local exit_code=$?
  set +e
  if [[ -n "${CR_NAME}" && -n "${ORIGINAL_REPLICAS}" && "${EXPECTED_REPLICAS}" != "${ORIGINAL_REPLICAS}" ]]; then
    kubectl -n "${NAMESPACE}" patch worker "${CR_NAME}" --type=merge \
      -p "{\"spec\":{\"replicas\":${ORIGINAL_REPLICAS}}}" >/dev/null 2>&1 || true
  fi
  kill "${KEYCLOAK_PID}" "${CONTROL_PLANE_PID}" >/dev/null 2>&1 || true
  wait "${KEYCLOAK_PID}" "${CONTROL_PLANE_PID}" >/dev/null 2>&1 || true
  exit "${exit_code}"
}
trap cleanup EXIT

KEYCLOAK_URL="http://127.0.0.1:${KEYCLOAK_PORT}"
API_URL="http://127.0.0.1:${API_PORT}"
for attempt in $(seq 1 60); do
  if curl --silent --fail "${KEYCLOAK_URL}/realms/agentteams/.well-known/openid-configuration" >/dev/null \
      && curl --silent --fail "${API_URL}/actuator/health" >/dev/null; then
    break
  fi
  if [[ "${attempt}" == 60 ]]; then echo "Keycloak or Control Plane did not become ready" >&2; exit 1; fi
  sleep 1
done

TOKEN_RESPONSE=$(curl --silent --fail --show-error -X POST \
  "${KEYCLOAK_URL}/realms/agentteams/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' --data-urlencode 'client_id=agentteams-api' \
  --data-urlencode "username=${AGENTTEAMS_E2E_USERNAME}" --data-urlencode "password=${AGENTTEAMS_E2E_PASSWORD}")
TOKEN=$(jq -er '.access_token' <<<"${TOKEN_RESPONSE}")
WORKERS=$(curl --silent --fail -H "Authorization: Bearer ${TOKEN}" \
  "${API_URL}/api/v1/agents?projectId=${PROJECT_ID}")
WORKER_ID=$(jq -er '.items[] | select(.phase == "READY" and (.name | startswith("template-worker-"))) | .id' <<<"${WORKERS}" | head -n 1)
WORKER_VERSION=$(jq -er --arg id "${WORKER_ID}" '.items[] | select(.id == $id) | .version' <<<"${WORKERS}")
CR_NAME="worker-$(printf '%s' "${WORKER_ID}" | tr -d '-')"
STABLE_SPEC=$(kubectl -n "${NAMESPACE}" get worker "${CR_NAME}" -o json | jq -c '.spec')
ORIGINAL_REPLICAS=$(jq -er '.replicas' <<<"${STABLE_SPEC}")

if (( EXPECTED_REPLICAS != ORIGINAL_REPLICAS )); then
  kubectl -n "${NAMESPACE}" patch worker "${CR_NAME}" --type=merge \
    -p "{\"spec\":{\"replicas\":${EXPECTED_REPLICAS}}}" >/dev/null
  for attempt in $(seq 1 60); do
    deployment_replicas=$(kubectl -n "${NAMESPACE}" get deployment "${CR_NAME}" \
      -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
    ready_replicas=$(kubectl -n "${NAMESPACE}" get deployment "${CR_NAME}" \
      -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
    [[ "${deployment_replicas}" == "${EXPECTED_REPLICAS}" \
      && "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]] && break
    sleep 1
  done
  [[ "${deployment_replicas}" == "${EXPECTED_REPLICAS}" \
    && "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]] || {
    echo "Worker Deployment did not reach ${EXPECTED_REPLICAS} ready replicas" >&2
    exit 1
  }
  POD_NAME=$(kubectl -n "${NAMESPACE}" get pod \
    -l "app.kubernetes.io/name=agentteams-worker,agentteams.io/agent-id=${WORKER_ID}" \
    -o jsonpath='{.items[0].metadata.name}')
  [[ -n "${POD_NAME}" ]] || { echo "No Worker Pod found for replica repair" >&2; exit 1; }
  kubectl -n "${NAMESPACE}" delete pod "${POD_NAME}" --wait=false >/dev/null
  for attempt in $(seq 1 60); do
    ready_replicas=$(kubectl -n "${NAMESPACE}" get deployment "${CR_NAME}" \
      -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
    [[ "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]] && break
    sleep 1
  done
  [[ "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]] || {
    echo "Worker Deployment did not repair the deleted replica" >&2
    exit 1
  }
  STABLE_SPEC=$(kubectl -n "${NAMESPACE}" get worker "${CR_NAME}" -o json | jq -c '.spec')
fi
# A newly created replica registers once while the Deployment scales. Read the
# lifecycle version after that registration settles; heartbeat refreshes do not
# change it, so the operation guard remains stable afterwards.
WORKERS=$(curl --silent --fail -H "Authorization: Bearer ${TOKEN}" \
  "${API_URL}/api/v1/agents?projectId=${PROJECT_ID}")
WORKER_VERSION=$(jq -er --arg id "${WORKER_ID}" '.items[] | select(.id == $id) | .version' <<<"${WORKERS}")
STABLE_IMAGE=$(jq -er '.image' <<<"${STABLE_SPEC}")
STABLE_CONFIG=$(jq -er '.configRevision // ""' <<<"${STABLE_SPEC}")

REQUEST_KEY="kind-worker-recovery-$(date +%s)-${WORKER_ID}"
REQUEST_BODY=$(jq -cn --argjson expectedVersion "${WORKER_VERSION}" --argjson stable "${STABLE_SPEC}" \
  '{expectedVersion:$expectedVersion,imageDigest:"ghcr.io/ly416123/agentteams-agent-worker:kind-recovery-missing",runtime:"qwenpaw",configRevision:"kind-recovery-bad",secretGeneration:"kind-recovery-secret",previousStableSpec:($stable|tojson)}')
ROLLOUT=$(curl --silent --fail --show-error -X POST \
  "${API_URL}/api/v1/agents/${WORKER_ID}/operations/rollout?projectId=${PROJECT_ID}" \
  -H "Authorization: Bearer ${TOKEN}" -H "Idempotency-Key: ${REQUEST_KEY}" \
  -H 'Content-Type: application/json' --data "${REQUEST_BODY}")
OPERATION_ID=$(jq -er '.id' <<<"${ROLLOUT}")

# Controlled Kind-only failure injection; the scheduler owns classification.
kubectl -n "${NAMESPACE}" exec postgresql-0 -- psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c \
  "UPDATE worker_operations SET lease_expires_at=now()-interval '1 second' WHERE id='${OPERATION_ID}' AND status IN ('PENDING','RUNNING');" \
  >/dev/null

operation_status=""
lease_expiry_event=""
for attempt in $(seq 1 60); do
  operation_status=$(kubectl -n "${NAMESPACE}" exec postgresql-0 -- psql -U agentteams -d agentteams -Atc \
    "SELECT status || '|' || coalesce(failure_category,'') FROM worker_operations WHERE id='${OPERATION_ID}'")
  lease_expiry_event=$(kubectl -n "${NAMESPACE}" exec postgresql-0 -- psql -U agentteams -d agentteams -Atc \
    "SELECT count(*) FROM domain_events WHERE aggregate_type='worker_operation' AND aggregate_id='${OPERATION_ID}' AND event_type='WorkerOperationLeaseExpired'")
  [[ "${operation_status}" == 'FAILED|OPERATION_LEASE_EXPIRED' || "${operation_status}" == 'ROLLED_BACK|' ]] && break
  sleep 1
done
[[ "${operation_status}" == 'FAILED|OPERATION_LEASE_EXPIRED' || "${operation_status}" == 'ROLLED_BACK|' ]] \
  && [[ "${lease_expiry_event}" == 1 ]] || {
  echo "Worker operation was not classified as lease-expired: ${operation_status}" >&2
  exit 1
}

final_status=""
spec_image=""
spec_config=""
worker_phase=""
ready_replicas=""
for attempt in $(seq 1 60); do
  final_status=$(kubectl -n "${NAMESPACE}" exec postgresql-0 -- psql -U agentteams -d agentteams -Atc \
    "SELECT status FROM worker_operations WHERE id='${OPERATION_ID}'")
  worker_phase=$(kubectl -n "${NAMESPACE}" get worker "${CR_NAME}" -o jsonpath='{.status.phase}' 2>/dev/null || true)
  spec_image=$(kubectl -n "${NAMESPACE}" get worker "${CR_NAME}" -o jsonpath='{.spec.image}' 2>/dev/null || true)
  spec_config=$(kubectl -n "${NAMESPACE}" get worker "${CR_NAME}" -o jsonpath='{.spec.configRevision}' 2>/dev/null || true)
  ready_replicas=$(kubectl -n "${NAMESPACE}" get deployment "${CR_NAME}" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
  if [[ "${final_status}" == 'ROLLED_BACK' && "${spec_image}" == "${STABLE_IMAGE}" \
      && "${spec_config}" == "${STABLE_CONFIG}" && "${worker_phase}" == 'Ready' \
      && "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]]; then
    break
  fi
  sleep 1
done
[[ "${final_status}" == 'ROLLED_BACK' ]] || {
  echo "Worker operation was not rolled back by Operator: ${final_status}" >&2
  exit 1
}
[[ "${spec_image}" == "${STABLE_IMAGE}" && "${spec_config}" == "${STABLE_CONFIG}" \
    && "${worker_phase}" == 'Ready' ]] || {
  echo "Worker spec did not return to the stable snapshot" >&2
  exit 1
}
[[ "${ready_replicas}" == "${EXPECTED_REPLICAS}" ]] || {
  echo "Worker Deployment did not become ready with ${EXPECTED_REPLICAS} replicas after recovery" >&2
  exit 1
}
printf 'KIND_WORKER_RECOVERY_OK worker=%s operation=%s replicas=%s failure=OPERATION_LEASE_EXPIRED status=%s\n' \
  "${WORKER_ID}" "${OPERATION_ID}" "${EXPECTED_REPLICAS}" "${final_status}"
