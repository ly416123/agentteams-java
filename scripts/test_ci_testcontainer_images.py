#!/usr/bin/env python3
"""Guard GitHub CI integration tests from using unavailable image proxies."""

from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
TASK_PUSH_TEST = ROOT / "integration-tests/src/test/java/io/agentteams/it/TaskPushInfrastructureIT.java"


class CiTestcontainerImagesTest(unittest.TestCase):
    def test_minio_uses_official_fixed_tag_image(self):
        source = TASK_PUSH_TEST.read_text(encoding="utf-8")

        self.assertIn('"minio/minio:" + MINIO_VERSION', source)
        self.assertNotIn("dockerproxy.net/minio/minio", source)


if __name__ == "__main__":
    unittest.main()
