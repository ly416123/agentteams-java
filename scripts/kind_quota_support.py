"""Small stdlib-only helpers shared by project-quota Kind acceptance scripts."""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


def fail(message: str) -> None:
    raise RuntimeError(message)


def run(*args: str, namespace: str | None = None) -> str:
    command = ["kubectl"]
    if namespace:
        command += ["-n", namespace]
    command += list(args)
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed ({result.returncode}): {' '.join(command)}\n{detail}")
    return result.stdout.strip()


def sql_literal(value: str) -> str:
    """Return a PostgreSQL string literal for values supplied to psql -c."""
    return "'" + value.replace("'", "''") + "'"


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run(
        "exec",
        postgres_pod,
        "--",
        "psql",
        "-U",
        "agentteams",
        "-d",
        "agentteams",
        "-At",
        "-F",
        "|",
        "-c",
        statement,
        namespace=namespace,
    )


def configure_policy(
    namespace: str,
    postgres_pod: str,
    tenant: str,
    project: str,
    *,
    max_concurrent: int = 1,
    max_daily_calls: int = 20,
    max_daily_tokens: int = 10_000,
) -> None:
    tenant_sql = sql_literal(tenant)
    project_sql = sql_literal(project)
    statement = f"""
        INSERT INTO project_quota_policies
            (tenant_id, project_id, max_concurrent_calls, max_daily_calls,
             max_daily_tokens, current_concurrent_calls, daily_calls,
             daily_tokens, usage_day, created_at, updated_at)
        VALUES ({tenant_sql}, {project_sql}, {max_concurrent}, {max_daily_calls},
                {max_daily_tokens}, 0, 0, 0, CURRENT_DATE, NOW(), NOW())
        ON CONFLICT (tenant_id, project_id) DO UPDATE SET
            max_concurrent_calls = EXCLUDED.max_concurrent_calls,
            max_daily_calls = EXCLUDED.max_daily_calls,
            max_daily_tokens = EXCLUDED.max_daily_tokens,
            current_concurrent_calls = 0,
            daily_calls = 0,
            daily_tokens = 0,
            usage_day = CURRENT_DATE,
            updated_at = NOW();
    """
    sql(namespace, postgres_pod, statement)


def quota_state(namespace: str, postgres_pod: str, tenant: str, project: str) -> dict[str, int]:
    row = sql(
        namespace,
        postgres_pod,
        f"""
        SELECT current_concurrent_calls, daily_calls, daily_tokens,
               max_concurrent_calls, max_daily_calls, max_daily_tokens
          FROM project_quota_policies
         WHERE tenant_id = {sql_literal(tenant)}
           AND project_id = {sql_literal(project)};
        """,
    )
    values = row.split("|") if row else []
    if len(values) != 6:
        fail(f"quota policy row missing for scope {tenant}/{project}")
    names = (
        "current_concurrent_calls",
        "daily_calls",
        "daily_tokens",
        "max_concurrent_calls",
        "max_daily_calls",
        "max_daily_tokens",
    )
    return {name: int(value) for name, value in zip(names, values, strict=True)}


def find_grpcurl(explicit: str | None = None) -> str:
    candidate = explicit or os.environ.get("GRPCURL_BIN", "grpcurl")
    resolved = shutil.which(candidate)
    if not resolved:
        fail(
            "grpcurl is required for the Kind quota acceptance test; "
            "install it or set GRPCURL_BIN/--grpcurl-bin"
        )
    return resolved


def start_port_forward(namespace: str, service: str, local_port: int, remote_port: int) -> subprocess.Popen:
    process = subprocess.Popen(
        [
            "kubectl",
            "-n",
            namespace,
            "port-forward",
            f"service/{service}",
            f"{local_port}:{remote_port}",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return process


def stop_port_forward(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()


def wait_for_port(process: subprocess.Popen, host: str, port: int, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            fail("gateway port-forward exited before becoming ready")
        try:
            with socket.create_connection((host, port), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    fail(f"timed out waiting for gateway port-forward on {host}:{port}")


def grpc_call(
    grpcurl: str,
    host: str,
    proto_root: Path,
    method: str,
    request: dict[str, Any],
    timeout: float,
) -> dict[str, Any]:
    command = [
        grpcurl,
        "-plaintext",
        "-import-path",
        str(proto_root),
        "-proto",
        "quota.proto",
        "-max-time",
        str(max(1, int(timeout))),
        "-d",
        "@",
        host,
        method,
    ]
    result = subprocess.run(
        command,
        input=json.dumps(request),
        check=False,
        capture_output=True,
        text=True,
        timeout=max(timeout + 5, 10),
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"grpc call failed ({result.returncode}) method={method}: {detail}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        fail(f"grpc call returned invalid JSON method={method}: {error}")


def deadline(seconds_from_now: int) -> str:
    value = datetime.now(timezone.utc) + timedelta(seconds=seconds_from_now)
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def metadata(event_id: str) -> dict[str, str]:
    return {
        "event_id": event_id,
        "traceparent": "00-00000000000000000000000000000001-0000000000000001-01",
    }

def acquire_request(
    tenant: str,
    project: str,
    idempotency_key: str,
    estimated_tokens: int,
    *,
    event_id: str,
    deadline_value: str,
) -> dict[str, Any]:
    return {
        "metadata": metadata(event_id),
        "protocol_version": {"major": 2, "minor": 3},
        "tenant_id": tenant,
        "project_id": project,
        "idempotency_key": idempotency_key,
        "estimated_tokens": estimated_tokens,
        "max_concurrent": 1,
        "deadline": deadline_value,
    }


def release_request(
    tenant: str,
    project: str,
    reservation_id: str,
    idempotency_key: str,
    *,
    event_id: str,
    deadline_value: str,
) -> dict[str, Any]:
    return {
        "metadata": metadata(event_id),
        "protocol_version": {"major": 2, "minor": 3},
        "tenant_id": tenant,
        "project_id": project,
        "reservation_id": reservation_id,
        "idempotency_key": idempotency_key,
        "deadline": deadline_value,
    }
