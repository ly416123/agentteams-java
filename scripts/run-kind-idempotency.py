#!/usr/bin/env python3
"""Verify duplicate task creation returns one durable task and one execution."""

from __future__ import annotations

import argparse
import http.client
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def fail(message: str) -> None:
    raise RuntimeError(message)


def run(*args: str, namespace: str | None = None) -> str:
    command = ["kubectl"]
    if namespace:
        command += ["-n", namespace]
    command += list(args)
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-At", "-c", statement, namespace=namespace)


def wait_until(description: str, predicate, timeout: float = 240.0, interval: float = 1.0):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (RuntimeError, urllib.error.URLError, urllib.error.HTTPError,
                http.client.RemoteDisconnected, ConnectionResetError):
            pass
        time.sleep(interval)
    fail(f"timed out waiting for {description}; last={last!r}")


def api_request(url: str, method: str = "GET", body: dict | None = None,
                idempotency_key: str | None = None) -> tuple[int, dict]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    bearer = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", "").strip()
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def start_port_forward(namespace: str, service: str, local_port: int) -> subprocess.Popen:
    return subprocess.Popen(
        ["kubectl", "-n", namespace, "port-forward", f"service/{service}", f"{local_port}:8080"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_port_forward(port_forward: subprocess.Popen) -> None:
    if port_forward.poll() is None:
        port_forward.terminate()
        try:
            port_forward.wait(timeout=5)
        except subprocess.TimeoutExpired:
            port_forward.kill()


def deployment_ready(namespace: str, deployment: str) -> bool:
    payload = json.loads(run("get", "deployment", deployment, "-o", "json", namespace=namespace))
    status = payload.get("status", {})
    return int(status.get("readyReplicas", 0)) >= 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--worker-deployment", default="qwenpaw-real")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--local-port", type=int, default=18083)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    args = parser.parse_args()

    if not deployment_ready(args.namespace, args.worker_deployment):
        fail("QwenPaw worker must be ready before the idempotency test")

    port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
    base_url = f"http://127.0.0.1:{args.local_port}"
    task_id = None
    key = f"kind-idempotency-create-{uuid.uuid4()}"
    task_body = {
        "title": "kind-idempotency",
        "description": "duplicate task creation idempotency smoke test",
        "spec": {
            "scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": "KIND_IDEMPOTENCY_OK"},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    try:
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health"), args.timeout)
        first_status, first = api_request(f"{base_url}/api/v1/tasks", "POST", task_body, key)
        second_status, second = api_request(f"{base_url}/api/v1/tasks", "POST", task_body, key)
        if first_status != 201 or second_status != 201:
            fail(f"duplicate create expected HTTP 201 twice, got {first_status} and {second_status}")
        task_id = first.get("id")
        if not task_id or task_id != second.get("id"):
            fail(f"duplicate create returned different task ids: first={first!r}, second={second!r}")

        key_count = int(sql(args.namespace, args.postgres_pod,
                            f"select count(*) from idempotency_keys where idempotency_key = '{key}'"))
        if key_count != 1:
            fail(f"expected one idempotency record for the duplicate key, got {key_count}")
        task_count = int(sql(args.namespace, args.postgres_pod,
                             f"select count(*) from tasks where id = '{task_id}'"))
        if task_count != 1:
            fail(f"expected one task row for the duplicate key, got {task_count}")

        queue_status, _ = api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                                      f"kind-idempotency-queue-{uuid.uuid4()}")
        if queue_status != 200:
            fail(f"queue expected HTTP 200, got {queue_status}")

        def terminal_task():
            _, task = api_request(f"{base_url}/api/v1/tasks/{task_id}")
            return task if task.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"} else None

        final = wait_until("idempotent task completion", terminal_task, args.timeout)
        if final.get("phase") != "SUCCEEDED":
            fail(f"idempotent task did not succeed: {final}")
        attempts = int(sql(args.namespace, args.postgres_pod,
                           f"select count(*) from task_attempts where task_id = '{task_id}'"))
        if attempts != 1:
            fail(f"expected exactly one task attempt after duplicate create, got {attempts}")
        print(f"KIND_IDEMPOTENCY_OK task={task_id} attempts={attempts} phase={final['phase']}")
        return 0
    finally:
        stop_port_forward(port_forward)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_IDEMPOTENCY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
