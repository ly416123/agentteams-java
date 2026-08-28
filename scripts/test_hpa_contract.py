#!/usr/bin/env python3
"""Helm contract tests for optional workload autoscaling."""

from __future__ import annotations

import shutil
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
HELM = shutil.which("helm")


@unittest.skipUnless(HELM, "helm is unavailable")
class HpaContractTest(unittest.TestCase):
    def render(self, *overrides: str) -> list[dict]:
        result = subprocess.run(
            [HELM, "template", "agentteams", str(CHART), *overrides],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return [document for document in yaml.safe_load_all(result.stdout) if document]

    def test_hpa_is_disabled_by_default(self):
        documents = self.render()
        self.assertFalse(any(document.get("kind") == "HorizontalPodAutoscaler" for document in documents))

    def test_enabled_hpas_target_their_deployments(self):
        documents = self.render(
            "--set", "controlPlane.autoscaling.enabled=true",
            "--set", "gateway.autoscaling.enabled=true",
            "--set", "manager.enabled=true",
            "--set", "manager.autoscaling.enabled=true",
            "--set", "manager.existingSecret=agentteams-manager",
            "--set", "manager.security.enabled=true",
            "--set", "manager.security.issuerUri=https://idp.example.test/issuer",
            "--set", "manager.security.jwkSetUri=https://idp.example.test/jwks",
            "--set", "manager.security.audience=agentteams",
            "--set", "resources.requests.cpu=100m",
            "--set", "controlPlane.autoscaling.minReplicas=3",
            "--set", "controlPlane.autoscaling.maxReplicas=6",
        )
        hpas = [document for document in documents if document.get("kind") == "HorizontalPodAutoscaler"]
        self.assertEqual({hpa["metadata"]["name"] for hpa in hpas}, {
            "agentteams-agentteams-java-control-plane-hpa",
            "agentteams-agentteams-java-gateway-hpa",
            "agentteams-agentteams-java-manager-hpa",
        })
        expected_targets = {
            "agentteams-agentteams-java-control-plane-hpa": "agentteams-agentteams-java-control-plane",
            "agentteams-agentteams-java-gateway-hpa": "agentteams-agentteams-java-gateway",
            "agentteams-agentteams-java-manager-hpa": "agentteams-agentteams-java-manager",
        }
        for hpa in hpas:
            spec = hpa["spec"]
            self.assertEqual(spec["scaleTargetRef"], {
                "apiVersion": "apps/v1",
                "kind": "Deployment",
                "name": expected_targets[hpa["metadata"]["name"]],
            })
            self.assertEqual(spec["metrics"][0]["resource"]["name"], "cpu")
            self.assertEqual(spec["metrics"][0]["resource"]["target"], {
                "type": "Utilization",
                "averageUtilization": 70,
            })

        control_plane_hpa = next(
            hpa for hpa in hpas
            if hpa["metadata"]["name"].endswith("control-plane-hpa")
        )
        self.assertEqual(control_plane_hpa["spec"]["minReplicas"], 3)
        self.assertEqual(control_plane_hpa["spec"]["maxReplicas"], 6)

    def test_enabled_hpa_requires_cpu_request(self):
        result = subprocess.run(
            [
                HELM, "template", "agentteams", str(CHART),
                "--set", "controlPlane.autoscaling.enabled=true",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CPU request", result.stderr)

    def test_enabled_hpa_rejects_invalid_replica_range(self):
        result = subprocess.run(
            [
                HELM, "template", "agentteams", str(CHART),
                "--set", "resources.requests.cpu=100m",
                "--set", "controlPlane.autoscaling.enabled=true",
                "--set", "controlPlane.autoscaling.minReplicas=5",
                "--set", "controlPlane.autoscaling.maxReplicas=2",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("maxReplicas must be greater than or equal to minReplicas", result.stderr)


if __name__ == "__main__":
    unittest.main()
