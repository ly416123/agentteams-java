#!/usr/bin/env python3
"""Verify Gateway single-Pod failover replays an unacknowledged durable command."""

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


def deployment_replicas(namespace: str, deployment: str) -> int:
    return int(kubectl_json("get", "deployment", deployment, namespace=namespace)
               .get("spec", {}).get("replicas", 0))


def deployment_agent_id(namespace: str, deployment: str) -> str:
    return kubectl_json("get", "deployment", deployment, namespace=namespace) \
        .get("spec", {}).get("template", {}).get("metadata", {}).get("labels", {}).get(
            "agentteams.io/agent-id", "")


def deployment_ready(namespace: str, deployment: str, expected: int) -> bool:
    status = kubectl_json("get", "deployment", deployment, namespace=namespace).get("status", {})
    return int(status.get("readyReplicas", 0)) == expected


def gateway_pod_names(namespace: str) -> list[str]:
    pods = kubectl_json("get", "pods", "-l", "app.kubernetes.io/name=agentteams-gateway",
                        namespace=namespace)
    return [item["metadata"]["name"] for item in pods.get("items", [])
            if item.get("status", {}).get("phase") == "Running"]


def qwenpaw_agent_phases(namespace: str, postgres_pod: str) -> dict[str, str]:
    rows = sql(namespace, postgres_pod, """
        select id || '|' || phase
          from agents
         where capabilities ? 'qwenpaw';
    """)
    return {
        agent_id: phase
        for row in rows.splitlines()
        if row and len((parts := row.split("|", 1))) == 2
        for agent_id, phase in [parts]
    }


def task_phase(namespace: str, postgres_pod: str, task_id: str) -> str:
    return sql(namespace, postgres_pod, f"select phase from tasks where id = '{task_id}';").strip()


def gateway_command_count(namespace: str, postgres_pod: str, agent_id: str) -> int:
    return int(sql(namespace, postgres_pod,
                   f"select count(*) from gateway_commands where agent_id = '{agent_id}';"))


def latest_gateway_sequence(namespace: str, postgres_pod: str, agent_id: str) -> int:
    return int(sql(namespace, postgres_pod,
                   f"select coalesce(max(sequence), 0) from gateway_commands where agent_id = '{agent_id}';"))


def gateway_delivery_count(namespace: str, postgres_pod: str, agent_id: str, sequence: int) -> int:
    return int(sql(namespace, postgres_pod, f"""
        select count(*)
          from gateway_command_deliveries
         where agent_id = '{agent_id}' and sequence = {sequence};
    """))


def gateway_ack_sequence(namespace: str, postgres_pod: str, agent_id: str) -> int:
    return int(sql(namespace, postgres_pod, f"""
        select coalesce((select last_ack_sequence from gateway_ack_cursors
                          where agent_id = '{agent_id}'), 0);
    """))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID"))
    parser.add_argument("--worker-deployment", default="qwenpaw-real")
    parser.add_argument("--gateway-deployment", default="agentteams-agentteams-java-gateway")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--local-port", type=int, default=18082)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    args = parser.parse_args()
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")

    worker_replicas = deployment_replicas(args.namespace, args.worker_deployment)
    gateway_replicas = deployment_replicas(args.namespace, args.gateway_deployment)
    if worker_replicas <= 0 or gateway_replicas < 2:
        fail("Worker must have a replica and Gateway must have at least two replicas for HA failover")
    worker_agent_id = deployment_agent_id(args.namespace, args.worker_deployment)
    if worker_agent_id and worker_agent_id != args.agent_id:
        fail(f"worker deployment is bound to agent {worker_agent_id}, not {args.agent_id}")
    original_agent_phases = qwenpaw_agent_phases(args.namespace, args.postgres_pod)
    if args.agent_id not in original_agent_phases:
        fail(f"agent {args.agent_id} is not a qwenpaw-capable registered agent")

    port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
    base_url = f"http://127.0.0.1:{args.local_port}"
    task_id = None
    command_sequence = 0
    try:
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health"), args.timeout)
        run("scale", "deployment", args.worker_deployment, "--replicas=0", namespace=args.namespace)
        wait_until("Worker shutdown", lambda: deployment_ready(args.namespace, args.worker_deployment, 0), args.timeout)
        for agent_id in original_agent_phases:
            if agent_id != args.agent_id:
                sql(args.namespace, args.postgres_pod,
                    f"update agents set phase='OFFLINE', updated_at=now() where id='{agent_id}';")
        sql(args.namespace, args.postgres_pod,
            f"update agents set phase='READY', updated_at=now() where id='{args.agent_id}';")
        baseline_commands = gateway_command_count(args.namespace, args.postgres_pod, args.agent_id)

        created = api_request(
            f"{base_url}/api/v1/tasks", "POST",
            {"title": "kind-gateway-replay", "description": "Gateway restart command replay smoke test",
             "spec": {"scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
                      "taskType": "qwenpaw", "inputJson": {"prompt": "KIND_GATEWAY_REPLAY_OK"},
                      "requiredCapabilities": ["qwenpaw"]}},
            f"kind-gateway-replay-create-{uuid.uuid4()}")
        task_id = created["id"]
        api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                    f"kind-gateway-replay-queue-{uuid.uuid4()}")
        wait_until("task assignment in the database",
                   lambda: task_phase(args.namespace, args.postgres_pod, task_id) == "ASSIGNED",
                   args.timeout)
        wait_until("durable Gateway command before restart",
                   lambda: gateway_command_count(args.namespace, args.postgres_pod, args.agent_id) > baseline_commands,
                   args.timeout)
        command_sequence = latest_gateway_sequence(args.namespace, args.postgres_pod, args.agent_id)
        if gateway_delivery_count(args.namespace, args.postgres_pod, args.agent_id, command_sequence) != 0:
            fail("Gateway command was delivered before the restart; Worker shutdown race was not controlled")
        print(f"KIND_GATEWAY_COMMAND_DURABLE task={task_id} sequence={command_sequence}")

        gateway_pods = gateway_pod_names(args.namespace)
        if len(gateway_pods) < gateway_replicas:
            fail(f"expected {gateway_replicas} running Gateway Pods before fault injection, got {gateway_pods}")
        failed_gateway_pod = gateway_pods[0]
        run("delete", "pod", failed_gateway_pod, "--wait=true", namespace=args.namespace)
        wait_until("Gateway single-pod failover", lambda: deployment_ready(
            args.namespace, args.gateway_deployment, gateway_replicas), args.timeout)
        print(f"KIND_GATEWAY_POD_FAILOVER_OK deleted={failed_gateway_pod} replicas={gateway_replicas}")
        wait_until("Gateway recovery", lambda: deployment_ready(
            args.namespace, args.gateway_deployment, gateway_replicas), args.timeout)
        run("scale", "deployment", args.worker_deployment, f"--replicas={worker_replicas}",
            namespace=args.namespace)
        wait_until("Worker recovery", lambda: deployment_ready(
            args.namespace, args.worker_deployment, worker_replicas), args.timeout)

        wait_until("Gateway command replay delivery",
                   lambda: gateway_delivery_count(args.namespace, args.postgres_pod,
                                                  args.agent_id, command_sequence) > 0,
                   args.timeout)

        def terminal_task():
            task = api_request(f"{base_url}/api/v1/tasks/{task_id}")
            return task if task.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"} else None

        final = wait_until("task completion after Gateway replay", terminal_task, args.timeout)
        if final.get("phase") != "SUCCEEDED":
            fail(f"task did not succeed after Gateway replay: {final}")
        attempts = int(sql(args.namespace, args.postgres_pod,
                            f"select count(*) from task_attempts where task_id='{task_id}';"))
        if attempts != 1:
            fail(f"expected exactly one task attempt after Gateway replay, got {attempts}")
        if gateway_ack_sequence(args.namespace, args.postgres_pod, args.agent_id) < command_sequence:
            fail("replayed Gateway command was not durably acknowledged")
        print(f"KIND_GATEWAY_REPLAY_OK task={task_id} sequence={command_sequence} attempts={attempts} phase={final['phase']}")
        return 0
    finally:
        for agent_id, phase in original_agent_phases.items():
            sql(args.namespace, args.postgres_pod,
                f"update agents set phase='{phase}', updated_at=now() where id='{agent_id}';")
        if deployment_replicas(args.namespace, args.worker_deployment) != worker_replicas:
            run("scale", "deployment", args.worker_deployment, f"--replicas={worker_replicas}",
                namespace=args.namespace)
        stop_port_forward(port_forward)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_GATEWAY_REPLAY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
