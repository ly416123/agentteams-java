#!/usr/bin/env python3
"""Verify that the Kind OTLP collector received spans from the application path."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time


def fail(message: str) -> None:
    print(f"OTEL_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def collector_snapshot(namespace: str, deployment: str, snapshot_path: str,
                       command_timeout: float) -> str:
    try:
        result = subprocess.run(
            ["kubectl", "-n", namespace, "exec", f"deployment/{deployment}",
             "-c", "trace-reader", "--", "cat", snapshot_path],
            check=False,
            capture_output=True,
            text=True,
            timeout=command_timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("timed out reading OTLP trace snapshot") from exc
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "unable to read OTLP trace snapshot")
    return result.stdout


def trace_ids_by_span(snapshot: str, span_names: tuple[str, ...]) -> dict[str, set[str]]:
    trace_ids = {name: set() for name in span_names}
    lines = snapshot.splitlines()
    incomplete_tail = bool(lines) and not snapshot.endswith(("\n", "\r"))
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            root = json.loads(line)
        except (json.JSONDecodeError, TypeError) as exc:
            # The exporter may be appending the last JSON object while the
            # sidecar reads it. The next polling attempt observes the complete
            # line, so an incomplete tail is not a validation failure.
            if incomplete_tail and index == len(lines) - 1:
                continue
            raise ValueError(f"invalid OTLP JSON at line {index + 1}") from exc
        pending = [root]
        while pending:
            value = pending.pop()
            if isinstance(value, dict):
                name = value.get("name")
                trace_id = value.get("traceId", value.get("trace_id"))
                if name in trace_ids and isinstance(trace_id, str) and trace_id:
                    trace_ids[name].add(trace_id)
                pending.extend(value.values())
            elif isinstance(value, list):
                pending.extend(value)
    return trace_ids


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--deployment", default="otel-collector")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--command-timeout", type=float, default=10.0)
    parser.add_argument("--snapshot-path", default="/var/lib/otelcol/traces.jsonl")
    args = parser.parse_args()
    if args.timeout <= 0 or args.command_timeout <= 0:
        parser.error("--timeout and --command-timeout must be positive")

    required_spans = (
        "agentteams.nats.outbox.publish",
        "agentteams.nats.gateway.consume",
        "agentteams.grpc.agentchannel.server",
        "agentteams.worker.grpc.consume",
    )
    continuity_spans = (
        "agentteams.nats.outbox.publish",
        "agentteams.nats.gateway.consume",
        "agentteams.worker.grpc.consume",
    )
    deadline = time.monotonic() + args.timeout
    last_trace_ids = {name: set() for name in required_spans}
    last_error = ""
    missing = list(required_spans)
    while time.monotonic() < deadline:
        try:
            snapshot = collector_snapshot(
                args.namespace, args.deployment, args.snapshot_path,
                min(args.command_timeout, float(args.timeout)))
            last_trace_ids = trace_ids_by_span(snapshot, required_spans)
            missing = [span for span in required_spans if not last_trace_ids[span]]
            if not missing:
                shared_trace_ids = set.intersection(
                    *(last_trace_ids[span] for span in continuity_spans))
                if shared_trace_ids:
                    print("OTEL_OK trace_id=" + next(iter(shared_trace_ids)) + " " + " ".join(required_spans))
                    return
                missing = ["one shared trace ID across continuity spans"]
            last_error = ""
        except (OSError, RuntimeError, ValueError) as exc:
            missing = list(required_spans)
            last_error = str(exc)
        time.sleep(3)
    observed = {name: len(ids) for name, ids in last_trace_ids.items()}
    suffix = f"; snapshot error: {last_error}" if last_error else ""
    fail(f"collector did not receive required spans: {missing}; observed trace counts: {observed}{suffix}")


if __name__ == "__main__":
    main()
