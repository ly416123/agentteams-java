#!/usr/bin/env python3
"""Verify that a NATS outage leaves Outbox events pending and drains after recovery."""

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


def kubectl_json(*args: str, namespace: str | None = None) -> dict:
    return json.loads(run(*args, "-o", "json", namespace=namespace))


def wait_until(description: str, predicate, timeout: float = 180.0, interval: float = 1.0):
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


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-At", "-F", "|", "-c", statement, namespace=namespace)


def api_request(url: str, method: str = "GET", body: dict | None = None,
                idempotency_key: str | None = None) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    bearer = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", "").strip()
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


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


def statefulset_ready(namespace: str, name: str, expected: int) -> bool:
    status = kubectl_json("get", "statefulset", name, namespace=namespace).get("status", {})
    return int(status.get("readyReplicas", 0)) == expected


def deployment_ready(namespace: str, name: str, expected: int) -> bool:
    status = kubectl_json("get", "deployment", name, namespace=namespace).get("status", {})
    return int(status.get("readyReplicas", 0)) == expected


def outbox_state(namespace: str, postgres_pod: str, task_id: str) -> str:
    return sql(namespace, postgres_pod, f"""
        select status || '|' || attempts || '|' || coalesce(last_error, '')
          from outbox_events
         where aggregate_id = '{task_id}' and event_type = 'TaskAssigned'
         order by created_at desc
         limit 1
    """).strip()


def task_phase(namespace: str, postgres_pod: str, task_id: str) -> str:
    return sql(namespace, postgres_pod, f"select phase from tasks where id = '{task_id}';").strip()


def pending_outbox_state(namespace: str, postgres_pod: str, task_id: str) -> str | None:
    state = outbox_state(namespace, postgres_pod, task_id)
    return state if state and state.split("|", 1)[0] in {"PENDING", "IN_FLIGHT"} else None


def published_outbox_state(namespace: str, postgres_pod: str, task_id: str) -> str | None:
    state = outbox_state(namespace, postgres_pod, task_id)
    return state if state and state.startswith("PUBLISHED|") else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID"))
    parser.add_argument("--worker-deployment", default="qwenpaw-real")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--nats-statefulset", default="nats")
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--local-port", type=int, default=18081)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    args = parser.parse_args()
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")

    nats = kubectl_json("get", "statefulset", args.nats_statefulset, namespace=args.namespace)
    original_nats_replicas = int(nats.get("spec", {}).get("replicas", 0))
    if original_nats_replicas < 1:
        fail("NATS StatefulSet must have at least one replica before the test")
    if not deployment_ready(args.namespace, args.worker_deployment, 1):
        fail("QwenPaw worker must be ready before the NATS outage test")

    port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
    base_url = f"http://127.0.0.1:{args.local_port}"
    task_id = None
    try:
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health"), args.timeout)
        run("scale", "statefulset", args.nats_statefulset, "--replicas=0", namespace=args.namespace)
        wait_until("NATS outage", lambda: statefulset_ready(args.namespace, args.nats_statefulset, 0), args.timeout)

        created = api_request(
            f"{base_url}/api/v1/tasks", "POST",
            {"title": "kind-nats-outbox-recovery", "description": "NATS outage Outbox recovery smoke test",
             "spec": {"scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
                      "taskType": "qwenpaw", "inputJson": {"prompt": "KIND_NATS_OUTBOX_RECOVERY_OK"},
                      "requiredCapabilities": ["qwenpaw"]}},
            f"kind-nats-outbox-create-{uuid.uuid4()}")
        task_id = created["id"]
        api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                    f"kind-nats-outbox-queue-{uuid.uuid4()}")

        wait_until("task assignment in the database",
                   lambda: task_phase(args.namespace, args.postgres_pod, task_id) == "ASSIGNED",
                   args.timeout)
        pending = wait_until(
            "TaskAssigned Outbox event pending while NATS is down",
            lambda: pending_outbox_state(args.namespace, args.postgres_pod, task_id),
            args.timeout)
        print(f"KIND_NATS_OUTBOX_PENDING task={task_id} state={pending.split('|', 2)[0:2]}")

        run("scale", "statefulset", args.nats_statefulset, f"--replicas={original_nats_replicas}",
            namespace=args.namespace)
        run("rollout", "status", f"statefulset/{args.nats_statefulset}",
            f"--timeout={int(args.timeout)}s", namespace=args.namespace)
        wait_until("NATS recovery", lambda: statefulset_ready(
            args.namespace, args.nats_statefulset, original_nats_replicas), args.timeout)
        published = wait_until(
            "TaskAssigned Outbox event published after NATS recovery",
            lambda: published_outbox_state(args.namespace, args.postgres_pod, task_id),
            args.timeout)
        final = wait_until(
            "task completion after NATS recovery",
            lambda: (task := api_request(f"{base_url}/api/v1/tasks/{task_id}"))
            if task.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"} else None,
            args.timeout)
        if final.get("phase") != "SUCCEEDED":
            fail(f"task did not succeed after NATS recovery: {final}")
        print(f"KIND_NATS_OUTBOX_RECOVERY_OK task={task_id} outbox={published.split('|', 2)[0]} phase={final['phase']}")
        return 0
    finally:
        current = kubectl_json("get", "statefulset", args.nats_statefulset, namespace=args.namespace)
        if int(current.get("spec", {}).get("replicas", 0)) != original_nats_replicas:
            run("scale", "statefulset", args.nats_statefulset, f"--replicas={original_nats_replicas}",
                namespace=args.namespace)
        stop_port_forward(port_forward)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_NATS_OUTBOX_RECOVERY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
