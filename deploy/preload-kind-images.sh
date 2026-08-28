#!/usr/bin/env bash
set -euo pipefail

# 将 Kind 开发清单所需镜像注入 agentteams 集群节点。
# kind load（docker save → ctr import）在 Colima containerd-snapshotter 存储下会丢层失败，
# 因此改为直接在节点内 ctr pull 代理镜像并 tag 回官方镜像名，K8s 按官方名引用时本地命中。
# 对 digest 引用必须保留 docker.io registry 前缀；CRI 会将无前缀引用规范化为该名称。
# Docker Hub 镜像依次尝试多个代理源（网络抖动时回退）；registry.k8s.io 镜像走阿里云同步源。
# 注意：macOS 自带 bash 3.2 不支持关联数组，镜像对用 | 分隔，候选源用 ; 分隔。
# 用法：./deploy/preload-kind-images.sh

IMAGES=(
  "docker.io/library/postgres:16-alpine|dockerproxy.net/library/postgres:16-alpine;docker.m.daocloud.io/library/postgres:16-alpine"
  "docker.io/library/nats:2.10-alpine|dockerproxy.net/library/nats:2.10-alpine;docker.m.daocloud.io/library/nats:2.10-alpine"
  "docker.io/natsio/nats-box:0.14.0|dockerproxy.net/natsio/nats-box:0.14.0;docker.m.daocloud.io/natsio/nats-box:0.14.0"
  "docker.io/minio/minio:RELEASE.2024-11-07T00-52-20Z|dockerproxy.net/minio/minio:RELEASE.2024-11-07T00-52-20Z;docker.m.daocloud.io/minio/minio:RELEASE.2024-11-07T00-52-20Z"
  "docker.io/minio/mc:RELEASE.2025-07-21T05-28-08Z|dockerproxy.net/minio/mc:RELEASE.2025-07-21T05-28-08Z;docker.m.daocloud.io/minio/mc:RELEASE.2025-07-21T05-28-08Z"
  # 清单 kind-dev-infra.yaml 使用同一个 digest；必须预加载 CRI 规范化后的完整引用
  "docker.io/agentscope/qwenpaw@sha256:1132da56170f49c63aa583dd1ea3b09c19ce1ab76a1983813b8ad2f220771bcd|dockerproxy.net/agentscope/qwenpaw:latest;docker.m.daocloud.io/agentscope/qwenpaw:latest"
  "docker.io/prom/prometheus:v2.55.1|dockerproxy.net/prom/prometheus:v2.55.1;docker.m.daocloud.io/prom/prometheus:v2.55.1"
  "docker.io/grafana/grafana:11.3.0|dockerproxy.net/grafana/grafana:11.3.0;docker.m.daocloud.io/grafana/grafana:11.3.0"
  "registry.k8s.io/ingress-nginx/controller:v1.11.3|registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.11.3"
)

command -v docker >/dev/null 2>&1 || { echo "缺少 docker，请先 source deploy/dev-env.sh。" >&2; exit 1; }
command -v kind >/dev/null 2>&1 || { echo "缺少 kind，请先安装 Kind。" >&2; exit 1; }
kind get clusters 2>/dev/null | grep -Fxq agentteams || { echo "Kind 集群 agentteams 不存在，请先创建集群。" >&2; exit 1; }

NODES=("agentteams-control-plane" "agentteams-worker")

for entry in "${IMAGES[@]}"; do
  official="${entry%%|*}"
  source_list="${entry##*|}"
  for node in "${NODES[@]}"; do
    if docker exec "$node" ctr -n k8s.io images ls 2>/dev/null | grep -qw "$official"; then
      echo "[${node}] 已有 ${official}，跳过"
      continue
    fi
    tagged=0
    for source_image in ${source_list//;/ }; do
      if docker exec "$node" ctr -n k8s.io images ls 2>/dev/null | grep -qw "$source_image"; then
        echo "[${node}] 来源镜像已存在，直接 tag"
        docker exec "$node" ctr -n k8s.io images tag "$source_image" "$official"
        echo "[${node}] 已 tag 为 ${official}"
        tagged=1
        break
      fi
      echo "[${node}] 拉取 ${source_image}"
      pulled=0
      for attempt in 1 2 3; do
        if docker exec "$node" ctr -n k8s.io images pull "$source_image"; then
          pulled=1
          break
        fi
        echo "[${node}] 第 ${attempt} 次拉取失败，重试..."
        sleep 5
      done
      if [[ "$pulled" == "1" ]]; then
        docker exec "$node" ctr -n k8s.io images tag "$source_image" "$official"
        echo "[${node}] 已 tag 为 ${official}"
        tagged=1
        break
      fi
      echo "[${node}] 源 ${source_image} 不可用，尝试下一个源"
    done
    [[ "$tagged" == "1" ]] || { echo "[${node}] 全部源均失败：${official}" >&2; exit 1; }
  done
done

echo "所有清单镜像已注入 agentteams 集群节点。"
