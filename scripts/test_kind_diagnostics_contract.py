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
        self.assertIn("select event_type,status,attempts,created_at,updated_at from outbox_events", run)
        self.assertIn("select event_type,aggregate_id,created_at from domain_events", run)
        self.assertIn("custom-columns=", run)
        self.assertNotIn("kubectl -n agentteams logs", run)
        self.assertNotIn("created_at,payload", run)
        self.assertNotIn("worker-pods.yaml", run)


if __name__ == "__main__":
    unittest.main()
