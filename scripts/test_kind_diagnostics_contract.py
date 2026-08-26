#!/usr/bin/env python3
"""Guard Kind recovery diagnostics against secret and prompt leakage."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindDiagnosticsContractTest(unittest.TestCase):
    def _diagnostics_step(self) -> str:
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        return next(step["run"] for step in steps if step.get("name") == "Collect Kind diagnostics")

    def test_diagnostics_keep_only_safe_status_and_classified_event_fields(self):
        run = self._diagnostics_step()
        self.assertIn("select id,phase,version,updated_at from tasks", run)
        self.assertIn("select task_id,phase,version,created_at,completed_at from task_attempts", run)
        self.assertIn("select event_type,status,attempts,created_at,updated_at from outbox_events", run)
        self.assertIn("select event_type,aggregate_id,created_at from domain_events", run)
        self.assertIn("custom-columns=", run)
        self.assertNotIn("created_at,payload", run)
        self.assertNotIn("worker-pods.yaml", run)

    def test_pod_log_collection_is_always_grep_filtered_never_raw(self):
        run = self._diagnostics_step()
        self.assertIn("kubectl -n agentteams logs deployment/qwenpaw-real", run)
        self.assertIn('grep -E "Task assignment', run)
        self.assertIn("grep -iE \"stale Agent execution", run)
        # Every log collection must be piped through a filter so task prompts
        # and secrets inside pod logs never reach the diagnostics artifact.
        self.assertEqual(run.count("kubectl -n agentteams logs"), run.count("grep -"))


if __name__ == "__main__":
    unittest.main()
