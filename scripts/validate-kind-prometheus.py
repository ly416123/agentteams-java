#!/usr/bin/env python3
"""Verify that Prometheus discovers every application replica in Kind."""

from __future__ import annotations

import argparse
import http.client
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def fail(message: str) -> None:
    print(f"PROMETHEUS_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def request_json(url: str) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return json.load(response)
    except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected, ConnectionResetError) as exc:
        raise RuntimeError(str(exc)) from exc


def query(base_url: str, expression: str) -> float:
    url = f"{base_url}/api/v1/query?query={urllib.parse.quote(expression)}"
    payload = request_json(url)
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus query failed: {payload}")
    result = payload.get("data", {}).get("result", [])
    if len(result) != 1:
        raise RuntimeError(f"Expected one query result for {expression!r}, got {result}")
    return float(result[0]["value"][1])


def wait_for_prometheus(base_url: str, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if request_json(f"{base_url}/api/v1/status/buildinfo").get("status") == "success":
                return
        except RuntimeError:
            pass
        time.sleep(2)
    fail("Prometheus API did not become ready")


def wait_for_scrape_count(base_url: str, job: str, expected: int, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    last_error = "no result"
    while time.monotonic() < deadline:
        try:
            actual = query(base_url, f'sum(up{{job="{job}"}})')
            target_count = query(base_url, f'count(up{{job="{job}"}})')
            if actual == expected and target_count == expected:
                return
            last_error = f"sum(up)={actual}, count(up)={target_count}"
        except (RuntimeError, KeyError, ValueError, json.JSONDecodeError) as exc:
            last_error = str(exc)
        time.sleep(3)
    fail(f"{job} scrape count expected {expected}, got {last_error}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--service", default="prometheus")
    parser.add_argument("--local-port", type=int, default=19090)
    parser.add_argument("--control-plane-replicas", type=int, default=2)
    parser.add_argument("--gateway-replicas", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    process = subprocess.Popen(
        [
            "kubectl",
            "-n",
            args.namespace,
            "port-forward",
            f"service/{args.service}",
            f"{args.local_port}:9090",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
    )
    base_url = f"http://127.0.0.1:{args.local_port}"
    try:
        wait_for_prometheus(base_url, args.timeout)
        checks = {
            "control-plane": args.control_plane_replicas,
            "gateway": args.gateway_replicas,
        }
        for job, expected in checks.items():
            wait_for_scrape_count(base_url, job, expected, args.timeout)
        print(
            "PROMETHEUS_OK "
            f"control-plane={args.control_plane_replicas} "
            f"gateway={args.gateway_replicas}"
        )
    except (RuntimeError, KeyError, ValueError, json.JSONDecodeError) as exc:
        fail(str(exc))
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


if __name__ == "__main__":
    main()
