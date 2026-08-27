#!/usr/bin/env python3
"""Verify an in-flight Worker restart recovers the task exactly once terminally."""

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


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-At", "-F", "|", "-c", statement, namespace=namespace)


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


def deployment_ready(namespace: str, deployment: str, expected: int) -> bool:
    status = kubectl_json("get", "deployment", deployment, namespace=namespace).get("status", {})
    return int(status.get("readyReplicas", 0)) == expected


def worker_pod_names(namespace: str, deployment: str) -> list[str]:
    deployment_json = kubectl_json("get", "deployment", deployment, namespace=namespace)
    selector = deployment_json.get("spec", {}).get("selector", {}).get("matchLabels", {})
    if not selector:
        fail(f"Worker Deployment {deployment} has no selector")
    selector_value = ",".join(f"{key}={value}" for key, value in selector.items())
    pods = kubectl_json("get", "pods", "-l", selector_value, namespace=namespace)
    return [item["metadata"]["name"] for item in pods.get("items", [])
            if item.get("status", {}).get("phase") == "Running"]


def mock_delay_is_active(namespace: str, deployment: str, seconds: int) -> bool:
    deployment_json = kubectl_json("get", "deployment", deployment, namespace=namespace)
    selector = deployment_json.get("spec", {}).get("selector", {}).get("matchLabels", {})
    if not selector:
        fail(f"Mock Deployment {deployment} has no selector")
    selector_value = ",".join(f"{key}={value}" for key, value in selector.items())
    pods = kubectl_json("get", "pods", "-l", selector_value, namespace=namespace)
    expected = str(seconds)
    for pod in pods.get("items", []):
        if pod.get("status", {}).get("phase") != "Running" or pod.get("metadata", {}).get("deletionTimestamp"):
            continue
        containers = pod.get("spec", {}).get("containers", [])
        environment = {item.get("name"): item.get("value") for item in containers[0].get("env", [])} \
            if containers else {}
        if environment.get("QWENPAW_MOCK_RESPONSE_DELAY_SECONDS") == expected:
            return True
    return False


def agent_phase(namespace: str, postgres_pod: str, agent_id: str) -> str:
    return sql(namespace, postgres_pod,
               f"select phase from agents where id = '{agent_id}';").strip()


def gateway_connection_state(namespace: str, postgres_pod: str, agent_id: str) -> tuple[str, str]:
    row = sql(namespace, postgres_pod, f"""
        select coalesce(connection_id::text, '') || '|' || coalesce(presence, '')
          from gateway_agent_state
         where agent_id = '{agent_id}';
    """).strip()
    if not row:
        return "", ""
    connection_id, presence = row.split("|", 1)
    return connection_id, presence


def gateway_connection_id(namespace: str, postgres_pod: str, agent_id: str) -> str:
    return gateway_connection_state(namespace, postgres_pod, agent_id)[0]


def latest_gateway_sequence(namespace: str, postgres_pod: str, agent_id: str) -> int:
    return int(sql(namespace, postgres_pod, f"""
        select coalesce(max(sequence), 0)
          from gateway_commands
         where agent_id = '{agent_id}';
    """))


def gateway_ack_sequence(namespace: str, postgres_pod: str, agent_id: str) -> int:
    return int(sql(namespace, postgres_pod, f"""
        select coalesce((select last_ack_sequence
                           from gateway_ack_cursors
                          where agent_id = '{agent_id}'), 0);
    """))


def task_phase(namespace: str, postgres_pod: str, task_id: str) -> str:
    return sql(namespace, postgres_pod,
               f"select phase from tasks where id = '{task_id}';").strip()


def qwenpaw_agent_ready(namespace: str, postgres_pod: str, agent_id: str) -> bool:
    connection_id, presence = gateway_connection_state(namespace, postgres_pod, agent_id)
    return bool(connection_id and presence == "ONLINE"
                and agent_phase(namespace, postgres_pod, agent_id) == "READY")


def task_snapshot(namespace: str, postgres_pod: str, task_id: str, agent_id: str) -> str:
    attempts, succeeded = task_attempts(namespace, postgres_pod, task_id)
    connection_id, presence = gateway_connection_state(namespace, postgres_pod, agent_id)
    return (f"phase={task_phase(namespace, postgres_pod, task_id)!r} attempts={attempts} "
            f"succeeded={succeeded} agent_phase={agent_phase(namespace, postgres_pod, agent_id)!r} "
            f"gateway_presence={presence!r} connection_id={connection_id or '<missing>'}")


def task_attempts(namespace: str, postgres_pod: str, task_id: str) -> tuple[int, int]:
    row = sql(namespace, postgres_pod, f"""
        select count(*), count(*) filter (where phase = 'SUCCEEDED')
          from task_attempts
         where task_id = '{task_id}';
    """)
    count, succeeded = row.split("|", 1)
    return int(count), int(succeeded)


def set_mock_delay(namespace: str, deployment: str, seconds: int, timeout: float) -> None:
    run("set", "env", f"deployment/{deployment}",
        f"QWENPAW_MOCK_RESPONSE_DELAY_SECONDS={seconds}", namespace=namespace)
    run("rollout", "status", f"deployment/{deployment}",
        f"--timeout={int(timeout)}s", namespace=namespace)
    wait_until("mock response delay", lambda: mock_delay_is_active(namespace, deployment, seconds), timeout)


def clear_mock_delay(namespace: str, deployment: str, timeout: float) -> None:
    try:
        run("set", "env", f"deployment/{deployment}",
            "QWENPAW_MOCK_RESPONSE_DELAY_SECONDS-", namespace=namespace)
        run("rollout", "status", f"deployment/{deployment}",
            f"--timeout={int(timeout)}s", namespace=namespace)
    except RuntimeError:
        # The failure path should still restore the Worker deployment and
        # expose the original assertion failure to CI.
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID"))
    parser.add_argument("--worker-deployment", default="qwenpaw-real")
    parser.add_argument("--mock-deployment", default="qwenpaw-openai-mock")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--local-port", type=int, default=18084)
    parser.add_argument("--response-delay-seconds", type=int, default=60)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    args = parser.parse_args()
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")

    worker_replicas = deployment_replicas(args.namespace, args.worker_deployment)
    if worker_replicas != 1:
        fail(f"Worker restart test expects exactly one replica, got {worker_replicas}")
    pods = worker_pod_names(args.namespace, args.worker_deployment)
    if len(pods) != 1:
        fail(f"expected one running Worker Pod before restart, got {pods}")

    port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
    mock_port_forward = None
    base_url = f"http://127.0.0.1:{args.local_port}"
    task_id = None
    delay_configured = False
    try:
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health"), args.timeout)
        delay_configured = True
        set_mock_delay(args.namespace, args.mock_deployment, args.response_delay_seconds, args.timeout)
        mock_port_forward = start_port_forward(args.namespace, args.mock_deployment, args.local_port + 1)
        mock_base_url = f"http://127.0.0.1:{args.local_port + 1}"
        wait_until("QwenPaw mock API", lambda: api_request(f"{mock_base_url}/v1/models"), args.timeout)
        wait_until(f"QwenPaw Agent registration for {args.agent_id}", lambda: qwenpaw_agent_ready(
            args.namespace, args.postgres_pod, args.agent_id), args.timeout)

        created = api_request(
            f"{base_url}/api/v1/tasks", "POST",
            {"title": "kind-worker-restart", "description": "in-flight Worker restart recovery smoke test",
             "spec": {"scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
                      "taskType": "qwenpaw", "inputJson": {"prompt": "KIND_WORKER_RESTART_OK"},
                      "requiredCapabilities": ["qwenpaw"]}},
            f"kind-worker-restart-create-{uuid.uuid4()}")
        task_id = created["id"]
        api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                    f"kind-worker-restart-queue-{uuid.uuid4()}")
        try:
            wait_until("task RUNNING before Worker restart",
                       lambda: task_phase(args.namespace, args.postgres_pod, task_id) == "RUNNING"
                       and api_request(f"{mock_base_url}/debug/inflight").get("inflight", 0) > 0,
                       args.timeout)
        except RuntimeError as error:
            fail(f"{error}; {task_snapshot(args.namespace, args.postgres_pod, task_id, args.agent_id)}")
        attempts_before, _ = task_attempts(args.namespace, args.postgres_pod, task_id)
        if attempts_before != 1:
            fail(f"expected one active attempt before Worker restart, got {attempts_before}")
        initial_command_sequence = latest_gateway_sequence(
            args.namespace, args.postgres_pod, args.agent_id)
        if initial_command_sequence <= 0:
            fail("could not find the initial TaskAssigned Gateway command")
        wait_until(
            "initial TaskAssigned acknowledgement",
            lambda: gateway_ack_sequence(args.namespace, args.postgres_pod, args.agent_id)
            >= initial_command_sequence,
            args.timeout,
        )
        print(f"KIND_WORKER_RESTART_ACKED task={task_id} sequence={initial_command_sequence}")
        print(f"KIND_WORKER_RESTART_RUNNING task={task_id} pod={pods[0]}")

        failed_worker_pod = pods[0]
        previous_connection_id = gateway_connection_id(args.namespace, args.postgres_pod, args.agent_id)
        if not previous_connection_id:
            fail("could not find the active Gateway connection before Worker restart")
        run("delete", "pod", failed_worker_pod, "--wait=true", namespace=args.namespace)
        wait_until(
            "replacement Worker Pod",
            lambda: next((pod for pod in worker_pod_names(args.namespace, args.worker_deployment)
                          if pod != failed_worker_pod), None),
            args.timeout,
        )
        wait_until("Worker Deployment ready after restart", lambda: deployment_ready(
            args.namespace, args.worker_deployment, worker_replicas), args.timeout)
        def restarted_worker_ready():
            current_connection_id = gateway_connection_id(args.namespace, args.postgres_pod, args.agent_id)
            return (current_connection_id if current_connection_id and current_connection_id != previous_connection_id
                    and agent_phase(args.namespace, args.postgres_pod, args.agent_id) == "READY" else None)

        wait_until("new Worker Gateway registration after restart", restarted_worker_ready, args.timeout)

        updated = sql(args.namespace, args.postgres_pod, f"""
            update agent_leases l set expires_at=now()-interval '1 second', updated_at=now()
              from task_attempts a
             where a.lease_id=l.id and a.task_id='{task_id}' and l.status='ACTIVE'
            returning l.id;
        """)
        if not updated:
            fail("could not find the in-flight active lease after Worker restart")
        print(f"KIND_WORKER_RESTART_LEASE_EXPIRED task={task_id}")

        def recovered_attempts():
            attempts, _ = task_attempts(args.namespace, args.postgres_pod, task_id)
            return attempts if attempts >= 2 else None

        try:
            wait_until("second task attempt after Worker restart", recovered_attempts, args.timeout)
        except RuntimeError as error:
            fail(f"{error}; {task_snapshot(args.namespace, args.postgres_pod, task_id, args.agent_id)}")

        def terminal_task():
            task = api_request(f"{base_url}/api/v1/tasks/{task_id}")
            return task if task.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"} else None

        final = wait_until("task completion after Worker restart", terminal_task, args.timeout)
        if final.get("phase") != "SUCCEEDED":
            fail(f"task did not recover after Worker restart: {final}")
        attempts, succeeded = task_attempts(args.namespace, args.postgres_pod, task_id)
        if attempts != 2 or succeeded != 1:
            fail(f"expected two attempts with one success after lease recovery, got attempts={attempts} succeeded={succeeded}")
        print(f"KIND_WORKER_RESTART_OK task={task_id} attempts={attempts} succeeded={succeeded} phase={final['phase']}")
        return 0
    finally:
        if delay_configured:
            clear_mock_delay(args.namespace, args.mock_deployment, args.timeout)
        if deployment_replicas(args.namespace, args.worker_deployment) != worker_replicas:
            run("scale", "deployment", args.worker_deployment, f"--replicas={worker_replicas}",
                namespace=args.namespace)
        stop_port_forward(port_forward)
        if mock_port_forward is not None:
            stop_port_forward(mock_port_forward)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_WORKER_RESTART_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
