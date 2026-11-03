#!/usr/bin/env python3
import subprocess
import sys
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "deploy" / "kind-observability.yaml"


class KindObservabilityContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resources = [item for item in yaml.safe_load_all(MANIFEST.read_text(encoding="utf-8")) if item]
        cls.by_name = {(item.get("kind"), item.get("metadata", {}).get("name")): item for item in cls.resources}
        cls.prometheus_config = yaml.safe_load(
            cls.by_name[("ConfigMap", "prometheus-config")]["data"]["prometheus.yml"]
        )

    def test_application_scrapes_keep_only_running_pods(self):
        jobs = {job["job_name"]: job for job in self.prometheus_config["scrape_configs"]}
        for job_name in ("control-plane", "gateway"):
            with self.subTest(job=job_name):
                relabels = jobs[job_name]["relabel_configs"]
                self.assertIn({
                    "source_labels": ["__meta_kubernetes_pod_phase"],
                    "regex": "Running",
                    "action": "keep",
                }, relabels)

    def test_kube_state_metrics_is_deployment_scoped_and_read_only(self):
        deployment = self.by_name[("Deployment", "kube-state-metrics")]
        pod_spec = deployment["spec"]["template"]["spec"]
        self.assertEqual("kube-state-metrics", pod_spec["serviceAccountName"])
        container = pod_spec["containers"][0]
        self.assertIn("--resources=deployments", container["args"])
        self.assertIn("--namespaces=agentteams", container["args"])
        self.assertEqual(
            {"httpGet": {"path": "/readyz", "port": "telemetry"},
             "periodSeconds": 10, "timeoutSeconds": 3},
            container["readinessProbe"],
        )
        self.assertEqual(
            {"httpGet": {"path": "/livez", "port": "http-metrics"},
             "periodSeconds": 20, "timeoutSeconds": 3},
            container["livenessProbe"],
        )

        role = self.by_name[("Role", "kube-state-metrics")]
        self.assertEqual([{
            "apiGroups": ["apps"],
            "resources": ["deployments"],
            "verbs": ["get", "list", "watch"],
        }], role["rules"])
        binding = self.by_name[("RoleBinding", "kube-state-metrics")]
        self.assertEqual("kube-state-metrics", binding["roleRef"]["name"])
        self.assertEqual("kube-state-metrics", binding["subjects"][0]["name"])

    def test_prometheus_scrapes_deployment_availability_metric(self):
        jobs = {job["job_name"]: job for job in self.prometheus_config["scrape_configs"]}
        job = jobs["kube-state-metrics"]
        self.assertIn("kube-state-metrics.agentteams.svc:8080", job["static_configs"][0]["targets"])
        self.assertEqual(
            ["kube_deployment_status_replicas_available"],
            job["metric_relabel_configs"][0]["regex"].split("|"),
        )

    def test_repository_observability_validator_accepts_the_manifest(self):
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "validate-observability.py")],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )


if __name__ == "__main__":
    unittest.main()
