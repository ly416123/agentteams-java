#!/usr/bin/env python3
"""Guard Matrix smoke handling of asynchronous AppService delivery."""

from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindMatrixSmokeContractTest(unittest.TestCase):
    def test_start_retries_only_before_appservice_delivery_is_recorded(self):
        script_text = (ROOT / "scripts/smoke-kind-matrix.sh").read_text(encoding="utf-8")
        start_task = script_text.split("start_task() {", 1)[1].split("\nwait_task_phase", 1)[0]

        self.assertIn("matrix_event_seen", script_text)
        self.assertIn("wait_for_matrix_event", script_text)
        self.assertIn("Establish that delivery path before creating tasks", script_text)
        self.assertIn("local max_attempts=3", start_task)
        self.assertIn('if wait_for_matrix_event "${body}" 20; then', start_task)
        self.assertIn("reached Control Plane but did not create task", start_task)
        self.assertIn("was not delivered to Control Plane", start_task)

    def test_start_returns_task_id_after_waiting_for_async_creation(self):
        script_text = (ROOT / "scripts/smoke-kind-matrix.sh").read_text(encoding="utf-8")
        start_task = script_text.split("start_task() {", 1)[1].split("\nwait_task_phase", 1)[0]

        self.assertIn(
            'if task_id="$(wait_for_task "${title}" 70)"; then\n'
            '        printf \'%s\\n\' "${task_id}"\n'
            '        return 0',
            start_task,
        )


if __name__ == "__main__":
    unittest.main()
