#!/usr/bin/env bash
set -Eeuo pipefail

for command_name in curl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} is required" >&2
    exit 1
  }
done

: "${API_HEALTH_URL:?API_HEALTH_URL is required}"
: "${OIDC_ISSUER_URI:?OIDC_ISSUER_URI is required}"
: "${OIDC_JWKS_URI:?OIDC_JWKS_URI is required}"

CURL_ARGS=(--fail --silent --show-error --location --max-time "${CURL_MAX_TIME_SECONDS:-10}")

check_url() {
  local label="$1"
  local url="$2"
  curl "${CURL_ARGS[@]}" --output /dev/null "${url}" || {
    echo "PRODUCTION_ENDPOINTS_FAIL: ${label} is unreachable: ${url}" >&2
    exit 1
  }
  echo "${label}: OK"
}

check_url api-readiness "${API_HEALTH_URL}"
check_url oidc-discovery "${OIDC_ISSUER_URI%/}/.well-known/openid-configuration"
check_url oidc-jwks "${OIDC_JWKS_URI}"

if [[ -n "${MATRIX_HOMESERVER_URL:-}" ]]; then
  check_url matrix-homeserver "${MATRIX_HOMESERVER_URL%/}/_matrix/client/versions"
else
  echo "matrix-homeserver: skipped (set MATRIX_HOMESERVER_URL to enable)"
fi

echo "PRODUCTION_ENDPOINTS_OK"
