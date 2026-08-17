#!/usr/bin/env bash
set -euo pipefail

# Load this file with: source deploy/dev-env.sh
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "请使用 source deploy/dev-env.sh 加载开发环境变量。" >&2
  exit 1
fi

USER_HOME="$(cd ~ && pwd)"
COLIMA_SOCKET="unix://${USER_HOME}/.colima/default/docker.sock"

command -v colima >/dev/null || { echo "缺少 colima，请先安装 Colima。" >&2; return 1; }
command -v docker >/dev/null || { echo "缺少 docker CLI，请先安装 Docker CLI。" >&2; return 1; }
command -v kind >/dev/null || { echo "缺少 kind，请先安装 Kind。" >&2; return 1; }
command -v kubectl >/dev/null || { echo "缺少 kubectl，请先安装 kubectl。" >&2; return 1; }

if ! colima status >/dev/null 2>&1; then
  colima start --cpus 4 --memory 8 --disk 60 --vm-type vz --mount-type virtiofs
fi

docker context use colima >/dev/null
export DOCKER_HOST="${COLIMA_SOCKET}"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

if [[ -d /Applications/Docker.app/Contents/Resources/bin ]]; then
  case ":${PATH}:" in
    *:/Applications/Docker.app/Contents/Resources/bin:*) ;;
    *) export PATH="/Applications/Docker.app/Contents/Resources/bin:${PATH}" ;;
  esac
fi

docker info --format 'Docker context={{.Name}} server={{.ServerVersion}}'
echo "Testcontainers DOCKER_HOST=${DOCKER_HOST}"
