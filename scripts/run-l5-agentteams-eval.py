#!/usr/bin/env python3
"""Run the AgentTeams core evaluation suite from an L5 host.

This entrypoint intentionally runs inside the K3s host network. It resolves the
Control Plane ClusterIP, checks that the Worker fleet is ready, reads a short
lived bearer token from the environment, and writes the same redacted report
used by the offline and L5 acceptance flows. It does not create memberships or
change cluster resources.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

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


def assert_workers_ready(namespace: str) -> None:
    raw = command(*kubectl_command(), "-n", namespace, "get", "worker", "-o", "json")
    payload = json.loads(raw)
    workers = payload.get("items", [])
    if not workers:
        raise EvalError("no Worker resources found in the L5 namespace")
    not_ready = [item.get("metadata", {}).get("name", "<unnamed>")
                 for item in workers
                 if item.get("status", {}).get("phase") != "Ready"]
    if not_ready:
        raise EvalError(f"L5 Workers are not Ready: {', '.join(not_ready)}")


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--cases", default="eval/cases/core.yaml")
    parser.add_argument("--report", default="eval/reports/l5-core-latest.json")
    parser.add_argument("--token-env", default="AGENTTEAMS_EVAL_TOKEN")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--poll-seconds", type=float, default=3.0)
    parser.add_argument("--deepeval-export-dir", type=Path,
                        help="optional directory for credential-free DeepEval case exports")
    args = parser.parse_args(argv)
    try:
        token = os.environ.get(args.token_env, "")
        if not token:
            raise EvalError(f"environment variable {args.token_env} is not set")
        assert_workers_ready(args.namespace)
        base_url = args.base_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-control-plane", 8080)
        report = run_suite(AgentTeamsClient(base_url, token), load_cases(args.cases),
                           args.timeout_seconds, args.poll_seconds,
                           deepeval_export_dir=args.deepeval_export_dir)
        write_report(Path(args.report), report)
        return 0 if report["passed"] else 1
    except (EvalError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"L5 evaluation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
