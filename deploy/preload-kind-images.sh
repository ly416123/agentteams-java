#!/usr/bin/env bash
set -euo pipefail

# 将 Kind 开发清单所需镜像注入 agentteams 集群节点。
# kind load（docker save → ctr import）在 Colima containerd-snapshotter 存储下会丢层失败，
# 因此改为直接在节点内 ctr pull 代理镜像并 tag 回官方镜像名，K8s 按官方名引用时本地命中。
# Docker Hub 镜像走 dockerproxy.net 代理；registry.k8s.io 镜像走阿里云 google_containers 同步源。
# 注意：macOS 自带 bash 3.2 不支持关联数组，镜像对用 | 分隔（官方名|来源镜像）。
# 用法：./deploy/preload-kind-images.sh

IMAGES=(
  "docker.io/library/postgres:16-alpine|dockerproxy.net/library/postgres:16-alpine"
  "docker.io/library/nats:2.10-alpine|dockerproxy.net/library/nats:2.10-alpine"
  "docker.io/natsio/nats-box:0.14.0|dockerproxy.net/natsio/nats-box:0.14.0"
  "docker.io/minio/minio:RELEASE.2024-11-07T00-52-20Z|dockerproxy.net/minio/minio:RELEASE.2024-11-07T00-52-20Z"
  "docker.io/minio/mc:RELEASE.2025-07-21T05-28-08Z|dockerproxy.net/minio/mc:RELEASE.2025-07-21T05-28-08Z"
  "docker.io/agentscope/qwenpaw:v2.1.0|dockerproxy.net/agentscope/qwenpaw:v2.1.0"
  "docker.io/prom/prometheus:v2.55.1|dockerproxy.net/prom/prometheus:v2.55.1"
  "docker.io/grafana/grafana:11.3.0|dockerproxy.net/grafana/grafana:11.3.0"
  "registry.k8s.io/ingress-nginx/controller:v1.11.3|registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.11.3"
)

command -v docker >/dev/null 2>&1 || { echo "缺少 docker，请先 source deploy/dev-env.sh。" >&2; exit 1; }
command -v kind >/dev/null 2>&1 || { echo "缺少 kind，请先安装 Kind。" >&2; exit 1; }
kind get clusters 2>/dev/null | grep -Fxq agentteams || { echo "Kind 集群 agentteams 不存在，请先创建集群。" >&2; exit 1; }

NODES=("agentteams-control-plane" "agentteams-worker")

for entry in "${IMAGES[@]}"; do
  official="${entry%%|*}"
  source_image="${entry##*|}"
  for node in "${NODES[@]}"; do
    if docker exec "$node" ctr -n k8s.io images ls 2>/dev/null | grep -qw "$official"; then
      echo "[${node}] 已有 ${official}，跳过"
      continue
    fi
    echo "[${node}] 拉取 ${source_image}"
    docker exec "$node" ctr -n k8s.io images pull "$source_image"
    docker exec "$node" ctr -n k8s.io images tag "$source_image" "$official"
    echo "[${node}] 已 tag 为 ${official}"
  done
done

echo "所有清单镜像已注入 agentteams 集群节点。"
