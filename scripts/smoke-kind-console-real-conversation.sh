#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE="${KIND_NAMESPACE:-agentteams}"
MANAGER_PORT="${KIND_MANAGER_LOCAL_PORT:-18084}"
KEYCLOAK_PORT="${KIND_KEYCLOAK_LOCAL_PORT:-18082}"
BASE_URL="${AGENTTEAMS_MANAGER_BASE_URL:-http://127.0.0.1:${MANAGER_PORT}}"
IMAGE="${AGENTTEAMS_CONVERSATION_IMAGE:-ghcr.io/ly416123/agentteams-manager:latest}"
TOKEN="${AGENTTEAMS_API_BEARER_TOKEN:-}"
MANAGER_PID=""
KEYCLOAK_PID=""

cleanup() {
  if [[ -n "${MANAGER_PID}" ]]; then
    kill "${MANAGER_PID}" >/dev/null 2>&1 || true
    wait "${MANAGER_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${KEYCLOAK_PID}" ]]; then
    kill "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
    wait "${KEYCLOAK_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

for command_name in curl jq kubectl python3; do
  command -v "${command_name}" >/dev/null || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

if [[ -z "${AGENTTEAMS_MANAGER_BASE_URL:-}" ]]; then
  kubectl -n "${NAMESPACE}" port-forward \
    service/agentteams-agentteams-java-manager "${MANAGER_PORT}:8080" \
    >/tmp/agentteams-console-real-conversation-manager.log 2>&1 &
  MANAGER_PID=$!
  for attempt in $(seq 1 60); do
    if curl --silent --fail "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
      break
    fi
    if [[ "${attempt}" == 60 ]]; then
      echo "Manager did not become ready at ${BASE_URL}" >&2
      exit 1
    fi
    sleep 1
  done
fi

if [[ -z "${TOKEN}" ]]; then
  kubectl -n "${NAMESPACE}" get service/keycloak >/dev/null 2>&1 || {
    echo "Keycloak is required when AGENTTEAMS_API_BEARER_TOKEN is unset" >&2
    exit 1
  }
  kubectl -n "${NAMESPACE}" port-forward service/keycloak "${KEYCLOAK_PORT}:8080" \
    >/tmp/agentteams-console-real-conversation-keycloak.log 2>&1 &
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
fi

AGENTTEAMS_API_BEARER_TOKEN="${TOKEN}" \
  python3 "${ROOT_DIR}/scripts/run-kind-qwenpaw-conversation-acceptance.py" \
    --base-url "${BASE_URL}" --image "${IMAGE}"
echo "CONSOLE_REAL_CONVERSATION_OK"
