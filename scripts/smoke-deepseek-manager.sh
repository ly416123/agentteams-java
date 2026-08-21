#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APIKEY_FILE="${DEEPSEEK_APIKEY_FILE:-${ROOT_DIR}/apikey}"
MAVEN_REPO="${MAVEN_REPO:-/private/tmp/agentteams-java-m2}"

if [[ ! -f "${APIKEY_FILE}" ]]; then
  echo "DeepSeek API key file not found: ${APIKEY_FILE}" >&2
  exit 1
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  KEY_MODE="$(stat -f '%Lp' "${APIKEY_FILE}")"
else
  KEY_MODE="$(stat -c '%a' "${APIKEY_FILE}")"
fi
if (( (8#${KEY_MODE} & 77) != 0 )); then
  echo "DeepSeek API key file must not be group/world accessible: ${APIKEY_FILE}" >&2
  exit 1
fi

KEY_LINE_COUNT=0
DEEPSEEK_API_KEY=""
while IFS= read -r KEY_LINE || [[ -n "${KEY_LINE}" ]]; do
  KEY_LINE_COUNT=$((KEY_LINE_COUNT + 1))
  DEEPSEEK_API_KEY="${KEY_LINE}"
done < "${APIKEY_FILE}"
if [[ "${KEY_LINE_COUNT}" -ne 1 || -z "${DEEPSEEK_API_KEY//[[:space:]]/}" ]]; then
  echo "DeepSeek API key file must contain exactly one non-empty line" >&2
  exit 1
fi
if [[ "${DEEPSEEK_API_KEY}" == *[[:space:]]* ]]; then
  echo "DeepSeek API key must not contain whitespace" >&2
  exit 1
fi
export DEEPSEEK_API_KEY
export DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-v4-flash}"

cd "${ROOT_DIR}"
mvn -q -Dmaven.repo.local="${MAVEN_REPO}" -pl manager -am -DskipTests package \
  dependency:build-classpath \
  -Dmdep.outputFile="${ROOT_DIR}/manager/target/deepseek-smoke.classpath" \
  -Dmdep.includeScope=runtime

CLASSPATH="manager/target/classes:$(tr '\n' ':' < manager/target/deepseek-smoke.classpath)"
exec java -cp "${CLASSPATH}" io.agentteams.manager.ManagerSmokeApplication
