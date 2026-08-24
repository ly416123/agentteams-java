#!/usr/bin/env python3
"""Verify a real QwenPaw Worker reserves and releases project quota in Kind."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import os
import sys
import uuid

from kind_test_support import HttpResult, KindTestError, PortForward, api_request, query_url, wait_until


@dataclass(frozen=True)
class QuotaSnapshot:
    current_concurrent: int
    daily_calls: int
    daily_tokens: int


def require_status(result: HttpResult, expected: int, operation: str) -> dict:
    if result.status != expected or not isinstance(result.payload, dict):
        raise KindTestError(f"{operation} expected HTTP {expected}, got {result.status}: {result.payload!r}")
    return result.payload


def configure_quota(base_url: str, tenant: str, project: str, args: argparse.Namespace) -> None:
    require_status(api_request(
        f"{base_url}/api/v1/usage/quota", "PUT",
        {"tenantId": tenant, "projectId": project,
         "maxConcurrentCalls": args.max_concurrent,
         "maxDailyCalls": args.max_daily_calls,
         "maxDailyTokens": args.max_daily_tokens}), 200,
        f"configure quota {tenant}/{project}")


def read_quota(base_url: str, tenant: str, project: str) -> QuotaSnapshot:
    payload = require_status(api_request(query_url(
        base_url, "/api/v1/usage/quota", {"tenantId": tenant, "projectId": project})), 200,
        f"read quota {tenant}/{project}")
    try:
        return QuotaSnapshot(int(payload["currentConcurrentCalls"]),
                             int(payload["dailyCalls"]), int(payload["dailyTokens"]))
    except (KeyError, TypeError, ValueError) as error:
        raise KindTestError(f"quota response has no stable counters: {payload!r}") from error


def create_team(base_url: str, agent_id: str) -> str:
    suffix = uuid.uuid4().hex[:10]
    payload = require_status(api_request(
        f"{base_url}/api/v1/teams", "POST",
        {"name": f"kind-worker-quota-{suffix}",
         "displayName": "Kind Worker quota admission",
         "maxConcurrentTasks": 1,
         "requireHumanApproval": False,
         "allowedRuntimes": ["qwenpaw"],
         "requiredCapabilities": ["qwenpaw"]}), 201, "create quota admission team")
    team_id = payload.get("id")
    if not team_id:
        raise KindTestError(f"create team response has no id: {payload!r}")
    team_id = str(team_id)
    require_status(api_request(f"{base_url}/api/v1/teams/{team_id}/members", "POST",
                               {"agentId": agent_id, "role": "WORKER"}), 200,
                   f"add Worker {agent_id} to team {team_id}")
    return team_id


def create_task(base_url: str, tenant: str, project: str, team: str, team_id: str) -> str:
    payload = require_status(api_request(
        f"{base_url}/api/v1/tasks", "POST",
        {"title": "kind-worker-quota-admission",
         "description": "Real Worker project-quota admission smoke test",
         "spec": {"scope": {"tenant": tenant, "project": project, "team": team},
                  "teamId": team_id,
                  "taskType": "qwenpaw",
                  "inputJson": {"prompt": "KIND_WORKER_QUOTA_ADMISSION_OK"},
                  "requiredCapabilities": ["qwenpaw"]}},
        f"kind-worker-quota-create-{uuid.uuid4()}"), 201, "create quota admission task")
    task_id = payload.get("id")
    if not task_id:
        raise KindTestError(f"create task response has no id: {payload!r}")
    return str(task_id)


def delete_team(base_url: str, team_id: str | None) -> None:
    if team_id:
        try:
            api_request(f"{base_url}/api/v1/teams/{team_id}", "DELETE")
        except (KindTestError, OSError):
            pass


def queue_task(base_url: str, task_id: str) -> None:
    require_status(api_request(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                               f"kind-worker-quota-queue-{uuid.uuid4()}"), 200,
                   f"queue quota admission task {task_id}")


def task_completion(base_url: str, task_id: str) -> dict | None:
    result = api_request(f"{base_url}/api/v1/tasks/{task_id}")
    if result.status != 200 or not isinstance(result.payload, dict):
        raise KindTestError(f"read task {task_id} failed: {result.status}: {result.payload!r}")
    return result.payload if result.payload.get("phase") in {"SUCCEEDED", "FAILED", "CANCELLED"} else None


def quota_released(base_url: str, tenant: str, project: str,
                   baseline: QuotaSnapshot) -> QuotaSnapshot | None:
    snapshot = read_quota(base_url, tenant, project)
    return snapshot if snapshot.current_concurrent == 0 and snapshot.daily_calls > baseline.daily_calls else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("KIND_NAMESPACE", "agentteams"))
    parser.add_argument("--control-plane-service",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_SERVICE",
                                               "agentteams-agentteams-java-control-plane"))
    parser.add_argument("--local-port", type=int, default=18087)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID"))
    parser.add_argument("--max-concurrent", type=int, default=1)
    parser.add_argument("--max-daily-calls", type=int, default=10000)
    parser.add_argument("--max-daily-tokens", type=int, default=1000000)
    args = parser.parse_args()
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")
    if args.max_concurrent != 1 or args.max_daily_calls <= 0 or args.max_daily_tokens <= 0:
        parser.error("quota limits must be max-concurrent=1 and positive daily limits")

    forward = PortForward(args.namespace, args.control_plane_service, args.local_port, 8080)
    base_url = f"http://127.0.0.1:{args.local_port}"
    team_id = None
    try:
        forward.start(timeout=args.timeout)
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health").status == 200,
                   args.timeout)
        configure_quota(base_url, args.tenant, args.project, args)
        baseline = read_quota(base_url, args.tenant, args.project)
        team_id = create_team(base_url, args.agent_id)
        task_id = create_task(base_url, args.tenant, args.project, args.team, team_id)
        queue_task(base_url, task_id)
        final = wait_until(f"real Worker task {task_id} completion",
                           lambda: task_completion(base_url, task_id), args.timeout)
        if final.get("phase") != "SUCCEEDED":
            raise KindTestError(f"real Worker task did not succeed: {final!r}")

        after = wait_until(f"quota release for task {task_id}",
                           lambda: quota_released(base_url, args.tenant, args.project, baseline),
                           args.timeout)
        calls_delta = after.daily_calls - baseline.daily_calls
        tokens_delta = after.daily_tokens - baseline.daily_tokens
        if calls_delta < 1 or tokens_delta <= 0:
            raise KindTestError(
                f"real Worker quota counters did not increase: baseline={baseline!r}, after={after!r}")
        print(f"KIND_WORKER_QUOTA_ADMISSION_OK task={task_id} agent={args.agent_id} "
              f"daily_calls_delta={calls_delta} daily_tokens_delta={tokens_delta} "
              f"current_concurrent_calls={after.current_concurrent}")
        return 0
    finally:
        delete_team(base_url, team_id)
        forward.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KindTestError, RuntimeError) as error:
        print(f"KIND_WORKER_QUOTA_ADMISSION_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
