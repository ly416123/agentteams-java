#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
CONTROL_PLANE_IMAGE=${AGENTTEAMS_CONTROL_PLANE_IMAGE:-}
source "$ROOT/deploy/console-deployment.sh"

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
HELM_VALUES+=( --set "console.enabled=$CONSOLE_ENABLED" )
HELM_UPGRADE_ARGS=(
  --namespace "${NAMESPACE}" --create-namespace --timeout 5m --force-conflicts --wait
)
if [[ -n "${CONTROL_PLANE_IMAGE}" ]]; then
  HELM_VALUES+=( --set-string "images.controlPlane=${CONTROL_PLANE_IMAGE}" )
  # A local dev tag can point to a newly loaded image without changing the
  # Deployment template. Apply the image first, then restart it below.
  HELM_UPGRADE_ARGS=(
    --namespace "${NAMESPACE}" --create-namespace --timeout 5m --force-conflicts
  )
fi
helm upgrade --install agentteams "${ROOT}/deploy/helm/agentteams-java" \
  "${HELM_UPGRADE_ARGS[@]}" \
  "${HELM_VALUES[@]}"
if [[ -n "${CONTROL_PLANE_IMAGE}" ]]; then
  kubectl -n "${NAMESPACE}" rollout restart deployment/agentteams-agentteams-java-control-plane
fi

kubectl -n "${NAMESPACE}" rollout status \
  deployment/agentteams-agentteams-java-control-plane --timeout=180s

# The OIDC smoke users authenticate through Keycloak, while task authorization
# is decided from the Control Plane project membership facts. Keep the project
# fixture idempotent and scoped to the documented tenant/project used by smoke;
# the membership subject is seeded after the smoke obtains the real JWT subject.
DB_PASSWORD=$(kubectl -n "${NAMESPACE}" get secret agentteams-database \
  -o jsonpath='{.data.password}' | base64 --decode)
kubectl -n "${NAMESPACE}" exec statefulset/postgresql -- env PGPASSWORD="${DB_PASSWORD}" \
  psql -v ON_ERROR_STOP=1 -U agentteams -d agentteams -c "
    INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
    VALUES ('00000000-0000-0000-0000-000000000025', 'tenant-a', 'project-a', 'ACTIVE', 'alice', now(), now(), 0)
    ON CONFLICT (tenant_id, name) DO UPDATE SET status = 'ACTIVE', updated_at = now();
  " >/dev/null
echo "OIDC authorization project fixture ready: tenant-a/project-a"

"${ROOT}/scripts/smoke-kind-oidc.sh"
