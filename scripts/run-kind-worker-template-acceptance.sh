#!/usr/bin/env bash
set -euo pipefail

# Runs the real-OIDC Console flow and verifies the Worker CR, Operator
# Deployment, and Ready Worker Pod created by explicit instantiation.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAMESPACE=${AGENTTEAMS_NAMESPACE:-agentteams}
: "${AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME:?set a non-production OIDC username}"
: "${AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD:?set a non-production OIDC password}"

(cd "$ROOT/console" && \
  AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME="$AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME" \
  AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD="$AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD" \
  AGENTTEAMS_E2E_OIDC_PORT="${AGENTTEAMS_E2E_OIDC_PORT:-18082}" \
  AGENTTEAMS_E2E_BASE_URL="${AGENTTEAMS_E2E_BASE_URL:-http://api.agentteams.localhost:30080}" \
  npx playwright test tests/e2e/smoke.spec.ts -g "template flow provisions")

worker_id=$(kubectl -n "$NAMESPACE" exec postgresql-0 -- psql -U agentteams -d agentteams -Atc \
  "select worker_id from worker_template_instances where status='SUCCEEDED' and worker_id is not null order by created_at desc limit 1" \
  | tr -d '[:space:]')
[[ -n "$worker_id" ]] || { echo "未找到成功实例化的 Worker" >&2; exit 1; }

cr_name="worker-$(printf '%s' "$worker_id" | tr -d '-')"
kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.phase}'=Ready "worker/$cr_name" --timeout=120s
kubectl -n "$NAMESPACE" wait --for=jsonpath='{.status.readyReplicas}'=1 "deployment/$cr_name" --timeout=120s
pod_name=$(kubectl -n "$NAMESPACE" get pod -l "app.kubernetes.io/name=agentteams-worker,agentteams.io/agent-id=$worker_id" \
  -o jsonpath='{.items[0].metadata.name}')
[[ -n "$pod_name" ]] || { echo "未找到 Operator 创建的 Worker Pod" >&2; exit 1; }
kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$pod_name" --timeout=120s
printf 'KIND_WORKER_TEMPLATE_OK worker=%s cr=%s deployment=%s pod=%s\n' "$worker_id" "$cr_name" "$cr_name" "$pod_name"
