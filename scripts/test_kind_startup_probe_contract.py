#!/usr/bin/env python3
"""Verify that slow-starting Kind workloads have a startup probe."""

from __future__ import annotations

import subprocess
import unittest

import yaml


class StartupProbeContractTest(unittest.TestCase):
    def test_control_plane_and_gateway_gate_liveness_until_startup_finishes(self):
        rendered = subprocess.run(
            ["helm", "template", "agentteams", "deploy/helm/agentteams-java",
             "-f", "deploy/helm/kind-values.yaml"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        deployments = {
            document.get("metadata", {}).get("name"): document
            for document in yaml.safe_load_all(rendered)
            if isinstance(document, dict) and document.get("kind") == "Deployment"
        }
        for suffix in ("control-plane", "gateway"):
            deployment = next(
                value for name, value in deployments.items() if name and name.endswith(suffix)
            )
            container = deployment["spec"]["template"]["spec"]["containers"][0]
            self.assertIn("startupProbe", container)


if __name__ == "__main__":
    unittest.main()
