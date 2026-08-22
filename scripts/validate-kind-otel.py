#!/usr/bin/env python3
"""Verify that the Kind OTLP collector received spans from the application path."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time


def fail(message: str) -> None:
    print(f"OTEL_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def collector_logs(namespace: str, deployment: str) -> str:
    result = subprocess.run(
        ["kubectl", "-n", namespace, "logs", f"deployment/{deployment}", "--tail=10000"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "unable to read OTLP collector logs")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--deployment", default="otel-collector")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

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
    last_logs = ""
    while time.monotonic() < deadline:
        try:
            last_logs = collector_logs(args.namespace, args.deployment)
            missing = [span for span in required_spans if span not in last_logs]
            if not missing:
                blocks = re.split(r"(?=Span #\d+)", last_logs)
                trace_ids_by_span = {}
                for span in continuity_spans:
                    matching = [block for block in blocks if span in block]
                    trace_ids_by_span[span] = {
                        match.group(1)
                        for block in matching
                        for match in [re.search(r"Trace ID\s*:\s*([0-9a-f]+)", block)]
                        if match
                    }
                if all(trace_ids_by_span.values()):
                    shared_trace_ids = set.intersection(*trace_ids_by_span.values())
                else:
                    shared_trace_ids = set()
                if shared_trace_ids:
                    print("OTEL_OK trace_id=" + next(iter(shared_trace_ids)) + " " + " ".join(required_spans))
                    return
                if not any(trace_ids_by_span.values()):
                    missing = ["trace IDs for required spans"]
                else:
                    missing = [f"one shared trace ID across continuity spans, got {trace_ids_by_span}"]
        except (OSError, RuntimeError) as exc:
            missing = list(required_spans)
            last_logs = str(exc)
        time.sleep(3)
    fail(f"collector did not receive required spans: {missing}; last output: {last_logs[-2000:]}")


if __name__ == "__main__":
    main()
