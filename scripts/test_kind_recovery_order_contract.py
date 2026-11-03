#!/usr/bin/env python3
"""Keep foundational Kind recovery checks ahead of long acceptance chains."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindRecoveryOrderContractTest(unittest.TestCase):
    def test_postgres_and_minio_restore_run_before_worker_quota_admission(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        names = [step.get("name") for step in steps]

        install_chart = names.index("Install AgentTeams chart")
        quota_admission = names.index("Run Kind Worker quota admission")
        self.assertEqual(
            [
                "Run Kind PostgreSQL backup and restore validation",
                "Run Kind MinIO backup and restore validation",
            ],
            names[install_chart + 1:install_chart + 3],
        )
        for restore_step in (
            "Run Kind PostgreSQL backup and restore validation",
            "Run Kind MinIO backup and restore validation",
        ):
            with self.subTest(restore_step=restore_step):
                self.assertEqual(1, names.count(restore_step))
                self.assertLess(names.index(restore_step), quota_admission)


if __name__ == "__main__":
    unittest.main()
