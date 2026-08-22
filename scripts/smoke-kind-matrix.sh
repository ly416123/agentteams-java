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

echo "KIND_MATRIX_APPSERVICE_OK"
