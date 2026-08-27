#!/usr/bin/env python3
"""Guard the NATS Outbox smoke test's live Gateway precondition."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindNatsOutboxRecoveryContractTest(unittest.TestCase):
    def test_waits_for_the_worker_gateway_stream_not_only_agent_phase(self):
        script = (ROOT / "scripts/run-kind-nats-outbox-recovery.py").read_text(encoding="utf-8")

        self.assertIn("gateway_agent_online", script)
        self.assertIn("gateway_agent_state", script)
        self.assertIn("presence = 'ONLINE'", script)
        self.assertIn("QwenPaw Gateway connection", script)


if __name__ == "__main__":
    unittest.main()
