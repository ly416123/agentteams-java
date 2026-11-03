#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="${AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE:-${ROOT_DIR}/chatgpt-access-token}"
MAVEN_REPO="${MAVEN_REPO:-/private/tmp/agentteams-java-m2}"

if [[ ! -f "${TOKEN_FILE}" || ! -r "${TOKEN_FILE}" ]]; then
  echo "ChatGPT OAuth access-token file is missing or unreadable: ${TOKEN_FILE}" >&2
  exit 1
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  TOKEN_MODE="$(stat -f '%Lp' "${TOKEN_FILE}")"
else
  TOKEN_MODE="$(stat -c '%a' "${TOKEN_FILE}")"
fi
if (( (8#${TOKEN_MODE} & 77) != 0 )); then
  echo "ChatGPT OAuth access-token file must not be group/world accessible: ${TOKEN_FILE}" >&2
  exit 1
fi

TOKEN_LINE_COUNT=0
while IFS= read -r TOKEN_LINE || [[ -n "${TOKEN_LINE}" ]]; do
  TOKEN_LINE_COUNT=$((TOKEN_LINE_COUNT + 1))
  if [[ -z "${TOKEN_LINE//[[:space:]]/}" || "${TOKEN_LINE}" == *[[:space:]]* ]]; then
    echo "ChatGPT OAuth access-token file must contain one non-empty token line" >&2
    exit 1
  fi
done < "${TOKEN_FILE}"
if [[ "${TOKEN_LINE_COUNT}" -ne 1 ]]; then
  echo "ChatGPT OAuth access-token file must contain exactly one line" >&2
  exit 1
fi

export AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE="${TOKEN_FILE}"
export AGENTTEAMS_MODEL="${AGENTTEAMS_MODEL:-gpt-5.6-luna}"

cd "${ROOT_DIR}"
mvn -q -Dmaven.repo.local="${MAVEN_REPO}" -pl manager -am -DskipTests package \
  dependency:build-classpath \
  -Dmdep.outputFile="${ROOT_DIR}/manager/target/chatgpt-plan-smoke.classpath" \
  -Dmdep.includeScope=runtime

CLASSPATH="manager/target/classes:$(tr '\n' ':' < manager/target/chatgpt-plan-smoke.classpath)"
exec java -cp "${CLASSPATH}" io.agentteams.manager.ManagerSmokeApplication
