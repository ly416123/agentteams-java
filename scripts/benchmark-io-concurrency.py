#!/usr/bin/env python3
"""Measure blocking HTTP/SSE I/O under platform and virtual-thread modes.

The harness is intentionally client-side and conservative: it never claims an
optional server metric that was not exposed by the target, and it does not run
the long experiment unless invoked explicitly.  ``platform`` and ``virtual``
are labels for separately deployed server configurations; the harness does not
change the target deployment.  The default path targets QwenPaw's native
console API, while the client itself uses a bounded Python thread pool for
reproducible load.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
import json
import platform
import socket
import sys
import threading
import time
from pathlib import Path
import urllib.error
import urllib.request
import uuid


DEFAULT_CONCURRENCIES = (16, 64, 128, 256)
DEFAULT_PATH = "/api/console/chat"
DEFAULT_TARGET = "qwenpaw"
MANAGER_CONVERSATIONS_PATH = "/api/v1/conversations"
METRIC_KEYS = (
    "rss_bytes",
    "gc_pause_count",
    "db_pool_waiting",
    "nats_backlog",
    "outbox_oldest_pending_age_seconds",
    "upstream_error_rate",
)
METRIC_NAMES = {
    "rss_bytes": ("process_resident_memory_bytes",),
    "gc_pause_count": ("jvm_gc_pause_seconds_count",),
    "db_pool_waiting": ("hikaricp_connections_pending",),
    "nats_backlog": ("agentteams_nats_backlog", "agentteams_outbox_backlog"),
    "outbox_oldest_pending_age_seconds": ("agentteams_outbox_oldest_pending_age_seconds",),
    "upstream_error_rate": ("agentteams_upstream_error_rate",),
}


@dataclass(frozen=True)
class BenchmarkOptions:
    base_url: str
    path: str
    duration_seconds: float
    warmup_seconds: float
    runs: int
    mode: str
    concurrencies: tuple[int, ...]
    cancel_after_seconds: float | None
    timeout_seconds: float
    output: str | None
    target: str = DEFAULT_TARGET
    bearer_token: str | None = None
    project: str | None = None
    team: str | None = None


def validate_options(base_url: str, path: str, duration_seconds: float,
                     warmup_seconds: float, runs: int, mode: str,
                     concurrencies: list[int] | tuple[int, ...] | None,
                     cancel_after_seconds: float | None = None,
                     timeout_seconds: float = 30.0,
                     output: str | None = None,
                     target: str = DEFAULT_TARGET,
                     bearer_token: str | None = None,
                     project: str | None = None,
                     team: str | None = None) -> BenchmarkOptions:
    if not isinstance(base_url, str) or not base_url.strip():
        raise ValueError("base_url must not be blank")
    if not base_url.startswith(("http://", "https://")):
        raise ValueError("base_url must use http or https")
    if not isinstance(path, str) or not path.startswith("/"):
        raise ValueError("path must start with '/'")
    for name, value in (("duration_seconds", duration_seconds),
                        ("timeout_seconds", timeout_seconds)):
        if not isinstance(value, (int, float)) or value <= 0:
            raise ValueError(f"{name} must be positive")
    if not isinstance(warmup_seconds, (int, float)) or warmup_seconds < 0:
        raise ValueError("warmup_seconds must not be negative")
    if not isinstance(runs, int) or runs <= 0:
        raise ValueError("runs must be positive")
    if mode not in {"platform", "virtual", "both"}:
        raise ValueError("mode must be platform, virtual, or both")
    if target not in {"qwenpaw", "manager"}:
        raise ValueError("target must be qwenpaw or manager")
    if target == "manager" and (not isinstance(bearer_token, str) or not bearer_token.strip()):
        raise ValueError("manager target requires bearer_token")
    selected = tuple(DEFAULT_CONCURRENCIES if concurrencies is None else concurrencies)
    if not selected or any(not isinstance(value, int) or value <= 0 for value in selected):
        raise ValueError("concurrencies must contain positive integers")
    if cancel_after_seconds is not None and cancel_after_seconds <= 0:
        raise ValueError("cancel_after_seconds must be positive")
    if cancel_after_seconds is not None and cancel_after_seconds >= timeout_seconds:
        raise ValueError("cancel_after_seconds must be less than timeout_seconds")
    return BenchmarkOptions(
        base_url=base_url.rstrip("/"), path=path,
        duration_seconds=float(duration_seconds), warmup_seconds=float(warmup_seconds),
        runs=runs, mode=mode, concurrencies=selected,
        cancel_after_seconds=None if cancel_after_seconds is None else float(cancel_after_seconds),
        timeout_seconds=float(timeout_seconds), output=output,
        target=target, bearer_token=bearer_token.strip() if bearer_token else None,
        project=project.strip() if project and project.strip() else None,
        team=team.strip() if team and team.strip() else None)


def percentile(values: list[float], quantile: float) -> float | None:
    """Return a linearly interpolated percentile, or None for no samples."""
    if not values:
        return None
    if not 0 <= quantile <= 1:
        raise ValueError("quantile must be between 0 and 1")
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summarize(mode: str, concurrency: int, run: int, outcomes: list[dict],
              metrics: dict[str, float | None]) -> dict:
    latencies = [float(item["latency_seconds"]) for item in outcomes
                 if item.get("latency_seconds") is not None]
    cancel_latencies = [float(item["cancel_latency_seconds"]) for item in outcomes
                        if item.get("cancel_latency_seconds") is not None]
    return {
        "mode": mode, "concurrency": concurrency, "run": run,
        "total": len(outcomes),
        "success": sum(item.get("outcome") == "success" for item in outcomes),
        "error": sum(item.get("outcome") == "error" for item in outcomes),
        "cancel": sum(item.get("outcome") == "cancel" for item in outcomes),
        "throughput_requests_per_second": (len(outcomes) / max(
            max((float(item.get("elapsed_seconds", 0.0)) for item in outcomes), default=0.0), 1e-9)),
        "latency_seconds": {
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
        },
        "cancel_latency_seconds": {
            "p50": percentile(cancel_latencies, 0.50),
            "p95": percentile(cancel_latencies, 0.95),
            "p99": percentile(cancel_latencies, 0.99),
        },
        "metrics": {key: metrics.get(key) for key in METRIC_KEYS},
    }


def _parse_prometheus(body: str) -> dict[str, float]:
    values: dict[str, float] = {}
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        name = line.split("{", 1)[0].split(" ", 1)[0]
        raw = line.rsplit(" ", 1)[-1]
        try:
            values[name] = float(raw)
        except ValueError:
            continue
    return values


def scrape_metrics(base_url: str, timeout_seconds: float,
                   bearer_token: str | None = None) -> dict[str, float | None]:
    metrics: dict[str, float | None] = {key: None for key in METRIC_KEYS}
    headers = {"Accept": "text/plain"}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    request = urllib.request.Request(base_url.rstrip("/") + "/actuator/prometheus",
                                     headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                return metrics
            values = _parse_prometheus(response.read().decode("utf-8", errors="replace"))
    except (OSError, urllib.error.URLError):
        return metrics
    for key, names in METRIC_NAMES.items():
        for name in names:
            if name in values:
                metrics[key] = values[name]
                break
    return metrics


def _sse_status(line: str) -> str | None:
    if not line.startswith("data:"):
        return None
    data = line[5:].strip()
    if data == "[DONE]":
        return "completed"
    try:
        value = json.loads(data)
    except json.JSONDecodeError:
        return None
    if not isinstance(value, dict):
        return None
    status = value.get("status")
    return str(status).lower() if status is not None else None


def build_request_payload(session_id: str) -> dict:
    """Build the AgentScope AgentRequest expected by QwenPaw console chat."""
    return {
        "input": [{
            "role": "user",
            "content": [{"type": "text", "text": "benchmark"}],
        }],
        "session_id": session_id,
        "user_id": "benchmark",
        "channel": "console",
    }


def build_manager_create_payload(session_id: str, project: str | None = None,
                                 team: str | None = None) -> dict:
    """Build the Manager Conversation API create request."""
    payload = {"sessionId": session_id}
    if project:
        payload["project"] = project
    if team:
        payload["team"] = team
    return payload


def build_manager_message_payload(content: str) -> dict:
    """Build the Manager Conversation API message request."""
    return {"content": content}


def manager_event_outcome(event_type: str | None) -> str | None:
    """Map stable Manager SSE event types to benchmark outcomes."""
    return {
        "message.completed": "success",
        "conversation.failed": "error",
        "conversation.cancelled": "cancel",
    }.get(event_type)


def _request_qwenpaw_once(options: BenchmarkOptions) -> dict:
    started = time.monotonic()
    session_id = str(uuid.uuid4())
    request = urllib.request.Request(
        options.base_url + options.path,
        data=json.dumps(build_request_payload(session_id)).encode("utf-8"),
        headers={"Accept": "text/event-stream", "Content-Type": "application/json",
                 "X-Agent-Id": "default", "Idempotency-Key": str(uuid.uuid4())},
        method="POST")
    cancel_timer: threading.Timer | None = None
    cancelled = threading.Event()
    response = None
    try:
        response = urllib.request.urlopen(request, timeout=options.timeout_seconds)
        if options.cancel_after_seconds is not None:
            def cancel_response() -> None:
                cancelled.set()
                if response is not None:
                    response.close()
            cancel_timer = threading.Timer(options.cancel_after_seconds, cancel_response)
            cancel_timer.daemon = True
            cancel_timer.start()
        while True:
            line = response.readline()
            if not line:
                break
            status = _sse_status(line.decode("utf-8", errors="replace").rstrip("\r\n"))
            if status in {"completed", "success"}:
                elapsed = time.monotonic() - started
                return {"outcome": "success", "latency_seconds": elapsed,
                        "elapsed_seconds": elapsed}
            if status in {"failed", "error"}:
                elapsed = time.monotonic() - started
                return {"outcome": "error", "latency_seconds": elapsed,
                        "elapsed_seconds": elapsed}
        elapsed = time.monotonic() - started
        if cancelled.is_set():
            return {"outcome": "cancel", "latency_seconds": elapsed,
                    "cancel_latency_seconds": max(0.0, elapsed - float(options.cancel_after_seconds)),
                    "elapsed_seconds": elapsed}
        return {"outcome": "error", "latency_seconds": elapsed, "elapsed_seconds": elapsed}
    except (urllib.error.HTTPError, urllib.error.URLError, OSError, socket.timeout):
        elapsed = time.monotonic() - started
        if cancelled.is_set():
            return {"outcome": "cancel", "latency_seconds": elapsed,
                    "cancel_latency_seconds": max(0.0, elapsed - float(options.cancel_after_seconds)),
                    "elapsed_seconds": elapsed}
        return {"outcome": "error", "latency_seconds": elapsed, "elapsed_seconds": elapsed}
    finally:
        if cancel_timer is not None:
            cancel_timer.cancel()
        if response is not None:
            response.close()


def _manager_json_request(options: BenchmarkOptions, method: str, path: str,
                          payload: dict | None = None) -> bytes:
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {options.bearer_token}",
        "Content-Type": "application/json",
        "Idempotency-Key": str(uuid.uuid4()),
    }
    request = urllib.request.Request(
        options.base_url + path,
        data=None if payload is None else json.dumps(payload).encode("utf-8"),
        headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=options.timeout_seconds) as response:
        if response.status < 200 or response.status >= 300:
            raise urllib.error.HTTPError(request.full_url, response.status,
                                         "unexpected Manager status", response.headers, None)
        return response.read()


def _request_manager_once(options: BenchmarkOptions) -> dict:
    started = time.monotonic()
    session_id = str(uuid.uuid4())
    session_path = f"{MANAGER_CONVERSATIONS_PATH}/{session_id}"
    response = None
    cancel_timer: threading.Timer | None = None
    cancelled = threading.Event()
    try:
        _manager_json_request(
            options, "POST", MANAGER_CONVERSATIONS_PATH,
            build_manager_create_payload(session_id, options.project, options.team))
        _manager_json_request(
            options, "POST", f"{session_path}/messages",
            build_manager_message_payload("benchmark"))
        request = urllib.request.Request(
            options.base_url + f"{session_path}/events?after=0",
            headers={"Accept": "text/event-stream",
                     "Authorization": f"Bearer {options.bearer_token}"},
            method="GET")
        response = urllib.request.urlopen(request, timeout=options.timeout_seconds)

        if options.cancel_after_seconds is not None:
            def cancel_manager_session() -> None:
                cancelled.set()
                try:
                    _manager_json_request(options, "POST", f"{session_path}/cancel", {})
                except (urllib.error.HTTPError, urllib.error.URLError, OSError, socket.timeout):
                    pass
                if response is not None:
                    response.close()

            cancel_timer = threading.Timer(options.cancel_after_seconds, cancel_manager_session)
            cancel_timer.daemon = True
            cancel_timer.start()

        event_type: str | None = None
        while True:
            line = response.readline()
            if not line:
                break
            decoded = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if decoded.startswith("event:"):
                event_type = decoded[6:].strip()
            elif not decoded:
                outcome = manager_event_outcome(event_type)
                event_type = None
                if outcome is not None:
                    elapsed = time.monotonic() - started
                    result = {"outcome": outcome, "latency_seconds": elapsed,
                              "elapsed_seconds": elapsed}
                    if outcome == "cancel":
                        result["cancel_latency_seconds"] = max(
                            0.0, elapsed - float(options.cancel_after_seconds or 0.0))
                    return result
        elapsed = time.monotonic() - started
        if cancelled.is_set():
            return {"outcome": "cancel", "latency_seconds": elapsed,
                    "cancel_latency_seconds": max(
                        0.0, elapsed - float(options.cancel_after_seconds or 0.0)),
                    "elapsed_seconds": elapsed}
        return {"outcome": "error", "latency_seconds": elapsed, "elapsed_seconds": elapsed}
    except (urllib.error.HTTPError, urllib.error.URLError, OSError, socket.timeout):
        elapsed = time.monotonic() - started
        if cancelled.is_set():
            return {"outcome": "cancel", "latency_seconds": elapsed,
                    "cancel_latency_seconds": max(
                        0.0, elapsed - float(options.cancel_after_seconds or 0.0)),
                    "elapsed_seconds": elapsed}
        return {"outcome": "error", "latency_seconds": elapsed, "elapsed_seconds": elapsed}
    finally:
        if cancel_timer is not None:
            cancel_timer.cancel()
        if response is not None:
            response.close()


def _request_once(options: BenchmarkOptions) -> dict:
    if options.target == "manager":
        return _request_manager_once(options)
    return _request_qwenpaw_once(options)


def _run_window(options: BenchmarkOptions, concurrency: int, duration: float) -> list[dict]:
    deadline = time.monotonic() + duration
    outcomes: list[dict] = []
    lock = threading.Lock()

    def worker() -> None:
        while time.monotonic() < deadline:
            outcome = _request_once(options)
            with lock:
                outcomes.append(outcome)

    with ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="benchmark-client") as pool:
        futures = [pool.submit(worker) for _ in range(concurrency)]
        for future in as_completed(futures):
            future.result()
    return outcomes


def run_benchmark(options: BenchmarkOptions) -> list[dict]:
    modes = ("platform", "virtual") if options.mode == "both" else (options.mode,)
    samples: list[dict] = []
    for mode in modes:
        for concurrency in options.concurrencies:
            for run in range(1, options.runs + 1):
                if options.warmup_seconds:
                    _run_window(options, concurrency, options.warmup_seconds)
                started = time.monotonic()
                outcomes = _run_window(options, concurrency, options.duration_seconds)
                elapsed = max(time.monotonic() - started, 1e-9)
                for outcome in outcomes:
                    outcome["elapsed_seconds"] = elapsed
                sample = summarize(mode, concurrency, run, outcomes,
                                   scrape_metrics(options.base_url, options.timeout_seconds,
                                                  options.bearer_token))
                # Keep per-request observations so the JSON is an auditable raw
                # artifact, rather than only a lossy percentile summary.
                sample["raw_outcomes"] = outcomes
                samples.append(sample)
    return samples


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--path", default=DEFAULT_PATH)
    parser.add_argument("--target", choices=("qwenpaw", "manager"), default=DEFAULT_TARGET,
                        help="request protocol; manager uses /api/v1/conversations")
    parser.add_argument("--bearer-token", help="Manager Bearer token; required with --target manager")
    parser.add_argument("--project", help="optional Manager project id or project key")
    parser.add_argument("--team", help="optional Manager team id or team key")
    parser.add_argument("--duration-seconds", type=float, default=300.0)
    parser.add_argument("--warmup-seconds", type=float, default=60.0)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--mode", choices=("platform", "virtual", "both"), default="both",
                        help="label for the separately deployed server configuration; does not switch it")
    parser.add_argument("--concurrency", dest="concurrencies", type=int, action="append",
                        help="repeat for one or more levels; default: 16,64,128,256")
    parser.add_argument("--cancel-after-seconds", type=float)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--output", help="write raw JSON to this path")
    parser.add_argument("--dry-run", action="store_true", help="validate and print the experiment plan")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        options = validate_options(
            args.base_url, args.path, args.duration_seconds, args.warmup_seconds,
            args.runs, args.mode, args.concurrencies, args.cancel_after_seconds,
            args.timeout_seconds, args.output, args.target, args.bearer_token,
            args.project, args.team)
    except ValueError as error:
        print(f"invalid benchmark options: {error}", file=sys.stderr)
        return 2
    if args.dry_run:
        print(json.dumps(asdict(options), ensure_ascii=False, sort_keys=True))
        return 0
    samples = run_benchmark(options)
    document = {"options": asdict(options),
                "environment": {"python": sys.version, "platform": platform.platform()},
                "samples": samples,
                "generated_at_epoch_seconds": time.time()}
    encoded = json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True)
    if options.output:
        output = Path(options.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded + "\n", encoding="utf-8")
    else:
        print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
