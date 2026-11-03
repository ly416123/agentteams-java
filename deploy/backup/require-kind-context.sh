#!/usr/bin/env bash

# Capture the executable before defining the explicit-context wrapper below.
KUBECTL_BIN=$(command -v kubectl) || {
  echo "缺少 kubectl。" >&2
  exit 1
}

AGENTTEAMS_KIND_CONTEXT=${AGENTTEAMS_KIND_CONTEXT:-kind-agentteams}
if [[ "$AGENTTEAMS_KIND_CONTEXT" != kind-* ]]; then
  echo "拒绝使用非 Kind 上下文：$AGENTTEAMS_KIND_CONTEXT。" >&2
  exit 1
fi

CURRENT_CONTEXT=$("$KUBECTL_BIN" config current-context) || {
  echo "无法读取当前 kubectl 上下文。" >&2
  exit 1
}
if [[ "$CURRENT_CONTEXT" != "$AGENTTEAMS_KIND_CONTEXT" ]]; then
  echo "当前 kubectl 上下文为 $CURRENT_CONTEXT；请切换到 $AGENTTEAMS_KIND_CONTEXT 后重试。" >&2
  exit 1
fi

API_SERVER=$("$KUBECTL_BIN" --context "$AGENTTEAMS_KIND_CONTEXT" \
  config view --minify -o 'jsonpath={.clusters[0].cluster.server}') || {
  echo "无法读取 Kind API server 地址。" >&2
  exit 1
}
if [[ ! "$API_SERVER" =~ ^https?://(127\.0\.0\.1|localhost)(:[0-9]+)?$ ]]; then
  echo "拒绝连接非本机 Kubernetes API server：$API_SERVER。" >&2
  exit 1
fi

kubectl() {
  "$KUBECTL_BIN" --context "$AGENTTEAMS_KIND_CONTEXT" "$@"
}
