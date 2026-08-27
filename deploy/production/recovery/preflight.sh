#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf '%s\n' 'RECOVERY_PREFLIGHT_FAIL' >&2
  exit 1
}

environment=''
backup_id=''
restore_point=''
endpoint=''
manifest_digest=''

while (($# > 0)); do
  case "$1" in
    --environment)
      (($# >= 2)) || fail
      environment="$2"
      shift 2
      ;;
    --backup-id)
      (($# >= 2)) || fail
      backup_id="$2"
      shift 2
      ;;
    --restore-point)
      (($# >= 2)) || fail
      restore_point="$2"
      shift 2
      ;;
    --endpoint)
      (($# >= 2)) || fail
      endpoint="$2"
      shift 2
      ;;
    --manifest-digest)
      (($# >= 2)) || fail
      manifest_digest="$2"
      shift 2
      ;;
    *)
      fail
      ;;
  esac
done

[[ "$environment" == 'production' ]] || fail

# IDs are labels only. They cannot contain path separators, whitespace, or
# traversal markers and are deliberately never echoed by this script.
[[ "$backup_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || fail
[[ "$backup_id" != *'..'* ]] || fail

# Require a real UTC instant, not merely a string that resembles one.
[[ "$restore_point" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail
if ! python3 - "$restore_point" <<'PY'
from datetime import datetime
import sys

try:
    datetime.strptime(sys.argv[1], "%Y-%m-%dT%H:%M:%SZ")
except (TypeError, ValueError):
    raise SystemExit(1)
PY
then
  fail
fi

[[ "$manifest_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail

# The endpoint is a location, never a credential carrier. URL parsing is
# performed without making a network request and without printing the value.
endpoint_lower=$(printf '%s' "$endpoint" | tr '[:upper:]' '[:lower:]')
case "$endpoint_lower" in
  *secret*|*password*|*passwd*|*token*|*credential*|*apikey*|*api-key*|*access_key*|*access-key*)
    fail
    ;;
esac
if ! python3 - "$endpoint" <<'PY'
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
if any(ord(char) < 0x20 or char == "\x7f" for char in value):
    raise SystemExit(1)

parts = urlsplit(value)
if parts.scheme not in {"s3", "https"}:
    raise SystemExit(1)
if not parts.netloc or parts.username is not None or parts.password is not None:
    raise SystemExit(1)
if parts.query or parts.fragment:
    raise SystemExit(1)
if not parts.hostname:
    raise SystemExit(1)
try:
    parts.port
except ValueError:
    raise SystemExit(1)
PY
then
  fail
fi

printf '%s\n' 'RECOVERY_PREFLIGHT_OK'
