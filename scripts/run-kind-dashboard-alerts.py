#!/usr/bin/env python3
"""Run the Kind Dashboard alert scheduler, deduplication and retry acceptance."""

from __future__ import annotations

import argparse
import os
import sys
import uuid

from kind_test_support import KindTestError, PortForward, api_request, run, wait_until


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-At", "-c", statement, namespace=namespace)


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def event_rows(namespace: str, postgres_pod: str, tenant: str, project: str) -> list[tuple[str, str, int, str]]:
    statement = f"""
        select rule, status, attempts, coalesce(next_attempt_at::text, '')
          from dashboard_alert_events
         where tenant_id = {sql_literal(tenant)} and project_id = {sql_literal(project)}
         order by rule
    """
    rows = sql(namespace, postgres_pod, statement)
    if not rows:
        return []
    return [tuple(line.split("|", 3)) for line in rows.splitlines()]


def insert_audit(namespace: str, postgres_pod: str, tenant: str, project: str,
                 outcome: str, cost_usd: int) -> str:
    audit_id = str(uuid.uuid4())
    statement = f"""
        insert into model_call_audits
            (id, provider, model, latency_millis, prompt_tokens, completion_tokens,
             request_hash, response_hash, outcome, error_category, occurred_at,
             tenant_id, project_id, cost_usd, worker_id, task_id, team_id,
             tool_id, quota_id, quota_dimension, source_event_id)
        values
            ({sql_literal(audit_id)}::uuid, 'kind', 'dashboard-alert-kind', 100, 1, 1,
             repeat('a', 64), repeat('b', 64), {sql_literal(outcome)}, null, clock_timestamp(),
             {sql_literal(tenant)}, {sql_literal(project)}, {cost_usd}, null, null, null,
             null, null, null, null)
    """
    sql(namespace, postgres_pod, statement)
    return audit_id


def set_receiver_mode(args: argparse.Namespace, mode: str) -> None:
    run("set", "env", f"deployment/{args.receiver_deployment}",
        f"DASHBOARD_ALERT_RECEIVER_MODE={mode}", namespace=args.namespace)
    run("rollout", "status", f"deployment/{args.receiver_deployment}",
        f"--timeout={int(args.timeout)}s", namespace=args.namespace)


def wait_for_event(args: argparse.Namespace, tenant: str, project: str, rule: str,
                   status: str, minimum_attempts: int = 1) -> tuple[str, str, int, str]:
    def matching():
        rows = event_rows(args.namespace, args.postgres_pod, tenant, project)
        for row in rows:
            if row[0] == rule and row[1] == status and int(row[2]) >= minimum_attempts:
                return row
        return None

    return wait_until(f"{rule} event status {status}", matching, timeout=args.timeout)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--postgres-pod", default=os.environ.get("AGENTTEAMS_POSTGRES_POD", "postgresql-0"))
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--receiver-deployment", default="dashboard-alert-receiver")
    parser.add_argument("--local-port", type=int, default=18084)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--tenant", default=None)
    parser.add_argument("--project", default=None)
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:12]
    tenant = args.tenant or f"kind-dashboard-alert-{suffix}"
    project = args.project or f"project-{suffix}"

    with PortForward(args.namespace, args.control_plane_service, args.local_port, 8080):
        base_url = f"http://127.0.0.1:{args.local_port}"
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health").status == 200,
                   timeout=args.timeout)
        configured_rules = int(sql(args.namespace, args.postgres_pod,
                                   "select count(*) from dashboard_alert_rules where enabled"))
        if configured_rules < 2:
            raise KindTestError(f"expected at least two enabled dashboard alert rules, got {configured_rules}")

        first_audit = insert_audit(args.namespace, args.postgres_pod, tenant, project, "SUCCESS", 150)
        sent = wait_for_event(args, tenant, project, "COST", "SENT")
        if sent[2] != "1":
            raise KindTestError(f"first COST delivery must be one attempt, got {sent}")

        # The scheduler evaluates the same scope repeatedly. The unique fingerprint
        # must suppress a second event for the same COST/window/rule.
        rows_after_repeat = event_rows(args.namespace, args.postgres_pod, tenant, project)
        cost_rows = [row for row in rows_after_repeat if row[0] == "COST"]
        if len(cost_rows) != 1:
            raise KindTestError(f"expected one deduplicated COST event, got {cost_rows}")

        set_receiver_mode(args, "fail")
        second_audit = insert_audit(args.namespace, args.postgres_pod, tenant, project, "FAILURE", 0)
        failed = wait_for_event(args, tenant, project, "FAILURE_RATE", "FAILED")
        if not failed[3]:
            raise KindTestError(f"FAILED event must have next_attempt_at, got {failed}")

        set_receiver_mode(args, "success")
        recovered = wait_for_event(args, tenant, project, "FAILURE_RATE", "SENT", minimum_attempts=2)
        if len(event_rows(args.namespace, args.postgres_pod, tenant, project)) != 2:
            raise KindTestError("expected exactly two durable events after retry recovery")

        events_response = api_request(
            f"{base_url}/api/v1/dashboard/alerts/events?tenant={tenant}&project={project}&limit=100")
        if events_response.status != 200 or not isinstance(events_response.payload, list):
            raise KindTestError(f"events API did not return a JSON list: {events_response}")
        api_rules = {event.get("rule") for event in events_response.payload if isinstance(event, dict)}
        if not {"COST", "FAILURE_RATE"}.issubset(api_rules):
            raise KindTestError(f"events API missing expected rules: {events_response.payload!r}")

    print(f"KIND_DASHBOARD_ALERTS_OK tenant={tenant} project={project} "
          f"audits={first_audit},{second_audit} recovered_attempts={recovered[2]}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KindTestError, ValueError) as error:
        print(f"KIND_DASHBOARD_ALERTS_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
