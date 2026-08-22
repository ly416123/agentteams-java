#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APIKEY_FILE="${DEEPSEEK_APIKEY_FILE:-${ROOT_DIR}/apikey}"
NAMESPACE="${KIND_NAMESPACE:-agentteams}"
MODEL="${DEEPSEEK_MODEL:-deepseek-v4-flash}"
LOCAL_PORT="${QWENPAW_LOCAL_PORT:-18088}"
BASE_URL="${QWENPAW_BASE_URL:-http://127.0.0.1:${LOCAL_PORT}}"
TMP_DIR="${TMPDIR:-/tmp}"
PORT_FORWARD_PID=""
MODELS_FILE=""

cleanup() {
  if [[ -n "${MODELS_FILE}" && -f "${MODELS_FILE}" ]]; then
    rm -f "${MODELS_FILE}"
  fi
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ ! -f "${APIKEY_FILE}" ]]; then
  echo "DeepSeek API key file not found: ${APIKEY_FILE}" >&2
  exit 1
fi
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    command -v icacls >/dev/null || {
      echo "icacls is required to validate Windows API key permissions" >&2
      exit 1
    }
    command -v cygpath >/dev/null || {
      echo "cygpath is required to validate Windows API key permissions" >&2
      exit 1
    }
    WINDOWS_APIKEY_FILE="$(cygpath -w "${APIKEY_FILE}")"
    KEY_ACL="$(icacls "${WINDOWS_APIKEY_FILE}" 2>&1)" || {
      echo "could not inspect Windows API key permissions: ${APIKEY_FILE}" >&2
      exit 1
    }
    if printf '%s\n' "${KEY_ACL}" | grep -Eiq '(^|[[:space:]])(Everyone|Authenticated Users|BUILTIN\\Users|Users):'; then
      echo "DeepSeek API key file must not be readable by broad Windows principals: ${APIKEY_FILE}" >&2
      exit 1
    fi
    ;;
  *)
    if [[ "$(uname -s)" == "Darwin" ]]; then
      KEY_MODE="$(stat -f '%Lp' "${APIKEY_FILE}")"
    else
      KEY_MODE="$(stat -c '%a' "${APIKEY_FILE}")"
    fi
    if (( (8#${KEY_MODE} & 77) != 0 )); then
      echo "DeepSeek API key file must not be group/world accessible: ${APIKEY_FILE}" >&2
      exit 1
    fi
    ;;
esac
KEY_LINE_COUNT=0
DEEPSEEK_API_KEY=""
while IFS= read -r KEY_LINE || [[ -n "${KEY_LINE}" ]]; do
  KEY_LINE_COUNT=$((KEY_LINE_COUNT + 1))
  DEEPSEEK_API_KEY="${KEY_LINE}"
done < "${APIKEY_FILE}"
if [[ "${KEY_LINE_COUNT}" -ne 1 || -z "${DEEPSEEK_API_KEY//[[:space:]]/}" ]]; then
  echo "DeepSeek API key file must contain exactly one non-empty line" >&2
  exit 1
fi
if [[ "${DEEPSEEK_API_KEY}" == *[[:space:]]* ]]; then
  echo "DeepSeek API key must not contain whitespace" >&2
  exit 1
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

if [[ -z "${QWENPAW_BASE_URL:-}" ]]; then
  command -v kubectl >/dev/null || { echo "kubectl is required" >&2; exit 1; }
  PF_LOG="${TMP_DIR}/agentteams-qwenpaw-port-forward.log"
  kubectl -n "${NAMESPACE}" port-forward "svc/qwenpaw" "${LOCAL_PORT}:8088" >"${PF_LOG}" 2>&1 &
  PORT_FORWARD_PID=$!
fi

request() {
  local method="$1"
  local path="$2"
  local output_file="$3"
  local status
  shift 3
  if [[ "$#" -eq 0 ]]; then
    status="$(curl --silent --show-error --connect-timeout 3 --max-time 30 \
      -X "${method}" -o "${output_file}" -w '%{http_code}' "${BASE_URL}${path}")" || {
      echo "QwenPaw request failed: ${method} ${path}" >&2
      return 1
    }
  else
    status="$(printf '%s' "$1" | curl --silent --show-error --connect-timeout 3 --max-time 30 \
      -X "${method}" -H 'Content-Type: application/json' --data-binary @- \
      -o "${output_file}" -w '%{http_code}' "${BASE_URL}${path}")" || {
      echo "QwenPaw request failed: ${method} ${path}" >&2
      return 1
    }
  fi
  if [[ "${status}" != 2* ]]; then
    echo "QwenPaw request returned HTTP ${status}: ${method} ${path}" >&2
    return 1
  fi
}

MODELS_FILE="$(mktemp "${TMP_DIR}/agentteams-qwenpaw-models.XXXXXX")"
for _ in {1..60}; do
  if request GET /api/models "${MODELS_FILE}"; then
    break
  fi
  sleep 1
done
if ! jq -e 'if type == "array" then . else (.providers // []) end | any(.[]; (.id // .provider_id) == "deepseek")' \
    "${MODELS_FILE}" >/dev/null; then
  echo "QwenPaw did not expose the built-in deepseek provider" >&2
  exit 1
fi

CONFIG_FILE="$(mktemp "${TMP_DIR}/agentteams-qwenpaw-config.XXXXXX")"
trap 'rm -f "${CONFIG_FILE}" "${MODELS_FILE}"; if [[ -n "${PORT_FORWARD_PID}" ]]; then kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true; wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true; fi' EXIT
jq -n --arg key "${DEEPSEEK_API_KEY}" '{api_key: $key, auto_discover: false}' >"${CONFIG_FILE}"
request PUT /api/models/deepseek/config "${CONFIG_FILE}" "$(<"${CONFIG_FILE}")"
rm -f "${CONFIG_FILE}"

if ! jq -e --arg model "${MODEL}" '
    (if type == "array" then . else (.providers // []) end)
    | map(select((.id // .provider_id) == "deepseek")) | .[0]
    | [(.models // [])[], (.extra_models // [])[]]
    | map(.id // .model // .name) | index($model) != null
  ' "${MODELS_FILE}" >/dev/null; then
  ADD_MODEL_BODY="$(jq -n --arg id "${MODEL}" --arg name "${MODEL}" \
    '{id: $id, name: $name, is_free: false}')"
  ADD_MODEL_FILE="$(mktemp "${TMP_DIR}/agentteams-qwenpaw-add-model.XXXXXX")"
  if ! request POST /api/models/deepseek/models "${ADD_MODEL_FILE}" "${ADD_MODEL_BODY}"; then
    echo "QwenPaw could not add model ${MODEL}" >&2
    exit 1
  fi
  rm -f "${ADD_MODEL_FILE}"
fi

ACTIVE_BODY="$(jq -n --arg model "${MODEL}" \
  '{provider_id: "deepseek", model: $model, scope: "global"}')"
ACTIVE_FILE="$(mktemp "${TMP_DIR}/agentteams-qwenpaw-active.XXXXXX")"
request PUT /api/models/active "${ACTIVE_FILE}" "${ACTIVE_BODY}"
if ! jq -e --arg model "${MODEL}" '
    (.active_llm.provider_id == "deepseek") and (.active_llm.model == $model)
  ' "${ACTIVE_FILE}" >/dev/null; then
  echo "QwenPaw active model verification failed" >&2
  exit 1
fi
rm -f "${ACTIVE_FILE}"

TEST_FILE="$(mktemp "${TMP_DIR}/agentteams-qwenpaw-test.XXXXXX")"
request POST /api/models/deepseek/models/test "${TEST_FILE}" \
  "$(jq -n --arg model "${MODEL}" '{model_id: $model}')"
if ! jq -e '.success == true' "${TEST_FILE}" >/dev/null; then
  echo "QwenPaw provider model test failed" >&2
  exit 1
fi
rm -f "${TEST_FILE}"

echo "QWENPAW_DEEPSEEK_OK provider=deepseek model=${MODEL}"
