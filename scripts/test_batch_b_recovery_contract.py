#!/usr/bin/env python3
"""Contract tests for production ingress, egress and recovery safeguards."""

from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
RECOVERY_DIR = ROOT / "deploy/production/recovery"
PREFLIGHT = RECOVERY_DIR / "preflight.sh"
CONSISTENCY = RECOVERY_DIR / "consistency-check.py"
VALIDATOR_PATH = ROOT / "scripts/validate-production-network.py"
VALIDATOR_SPEC = importlib.util.spec_from_file_location("validate_production_network", VALIDATOR_PATH)
VALIDATOR = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR)


class BatchBRecoveryContractTest(unittest.TestCase):
    def test_public_entrypoints_are_optional_and_never_expose_internal_paths(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
        self.assertFalse(values["ingress"]["enabled"])
        self.assertEqual(values["ingress"]["mode"], "INGRESS")
        ingress = (CHART / "templates/ingress.yaml").read_text(encoding="utf-8")
        gateway_api = (CHART / "templates/gateway-api.yaml").read_text(encoding="utf-8")
        for template in (ingress, gateway_api):
            self.assertNotIn("/internal", template)
            self.assertNotIn("actuator", template)
        self.assertIn("port: {{ .Values.controlPlane.port }}", gateway_api)
        self.assertNotIn("gateway.port", gateway_api)

    def test_network_validator_supports_proxy_and_platform_modes(self):
        values = yaml.safe_load((CHART / "values-production.example.yaml").read_text(encoding="utf-8"))
        proxy = copy.deepcopy(values)
        proxy["networkPolicy"]["egressMode"] = "PROXY"
        proxy["networkPolicy"]["external"] = {key: [] for key in proxy["networkPolicy"]["external"]}
        proxy["networkPolicy"]["proxy"] = {
            "host": "egress.example.internal",
            "port": 8443,
            "cidr": "198.51.105.0/24",
        }
        VALIDATOR.validate_data(proxy)

        platform = copy.deepcopy(values)
        platform["networkPolicy"]["egressMode"] = "PLATFORM"
        platform["networkPolicy"]["external"] = {key: [] for key in platform["networkPolicy"]["external"]}
        platform["networkPolicy"]["platformPolicyArtifact"] = "https://platform.example/policies/agentteams.yaml"
        VALIDATOR.validate_data(platform)

        invalid = copy.deepcopy(proxy)
        invalid["networkPolicy"]["proxy"]["cidr"] = "0.0.0.0/0"
        with self.assertRaises(SystemExit):
            VALIDATOR.validate_data(invalid)

    def test_recovery_preflight_rejects_secret_bearing_inputs(self):
        valid = subprocess.run(
            [str(PREFLIGHT), "--environment", "production", "--backup-id", "pg-20260827-0100",
             "--restore-point", "2026-08-27T01:00:00Z", "--endpoint", "s3://backup.example/agentteams",
             "--manifest-digest", "sha256:" + "a" * 64],
            capture_output=True, text=True,
        )
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assertIn("RECOVERY_PREFLIGHT_OK", valid.stdout)
        invalid = subprocess.run(
            [str(PREFLIGHT), "--environment", "production", "--backup-id", "pg-20260827-0100",
             "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
             "https://user:password@example/backup", "--manifest-digest", "sha256:" + "a" * 64],
            capture_output=True, text=True,
        )
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("RECOVERY_PREFLIGHT_FAIL", invalid.stderr)

    def test_consistency_check_detects_orphaned_references_without_reading_content(self):
        valid = {
            "tasks": [{"id": "task-1"}],
            "attempts": [{"id": "attempt-1", "task_id": "task-1"}],
            "artifacts": [{"id": "artifact-1", "attempt_id": "attempt-1"}],
            "config_bindings": [{"id": "binding-1", "snapshot_id": "snapshot-1"}],
            "config_snapshots": [{"id": "snapshot-1"}],
            "quota_reservations": [{"id": "quota-1", "task_id": "task-1"}],
            "sandboxes": [{"id": "sandbox-1", "task_id": "task-1"}],
            "outbox": [{"id": "event-1", "aggregate_id": "task-1"}],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metadata.json"
            path.write_text(json.dumps(valid), encoding="utf-8")
            result = subprocess.run([sys.executable, str(CONSISTENCY), "--input", str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            invalid = copy.deepcopy(valid)
            invalid["artifacts"][0]["attempt_id"] = "missing-attempt"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            result = subprocess.run([sys.executable, str(CONSISTENCY), "--input", str(path)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("RECOVERY_CONSISTENCY_FAIL", result.stderr)


if __name__ == "__main__":
    unittest.main()
