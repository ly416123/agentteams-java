#!/usr/bin/env python3
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = ROOT / "deploy/docker/task-sandbox.Dockerfile"
RUNNER = ROOT / "deploy/docker/TaskSandboxRunner.java"


class TaskSandboxImageContractTest(unittest.TestCase):
    def test_acceptance_image_is_non_root_and_credential_free(self):
        dockerfile = DOCKERFILE.read_text(encoding="utf-8")
        self.assertIn("FROM eclipse-temurin:17-jre", dockerfile)
        self.assertIn("javac --release 17", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        self.assertIn("EXPOSE 7443", dockerfile)
        self.assertNotIn("docker.sock", dockerfile)
        self.assertNotIn("SECRET", dockerfile.upper())
        self.assertNotIn("TOKEN", dockerfile.upper())

    def test_runner_binds_only_the_fixed_health_port(self):
        runner = RUNNER.read_text(encoding="utf-8")
        self.assertIn('new InetSocketAddress("0.0.0.0", 7443)', runner)
        self.assertIn('"/healthz"', runner)
        self.assertNotIn("Runtime.getRuntime", runner)
        self.assertNotIn("System.getenv", runner)


if __name__ == "__main__":
    unittest.main()
