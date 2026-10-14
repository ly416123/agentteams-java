#!/usr/bin/env python3
"""L5 negative probe: Skill-policy context blocks a required MCP invocation.

Run on the L5 host with AGENTTEAMS_API_PASSWORD and AGENTTEAMS_TEST_TEAM_ID
set. Only quota-admin's project membership is temporarily elevated for task
creation; its previous role is restored when the probe finishes.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

POLL_TIMEOUT_SECONDS = 240.0
POLL_INTERVAL_SECONDS = 3.0
KUBECTL = ("sudo", "/usr/local/bin/k3s", "kubectl")
EXPECTED_TOOL = "web_search"
EXPECTED_SKILL_TOOL = "load_skill_through_path"
EXPECTED_FAILURE = "REQUIRED_TOOL_NOT_CALLED: web_search"


def fail(message: str) -> None:
    raise RuntimeError(message)


def event_payload(event: dict) -> dict:
    payload = event.get("payload")
    if isinstance(payload, str):
        try:
            payload = json.loads(payload)
        except ValueError:
            return {}
    return payload if isinstance(payload, dict) else {}


def verify_denial(task: dict, run: dict, events: list[dict]) -> dict:
    """Assert policy filtering rather than an unrelated execution failure."""
    if task.get("phase") != "FAILED":
        fail(f"expected Task phase FAILED, got {task.get('phase')!r}")
    if run.get("status") != "FAILED":
        fail(f"expected Run status FAILED, got {run.get('status')!r}")
    summary = str(run.get("summary", ""))
    if not summary.startswith(EXPECTED_FAILURE):
        fail(f"expected {EXPECTED_FAILURE}, got {summary!r}")

    skill_load_tool_call_observed = any(
        event.get("eventType") == "tool.finished"
        and event_payload(event).get("tool") == EXPECTED_SKILL_TOOL
        for event in events
    )
    if not skill_load_tool_call_observed:
        fail("Skill load tool call must be observed before checking MCP policy")

    web_search_events = [
        event for event in events
        if event_payload(event).get("tool") == EXPECTED_TOOL
        and str(event.get("eventType", "")).startswith("tool.")
    ]
    if web_search_events:
        fail("web_search was invoked despite the expected DENY_ALL policy")

    return {
        "denialVerified": True,
        "skillLoadToolCallObserved": True,
        "blockedTool": EXPECTED_TOOL,
        "taskPhase": task["phase"],
        "runStatus": run["status"],
        "failureSummary": summary,
    }


def run_command(*args: str) -> str:
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed: {' '.join(args)}: {detail}")
    return result.stdout.strip()


def kubectl(namespace: str, *args: str) -> str:
    return run_command(*KUBECTL, "-n", namespace, *args)


def cluster_service_url(namespace: str, service: str, port: int = 8080) -> str:
    ip = kubectl(namespace, "get", "svc", service, "-o", "jsonpath={.spec.clusterIP}")
    if not ip:
        fail(f"service/{service} in namespace {namespace} has no cluster IP")
    return f"http://{ip}:{port}"


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def database_password(namespace: str) -> str:
    encoded = kubectl(namespace, "get", "secret", "agentteams-database",
                      "-o", "jsonpath={.data.password}")
    return base64.b64decode(encoded).decode()


def psql(namespace: str, password: str, statement: str) -> str:
    return kubectl(namespace, "exec", "statefulset/postgresql", "--",
                   "env", f"PGPASSWORD={password}", "psql", "-U", "agentteams",
                   "-d", "agentteams", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def token_subject(token: str) -> str:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))["sub"]
    except (IndexError, KeyError, ValueError) as error:
        fail(f"could not read OIDC subject from access token: {error}")


def fetch_token(keycloak_url: str, username: str, password: str) -> str:
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "agentteams-api",
        "username": username, "password": password, "scope": "openid",
    }).encode()
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/agentteams/protocol/openid-connect/token",
        data=body, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            token = json.loads(response.read().decode()).get("access_token")
    except (urllib.error.URLError, urllib.error.HTTPError) as error:
        fail(f"could not obtain a bearer token from Keycloak: {error}")
    if not token:
        fail("Keycloak token response carried no access_token")
    return token


def request_json(url: str, method: str, body: dict | None, token: str,
                 idempotency_key: str | None = None) -> tuple[int, object]:
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
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
        detail = error.read().decode(errors="replace")[:500]
        fail(f"{method} {url} returned HTTP {error.code}: {detail}")


def membership_snapshot(namespace: str, subject: str, tenant: str, project: str) -> dict:
    password = database_password(namespace)
    where = (f"subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND "
             f"project_id=(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} "
             f"AND name={sql_literal(project)})")
    rows = psql(namespace, password,
                f"SELECT json_build_object('role',role,'status',status,'updatedAt',updated_at)::text "
                f"FROM project_memberships WHERE {where};").splitlines()
    if len(rows) != 1:
        if not rows:
            fail("quota-admin has no existing project membership; refusing to create one")
        fail(f"expected one quota-admin project membership, found {len(rows)}")
    try:
        snapshot = json.loads(rows[0])
    except ValueError as error:
        fail(f"could not parse project membership snapshot: {error}")
    if not isinstance(snapshot, dict) or not snapshot.get("role"):
        fail("quota-admin has no existing project membership; refusing to create one")
    return snapshot


def ensure_project_admin(namespace: str, subject: str, tenant: str, project: str) -> dict:
    snapshot = membership_snapshot(namespace, subject, tenant, project)
    password = database_password(namespace)
    where = (f"subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND "
             f"project_id=(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} "
             f"AND name={sql_literal(project)})")
    psql(namespace, password,
         f"UPDATE project_memberships SET role='ADMIN' WHERE {where};")
    return snapshot


def restore_project_role(namespace: str, subject: str, tenant: str,
                         project: str, previous: dict) -> None:
    password = database_password(namespace)
    where = (f"subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND "
             f"project_id=(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} "
             f"AND name={sql_literal(project)})")
    updated_at = previous.get("updatedAt")
    updated_at_sql = "NULL" if updated_at is None else f"{sql_literal(str(updated_at))}::timestamptz"
    psql(namespace, password,
         f"UPDATE project_memberships SET role={sql_literal(previous['role'])}, "
         f"status={sql_literal(previous['status'])}, updated_at={updated_at_sql} WHERE {where};")


def request(base_url: str, token: str, method: str, path: str,
            body: dict | None = None, idempotency_key: str | None = None) -> object:
    return request_json(base_url.rstrip("/") + path, method, body, token, idempotency_key)[1]


def create_probe_task(base_url: str, token: str, args: argparse.Namespace) -> str:
    prompt = (
        "Load the industry-research Skill with load_skill_through_path using ID "
        "industry-research. Then invoke web_search exactly once with query "
        "site:www.w3.org PDF test document. If unavailable, say so clearly and do not "
        "invent results. This is read-only; do not create or modify business data."
    )
    spec = {
        "scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
        "teamId": args.team_id,
        "teamRole": "LEADER",
        "orchestrationPhase": "EXECUTE",
        "taskType": "agentscope",
        "inputJson": {"prompt": prompt, "requiredMcpTools": [EXPECTED_TOOL]},
        "requiredCapabilities": ["agentscope"],
    }
    task_title = f"quota-admin-skill-mcp-denyall-probe-{uuid.uuid4().hex[:8]}"
    created = request(base_url, token, "POST", "/api/v1/tasks", {
        "title": task_title,
        "description": "Read-only regression for Skill DENY_ALL MCP filtering",
        "spec": spec,
    }, f"l5-skill-mcp-policy-create-{uuid.uuid4()}")
    task_id = created.get("id") if isinstance(created, dict) else None
    if not task_id:
        fail("probe task creation returned no task id")
    request(base_url, token, "POST", f"/api/v1/tasks/{task_id}/queue", {},
            f"l5-skill-mcp-policy-queue-{uuid.uuid4()}")
    return task_id


def poll_task(base_url: str, token: str, task_id: str) -> dict:
    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    latest = None
    while time.monotonic() < deadline:
        latest = request(base_url, token, "GET", f"/api/v1/tasks/{task_id}")
        if latest.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            return latest
        time.sleep(POLL_INTERVAL_SECONDS)
    fail(f"timed out waiting for probe task; last task={latest}")


def run_acceptance(args: argparse.Namespace) -> dict:
    password = args.password or os.environ.get("AGENTTEAMS_API_PASSWORD", "")
    if not password:
        fail("set AGENTTEAMS_API_PASSWORD; password is not accepted on the command line")
    keycloak_url = args.keycloak_url or cluster_service_url(args.namespace, args.keycloak_service)
    base_url = args.base_url or cluster_service_url(args.namespace, args.control_plane_service)
    token = fetch_token(keycloak_url, args.username, password)
    subject = token_subject(token)
    previous_membership = ensure_project_admin(args.namespace, subject, args.tenant, args.project)
    try:
        task_id = create_probe_task(base_url, token, args)
        task = poll_task(base_url, token, task_id)
        runs = request(base_url, token, "GET", f"/api/v1/tasks/{task_id}/runs")
        rows = runs if isinstance(runs, list) else runs.get("runs", [])
        if not rows:
            fail("probe task completed without a Run")
        run_record = rows[0]
        run_id = run_record.get("runId") or run_record.get("id")
        if not run_id:
            fail("probe Run record returned no run id")
        events_result = request(base_url, token, "GET",
                                f"/api/v1/tasks/{task_id}/runs/{run_id}/process-events")
        events = events_result if isinstance(events_result, list) else events_result.get("events", [])
        result = request(base_url, token, "GET",
                         f"/api/v1/tasks/{task_id}/runs/{run_id}/result")
        verified = verify_denial(task, result, events)
    finally:
        restore_project_role(args.namespace, subject, args.tenant, args.project, previous_membership)
    restored_membership = membership_snapshot(args.namespace, subject, args.tenant, args.project)
    if restored_membership != previous_membership:
        fail(f"project membership was not restored exactly: expected {previous_membership}, "
             f"got {restored_membership}")
    verified.update({"taskId": task_id, "runId": run_id,
                     "projectMembershipRestored": restored_membership})
    return verified


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--username", default=os.environ.get("AGENTTEAMS_API_USERNAME", "quota-admin"))
    parser.add_argument("--password", default="", help=argparse.SUPPRESS)
    parser.add_argument("--tenant", default="tenant-a")
    parser.add_argument("--project", default="project-a")
    parser.add_argument("--team", default="team-a")
    parser.add_argument("--team-id", default=os.environ.get("AGENTTEAMS_TEST_TEAM_ID", ""))
    parser.add_argument("--base-url", default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", ""))
    parser.add_argument("--keycloak-url", default=os.environ.get("AGENTTEAMS_KEYCLOAK_URL", ""))
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--keycloak-service", default="keycloak")
    args = parser.parse_args(argv)
    if not args.team_id:
        fail("set --team-id or AGENTTEAMS_TEST_TEAM_ID to the quota-admin team UUID")
    return args


def main(argv: list[str] | None = None) -> int:
    try:
        print(json.dumps(run_acceptance(parse_args(argv)), ensure_ascii=False, sort_keys=True))
        return 0
    except RuntimeError as error:
        print(f"L5 Skill MCP policy acceptance failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
