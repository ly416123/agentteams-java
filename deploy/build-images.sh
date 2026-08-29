#!/usr/bin/env bash
set -euo pipefail

# Build and load the local service images into the agentteams Kind cluster.
# Prerequisites: Docker/Colima, kind, and an existing cluster named agentteams.

ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/deploy/console-deployment.sh"

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

# Dockerfile 的基础镜像也走代理回退，保证 Docker Hub 直连不稳定时仍可构建。
# 基础镜像只需要存在于本机 Docker，不需要加载到 Kind 节点；构建产物会在下方单独加载。
declare -a base_images=(
  "maven:3.9.16-eclipse-temurin-17|dockerproxy.net/library/maven:3.9.16-eclipse-temurin-17;docker.m.daocloud.io/library/maven:3.9.16-eclipse-temurin-17"
  "eclipse-temurin:17-jre|dockerproxy.net/library/eclipse-temurin:17-jre;docker.m.daocloud.io/library/eclipse-temurin:17-jre"
)

for entry in "${base_images[@]}"; do
  official="${entry%%|*}"
  source_list="${entry##*|}"
  if docker image inspect "$official" >/dev/null 2>&1; then
    echo "本机已有构建基础镜像 ${official}，跳过拉取"
    continue
  fi

  acquired=0
  for source_image in ${source_list//;/ }; do
    if docker image inspect "$source_image" >/dev/null 2>&1; then
      echo "使用本机已有代理镜像 $source_image"
      docker tag "$source_image" "$official"
      acquired=1
      break
    fi
    echo "拉取构建基础镜像 $source_image"
    pulled=0
    for attempt in 1 2 3; do
      if docker pull "$source_image"; then
        pulled=1
        break
      fi
      echo "第 ${attempt} 次拉取失败，重试..." >&2
      sleep 5
    done
    if [[ "$pulled" == "1" ]]; then
      docker tag "$source_image" "$official"
      acquired=1
      break
    fi
    echo "代理源 $source_image 不可用，尝试下一个源" >&2
  done
  [[ "$acquired" == "1" ]] || { echo "构建基础镜像全部来源均失败：${official}" >&2; exit 1; }
done

declare -a images=(
  "deploy/docker/control-plane.Dockerfile|ghcr.io/ly416123/agentteams-control-plane:latest"
  "deploy/docker/gateway.Dockerfile|ghcr.io/ly416123/agentteams-agent-gateway:latest"
  "deploy/docker/operator.Dockerfile|ghcr.io/ly416123/agentteams-operator:latest"
  "deploy/docker/worker.Dockerfile|ghcr.io/ly416123/agentteams-agent-worker:latest"
)
# Source gate equivalent: if [[ -d console ]]; then
if [[ "$CONSOLE_ENABLED" == true ]]; then
  images+=("deploy/docker/console.Dockerfile|ghcr.io/ly416123/agentteams-console:latest")
else
  echo "console/ 不存在，跳过本地 Console 镜像构建和部署。"
fi

for image in "${images[@]}"; do
  IFS='|' read -r dockerfile tag <<<"$image"
  echo "构建 $tag"
  docker build -f "$dockerfile" -t "$tag" .
  echo "加载 $tag 到 Kind"
  kind load docker-image "$tag" --name agentteams
done

echo "服务和 QwenPaw Worker 镜像已加载到 agentteams Kind 集群。"
