#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}

for command_name in helm kubectl kind; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "缺少 ${command_name}，请先安装。" >&2
    exit 1
  }
done
kind get clusters | grep -Fxq agentteams || {
  echo "未找到 agentteams Kind 集群，请先执行 deploy/install-kind-dev.sh。" >&2
  exit 1
}

"${ROOT}/deploy/bootstrap-kind-keycloak.sh"

helm lint "${ROOT}/deploy/helm/agentteams-java"
HELM_VALUES=(
  -f "${ROOT}/deploy/helm/kind-values.yaml"
  -f "${ROOT}/deploy/helm/kind-oidc-values.yaml"
)
if kubectl -n "${NAMESPACE}" get secret matrix-appservice >/dev/null 2>&1; then
  # Preserve Matrix AppService authentication when the Matrix dev link is already installed.
  HELM_VALUES+=( -f "${ROOT}/deploy/helm/kind-matrix-values.yaml" )
fi
helm upgrade --install agentteams "${ROOT}/deploy/helm/agentteams-java" \
  --namespace "${NAMESPACE}" --create-namespace --wait --timeout 5m \
  "${HELM_VALUES[@]}"

kubectl -n "${NAMESPACE}" rollout status \
  deployment/agentteams-agentteams-java-control-plane --timeout=180s

# The OIDC smoke users authenticate through Keycloak, while task authorization
# is decided from the Control Plane project membership facts. Keep this fixture
# idempotent and scoped to the documented tenant/project used by the smoke.
DB_PASSWORD=$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
  -o jsonpath='{.data.password}' | base64 --decode)
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "
    INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
    VALUES ('00000000-0000-0000-0000-000000000025', 'tenant-a', 'project-a', 'ACTIVE', 'alice', now(), now(), 0)
    ON CONFLICT (tenant_id, name) DO UPDATE SET status = 'ACTIVE', updated_at = now();
    INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
    SELECT 'tenant-a', id, 'alice', 'DEVELOPER', 'ACTIVE', now(), now(), 0
      FROM projects WHERE tenant_id = 'tenant-a' AND name = 'project-a'
    ON CONFLICT (tenant_id, project_id, subject)
    DO UPDATE SET role = 'DEVELOPER', status = 'ACTIVE', updated_at = now();
  " >/dev/null
echo "OIDC authorization fixture ready: tenant-a/project-a alice=DEVELOPER"

"${ROOT}/scripts/smoke-kind-oidc.sh"
