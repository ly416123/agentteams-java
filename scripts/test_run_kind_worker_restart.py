#!/usr/bin/env python3
"""Guard the in-flight Worker restart smoke against a registration race."""

from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/run-kind-worker-restart.py"


class KindWorkerRestartContractTest(unittest.TestCase):
    def test_waits_for_online_ready_worker_before_queueing_task(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("def qwenpaw_agent_ready", source)
        self.assertIn('presence == "ONLINE"', source)
        self.assertIn("QwenPaw Agent registration", source)
        self.assertLess(
            source.index("QwenPaw Agent registration"),
            source.index('f"{base_url}/api/v1/tasks"', source.index("created = api_request")),
        )

    def test_timeout_reports_current_task_and_worker_state(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("def task_snapshot", source)
        self.assertIn("task_snapshot(args.namespace", source)
        self.assertIn("gateway_connection_state", source)


if __name__ == "__main__":
    unittest.main()
