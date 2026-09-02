#!/usr/bin/env python3
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/run-kind-worker-operation-recovery-acceptance.sh"


class KindWorkerMultiReplicaContractTest(unittest.TestCase):
    def test_recovery_acceptance_can_exercise_replica_repair_and_restore(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("AGENTTEAMS_WORKER_REPLICAS", source)
        self.assertIn("kubectl -n", source)
        self.assertIn('patch worker "${CR_NAME}"', source)
        self.assertIn('delete pod "${POD_NAME}"', source)
        self.assertIn("readyReplicas", source)
        self.assertIn("ORIGINAL_REPLICAS", source)
        self.assertIn("OPERATION_LEASE_EXPIRED", source)
        self.assertNotIn("kubectl get secret", source)


if __name__ == "__main__":
    unittest.main()
