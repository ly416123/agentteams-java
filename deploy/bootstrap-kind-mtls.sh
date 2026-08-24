#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
CERT_DIR=${AGENTTEAMS_MTLS_DIR:-$ROOT/.local/kind-mtls}
GATEWAY_SECRET=${AGENTTEAMS_GATEWAY_MTLS_SECRET:-agentteams-gateway-mtls}
WORKER_SECRET=${AGENTTEAMS_WORKER_MTLS_SECRET:-agentteams-worker-mtls}
CONTROL_PLANE_RELEASE=${AGENTTEAMS_HELM_RELEASE:-agentteams}
WORKERS_RAW=${AGENTTEAMS_MTLS_WORKERS:-qwenpaw-worker,qwenpaw-worker-team-2}
RUN_ID=${AGENTTEAMS_MTLS_RUN_ID:-$(date +%s)}

for command_name in openssl kubectl helm jq; do
  command -v "$command_name" >/dev/null || {
    echo "缺少 ${command_name}，请先安装。" >&2
    exit 1
  }
done

mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"
umask 077

if [[ ! -f "$CERT_DIR/ca.key" ]]; then
  openssl genrsa -out "$CERT_DIR/ca.key" 4096 >/dev/null 2>&1
  openssl req -x509 -new -nodes -key "$CERT_DIR/ca.key" -sha256 -days 30 \
    -out "$CERT_DIR/ca.crt" -subj "/CN=agentteams-kind-ca" >/dev/null 2>&1
fi

if [[ ! -f "$CERT_DIR/gateway.key" ]]; then
  openssl genrsa -out "$CERT_DIR/gateway.key" 2048 >/dev/null 2>&1
  openssl req -new -key "$CERT_DIR/gateway.key" -out "$CERT_DIR/gateway.csr" \
    -subj "/CN=agentteams-gateway" >/dev/null 2>&1
  printf '%s\n' \
    'subjectAltName=DNS:agentteams-agentteams-java-gateway,DNS:agentteams-agentteams-java-gateway.agentteams.svc,DNS:agentteams-agentteams-java-gateway.agentteams.svc.cluster.local' \
    'extendedKeyUsage=serverAuth' > "$CERT_DIR/gateway.ext"
  openssl x509 -req -in "$CERT_DIR/gateway.csr" -CA "$CERT_DIR/ca.crt" \
    -CAkey "$CERT_DIR/ca.key" -CAcreateserial -out "$CERT_DIR/gateway.crt" \
    -days 30 -sha256 -extfile "$CERT_DIR/gateway.ext" >/dev/null 2>&1
fi

if [[ ! -f "$CERT_DIR/worker.key" ]]; then
  openssl genrsa -out "$CERT_DIR/worker.key" 2048 >/dev/null 2>&1
  openssl req -new -key "$CERT_DIR/worker.key" -out "$CERT_DIR/worker.csr" \
    -subj "/CN=agentteams-kind-worker" >/dev/null 2>&1
  printf '%s\n' 'extendedKeyUsage=clientAuth' > "$CERT_DIR/worker.ext"
  openssl x509 -req -in "$CERT_DIR/worker.csr" -CA "$CERT_DIR/ca.crt" \
    -CAkey "$CERT_DIR/ca.key" -CAcreateserial -out "$CERT_DIR/worker.crt" \
    -days 30 -sha256 -extfile "$CERT_DIR/worker.ext" >/dev/null 2>&1
fi

kubectl -n "$NAMESPACE" create secret generic "$GATEWAY_SECRET" \
  --from-file=ca.crt="$CERT_DIR/ca.crt" \
  --from-file=tls.crt="$CERT_DIR/gateway.crt" \
  --from-file=tls.key="$CERT_DIR/gateway.key" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NAMESPACE" create secret generic "$WORKER_SECRET" \
  --from-file=ca.crt="$CERT_DIR/ca.crt" \
  --from-file=tls.crt="$CERT_DIR/worker.crt" \
  --from-file=tls.key="$CERT_DIR/worker.key" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

helm upgrade --install "$CONTROL_PLANE_RELEASE" "$ROOT/deploy/helm/agentteams-java" \
  --namespace "$NAMESPACE" --create-namespace --wait --timeout 5m \
  -f "$ROOT/deploy/helm/kind-values.yaml" --set gateway.tls.enabled=true \
  --set gateway.tls.secretName="$GATEWAY_SECRET"

# The gRPC server loads its certificate chain during process startup. Updating
# the mounted Secret alone does not reload the in-memory Netty SSL context.
kubectl -n "$NAMESPACE" rollout restart deployment/agentteams-agentteams-java-gateway
kubectl -n "$NAMESPACE" rollout status deployment/agentteams-agentteams-java-gateway --timeout=300s

# The image tag is intentionally stable for Kind. Restart the Operator so a
# freshly loaded image is used before it reconciles Worker TLS volumes.
kubectl -n "$NAMESPACE" rollout restart deployment/agentteams-agentteams-java-operator
kubectl -n "$NAMESPACE" rollout status deployment/agentteams-agentteams-java-operator --timeout=180s

IFS=',' read -r -a workers <<< "$WORKERS_RAW"
for worker in "${workers[@]}"; do
  [[ -n "$worker" ]] || continue
  patch=$(jq -nc --arg secret "$WORKER_SECRET" --arg run_id "$RUN_ID" \
    '{metadata:{annotations:{"agentteams.io/mtls-configured":$run_id}},spec:{tlsSecret:$secret,env:{AGENTTEAMS_GATEWAY_TLS_ENABLED:"true",AGENTTEAMS_GATEWAY_TLS_CA_CERT_PATH:"/etc/agentteams/gateway-tls/ca.crt",AGENTTEAMS_GATEWAY_TLS_CLIENT_CERT_PATH:"/etc/agentteams/gateway-tls/tls.crt",AGENTTEAMS_GATEWAY_TLS_CLIENT_KEY_PATH:"/etc/agentteams/gateway-tls/tls.key"}}}')
  kubectl -n "$NAMESPACE" patch worker "$worker" --type=merge -p "$patch" >/dev/null
  # The Worker loads its client certificate into the gRPC channel at startup;
  # updating the mounted Secret does not refresh that in-memory channel.
  kubectl -n "$NAMESPACE" rollout restart deployment/"$worker"
done

for worker in "${workers[@]}"; do
  [[ -n "$worker" ]] || continue
  kubectl -n "$NAMESPACE" wait --for=condition=available "deployment/$worker" --timeout=300s
  kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.phase}'=Ready "worker/$worker" --timeout=300s
done

echo "Kind mTLS 已启用：gateway-secret=${GATEWAY_SECRET} worker-secret=${WORKER_SECRET}"
