#!/usr/bin/env python3
"""Contract tests for the Kind mTLS bootstrap lifecycle."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindMtlsBootstrapContractTest(unittest.TestCase):
    def test_gateway_restarts_after_gateway_secret_is_applied(self):
        script = (ROOT / "deploy/bootstrap-kind-mtls.sh").read_text()
        secret_apply = script.index('kubectl -n "$NAMESPACE" create secret generic "$GATEWAY_SECRET"')
        gateway_restart = script.index(
            'kubectl -n "$NAMESPACE" rollout restart deployment/agentteams-agentteams-java-gateway')
        gateway_status = script.index(
            'kubectl -n "$NAMESPACE" rollout status deployment/agentteams-agentteams-java-gateway')

        self.assertGreater(gateway_restart, secret_apply)
        self.assertGreater(gateway_status, gateway_restart)

    def test_each_worker_restarts_after_its_mtls_configuration_is_patched(self):
        script = (ROOT / "deploy/bootstrap-kind-mtls.sh").read_text()
        worker_patch = script.index('kubectl -n "$NAMESPACE" patch worker "$worker"')
        worker_restart = script.index(
            'kubectl -n "$NAMESPACE" rollout restart deployment/"$worker"')

        self.assertGreater(worker_restart, worker_patch)


if __name__ == "__main__":
    unittest.main()
