#!/usr/bin/env python3
"""Guard the Kind Worker recovery acceptance against unsafe diagnostics."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindWorkerRecoveryContractTest(unittest.TestCase):
    def test_acceptance_uses_real_oidc_rollout_and_operator_recovery(self):
        source = (ROOT / "scripts/run-kind-worker-operation-recovery-acceptance.sh").read_text()
        for required in (
                "AGENTTEAMS_E2E_USERNAME",
                "/protocol/openid-connect/token",
                "/api/v1/agents",
                "worker_operations",
                "domain_events",
                "OPERATION_LEASE_EXPIRED",
                "status.phase",
                "ROLLED_BACK",
                "KIND_WORKER_RECOVERY_OK"):
            self.assertIn(required, source)

    def test_acceptance_never_prints_token_or_secret_values(self):
        source = (ROOT / "scripts/run-kind-worker-operation-recovery-acceptance.sh").read_text()
        self.assertNotIn("echo \"${TOKEN}", source)
        self.assertNotIn("printf '%s\\n' \"${TOKEN}", source)
        self.assertNotIn("kubectl get secret", source)


if __name__ == "__main__":
    unittest.main()
