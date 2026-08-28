#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
INGRESS_CHART_VERSION=${INGRESS_NGINX_CHART_VERSION:-4.11.3}

source "$ROOT/deploy/dev-env.sh"

for command_name in helm jq; do
  command -v "$command_name" >/dev/null || {
    echo "缺少 ${command_name}，请先安装。" >&2
    exit 1
  }
done

if ! kind get clusters | grep -Fxq agentteams; then
  "$ROOT/deploy/pull-kind-node-image.sh"
  kind create cluster --config "$ROOT/deploy/kind-config.yaml"
else
  echo "复用现有 agentteams Kind 集群；kind-config.yaml 的新端口映射不会自动应用。"
fi

# 节点内 containerd 无镜像加速器：先在本地拉取（走 Colima 加速器）再注入节点
"$ROOT/deploy/preload-kind-images.sh"

kubectl apply -f "$ROOT/deploy/kind-dev-infra.yaml"
kubectl -n "$NAMESPACE" rollout status statefulset/postgresql statefulset/nats statefulset/minio --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=complete job/nats-stream-bootstrap job/minio-bucket-bootstrap --timeout=180s

kubectl apply -f "$ROOT/deploy/kind-observability.yaml"
kubectl -n "$NAMESPACE" wait --for=condition=available deployment/prometheus deployment/grafana deployment/qwenpaw --timeout=240s
# The development manifest uses a mounted ConfigMap and Prometheus has no
# sidecar reloader; restart it so updated rules/config are loaded immediately.
kubectl -n "$NAMESPACE" rollout restart deployment/prometheus
kubectl -n "$NAMESPACE" rollout status deployment/prometheus --timeout=180s
kubectl -n "$NAMESPACE" rollout status deployment/otel-collector --timeout=180s

if helm status ingress-nginx --namespace ingress-nginx >/dev/null 2>&1; then
  echo "复用现有 ingress-nginx Helm release，跳过远程仓库刷新。"
else
  helm upgrade --install ingress-nginx ingress-nginx \
    --repo https://kubernetes.github.io/ingress-nginx \
    --version "$INGRESS_CHART_VERSION" \
    --namespace ingress-nginx --create-namespace --wait --timeout 5m \
    --set controller.service.type=NodePort \
    --set controller.service.nodePorts.http=30080 \
    --set controller.service.nodePorts.https=30443 \
    --set controller.ingressClassResource.default=true \
    --set controller.admissionWebhooks.enabled=false \
    --set controller.image.digest=""
fi
kubectl apply -f "$ROOT/deploy/kind-ingress.yaml"

"$ROOT/deploy/build-images.sh"
kubectl apply -f "$ROOT/deploy/helm/agentteams-java/crds/teams.yaml"
kubectl apply -f "$ROOT/deploy/helm/agentteams-java/crds/workers.yaml"
helm lint "$ROOT/deploy/helm/agentteams-java"
helm upgrade --install agentteams "$ROOT/deploy/helm/agentteams-java" \
  --namespace "$NAMESPACE" --create-namespace --wait --timeout 5m \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_ENABLED=true \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_POLL_INTERVAL_MS=1000 \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_LEASE_DURATION=30s \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_WINDOW=24h \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_MAX_PROJECTS_PER_RUN=100 \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_RETRY_DELAY=2s \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_NOTIFICATION_ENABLED=true \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_NOTIFICATION_WEBHOOK_URL=http://dashboard-alert-receiver:8080/alerts \
  --set-string controlPlane.env.AGENTTEAMS_DASHBOARD_ALERTS_NOTIFICATION_TIMEOUT=3s \
  -f "$ROOT/deploy/helm/kind-values.yaml"
# Kind intentionally reuses stable :latest tags. Helm cannot detect a rebuilt
# image when the tag is unchanged, so force a safe application rollout.
kubectl -n "$NAMESPACE" rollout restart \
  deployment/agentteams-agentteams-java-control-plane \
  deployment/agentteams-agentteams-java-gateway \
  deployment/agentteams-agentteams-java-operator
kubectl -n "$NAMESPACE" wait --for=condition=available \
  deployment/agentteams-agentteams-java-control-plane \
  deployment/agentteams-agentteams-java-gateway \
  deployment/agentteams-agentteams-java-operator --timeout=300s

"$ROOT/deploy/bootstrap-kind-qwenpaw-worker.sh"

echo "Kind 本地基础设施闭环已安装。"
echo "入口：api.agentteams.localhost、gateway.agentteams.localhost、qwenpaw.agentteams.localhost、prometheus.agentteams.localhost、grafana.agentteams.localhost"
echo "如需启用本地 OIDC，请执行 deploy/install-kind-oidc.sh。"
