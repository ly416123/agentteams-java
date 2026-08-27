#!/usr/bin/env python3
"""Run a deterministic, bounded quota reservation load test.

The harness deliberately prints only aggregate counters.  It never prints a
quota request, response, scope claim, token, prompt, or grpcurl diagnostic.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import random
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from kind_test_support import KindTestError, grpcurl_call  # noqa: E402


@dataclass(frozen=True)
class LoadOptions:
    concurrency: int
    requests: int
    projects: int
    timeout: float
    seed: int
    tenant: str
    max_concurrent: int
    estimated_tokens: int


class QuotaLoadError(RuntimeError):
    """A safe, stable failure category for the load harness."""


def validate_options(concurrency: int, requests: int, projects: int, timeout: float,
                     seed: int, tenant: str, max_concurrent: int,
                     estimated_tokens: int) -> LoadOptions:
    values = {
        "concurrency": concurrency,
        "requests": requests,
        "projects": projects,
        "max_concurrent": max_concurrent,
        "estimated_tokens": estimated_tokens,
    }
    for name, value in values.items():
        if not isinstance(value, int) or value < 0 or (name != "estimated_tokens" and value == 0):
            raise ValueError(f"{name} must be a positive integer")
    if estimated_tokens < 0:
        raise ValueError("estimated_tokens must not be negative")
    if projects > concurrency:
        raise ValueError("projects must not exceed concurrency")
    if not isinstance(timeout, (int, float)) or timeout <= 0:
        raise ValueError("timeout must be positive")
    if not isinstance(tenant, str) or not tenant.strip():
        raise ValueError("tenant must not be blank")
    return LoadOptions(concurrency, requests, projects, float(timeout), seed,
                       tenant.strip(), max_concurrent, estimated_tokens)


def _deadline(timeout: float) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=max(timeout, 1.0))).isoformat().replace("+00:00", "Z")


def _request_metadata(index: int) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "sequence": index,
    }


def _acquire_request(tenant: str, project: str, key: str, estimated_tokens: int,
                     max_concurrent: int, index: int, timeout: float) -> dict:
    return {
        "metadata": _request_metadata(index),
        "protocolVersion": {"major": 2, "minor": 3},
        "tenantId": tenant,
        "projectId": project,
        "idempotencyKey": key,
        "estimatedTokens": estimated_tokens,
        "maxConcurrent": max_concurrent,
        "deadline": _deadline(timeout),
    }


def _release_request(tenant: str, project: str, reservation_id: str, key: str,
                     index: int, timeout: float) -> dict:
    return {
        "metadata": _request_metadata(index),
        "protocolVersion": {"major": 2, "minor": 3},
        "tenantId": tenant,
        "projectId": project,
        "reservationId": reservation_id,
        "idempotencyKey": key,
        "deadline": _deadline(timeout),
    }


def _protocol(response: dict, field: str) -> str:
    value = response.get(field, "") if isinstance(response, dict) else ""
    return str(value).upper()


def _is_timeout(response: dict) -> bool:
    return "DEADLINE_EXCEEDED" in _protocol(response, "protocolError")


def _is_accepted(response: dict) -> bool:
    return isinstance(response, dict) and bool(response.get("accepted"))


def _one_request(call, options: LoadOptions, project: str, key: str, index: int,
                 state: dict, lock: threading.Lock) -> dict:
    result = {"outcome": "rejected", "released": True, "duplicate": 0,
              "observed_concurrency": 0, "project": project}
    reservation_id = None
    acquired = False
    try:
        response = call("Acquire", _acquire_request(
            options.tenant, project, key, options.estimated_tokens,
            options.max_concurrent, index, options.timeout), options.timeout)
        if not _is_accepted(response):
            result["outcome"] = "timeout" if _is_timeout(response) else "rejected"
            return result
        reservation_id = str(response.get("reservationId", ""))
        if not reservation_id:
            result["released"] = False
            result["protocol_violation"] = True
            return result
        acquired = True
        with lock:
            state["active"] += 1
            state["max"] = max(state["max"], state["active"])
            result["observed_concurrency"] = state["active"]

        duplicate = call("Acquire", _acquire_request(
            options.tenant, project, key, options.estimated_tokens,
            options.max_concurrent, index, options.timeout), options.timeout)
        if _is_accepted(duplicate) and str(duplicate.get("reservationId", "")) == reservation_id:
            result["duplicate"] = 1
        else:
            result["invariant_failure"] = True
        result["outcome"] = "success"
        return result
    except (TimeoutError, subprocess.TimeoutExpired):
        result["outcome"] = "timeout"
        return result
    except (OSError, QuotaLoadError, KindTestError) as error:
        raise QuotaLoadError("quota RPC unavailable") from error
    finally:
        if acquired and reservation_id:
            try:
                released = call("Release", _release_request(
                    options.tenant, project, reservation_id,
                    f"{key}-release", index, options.timeout), options.timeout)
                result["released"] = _is_accepted(released)
            except (TimeoutError, subprocess.TimeoutExpired, OSError, QuotaLoadError, KindTestError):
                result["released"] = False
            with lock:
                state["active"] -= 1


def run_load(call, concurrency: int, requests: int, projects: int, timeout: float,
             seed: int, tenant: str, max_concurrent: int,
             estimated_tokens: int, project_names: list[str] | None = None) -> list[dict]:
    options = validate_options(concurrency, requests, projects, timeout, seed,
                               tenant, max_concurrent, estimated_tokens)
    rng = random.Random(options.seed)
    run_id = uuid.uuid4().hex
    names = project_names or [f"quota-load-{run_id}-project-{index}" for index in range(options.projects)]
    if len(names) != options.projects or any(not isinstance(name, str) or not name.strip() for name in names):
        raise ValueError("project_names must contain one non-empty name per project")
    work = [(f"quota-load-{run_id}-{index}", names[rng.randrange(options.projects)], index)
            for index in range(options.requests)]
    state = {"active": 0, "max": 0}
    lock = threading.Lock()
    results: list[dict] = []
    with ThreadPoolExecutor(max_workers=options.concurrency) as executor:
        futures = [executor.submit(_one_request, call, options, project, key, index, state, lock)
                   for key, project, index in work]
        for future in as_completed(futures):
            results.append(future.result())
    with lock:
        maximum = state["max"]
    for result in results:
        result["observed_concurrency"] = max(result["observed_concurrency"], maximum)
    return results


def summarize_results(results: list[dict], requests: int, projects: int, seed: int) -> dict:
    return {
        "success": sum(item.get("outcome") == "success" for item in results),
        "rejected": sum(item.get("outcome") == "rejected" for item in results),
        "timeout": sum(item.get("outcome") == "timeout" for item in results),
        "duplicate": sum(int(item.get("duplicate", 0)) for item in results),
        "max_observed_concurrency": max(
            (int(item.get("observed_concurrency", 0)) for item in results), default=0),
        "unreleased_reservations": sum(not item.get("released", True) for item in results),
        "requests": requests,
        "projects": projects,
        "seed": seed,
    }


def _configure_quota(base_url: str, tenant: str, project: str, options: LoadOptions) -> None:
    body = json.dumps({
        "tenantId": tenant,
        "projectId": project,
        "maxConcurrentCalls": options.max_concurrent,
        "maxDailyCalls": options.requests * 3,
        "maxDailyTokens": options.requests * options.estimated_tokens * 3,
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/usage/quota", data=body,
        headers={"Content-Type": "application/json"}, method="PUT")
    try:
        with urllib.request.urlopen(request, timeout=options.timeout) as response:
            if response.status != 200:
                raise QuotaLoadError("quota configuration rejected")
    except (OSError, urllib.error.URLError) as error:
        raise QuotaLoadError("quota configuration unavailable") from error


def _read_quota(base_url: str, tenant: str, project: str, timeout: float) -> dict:
    query = urllib.parse.urlencode({"tenantId": tenant, "projectId": project})
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/usage/quota?{query}", method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise QuotaLoadError("quota snapshot rejected")
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise QuotaLoadError("quota snapshot unavailable") from error
    if not isinstance(payload, dict):
        raise QuotaLoadError("quota snapshot unavailable")
    try:
        return {
            "currentConcurrentCalls": int(payload["currentConcurrentCalls"]),
            "dailyCalls": int(payload["dailyCalls"]),
            "dailyTokens": int(payload["dailyTokens"]),
        }
    except (KeyError, TypeError, ValueError) as error:
        raise QuotaLoadError("quota snapshot unavailable") from error


def _grpc_call_factory(args: argparse.Namespace):
    proto_root = Path(args.proto_root).resolve()
    def call(method: str, request: dict, timeout: float) -> dict:
        return grpcurl_call(
            args.gateway_address, proto_root, args.proto_file,
            f"io.agentteams.contracts.v1.QuotaService/{method}", request,
            grpcurl=args.grpcurl, tls=args.tls, tls_ca=args.gateway_ca,
            tls_cert=args.gateway_client_cert, tls_key=args.gateway_client_key,
            tls_server_name=args.gateway_server_name, timeout=timeout)
    return call


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-address", default=os.environ.get("AGENTTEAMS_QUOTA_GATEWAY_ADDRESS", "127.0.0.1:19091"))
    parser.add_argument("--control-plane-url", default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", ""))
    parser.add_argument("--grpcurl", default=os.environ.get("GRPCURL_BIN"))
    parser.add_argument("--proto-root", default=str(ROOT / "contracts" / "src" / "main" / "proto"))
    parser.add_argument("--proto-file", default="quota.proto")
    parser.add_argument("--tls", action="store_true")
    parser.add_argument("--gateway-ca", default="")
    parser.add_argument("--gateway-client-cert", default="")
    parser.add_argument("--gateway-client-key", default="")
    parser.add_argument("--gateway-server-name", default="")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--projects", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--seed", type=int, default=20260828)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--max-concurrent", type=int, default=1)
    parser.add_argument("--estimated-tokens", type=int, default=10)
    args = parser.parse_args(argv)
    args.options = validate_options(args.concurrency, args.requests, args.projects,
                                    args.timeout, args.seed, args.tenant,
                                    args.max_concurrent, args.estimated_tokens)
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    options = args.options
    run_id = uuid.uuid4().hex[:12]
    project_names = [f"quota-load-{run_id}-project-{index}" for index in range(options.projects)]
    baselines = {}
    if args.control_plane_url:
        for project in project_names:
            _configure_quota(args.control_plane_url, options.tenant, project, options)
            baselines[project] = _read_quota(args.control_plane_url, options.tenant,
                                              project, options.timeout)
    results = run_load(_grpc_call_factory(args), options.concurrency,
                       options.requests, options.projects, options.timeout, options.seed,
                       options.tenant, options.max_concurrent, options.estimated_tokens,
                       project_names)
    summary = summarize_results(results, options.requests, options.projects, options.seed)
    if any(item.get("protocol_violation") for item in results):
        print("QUOTA_LOAD_FAIL: accepted response missing reservation", file=sys.stderr)
        return 1
    if any(item.get("invariant_failure") for item in results):
        print("QUOTA_LOAD_FAIL: duplicate reservation invariant failed", file=sys.stderr)
        return 1
    if args.control_plane_url:
        for project in project_names:
            final = _read_quota(args.control_plane_url, options.tenant,
                                project, options.timeout)
            baseline = baselines[project]
            expected_calls = sum(item.get("outcome") == "success" and item.get("project") == project
                                 for item in results)
            expected_tokens = expected_calls * options.estimated_tokens
            if (final["currentConcurrentCalls"] != baseline["currentConcurrentCalls"]
                    or final["dailyCalls"] - baseline["dailyCalls"] != expected_calls
                    or final["dailyTokens"] - baseline["dailyTokens"] != expected_tokens):
                print("QUOTA_LOAD_FAIL: quota snapshot invariant failed", file=sys.stderr)
                return 1
    print(json.dumps(summary, sort_keys=True, separators=(",", ":")))
    return 1 if summary["unreleased_reservations"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (QuotaLoadError, ValueError, KindTestError, OSError) as error:
        print(f"QUOTA_LOAD_FAIL: {type(error).__name__}", file=sys.stderr)
        raise SystemExit(1)
