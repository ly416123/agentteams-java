#!/usr/bin/env python3
"""Regression tests for the durable Kind OTLP trace snapshot."""

from __future__ import annotations

import importlib.util
import inspect
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

import yaml


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "validate-kind-otel.py"
SPEC = importlib.util.spec_from_file_location("validate_kind_otel", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class KindOtelSnapshotTest(unittest.TestCase):
    def test_collector_uses_file_exporter_with_read_only_snapshot_sidecar(self):
        resources = [
            item
            for item in yaml.safe_load_all(
                (ROOT / "deploy" / "kind-observability.yaml").read_text(encoding="utf-8")
            )
            if item
        ]
        by_name = {(item.get("kind"), item.get("metadata", {}).get("name")): item for item in resources}
        collector_config = by_name[("ConfigMap", "otel-collector-config")]["data"]["config.yaml"]
        collector = by_name[("Deployment", "otel-collector")]
        containers = {item["name"]: item for item in collector["spec"]["template"]["spec"]["containers"]}

        self.assertIn("file/ci:", collector_config)
        self.assertIn("path: /var/lib/otelcol/traces.jsonl", collector_config)
        self.assertIn("exporters: [debug, file/ci]", collector_config)
        self.assertIn("trace-reader", containers)
        self.assertTrue(containers["trace-reader"]["volumeMounts"][0]["readOnly"])

    def test_reads_snapshot_via_sidecar_instead_of_ephemeral_container_logs(self):
        completed = subprocess.CompletedProcess([], 0, '{"resourceSpans":[]}\n', "")
        with patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            reader = getattr(MODULE, "collector_snapshot", lambda *_: "")
            snapshot = reader("agentteams", "otel-collector", "/var/lib/otelcol/traces.jsonl", 7)

        self.assertEqual('{"resourceSpans":[]}\n', snapshot)
        command = run.call_args.args[0]
        self.assertIn("exec", command)
        self.assertIn("trace-reader", command)
        self.assertNotIn("logs", command)
        self.assertEqual(7, run.call_args.kwargs["timeout"])

    def test_snapshot_reader_classifies_a_stuck_kubectl_exec(self):
        self.assertIn("command_timeout", inspect.signature(MODULE.collector_snapshot).parameters)
        with patch.object(
                MODULE.subprocess, "run",
                side_effect=subprocess.TimeoutExpired(["kubectl", "exec"], 5)):
            with self.assertRaisesRegex(RuntimeError, "timed out reading OTLP trace snapshot"):
                MODULE.collector_snapshot(
                    "agentteams", "otel-collector", "/var/lib/otelcol/traces.jsonl", 5)

    def test_extracts_shared_trace_ids_from_otlp_json_lines(self):
        lines = [
            {
                "resourceSpans": [{
                    "scopeSpans": [{
                        "spans": [
                            {"traceId": "shared-trace", "name": "agentteams.nats.outbox.publish"},
                            {"traceId": "shared-trace", "name": "agentteams.nats.gateway.consume"},
                            {"traceId": "other-trace", "name": "http get /actuator/health/**"},
                        ]
                    }]
                }]
            },
            {
                "resourceSpans": [{
                    "scopeSpans": [{
                        "spans": [
                            {"traceId": "shared-trace", "name": "agentteams.worker.grpc.consume"},
                            {"traceId": "shared-trace", "name": "agentteams.grpc.agentchannel.server"},
                        ]
                    }]
                }]
            },
        ]
        snapshot = "\n".join(json.dumps(line) for line in lines) + "\n{\"resourceSpans\":"
        parser = getattr(MODULE, "trace_ids_by_span", lambda *_: {})

        actual = parser(snapshot, (
            "agentteams.nats.outbox.publish",
            "agentteams.nats.gateway.consume",
            "agentteams.worker.grpc.consume",
        ))

        self.assertEqual({
            "agentteams.nats.outbox.publish": {"shared-trace"},
            "agentteams.nats.gateway.consume": {"shared-trace"},
            "agentteams.worker.grpc.consume": {"shared-trace"},
        }, actual)

    def test_rejects_corrupt_complete_json_lines_but_allows_an_incomplete_tail(self):
        parser = getattr(MODULE, "trace_ids_by_span", lambda *_: {})
        valid = json.dumps({"spans": [{
            "traceId": "shared-trace",
            "name": "agentteams.nats.outbox.publish",
        }]})

        with self.assertRaisesRegex(ValueError, "line 2"):
            parser(valid + "\nnot-json\n" + valid + "\n", ("agentteams.nats.outbox.publish",))

        self.assertEqual(
            {"agentteams.nats.outbox.publish": {"shared-trace"}},
            parser(valid + "\n{\"resourceSpans\":", ("agentteams.nats.outbox.publish",)),
        )

    def test_main_rejects_missing_required_server_without_snapshot_leak(self):
        snapshot = json.dumps({"resourceSpans": [{"scopeSpans": [{"spans": [
            {"traceId": "trace-a", "name": "agentteams.nats.outbox.publish",
             "attributes": [{"key": "secret-marker", "value": {"stringValue": "do-not-print"}}]},
            {"traceId": "trace-b", "name": "agentteams.nats.gateway.consume"},
            {"traceId": "trace-c", "name": "agentteams.worker.grpc.consume"},
        ]}]}]}) + "\n"
        stderr = io.StringIO()
        with patch.object(sys, "argv", ["validate-kind-otel.py", "--timeout", "1"]), \
                patch.object(MODULE, "collector_snapshot", return_value=snapshot), \
                patch.object(MODULE.time, "monotonic", side_effect=[0, 0, 2]), \
                patch.object(MODULE.time, "sleep"), redirect_stderr(stderr):
            with self.assertRaises(SystemExit):
                MODULE.main()

        diagnostic = stderr.getvalue()
        self.assertIn("agentteams.grpc.agentchannel.server", diagnostic)
        self.assertNotIn("do-not-print", diagnostic)

    def test_main_rejects_mismatched_continuity_trace_ids(self):
        snapshot = json.dumps({"resourceSpans": [{"scopeSpans": [{"spans": [
            {"traceId": "trace-a", "name": "agentteams.nats.outbox.publish"},
            {"traceId": "trace-b", "name": "agentteams.nats.gateway.consume"},
            {"traceId": "trace-c", "name": "agentteams.worker.grpc.consume"},
            {"traceId": "trace-d", "name": "agentteams.grpc.agentchannel.server"},
        ]}]}]}) + "\n"
        stderr = io.StringIO()
        with patch.object(sys, "argv", ["validate-kind-otel.py", "--timeout", "1"]), \
                patch.object(MODULE, "collector_snapshot", return_value=snapshot), \
                patch.object(MODULE.time, "monotonic", side_effect=[0, 0, 2]), \
                patch.object(MODULE.time, "sleep"), redirect_stderr(stderr):
            with self.assertRaises(SystemExit):
                MODULE.main()

        self.assertIn("one shared trace ID across continuity spans", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
