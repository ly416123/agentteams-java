#!/usr/bin/env python3
"""Keep the Kind OTLP check after a trace-producing real Worker task."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindOtelContractTest(unittest.TestCase):
    def test_trace_continuity_is_checked_immediately_after_real_worker_task(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        names = [step.get("name") for step in steps]

        admission = names.index("Run Kind Worker quota admission")
        otel = names.index("Verify OTLP trace continuity")

        self.assertEqual(
            admission + 1,
            otel,
            "OTLP continuity must be checked after the real Worker task emits its trace",
        )


if __name__ == "__main__":
    unittest.main()
