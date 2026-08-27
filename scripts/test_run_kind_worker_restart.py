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

    def test_waits_for_initial_assignment_ack_before_deleting_worker(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("def gateway_ack_sequence", source)
        self.assertIn("initial TaskAssigned acknowledgement", source)
        self.assertIn("initial_command_sequence", source)

    def test_waits_for_a_second_attempt_after_expiring_the_lease(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("second task attempt after Worker restart", source)
        self.assertIn("expected two attempts with one success after lease recovery", source)

    def test_clears_mock_delay_after_old_worker_is_deleted(self):
        source = SCRIPT.read_text(encoding="utf-8")

        deleted = source.index('run("delete", "pod", failed_worker_pod')
        clear_delay = source.index("clear_mock_delay(", deleted)
        replacement = source.index('"replacement Worker Pod"', deleted)
        self.assertLess(deleted, clear_delay)
        self.assertLess(clear_delay, replacement)

    def test_verifies_mock_delay_is_running_in_a_replacement_pod(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("def mock_delay_is_active", source)
        self.assertIn("mock response delay", source)
        self.assertIn("QWENPAW_MOCK_RESPONSE_DELAY_SECONDS", source)
        self.assertIn("/debug/inflight", source)
        self.assertIn("mock_port_forward", source)


if __name__ == "__main__":
    unittest.main()
