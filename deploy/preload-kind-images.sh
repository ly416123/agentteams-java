#!/usr/bin/env bash
set -euo pipefail

# 将 Kind 开发清单所需的容器镜像预加载到 agentteams 集群节点。
# 本地 Docker（Colima）通过镜像加速器拉取，kind load 注入节点，
# 避免节点内 containerd 直连 Docker Hub / registry.k8s.io 失败。
# 用法：source deploy/dev-env.sh && ./deploy/preload-kind-images.sh

IMAGES=(
  postgres:16-alpine
  nats:2.10-alpine
  natsio/nats-box:0.14.0
  minio/minio:RELEASE.2024-11-07T00-52-20Z
  minio/mc:RELEASE.2025-07-21T05-28-08Z
  agentscope/qwenpaw:v2.1.0
  prom/prometheus:v2.55.1
  grafana/grafana:11.3.0
  registry.k8s.io/ingress-nginx/controller:v1.11.3
)

# registry.k8s.io 在国内不可直连，从阿里云 google_containers 同步源拉取后打回原名。
# 其余 Docker Hub 镜像直连失败时回退到 dockerproxy.net 代理前缀。
declare -A ALTERNATE_SOURCES=(
  [registry.k8s.io/ingress-nginx/controller:v1.11.3]=registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.11.3
)

command -v docker >/dev/null 2>&1 || { echo "缺少 docker，请先 source deploy/dev-env.sh。" >&2; exit 1; }
command -v kind >/dev/null 2>&1 || { echo "缺少 kind，请先安装 Kind。" >&2; exit 1; }
kind get clusters 2>/dev/null | grep -Fxq agentteams || { echo "Kind 集群 agentteams 不存在，请先创建集群。" >&2; exit 1; }

for image in "${IMAGES[@]}"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    if [[ -n "${ALTERNATE_SOURCES[$image]:-}" ]]; then
      src="${ALTERNATE_SOURCES[$image]}"
      echo "从替代源拉取 ${src}"
      docker pull "$src"
      docker tag "$src" "$image"
    else
      echo "拉取 ${image}"
      if ! docker pull "$image"; then
        echo "直连失败，回退到 dockerproxy.net 代理"
        docker pull "dockerproxy.net/${image}"
        docker tag "dockerproxy.net/${image}" "$image"
      fi
    fi
  else
    echo "本地已有 ${image}，跳过拉取"
  fi
  kind load docker-image "$image" --name agentteams
done

echo "所有清单镜像已预加载到 agentteams 集群。"
