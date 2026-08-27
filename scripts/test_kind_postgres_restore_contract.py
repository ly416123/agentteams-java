#!/usr/bin/env python3
"""Regression contracts for the PostgreSQL restore validator."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).with_name("run-kind-postgres-restore.py")
SPEC = importlib.util.spec_from_file_location("run_kind_postgres_restore", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class PostgresRestoreContractTest(unittest.TestCase):
    def test_outbox_signature_excludes_relay_mutable_fields(self):
        signature = MODULE.POSTGRES_TABLE_SIGNATURES
        outbox = signature.split("select 'outbox_events|", 1)[1].split("union all", 1)[0]
        self.assertNotIn("status::text", outbox)
        self.assertNotIn("attempts::text", outbox)
        self.assertNotIn("' || version::text", outbox)
        self.assertIn("aggregate_type", outbox)
        self.assertIn("aggregate_id", outbox)
        self.assertIn("event_type", outbox)
        self.assertIn("md5(payload::text)", outbox)


if __name__ == "__main__":
    unittest.main()
