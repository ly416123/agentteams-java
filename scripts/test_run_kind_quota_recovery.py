#!/usr/bin/env python3
"""Unit tests for the Kind quota recovery orchestration."""

from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("run-kind-quota-recovery.py")
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("run_kind_quota_recovery", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class _Forward:
    def __init__(self):
        self.closed = False
        self.started_with = None

    def close(self):
        self.closed = True

    def start(self, timeout):
        self.started_with = timeout
        return self


class RestartControlPlaneTest(unittest.TestCase):
    @patch.object(MODULE, "PortForward")
    @patch.object(MODULE, "run")
    def test_restarts_port_forward_after_control_plane_rollout(self, run, port_forward):
        current = _Forward()
        replacement = _Forward()
        port_forward.return_value = replacement
        args = argparse.Namespace(
            namespace="agentteams",
            control_plane_service="agentteams-agentteams-java-control-plane",
            control_plane_port=18086,
            restart_timeout=120.0,
        )

        result = MODULE.restart_control_plane_forward(args, current)

        self.assertIs(result, replacement)
        self.assertTrue(current.closed)
        self.assertEqual(replacement.started_with, 120.0)
        port_forward.assert_called_once_with(
            "agentteams", "agentteams-agentteams-java-control-plane", 18086, 8080)
        self.assertEqual(run.call_count, 2)


class AcquireRetryTest(unittest.TestCase):
    @patch.object(MODULE, "grpc_call")
    @patch.object(MODULE, "wait_until")
    def test_retries_transient_internal_response_until_same_reservation_returns(self, wait_until, grpc_call):
        grpc_call.side_effect = [
            {"accepted": False, "protocolError": "QUOTA_PROTOCOL_ERROR_INTERNAL"},
            {"accepted": True, "reservationId": "reservation-1"},
        ]

        def execute_wait(_description, predicate, **_kwargs):
            predicate()
            return predicate()

        wait_until.side_effect = execute_wait
        args = argparse.Namespace(timeout=120.0)

        result = MODULE.wait_for_idempotent_acquire(
            args, MODULE.Scope("tenant-a", "project-a"), "acquire-key", "reservation-1")

        self.assertTrue(result["accepted"])
        self.assertEqual(result["reservationId"], "reservation-1")
        self.assertEqual(grpc_call.call_count, 2)


if __name__ == "__main__":
    unittest.main()
