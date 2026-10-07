#!/usr/bin/env bash
set -euo pipefail

NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
KUBECTL=${KUBECTL_BIN:-kubectl}

for dependency in "$KUBECTL" openssl base64; do
  command -v "$dependency" >/dev/null 2>&1 || {
    echo "Missing required command: $dependency" >&2
    exit 1
  }
done

"$KUBECTL" get namespace "$NAMESPACE" >/dev/null
"$KUBECTL" -n "$NAMESPACE" get statefulset/postgresql secret/agentteams-database >/dev/null
"$KUBECTL" -n "$NAMESPACE" rollout status statefulset/postgresql --timeout=180s >/dev/null

umask 077
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

if "$KUBECTL" -n "$NAMESPACE" get secret/keycloak-database >/dev/null 2>&1; then
  DB_USERNAME=$("$KUBECTL" -n "$NAMESPACE" get secret/keycloak-database \
    -o jsonpath='{.data.username}' | base64 --decode)
  DB_PASSWORD=$("$KUBECTL" -n "$NAMESPACE" get secret/keycloak-database \
    -o jsonpath='{.data.password}' | base64 --decode)
  [[ "$DB_USERNAME" == keycloak && "$DB_PASSWORD" =~ ^[A-Za-z0-9+/=]+$ ]] || {
    echo "Secret/keycloak-database must contain username=keycloak and a base64-safe password." >&2
    exit 1
  }
else
  DB_USERNAME=keycloak
  DB_PASSWORD=$(openssl rand -base64 36 | tr -d '\n')
fi

[[ "$DB_PASSWORD" =~ ^[A-Za-z0-9+/=]+$ ]] || {
  echo "Database password must contain only base64 characters." >&2
  exit 1
}

if ! "$KUBECTL" -n "$NAMESPACE" get secret/keycloak-database >/dev/null 2>&1; then
  printf '%s' "$DB_USERNAME" > "$TEMP_DIR/username"
  printf '%s' "$DB_PASSWORD" > "$TEMP_DIR/password"
  "$KUBECTL" -n "$NAMESPACE" create secret generic keycloak-database \
    --from-file=username="$TEMP_DIR/username" \
    --from-file=password="$TEMP_DIR/password" \
    --dry-run=client -o yaml | "$KUBECTL" apply -f - >/dev/null
fi

{
  printf "\\set keycloak_password '%s'\n" "$DB_PASSWORD"
  cat <<'SQL'
SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'keycloak_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak')
\gexec
ALTER ROLE keycloak WITH LOGIN PASSWORD :'keycloak_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
ALTER DATABASE keycloak OWNER TO keycloak;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE keycloak TO keycloak;
SQL
} > "$TEMP_DIR/bootstrap.sql"

"$KUBECTL" -n "$NAMESPACE" exec -i statefulset/postgresql -- \
  sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' \
  < "$TEMP_DIR/bootstrap.sql" >/dev/null

echo "Keycloak database and Secret are ready in namespace $NAMESPACE."
