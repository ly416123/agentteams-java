#!/usr/bin/env bash
set -euo pipefail

# Build and load the three local service images into the agentteams Kind cluster.
# Prerequisites: Docker/Colima, kind, and an existing cluster named agentteams.

if ! command -v docker >/dev/null 2>&1; then
  echo "缺少 docker，请先安装 Docker/Colima。" >&2
  exit 1
fi
if ! command -v kind >/dev/null 2>&1; then
  echo "缺少 kind，请先安装 Kind。" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker 守护进程不可用，请先执行 source deploy/dev-env.sh。" >&2
  exit 1
fi
if ! kind get clusters | grep -Fxq agentteams; then
  echo "未找到 agentteams Kind 集群，请先创建 deploy/kind-config.yaml。" >&2
  exit 1
fi

declare -a images=(
  "deploy/docker/control-plane.Dockerfile|ghcr.io/ly416123/agentteams-control-plane:latest"
  "deploy/docker/gateway.Dockerfile|ghcr.io/ly416123/agentteams-agent-gateway:latest"
  "deploy/docker/operator.Dockerfile|ghcr.io/ly416123/agentteams-operator:latest"
)

for image in "${images[@]}"; do
  IFS='|' read -r dockerfile tag <<<"$image"
  echo "构建 $tag"
  docker build -f "$dockerfile" -t "$tag" .
  echo "加载 $tag 到 Kind"
  kind load docker-image "$tag" --name agentteams
done

echo "三个服务镜像已加载到 agentteams Kind 集群。"
