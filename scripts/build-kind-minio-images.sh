#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVER_VERSION=RELEASE.2024-11-07T00-52-20Z
SERVER_COMMIT=cefc43e4daa4cbb490ef6726ea374e26a93eb85e
MC_VERSION=RELEASE.2025-07-21T05-28-08Z
MC_COMMIT=ee72571936f15b0e65dc8b4a231a4dd445e5ccb6
SERVER_IMAGE="docker.io/minio/minio:$SERVER_VERSION"
MC_IMAGE="docker.io/minio/mc:$MC_VERSION"
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agentteams-kind-minio.XXXXXX")

cleanup() {
  rm -rf "$BUILD_DIR"
}
trap cleanup EXIT

for command_name in docker git go; do
  command -v "$command_name" >/dev/null || {
    echo "缺少 $command_name，无法构建固定版本的 MinIO 测试镜像。" >&2
    exit 1
  }
done

GOARCH=$(go env GOARCH)
case "$GOARCH" in
  amd64|arm64) ;;
  *) echo "不支持的 Go 架构：$GOARCH" >&2; exit 1 ;;
esac

checkout_release() {
  local repository=$1
  local version=$2
  local expected_commit=$3
  local destination=$4

  git clone --quiet --depth 1 --branch "$version" \
    "https://github.com/$repository.git" "$destination"
  local actual_commit
  actual_commit=$(git -C "$destination" rev-parse HEAD)
  if [[ "$actual_commit" != "$expected_commit" ]]; then
    echo "$repository $version resolved to unexpected commit $actual_commit" >&2
    exit 1
  fi
}

checkout_release minio/minio "$SERVER_VERSION" "$SERVER_COMMIT" "$BUILD_DIR/minio"
checkout_release minio/mc "$MC_VERSION" "$MC_COMMIT" "$BUILD_DIR/mc"

MINIO_LDFLAGS=$(cd "$BUILD_DIR/minio" && MINIO_RELEASE=RELEASE go run buildscripts/gen-ldflags.go)
(
  cd "$BUILD_DIR/minio"
  CGO_ENABLED=0 GOOS=linux GOARCH="$GOARCH" \
    go build -tags kqueue -trimpath --ldflags "$MINIO_LDFLAGS" \
    -o "$BUILD_DIR/minio-bin" .
)

MC_LDFLAGS=$(cd "$BUILD_DIR/mc" && MC_RELEASE=RELEASE go run buildscripts/gen-ldflags.go)
(
  cd "$BUILD_DIR/mc"
  GO111MODULE=on CGO_ENABLED=0 GOOS=linux GOARCH="$GOARCH" \
    go build -trimpath -tags kqueue --ldflags "$MC_LDFLAGS" \
    -o "$BUILD_DIR/mc-bin" .
)

mkdir -p "$BUILD_DIR/server-context" "$BUILD_DIR/mc-context"
cp "$BUILD_DIR/minio-bin" "$BUILD_DIR/server-context/minio"
cp "$BUILD_DIR/mc-bin" "$BUILD_DIR/mc-context/mc"

docker build --platform "linux/$GOARCH" \
  --build-arg RELEASE="$SERVER_VERSION" \
  --build-arg SOURCE_COMMIT="$SERVER_COMMIT" \
  --file "$ROOT/deploy/kind-minio-server.Dockerfile" \
  --tag "$SERVER_IMAGE" "$BUILD_DIR/server-context"
docker build --platform "linux/$GOARCH" \
  --build-arg RELEASE="$MC_VERSION" \
  --build-arg SOURCE_COMMIT="$MC_COMMIT" \
  --file "$ROOT/deploy/kind-minio-mc.Dockerfile" \
  --tag "$MC_IMAGE" "$BUILD_DIR/mc-context"

docker image inspect "$SERVER_IMAGE" "$MC_IMAGE" >/dev/null
echo "KIND_MINIO_IMAGES_BUILT server=$SERVER_VERSION mc=$MC_VERSION arch=$GOARCH"
