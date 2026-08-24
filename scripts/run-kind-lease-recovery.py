#!/usr/bin/env python3
"""Run the repeatable Kind task lease recovery and replay smoke test."""

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
from pathlib import Path


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
    last_error = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (RuntimeError, urllib.error.URLError, urllib.error.HTTPError,
                http.client.RemoteDisconnected, ConnectionResetError) as error:
            last_error = str(error)
        time.sleep(interval)
    detail = f"last_error={last_error!r}" if last_error else f"last={last!r}"
    fail(f"timed out waiting for {description}; {detail}")


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-At", "-F", "|", "-c", statement, namespace=namespace)


def api_request(url: str, method: str = "GET", body: dict | None = None, idempotency_key: str | None = None) -> dict:
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


def deployment_replicas(namespace: str, deployment: str) -> int:
    deployment_json = kubectl_json("get", "deployment", deployment, namespace=namespace)
    return int(deployment_json.get("spec", {}).get("replicas", 0))


def deployment_agent_id(namespace: str, deployment: str) -> str:
    deployment_json = kubectl_json("get", "deployment", deployment, namespace=namespace)
    return deployment_json.get("spec", {}).get("template", {}).get("metadata", {}).get("labels", {}).get(
        "agentteams.io/agent-id", "")


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


def gateway_connection_state(namespace: str, postgres_pod: str, agent_id: str) -> tuple[str, str]:
    row = sql(namespace, postgres_pod, f"""
        select coalesce(connection_id::text, '') || '|' || presence
          from gateway_agent_state
         where agent_id = '{agent_id}';
    """).strip()
    if not row:
        return "", ""
    connection_id, presence = row.split("|", 1)
    return connection_id, presence


def wait_for_new_gateway_connection(namespace: str, postgres_pod: str, agent_id: str,
                                    previous_connection_id: str, timeout: float,
                                    interval: float = 1.0) -> str:
    def restarted_worker_ready():
        current_connection_id, presence = gateway_connection_state(namespace, postgres_pod, agent_id)
        return (current_connection_id if current_connection_id
                and current_connection_id != previous_connection_id
                and presence == "ONLINE"
                and qwenpaw_agent_phases(namespace, postgres_pod).get(agent_id) == "READY" else None)

    return wait_until("new Worker Gateway registration", restarted_worker_ready, timeout, interval)


def deployment_ready(namespace: str, deployment: str, expected: int) -> bool:
    deployment_json = kubectl_json("get", "deployment", deployment, namespace=namespace)
    status = deployment_json.get("status", {})
    return int(status.get("readyReplicas", 0)) == expected


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


def wait_for_api_with_port_forward(base_url: str, port_forward: subprocess.Popen,
                                   namespace: str, service: str, local_port: int,
                                   timeout: float) -> tuple[dict, subprocess.Popen]:
    """Keep reconnecting port-forward until the API is reachable after a rollout."""
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        if port_forward.poll() is not None:
            stop_port_forward(port_forward)
            port_forward = start_port_forward(namespace, service, local_port)
        try:
            return api_request(f"{base_url}/actuator/health"), port_forward
        except (RuntimeError, urllib.error.URLError, urllib.error.HTTPError,
                http.client.RemoteDisconnected, ConnectionResetError) as error:
            last_error = str(error)
        time.sleep(1)
    fail(f"timed out waiting for Control Plane API; last={last_error or '<no response>'}")


def task_row(namespace: str, postgres_pod: str, task_id: str) -> str:
    return sql(namespace, postgres_pod, f"""
        select t.phase || '|' || t.version || '|' || coalesce(l.status, '') || '|' ||
               coalesce(l.expires_at::text, '')
          from tasks t
          left join task_attempts a on a.task_id = t.id
          left join agent_leases l on l.task_attempt_id = a.id
         where t.id = '{task_id}'
         order by a.created_at desc nulls last
         limit 1
    """).strip()


def task_attempt_count(namespace: str, postgres_pod: str, task_id: str) -> int:
    return int(sql(namespace, postgres_pod,
                   f"select count(*) from task_attempts where task_id = '{task_id}'"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID"))
    parser.add_argument("--worker-deployment", default="qwenpaw-real")
    parser.add_argument("--control-plane-deployment", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--operator-deployment", default="agentteams-agentteams-java-operator")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--local-port", type=int, default=18080)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--completion-timeout", type=float, default=300.0,
                        help="maximum time to wait for the recovered Worker task to finish")
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    args = parser.parse_args()
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")

    worker_replicas = deployment_replicas(args.namespace, args.worker_deployment)
    operator_replicas = deployment_replicas(args.namespace, args.operator_deployment)
    if worker_replicas <= 0:
        fail("worker deployment must have at least one replica before the test")
    if operator_replicas <= 0:
        fail("operator deployment must have at least one replica before the test")
    worker_agent_id = deployment_agent_id(args.namespace, args.worker_deployment)
    if worker_agent_id and worker_agent_id != args.agent_id:
        fail(f"worker deployment {args.worker_deployment} is bound to agent {worker_agent_id}, not {args.agent_id}")
    original_agent_phases = qwenpaw_agent_phases(args.namespace, args.postgres_pod)
    if args.agent_id not in original_agent_phases:
        fail(f"agent {args.agent_id} is not a qwenpaw-capable registered agent")
    previous_connection_id, previous_presence = gateway_connection_state(
        args.namespace, args.postgres_pod, args.agent_id)
    if not previous_connection_id or previous_presence != "ONLINE":
        fail("could not find an online Gateway connection before Worker shutdown")

    port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
    base_url = f"http://127.0.0.1:{args.local_port}"
    task_id = None
    try:
        _, port_forward = wait_for_api_with_port_forward(
            base_url, port_forward, args.namespace, args.control_plane_service,
            args.local_port, args.timeout)
        # The Operator continuously reconciles Worker.spec.replicas. Pause it
        # before scaling the deployment down, otherwise the test worker can
        # reconnect and complete the task before its lease is expired.
        run("scale", "deployment", args.operator_deployment, "--replicas=0", namespace=args.namespace)
        wait_until("Operator shutdown", lambda: deployment_ready(
            args.namespace, args.operator_deployment, 0), args.timeout)
        run("scale", "deployment", args.worker_deployment, "--replicas=0", namespace=args.namespace)
        wait_until("Worker shutdown", lambda: deployment_ready(args.namespace, args.worker_deployment, 0), args.timeout)

        for agent_id in original_agent_phases:
            if agent_id != args.agent_id:
                sql(args.namespace, args.postgres_pod,
                    f"update agents set phase='OFFLINE', updated_at=now() where id='{agent_id}';")
        sql(args.namespace, args.postgres_pod,
            f"update agents set phase='READY', updated_at=now() where id='{args.agent_id}';")
        created = api_request(
            f"{base_url}/api/v1/tasks", "POST",
            {"title": "kind-lease-recovery", "description": "automated lease recovery smoke test",
             "spec": {"scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
                      "taskType": "qwenpaw", "inputJson": {"prompt": "KIND_LEASE_RECOVERY_OK"},
                      "requiredCapabilities": ["qwenpaw"]}},
            f"kind-lease-recovery-create-{uuid.uuid4()}")
        task_id = created["id"]
        api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                    f"kind-lease-recovery-queue-{uuid.uuid4()}")
        wait_until("initial assignment", lambda: "ASSIGNED|" in task_row(args.namespace, args.postgres_pod, task_id),
                   args.timeout)

        # The Operator and Worker are already paused. Restart the Control Plane
        # while the lease is still active, then expire it after the new scheduler
        # is ready. This prevents the old scheduler from recovering the lease
        # before the restart and makes the recovery boundary deterministic.
        run("rollout", "restart", f"deployment/{args.control_plane_deployment}", namespace=args.namespace)
        run("rollout", "status", f"deployment/{args.control_plane_deployment}",
            f"--timeout={int(args.timeout)}s", namespace=args.namespace)
        # A Control Plane rollout terminates the selected Service endpoint,
        # so refresh the local port-forward before continuing the API poll.
        stop_port_forward(port_forward)
        port_forward = start_port_forward(args.namespace, args.control_plane_service, args.local_port)
        _, port_forward = wait_for_api_with_port_forward(
            base_url, port_forward, args.namespace, args.control_plane_service,
            args.local_port, args.timeout)
        # Keep the recovered task queued while the Worker is still down. The
        # pre-shutdown READY value is only a seed for the initial assignment;
        # it is not proof of a live Gateway connection.
        sql(args.namespace, args.postgres_pod,
            f"update agents set phase='OFFLINE', updated_at=now() where id='{args.agent_id}';")
        updated = sql(args.namespace, args.postgres_pod, f"""
            update agent_leases l set expires_at=now()-interval '1 second', updated_at=now()
              from task_attempts a
             where a.lease_id=l.id and a.task_id='{task_id}' and l.status='ACTIVE'
            returning l.id;
        """)
        if not updated:
            fail("could not find an ACTIVE lease to expire")
        wait_until("TaskLeaseExpired event", lambda: "TaskLeaseExpired" in sql(
            args.namespace, args.postgres_pod,
            f"select event_type from domain_events where aggregate_id='{task_id}';"), args.timeout)
        run("scale", "deployment", args.worker_deployment, f"--replicas={worker_replicas}", namespace=args.namespace)
        wait_until("Worker recovery", lambda: deployment_ready(args.namespace, args.worker_deployment, worker_replicas),
                   args.timeout)
        wait_for_new_gateway_connection(args.namespace, args.postgres_pod, args.agent_id,
                                        previous_connection_id, args.timeout)
        wait_until("recovered task reassignment", lambda: task_attempt_count(
            args.namespace, args.postgres_pod, task_id) >= 2, args.timeout)
        def terminal_task():
            task = api_request(f"{base_url}/api/v1/tasks/{task_id}")
            if task.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                return task
            raise RuntimeError(f"task phase is {task.get('phase')!r}")

        # Reconnecting the Worker and warming the deterministic QwenPaw path can
        # take longer than the control-plane recovery checks. Keep this wait
        # independent so a slow first model call does not report a false
        # recovery failure.
        final = wait_until("task completion", terminal_task, args.completion_timeout)
        if final.get("phase") != "SUCCEEDED":
            fail(f"recovered task did not succeed: {final}")
        attempts = task_attempt_count(args.namespace, args.postgres_pod, task_id)
        if attempts < 2:
            fail(f"expected at least one recovered attempt, got {attempts}")
        print(f"KIND_LEASE_RECOVERY_OK task={task_id} attempts={attempts} phase={final['phase']}")
        return 0
    finally:
        cleanup_errors = []
        try:
            for agent_id, phase in original_agent_phases.items():
                sql(args.namespace, args.postgres_pod,
                    f"update agents set phase='{phase}', updated_at=now() where id='{agent_id}';")
        except (RuntimeError, OSError) as error:
            cleanup_errors.append(f"restore agent phases: {error}")
        for deployment, replicas, label in ((args.worker_deployment, worker_replicas, "Worker"),
                                             (args.operator_deployment, operator_replicas, "Operator")):
            try:
                if deployment_replicas(args.namespace, deployment) != replicas:
                    run("scale", "deployment", deployment, f"--replicas={replicas}", namespace=args.namespace)
                wait_until(f"{label} cleanup", lambda: deployment_ready(
                    args.namespace, deployment, replicas), args.timeout)
            except (RuntimeError, OSError) as error:
                cleanup_errors.append(f"restore {label}: {error}")
        try:
            stop_port_forward(port_forward)
        except OSError as error:
            cleanup_errors.append(f"stop port-forward: {error}")
        if cleanup_errors:
            cleanup_message = "; ".join(cleanup_errors)
            if sys.exc_info()[0] is not None:
                print(f"KIND_LEASE_RECOVERY_CLEANUP_WARN: {cleanup_message}", file=sys.stderr)
            else:
                fail(f"cleanup failed: {cleanup_message}")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_LEASE_RECOVERY_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
