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

        self.assertIn('"docker.io/minio/minio:" + MINIO_VERSION', source)
        self.assertNotIn('"quay.io/minio/minio:" + MINIO_VERSION', source)
        self.assertNotIn("dockerproxy.net/minio/minio", source)

    def test_ci_builds_minio_images_from_pinned_upstream_source(self):
        builder = (ROOT / "scripts/build-kind-minio-images.sh").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertIn("cefc43e4daa4cbb490ef6726ea374e26a93eb85e", builder)
        self.assertIn("ee72571936f15b0e65dc8b4a231a4dd445e5ccb6", builder)
        self.assertIn("docker build", builder)
        self.assertIn("build-kind-minio-images.sh", workflow)
        self.assertIn("kind load docker-image", workflow)


if __name__ == "__main__":
    unittest.main()
