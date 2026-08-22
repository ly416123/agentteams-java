#!/usr/bin/env bash
set -Eeuo pipefail

for command_name in curl jq; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

: "${API_URL:?API_URL is required}"
: "${TOKEN:?TOKEN is required}"
: "${SCOPE_TENANT:?SCOPE_TENANT is required}"
: "${SCOPE_PROJECT:?SCOPE_PROJECT is required}"
: "${SCOPE_TEAM:?SCOPE_TEAM is required}"

API_URL="${API_URL%/}"
RUN_ID="$(date +%s)-$$"
PAYLOAD="$(jq -cn \
  --arg title "oidc-acceptance-${RUN_ID}" \
  --arg tenant "${SCOPE_TENANT}" \
  --arg project "${SCOPE_PROJECT}" \
  --arg team "${SCOPE_TEAM}" \
  '{title: $title, spec: {scope: {tenant: $tenant, project: $project, team: $team}}}')"

post_task() {
  local label="$1"
  local expected_status="$2"
  local token="${3:-}"
  local idempotency_key="oidc-acceptance-${label}-${RUN_ID}"
  local -a curl_args=(
    --silent --show-error --output /dev/null --write-out '%{http_code}'
    --request POST "${API_URL}/api/v1/tasks"
    --header "Idempotency-Key: ${idempotency_key}"
    --header 'Content-Type: application/json'
    --data "${PAYLOAD}"
  )
  if [[ -n "${token}" ]]; then
    curl_args+=(--header "Authorization: Bearer ${token}")
  fi

  local actual_status
  actual_status="$(curl "${curl_args[@]}")"
  if [[ "${actual_status}" != "${expected_status}" ]]; then
    echo "${label}: expected HTTP ${expected_status}, got ${actual_status}" >&2
    exit 1
  fi
  echo "${label}: HTTP ${actual_status}"
}

echo "OIDC acceptance against ${API_URL}"
post_task missing-bearer 401
post_task invalid-bearer 401 'invalid.invalid.invalid'
post_task matching-permission 201 "${TOKEN}"

if [[ -n "${TOKEN_NO_PERMISSION:-}" ]]; then
  post_task missing-permission 403 "${TOKEN_NO_PERMISSION}"
else
  echo "missing-permission: skipped (set TOKEN_NO_PERMISSION to enable)"
fi

if [[ -n "${TOKEN_CROSS_SCOPE:-}" ]]; then
  post_task cross-scope 403 "${TOKEN_CROSS_SCOPE}"
else
  echo "cross-scope: skipped (set TOKEN_CROSS_SCOPE to enable)"
fi

if [[ -n "${TOKEN_ROTATED:-}" ]]; then
  post_task rotated-kid 201 "${TOKEN_ROTATED}"
else
  echo "rotated-kid: skipped (set TOKEN_ROTATED after publishing the new JWKS key)"
fi

echo "OIDC acceptance passed"
