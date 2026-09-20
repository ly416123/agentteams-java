#!/usr/bin/env python3
"""Guard config acceptance scripts against missing write idempotency keys."""

from __future__ import annotations

import ast
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindConfigAcceptanceContractTest(unittest.TestCase):
    def test_all_config_write_requests_supply_idempotency_keys(self):
        for filename in (
            "scripts/run-kind-resource-binding-ack.py",
            "scripts/run-kind-config-rollback.py",
        ):
            tree = ast.parse((ROOT / filename).read_text(encoding="utf-8"), filename=filename)
            writes = [
                node for node in ast.walk(tree)
                if isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "api_request"
                and len(node.args) >= 3
                and isinstance(node.args[2], ast.Constant)
                and node.args[2].value in {"POST", "PUT", "DELETE"}
            ]
            self.assertTrue(writes, filename)
            self.assertTrue(all(len(call.args) >= 5 for call in writes), filename)

    def test_resource_binding_ack_fixture_does_not_activate_unbacked_mcp_runtime(self):
        source = (ROOT / "scripts/run-kind-resource-binding-ack.py").read_text(encoding="utf-8")
        self.assertNotIn('"reference": "kind-mcp"', source)
        self.assertIn('"type": "SKILL"', source)

    def test_resource_binding_ack_verifies_startup_config_bootstrap(self):
        source = (ROOT / "scripts/run-kind-resource-binding-ack.py").read_text(encoding="utf-8")
        for required in (
                "def worker_pod_names",
                "def deployment_ready",
                "def pod_logs",
                "def wait_for_replacement_worker",
                "sequence=0",
                "applied=true",
                "KIND_CONFIG_BOOTSTRAP_OK",
        ):
            self.assertIn(required, source)

    def test_bootstrap_restart_runs_after_valid_apply_before_invalid_fixture(self):
        source = (ROOT / "scripts/run-kind-resource-binding-ack.py").read_text(encoding="utf-8")
        valid = source.index("KIND_RESOURCE_BINDING_ACK_OK")
        delete = source.index('run("delete", "pod"')
        bootstrap = source.index("KIND_CONFIG_BOOTSTRAP_OK")
        invalid = source.index("KIND_RESOURCE_BINDING_FAILURE_OK")
        self.assertLess(valid, delete)
        self.assertLess(delete, bootstrap)
        self.assertLess(bootstrap, invalid)
