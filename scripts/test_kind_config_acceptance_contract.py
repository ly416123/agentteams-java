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
