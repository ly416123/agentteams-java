#!/usr/bin/env python3
"""Run the Kind subtask decomposition acceptance against the deployed stack.

Drives the deterministic chain end to end: create and queue a qwenpaw task,
let the Worker runtime hit the conversation mock's decomposition script
(plan → RUNNING/SUCCEEDED/RUNNING/FAILED → summary), then assert the tree
projection, the process-events chain and terminal-state isolation through the
control-plane API. The Worker's QwenPaw endpoint is temporarily pointed at the
conversation mock for the duration of the run and restored afterwards.

Requires a Keycloak bearer token (alice) via --token or
AGENTTEAMS_API_BEARER_TOKEN, and a port-forward to the control-plane service
(default http://127.0.0.1:18080), e.g.:
  kubectl -n agentteams port-forward svc/agentteams-agentteams-java-control-plane 18080:8080
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

POLL_TIMEOUT_SECONDS = 180.0
POLL_INTERVAL_SECONDS = 2.0
EXPECTED_SUBTASK_TITLES = ("抓取邮件", "生成摘要")


def fail(message: str) -> None:
    raise RuntimeError(message)


def command_available(name: str) -> str:
    resolved = shutil.which(name)
    if not resolved:
        fail(f"required command is unavailable: {name}")
    return resolved


def run_command(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed: {' '.join(args)}: {detail}")
    return result.stdout.strip()


def kubectl(namespace: str, *args: str) -> str:
    return run_command("kubectl", "-n", namespace, *args)


def request_json(url: str, method: str = "GET", body: dict | list | None = None,
                 token: str = "", idempotency_key: str | None = None) -> tuple[int, object]:
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        fail(f"HTTP {error.code} from {url}: {raw[:500]}")


def require_environment(namespace: str, token: str) -> None:
    command_available("kubectl")
    command_available("kind")
    # Equivalent shell command: kind get clusters. Missing infrastructure is a
    # hard failure; this acceptance never emits SKIPPED as a success result.
    clusters = run_command("kind", "get", "clusters")
    if not clusters or "agentteams" not in clusters.splitlines():
        fail("Kind cluster agentteams is required; kind get clusters returned no matching cluster")
    deployments = json.loads(kubectl(namespace, "get", "deployments", "-o", "json"))
    names = {item["metadata"]["name"] for item in deployments.get("items", [])}
    for required in ("qwenpaw-worker", "qwenpaw-conversation-mock"):
        if required not in names:
            fail(f"deployment/{required} is required in namespace {namespace}")
    if not token:
        fail("--token or AGENTTEAMS_API_BEARER_TOKEN is required for the authenticated acceptance")


def worker_endpoint(namespace: str, worker_name: str) -> str:
    return kubectl(namespace, "get", "worker", worker_name,
                   "-o", "jsonpath={.spec.env.QWENPAW_ENDPOINT}")


def patch_worker_endpoint(namespace: str, worker_name: str, endpoint: str) -> None:
    patch = json.dumps({"spec": {"env": {"QWENPAW_ENDPOINT": endpoint}}})
    kubectl(namespace, "patch", "worker", worker_name, "--type=merge", "-p", patch)


def wait_for_worker_endpoint(namespace: str, worker_name: str, endpoint: str) -> None:
    """The Operator reconciles the deployment from the Worker CR; wait until the
    rendered deployment carries the patched endpoint AND the rollout finished,
    otherwise an old pod still pointed at the real QwenPaw picks the task up."""
    deadline = time.monotonic() + 120.0
    while time.monotonic() < deadline:
        deployment = json.loads(kubectl(namespace, "get", "deployment", worker_name, "-o", "json"))
        env_values = {
            entry.get("name"): entry.get("value")
            for container in deployment.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
            for entry in container.get("env", [])
        }
        if env_values.get("QWENPAW_ENDPOINT") == endpoint:
            kubectl(namespace, "rollout", "status", "deployment", worker_name, "--timeout=120s")
            return
        time.sleep(2.0)
    fail(f"deployment/{worker_name} did not pick up QWENPAW_ENDPOINT={endpoint} within 120s")


def token_subject(token: str) -> str:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def database_password(namespace: str) -> str:
    encoded = kubectl(namespace, "get", "secret", "agentteams-database",
                      "-o", "jsonpath={.data.password}")
    return base64.b64decode(encoded).decode()


def psql(namespace: str, password: str, sql: str) -> str:
    return kubectl(namespace, "exec", "statefulset/postgresql", "--",
                   "env", f"PGPASSWORD={password}",
                   "psql", "-U", "agentteams", "-d", "agentteams", "-At", "-c", sql)


def ensure_memberships(namespace: str, subject: str, tenant: str, project: str) -> str | None:
    """process-events/tree 需三重 membership（org/tenant/project，与 run-kind-memory-scope
    同约定）；返回 project membership 原 role 供结束后还原（None=原本不存在）。"""
    password = database_password(namespace)
    previous = psql(namespace, password,
                    "SELECT role FROM project_memberships "
                    f"WHERE subject='{subject}' AND tenant_id='{tenant}' AND project_id="
                    f"(SELECT id FROM projects WHERE tenant_id='{tenant}' AND name='{project}') LIMIT 1;").strip()
    psql(namespace, password, f"""
        INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
        SELECT organization_id, '{subject}', 'MEMBER', now(), now()
          FROM legacy_tenant_mappings WHERE legacy_tenant_key = '{tenant}'
        ON CONFLICT (organization_id, subject) DO UPDATE SET role = 'MEMBER', updated_at = now();
        INSERT INTO tenant_memberships(organization_id, tenant_id, subject, role, created_at, updated_at)
        SELECT organization_id, tenant_id, '{subject}', 'MEMBER', now(), now()
          FROM legacy_tenant_mappings WHERE legacy_tenant_key = '{tenant}'
        ON CONFLICT (tenant_id, subject) DO UPDATE SET role = 'MEMBER', updated_at = now();
        INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
        SELECT '{tenant}', id, '{subject}', 'ADMIN', 'ACTIVE', now(), now(), 0
          FROM projects WHERE tenant_id = '{tenant}' AND name = '{project}'
        ON CONFLICT (tenant_id, project_id, subject)
        DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = now();
    """)
    return previous or None


def restore_project_membership(namespace: str, subject: str, tenant: str, project: str,
                               previous: str | None) -> None:
    password = database_password(namespace)
    if previous:
        psql(namespace, password,
             f"UPDATE project_memberships SET role='{previous}', updated_at=now() "
             f"WHERE subject='{subject}' AND tenant_id='{tenant}' AND project_id="
             f"(SELECT id FROM projects WHERE tenant_id='{tenant}' AND name='{project}');")
    else:
        psql(namespace, password,
             f"DELETE FROM project_memberships WHERE subject='{subject}' AND tenant_id='{tenant}' "
             f"AND project_id=(SELECT id FROM projects WHERE tenant_id='{tenant}' AND name='{project}');")


def poll_until(action, description: str, timeout: float = POLL_TIMEOUT_SECONDS):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = action()
        if last:
            return last
        time.sleep(POLL_INTERVAL_SECONDS)
    fail(f"timed out waiting for {description}: {last}")


def create_and_queue_task(base_url: str, token: str, tenant: str, project: str, team: str) -> str:
    body = {
        "title": "kind-subtask-decomposition",
        "description": "Subtask decomposition end-to-end acceptance",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": "Execute the deterministic subtask decomposition script."},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body, token,
                                   f"kind-subtask-decomp-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {}, token,
                 f"kind-subtask-decomp-queue-{uuid.uuid4()}")
    return task_id


def wait_for_run_id(base_url: str, token: str, task_id: str) -> str:
    def check():
        _, runs = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs",
                               "GET", None, token)
        if isinstance(runs, list) and runs:
            return runs[0].get("id")
        return None

    return poll_until(check, "the task run to appear")


def wait_for_tree(base_url: str, token: str, task_id: str, run_id: str) -> list[dict]:
    """树投影：除根外出现 2 个子任务节点且终态为 SUCCEEDED + FAILED。"""
    def check():
        _, tree = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs/{run_id}/tree", "GET", None, token)
        if not isinstance(tree, list):
            return None
        children = [node for node in tree if node.get("parentTaskId") == task_id]
        if len(children) != 2:
            return None
        statuses = sorted(node.get("status") for node in children)
        if statuses != ["FAILED", "SUCCEEDED"]:
            return None
        return children

    return poll_until(check, "tree projection with 2 terminal subtasks (SUCCEEDED + FAILED)")


def wait_for_events(base_url: str, token: str, task_id: str, run_id: str) -> list[dict]:
    """过程事件链：planned×2 + started×2 + succeeded + failed，且 tool.* 事件在流中。"""
    def check():
        _, events = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs/{run_id}/process-events",
            "GET", None, token)
        if not isinstance(events, list):
            return None
        types = [event.get("eventType") for event in events]
        counts = {name: types.count(name) for name in
                  ("subtask.planned", "subtask.started", "subtask.succeeded", "subtask.failed")}
        if (counts["subtask.planned"] < 2 or counts["subtask.started"] < 2
                or counts["subtask.succeeded"] < 1 or counts["subtask.failed"] < 1):
            return None
        if not any(event_type.startswith("tool.") for event_type in types):
            return None
        return events

    return poll_until(check, "process-events chain (planned/started/succeeded/failed + tool.*)")


def assert_event_invariants(events: list[dict]) -> None:
    sequences = [event["sequence"] for event in events]
    if sequences != sorted(sequences) or len(set(sequences)) != len(sequences):
        fail(f"process-events sequence is not strictly monotonic: {sequences}")
    planned_titles = []
    for event in events:
        if event.get("eventType") != "subtask.planned" or not event.get("payload"):
            continue
        try:
            planned_titles.append(json.loads(event["payload"]).get("title"))
        except (TypeError, ValueError):
            fail(f"subtask.planned payload is not valid JSON: {event.get('payload')!r}")
    if sorted(title for title in planned_titles if title) != sorted(EXPECTED_SUBTASK_TITLES):
        fail(f"planned subtask titles {planned_titles} do not match {list(EXPECTED_SUBTASK_TITLES)}")


def wait_for_terminal_phase(base_url: str, token: str, task_id: str, expected: str) -> str:
    def check():
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}", "GET", None, token)
        phase = task.get("phase") if isinstance(task, dict) else None
        return phase if phase in ("SUCCEEDED", "FAILED", "CANCELLED") else None

    phase = poll_until(check, "the task to reach a terminal phase")
    if phase != expected:
        fail(f"task phase is {phase}, expected {expected} (subtask FAILED must not leak)")
    return phase


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "http://127.0.0.1:18080"))
    parser.add_argument("--token", default=os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", ""))
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_SCOPE_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_SCOPE_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_SCOPE_TEAM", "team-a"))
    parser.add_argument("--worker", default=os.environ.get("AGENTTEAMS_WORKER_NAME", "qwenpaw-worker"))
    parser.add_argument("--mock-endpoint", default="http://qwenpaw-conversation-mock:8080")
    parser.add_argument("--keep-worker-endpoint", action="store_true",
                        help="leave the Worker pointed at the conversation mock for debugging")
    args = parser.parse_args()

    require_environment(args.namespace, args.token)
    subject = token_subject(args.token)
    previous_role = ensure_memberships(args.namespace, subject, args.tenant, args.project)
    print(f"memberships ensured for subject={subject} (previous project role: {previous_role!r})")
    original_endpoint = worker_endpoint(args.namespace, args.worker)
    print(f"worker {args.worker} original QWENPAW_ENDPOINT={original_endpoint!r}")
    patch_worker_endpoint(args.namespace, args.worker, args.mock_endpoint)
    wait_for_worker_endpoint(args.namespace, args.worker, args.mock_endpoint)

    try:
        task_id = create_and_queue_task(args.base_url, args.token, args.tenant, args.project, args.team)
        print(f"task created and queued: {task_id}")
        run_id = wait_for_run_id(args.base_url, args.token, task_id)
        print(f"run started: {run_id}")
        children = wait_for_tree(args.base_url, args.token, task_id, run_id)
        events = wait_for_events(args.base_url, args.token, task_id, run_id)
        assert_event_invariants(events)
        phase = wait_for_terminal_phase(args.base_url, args.token, task_id, "SUCCEEDED")

        tool_called = sum(1 for event in events if event.get("eventType") == "tool.called")
        tool_finished = sum(1 for event in events if event.get("eventType") == "tool.finished")
        print("PASS kind-subtask-decomposition:")
        print(f"  task={task_id} run={run_id} phase={phase}")
        for node in sorted(children, key=lambda item: item.get("sequence", 0)):
            print(f"  subtask {node['taskId']} sequence={node.get('sequence')} status={node.get('status')}")
        print(f"  events={len(events)} (tool.called={tool_called}, tool.finished={tool_finished})")
        return 0
    finally:
        if args.keep_worker_endpoint:
            print("keeping the Worker pointed at the conversation mock (--keep-worker-endpoint)")
        else:
            patch_worker_endpoint(args.namespace, args.worker, original_endpoint)
            wait_for_worker_endpoint(args.namespace, args.worker, original_endpoint)
            print(f"worker {args.worker} restored to QWENPAW_ENDPOINT={original_endpoint!r}")
        restore_project_membership(args.namespace, subject, args.tenant, args.project, previous_role)
        print(f"project membership restored for subject={subject}")

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"KIND_SUBTASK_DECOMPOSITION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
