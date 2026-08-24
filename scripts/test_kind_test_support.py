#!/usr/bin/env python3
"""Unit tests for the stdlib-only Kind script helpers."""

from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parent))
from kind_test_support import grpcurl_call  # noqa: E402


class GrpcurlCallTest(unittest.TestCase):
    @patch("kind_test_support.shutil.which", return_value="/tmp/grpcurl")
    @patch("kind_test_support.subprocess.run")
    def test_tls_call_passes_ca_client_certificate_key_and_server_name(self, run, _which):
        run.return_value.returncode = 0
        run.return_value.stdout = json.dumps({"accepted": False})
        run.return_value.stderr = ""

        grpcurl_call(
            "127.0.0.1:19090",
            Path("contracts/src/main/proto"),
            "quota.proto",
            "io.agentteams.contracts.v1.QuotaService/Acquire",
            {},
            grpcurl="/tmp/grpcurl",
            tls=True,
            tls_ca="/tmp/ca.crt",
            tls_cert="/tmp/worker.crt",
            tls_key="/tmp/worker.key",
            tls_server_name="gateway.agentteams.svc",
        )

        command = run.call_args.args[0]
        self.assertIn(["-cacert", "/tmp/ca.crt"], _pairs(command))
        self.assertIn(["-cert", "/tmp/worker.crt"], _pairs(command))
        self.assertIn(["-key", "/tmp/worker.key"], _pairs(command))
        self.assertIn(["-authority", "gateway.agentteams.svc"], _pairs(command))


def _pairs(command: list[str]) -> list[list[str]]:
    return [command[index:index + 2] for index in range(len(command) - 1)]


if __name__ == "__main__":
    unittest.main()
