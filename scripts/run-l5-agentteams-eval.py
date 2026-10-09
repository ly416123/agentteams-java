#!/usr/bin/env python3
"""Run the AgentTeams core evaluation suite from an L5 host.

This entrypoint intentionally runs inside the K3s host network. It resolves the
Control Plane ClusterIP, checks that a target-scope Worker is ready, reads a short
lived bearer token from the environment, and writes the same redacted report
used by the offline and L5 acceptance flows. It does not create memberships or
change cluster resources.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import replace
from pathlib import Path
from typing import Mapping
from urllib.parse import quote

# Allow direct execution from the repository root or from scripts/.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import AgentTeamsClient, EvalError, load_cases, run_suite


def command(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise EvalError(f"command failed: {' '.join(args)}: {detail[:300]}")
    return result.stdout.strip()


def kubectl_command() -> tuple[str, ...]:
    if shutil.which("kubectl"):
        return ("kubectl",)
    if Path("/usr/local/bin/k3s").is_file():
        return ("sudo", "/usr/local/bin/k3s", "kubectl")
    raise EvalError("kubectl is not available; run this entrypoint on the L5 K3s host")


def cluster_service_url(namespace: str, service: str, port: int) -> str:
    value = command(*kubectl_command(), "-n", namespace, "get", "svc", service,
                    "-o", "jsonpath={.spec.clusterIP}")
    if not value or value == "None":
        raise EvalError(f"service/{service} has no ClusterIP in namespace {namespace}")
    return f"http://{value}:{port}"


def assert_workers_ready(namespace: str, tenant: str, project_id: str, team: str) -> None:
    raw = command(*kubectl_command(), "-n", namespace, "get", "worker", "-o", "json")
    payload = json.loads(raw)
    workers = payload.get("items", [])
    if not isinstance(workers, list):
        raise EvalError("L5 Worker listing returned an invalid response")
    target = {"agentteams.io/tenant": tenant, "agentteams.io/project": project_id,
              "agentteams.io/team": team}
    if not any(isinstance(item, dict)
               and all(item.get("metadata", {}).get("labels", {}).get(key) == value
                       for key, value in target.items())
               and item.get("status", {}).get("phase") == "Ready" for item in workers):
        raise EvalError("no Ready Worker found for the selected L5 target")


def write_report(path: Path, report: dict, token: str = "") -> None:
    validate_report_safety(report, token)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _field(value: object, key: str) -> object:
    return value.get(key) if isinstance(value, dict) else getattr(value, key, None)


def validate_case_scopes(cases: list, tenant: str, project: str, team: str) -> None:
    expected = {"tenant": tenant, "project": project, "team": team}
    if any(not isinstance(value, str) or not value.strip() for value in expected.values()):
        raise EvalError("expected L5 tenant, project and team must be provided")
    for case in cases:
        task = _field(case, "task")
        scope = _field(task, "scope")
        if not isinstance(scope, dict):
            raise EvalError("every L5 case must declare a target scope")
        for key, expected_value in expected.items():
            actual = scope.get(key)
            if actual != expected_value:
                case_id = _field(case, "case_id") or "unknown"
                raise EvalError(f"case {case_id} {key} does not match the selected L5 target")


def select_cases(cases: list, requested_ids: list[str]) -> list:
    if not requested_ids:
        return cases
    requested = set(requested_ids)
    selected = [case for case in cases if case.case_id in requested]
    if {case.case_id for case in selected} != requested:
        raise EvalError("a requested L5 case was not found in the suite")
    return selected


def bind_team_id(cases: list, team_id: str) -> list:
    if not team_id:
        raise EvalError("selected L5 team has no id")
    bound = []
    for case in cases:
        task = case.task
        existing = task.get("spec")
        if existing is not None:
            if not isinstance(existing, Mapping) or existing.get("scope") != task.get("scope"):
                raise EvalError(f"case {case.case_id} has an invalid task spec scope")
            spec = dict(existing)
            if spec.get("teamId") not in (None, team_id):
                raise EvalError(f"case {case.case_id} targets a different team id")
        else:
            spec = {"scope": dict(task["scope"]), "taskType": task.get("taskType", "agentscope"),
                    "inputJson": task.get("inputJson", {"prompt": task.get("prompt", "")}),
                    "requiredCapabilities": task.get("requiredCapabilities", [])}
        spec["teamId"] = team_id
        bound.append(replace(case, task={**task, "spec": spec}))
    return bound


def assert_scope_access(client: AgentTeamsClient, tenant: str, project: str, team: str,
                        team_id: str = "") -> tuple[str, str]:
    projects_payload = client._request("GET", f"/api/v1/projects?q={quote(project, safe='')}")
    projects = projects_payload.get("items", []) if isinstance(projects_payload, dict) else projects_payload
    if not isinstance(projects, list):
        raise EvalError("project access preflight returned an invalid response")
    matches = [item for item in projects if isinstance(item, dict)
               and item.get("name") == project and item.get("tenantId") == tenant
               and item.get("status") == "ACTIVE" and item.get("id")]
    if len(matches) != 1:
        raise EvalError("selected L5 project was not found uniquely in the selected tenant")
    project_id = str(matches[0]["id"])
    role = client._request("GET", f"/api/v1/projects/{quote(project_id, safe='')}/role?requiredRole=ADMIN")
    if not isinstance(role, dict) or role.get("allowed") is not True:
        raise EvalError("selected L5 identity lacks project task creation and execution permission")
    if team_id:
        selected = client._request("GET", f"/api/v1/teams/{quote(team_id, safe='')}?projectId={quote(project_id, safe='')}")
        if not isinstance(selected, dict) or selected.get("id") != team_id or selected.get("status") != "ACTIVE":
            raise EvalError("selected L5 team id was not found as an active team in the selected project")
        return project_id, team_id
    teams = client._request("GET", f"/api/v1/teams?projectId={quote(project_id, safe='')}")
    if not isinstance(teams, list):
        raise EvalError("team access preflight returned an invalid response")
    matching_teams = [item for item in teams if isinstance(item, dict) and item.get("name") == team
                      and item.get("status") == "ACTIVE" and item.get("id")]
    if len(matching_teams) != 1:
        raise EvalError("selected L5 team was not found uniquely in the selected project")
    return project_id, str(matching_teams[0]["id"])


def validate_report_safety(report: dict, token: str = "") -> None:
    serialized = json.dumps(report, ensure_ascii=False, sort_keys=True)
    if token and token in serialized:
        raise EvalError("refusing to write a report containing the evaluation token")
    if re.search(r"(?i)https?://", serialized):
        raise EvalError("refusing to write a report containing a URL")
    if re.search(r'(?i)"(?:authorization|access[_-]?token|refresh[_-]?token|password|client[_-]?secret|downloadUrl)"\s*:', serialized):
        raise EvalError("refusing to write a report containing a credential or download URL field")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--cases", default="eval/cases/core.yaml")
    parser.add_argument("--case", action="append", dest="case_ids", default=[],
                        help="run only this case id; may be repeated")
    parser.add_argument("--report", default="eval/reports/l5-core-latest.json")
    parser.add_argument("--token-env", default="AGENTTEAMS_EVAL_TOKEN")
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_EVAL_TENANT", ""))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_EVAL_PROJECT", ""))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_EVAL_TEAM", ""))
    parser.add_argument("--team-id", default=os.environ.get("AGENTTEAMS_EVAL_TEAM_ID", ""),
                        help="select a specific active Team while retaining the case scope team claim")
    parser.add_argument("--artifact-origin", default=os.environ.get(
        "AGENTTEAMS_EVAL_ARTIFACT_ORIGIN", "http://192.168.122.55:30090"),
        help="exact origin allowed for downloading the final output.md artifact")
    parser.add_argument("--timeout-seconds", type=float, default=None,
                        help="override the per-case timeoutSeconds limit")
    parser.add_argument("--poll-seconds", type=float, default=3.0)
    parser.add_argument("--deepeval-export-dir", type=Path,
                        help="optional directory for credential-free DeepEval case exports")
    args = parser.parse_args(argv)
    try:
        token = os.environ.get(args.token_env, "")
        if not token:
            raise EvalError(f"environment variable {args.token_env} is not set")
        cases = select_cases(load_cases(args.cases), args.case_ids)
        validate_case_scopes(cases, args.tenant, args.project, args.team)
        base_url = args.base_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-control-plane", 8080)
        client = AgentTeamsClient(base_url, token,
                                  allowed_artifact_origins=(args.artifact_origin,) if args.artifact_origin else ())
        project_id, team_id = assert_scope_access(client, args.tenant, args.project, args.team, args.team_id)
        assert_workers_ready(args.namespace, args.tenant, project_id, args.team)
        report = run_suite(client, bind_team_id(cases, team_id),
                           args.timeout_seconds, args.poll_seconds,
                           deepeval_export_dir=args.deepeval_export_dir)
        report["evaluatorVersion"] = "deterministic-v1"
        report["suiteVersion"] = hashlib.sha256(Path(args.cases).read_bytes()).hexdigest()
        report["targetScope"] = {"tenant": args.tenant, "project": args.project, "team": args.team}
        write_report(Path(args.report), report, token)
        return 0 if report["passed"] else 1
    except (EvalError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"L5 evaluation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
