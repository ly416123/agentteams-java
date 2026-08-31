#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

run() {
  echo "+ $*"
  "$@"
}

run mvn -q -pl application-contracts,control-plane -am test
run mvn -q -pl control-plane -am -Dtest=FoundationRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false test
run python3 -m unittest scripts/test_source_fingerprint.py scripts/test_batch_b_release_contract.py scripts/test_colima_testcontainers_config.py -q
run python3 scripts/validate-api-contract.py
run helm lint deploy/helm/agentteams-java
run git diff --check

echo "ENTERPRISE_EXECUTION_CONTRACT_OK"
