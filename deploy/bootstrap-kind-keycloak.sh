#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
KEYCLOAK_ADMIN_USERNAME=${KEYCLOAK_ADMIN_USERNAME:-admin}
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin-dev}

for command_name in kubectl kind; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "缺少 ${command_name}，请先安装。" >&2
    exit 1
  }
done
kind get clusters | grep -Fxq agentteams || {
  echo "未找到 agentteams Kind 集群，请先执行 deploy/install-kind-dev.sh。" >&2
  exit 1
}

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 || \
  kubectl create namespace "${NAMESPACE}"

kubectl -n "${NAMESPACE}" create secret generic keycloak-admin \
  --from-literal=username="${KEYCLOAK_ADMIN_USERNAME}" \
  --from-literal=password="${KEYCLOAK_ADMIN_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl -n "${NAMESPACE}" create configmap keycloak-realm \
  --from-file=agentteams-realm.json="${ROOT}/deploy/keycloak/agentteams-realm.json" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl apply -f "${ROOT}/deploy/kind-keycloak.yaml"
kubectl -n "${NAMESPACE}" rollout status deployment/keycloak --timeout=180s
kubectl -n "${NAMESPACE}" wait --for=condition=ready pod \
  -l app.kubernetes.io/name=keycloak --timeout=60s

echo "Keycloak 已就绪：service/keycloak:8080"
echo "Realm: agentteams；测试用户: alice/alice-dev、quota-admin/quota-admin-dev、reader/reader-dev、tenant-b-user/tenant-b-dev"
