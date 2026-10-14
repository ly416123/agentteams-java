#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PLATFORM=linux/amd64
WORKER_TAG=agentteams-agent-worker:l5-chatgpt-plan-proxy2-20261011-amd64
CONTROL_PLANE_TAG=agentteams-control-plane:l5-chatgpt-plan-proxy2-20261011-amd64
OPERATOR_TAG=agentteams-operator:l5-chatgpt-plan-proxy2-20261011-amd64
REFRESH_TAG=agentteams-chatgpt-token-refresh:l5-chatgpt-plan-proxy2-20261011-amd64

command -v docker >/dev/null 2>&1 || { echo "缺少 docker。" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker 守护进程不可用。" >&2; exit 1; }

docker build --platform "$PLATFORM" -f "$ROOT/deploy/docker/worker.Dockerfile" -t "$WORKER_TAG" "$ROOT"
docker build --platform "$PLATFORM" -f "$ROOT/deploy/docker/control-plane.Dockerfile" -t "$CONTROL_PLANE_TAG" "$ROOT"
docker build --platform "$PLATFORM" -f "$ROOT/deploy/docker/operator.Dockerfile" -t "$OPERATOR_TAG" "$ROOT"
docker build --platform "$PLATFORM" -f "$ROOT/deploy/docker/chatgpt-token-refresh.Dockerfile" -t "$REFRESH_TAG" "$ROOT"

for image in "$WORKER_TAG" "$CONTROL_PLANE_TAG" "$OPERATOR_TAG" "$REFRESH_TAG"; do
  actual=$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}')
  if [[ "$actual" != "$PLATFORM" ]]; then
    echo "镜像平台校验失败：$image 为 $actual，预期 $PLATFORM。" >&2
    exit 1
  fi
  echo "已构建 $image ($actual)"
done
