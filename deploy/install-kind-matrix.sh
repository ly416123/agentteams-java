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

kubectl apply -f "${ROOT}/deploy/kind-tuwunel.yaml"
helm upgrade --install agentteams "${ROOT}/deploy/helm/agentteams-java" \
  --namespace "${NAMESPACE}" --create-namespace --wait --timeout 5m \
  -f "${ROOT}/deploy/helm/kind-values.yaml" \
  -f "${ROOT}/deploy/helm/kind-oidc-values.yaml" \
  -f "${ROOT}/deploy/helm/kind-matrix-values.yaml"

kubectl -n "${NAMESPACE}" rollout status \
  deployment/agentteams-agentteams-java-control-plane --timeout=180s
kubectl -n "${NAMESPACE}" rollout status deployment/tuwunel --timeout=180s
