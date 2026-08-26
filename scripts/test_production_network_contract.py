#!/usr/bin/env python3
"""Keep the production network contract wired into the chart and CI."""

from pathlib import Path
import copy
import importlib.util
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
VALIDATOR_PATH = ROOT / "scripts/validate-production-network.py"
VALIDATOR_SPEC = importlib.util.spec_from_file_location("validate_production_network", VALIDATOR_PATH)
VALIDATOR = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR)


class ProductionNetworkContractTest(unittest.TestCase):
    def test_production_values_declare_cidr_targets_for_enabled_dependencies(self):
        values = yaml.safe_load((CHART / "values-production.example.yaml").read_text(encoding="utf-8"))
        policy = values["networkPolicy"]
        self.assertTrue(policy["enabled"])
        self.assertEqual("CIDR", policy["egressMode"])
        self.assertFalse(policy["allowPublicInternet"])
        for dependency in ("postgresql", "nats", "objectStorage", "otlp", "oidc"):
            self.assertTrue(policy["external"][dependency], dependency)

    def test_network_policy_renders_external_ip_blocks(self):
        template = (CHART / "templates/networkpolicy.yaml").read_text(encoding="utf-8")
        for required in ("egressMode", "external.postgresql", "external.nats", "external.objectStorage",
                         "external.otlp", "external.oidc", "ipBlock:"):
            self.assertIn(required, template)

    def test_production_values_use_secret_references_only(self):
        values = (CHART / "values-production.example.yaml").read_text(encoding="utf-8")
        self.assertIn("existingSecret:", values)
        self.assertNotRegex(values, r"(?i)(password|api[_-]?key|token|private[_-]?key):\s+[^#\n]+")

    def test_validator_rejects_public_internet_target(self):
        values = yaml.safe_load((CHART / "values-production.example.yaml").read_text(encoding="utf-8"))
        invalid = copy.deepcopy(values)
        invalid["networkPolicy"]["external"]["nats"][0]["cidr"] = "0.0.0.0/0"
        with self.assertRaises(SystemExit):
            VALIDATOR.validate_data(invalid)

    def test_validator_rejects_missing_target_for_enabled_dependency(self):
        values = yaml.safe_load((CHART / "values-production.example.yaml").read_text(encoding="utf-8"))
        invalid = copy.deepcopy(values)
        invalid["networkPolicy"]["external"]["postgresql"] = []
        with self.assertRaises(SystemExit):
            VALIDATOR.validate_data(invalid)


if __name__ == "__main__":
    unittest.main()
