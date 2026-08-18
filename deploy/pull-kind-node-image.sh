#!/usr/bin/env bash
set -euo pipefail

# Docker Hub 直连不稳定时，通过可用镜像代理准备 Kind 节点镜像。
# 用法：KIND_NODE_PROXY=dockerproxy.net ./deploy/pull-kind-node-image.sh

KIND_NODE_IMAGE="${KIND_NODE_IMAGE:-kindest/node:v1.36.1}"
KIND_NODE_PROXY="${KIND_NODE_PROXY:-dockerproxy.net}"
PROXY_IMAGE="${KIND_NODE_PROXY}/${KIND_NODE_IMAGE}"

if ! command -v docker >/dev/null 2>&1; then
  echo "缺少 docker，请先安装 Docker/Colima。" >&2
  exit 1
fi

if docker image inspect "$KIND_NODE_IMAGE" >/dev/null 2>&1; then
  echo "本地已有 ${KIND_NODE_IMAGE}，跳过下载。"
  exit 0
fi

echo "通过 ${KIND_NODE_PROXY} 拉取 ${KIND_NODE_IMAGE}"
docker pull "$PROXY_IMAGE"
docker tag "$PROXY_IMAGE" "$KIND_NODE_IMAGE"

echo "已准备 Kind 节点镜像："
docker image inspect "$KIND_NODE_IMAGE" \
  --format '  {{.RepoTags}} id={{.Id}} size={{.Size}}'
