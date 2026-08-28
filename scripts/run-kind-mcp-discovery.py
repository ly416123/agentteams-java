#!/usr/bin/env python3
"""Verify revision-fenced, cross-instance MCP discovery aggregation in Kind."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


class SmokeFailure(RuntimeError):
    pass


def api_request(base_url: str, path: str, method: str = "GET", body: dict | None = None) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if method in {"POST", "PUT", "PATCH", "DELETE"}:
        headers["Idempotency-Key"] = f"kind-mcp-discovery-{uuid.uuid4()}"
    request = urllib.request.Request(base_url.rstrip("/") + path, data=payload,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            decoded = json.loads(response.read().decode("utf-8"))
            if not isinstance(decoded, dict):
                raise SmokeFailure(f"unexpected response type for {method} {path}")
            return decoded
    except urllib.error.HTTPError as error:
        raise SmokeFailure(f"HTTP {error.code} {method} {path}") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise SmokeFailure(f"request failed {method} {path}") from error


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def postgres(namespace: str, pod: str, sql: str) -> None:
    command = ["kubectl", "-n", namespace, "exec", pod, "--",
               "psql", "-U", "agentteams", "-d", "agentteams", "-v", "ON_ERROR_STOP=1", "-c", sql]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SmokeFailure("postgres discovery fixture failed")


def aggregate(base_url: str, server_id: str) -> dict:
    return api_request(base_url, f"/api/v1/mcp-servers/{server_id}/discovery")


def assert_available(result: dict, server_id: str, revision: int) -> None:
    if (result.get("serverId") != server_id
            or result.get("serverRevision") != revision
            or result.get("status") != "AVAILABLE"
            or result.get("healthyInstances") != 2
            or result.get("freshInstances") != 2
            or result.get("toolsDigest") != "sha256:" + "a" * 64
            or result.get("failureCategories") != []):
        raise SmokeFailure("MCP discovery aggregate did not report two healthy current instances")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "").strip())
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--postgres-pod", default="postgresql-0")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    if not args.base_url:
        parser.error("--base-url or AGENTTEAMS_CONTROL_PLANE_URL is required")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    server_id = ""
    try:
        created = api_request(args.base_url, "/api/v1/mcp-servers", "POST", {
            "name": f"kind-discovery-{uuid.uuid4()}",
            "transport": "SSE",
            "endpoint": "https://mcp.example.test/sse",
            "enabled": False,
            "healthStatus": "UNKNOWN",
        })
        server_id = str(created.get("id", ""))
        if not server_id or created.get("version") != 0:
            raise SmokeFailure("MCP test server creation returned an invalid revision")

        updated = api_request(args.base_url, f"/api/v1/mcp-servers/{server_id}", "PUT", {
            "name": created["name"],
            "transport": "SSE",
            "endpoint": "https://mcp.example.test/sse",
            "enabled": False,
            "healthStatus": "UNKNOWN",
        })
        revision = int(updated.get("version", -1))
        if revision != 1:
            raise SmokeFailure("MCP test server revision did not advance")

        digest = "sha256:" + "a" * 64
        postgres(args.namespace, args.postgres_pod, f"""
            INSERT INTO mcp_discovery_snapshots
                (server_id, server_revision, instance_id, tools_digest, healthy,
                 failure_category, observed_at, expires_at)
            VALUES
                ({sql_literal(server_id)}::uuid, {revision}, 'control-plane-a', {sql_literal(digest)}, true,
                 'SUCCESS', now() - interval '1 second', now() + interval '2 minutes'),
                ({sql_literal(server_id)}::uuid, {revision}, 'control-plane-b', {sql_literal(digest)}, true,
                 'SUCCESS', now() - interval '1 second', now() + interval '2 minutes'),
                ({sql_literal(server_id)}::uuid, {revision - 1}, 'old-revision', {sql_literal('sha256:' + 'b' * 64)}, true,
                 'SUCCESS', now() - interval '1 second', now() + interval '2 minutes')
            ON CONFLICT (server_id, server_revision, instance_id) DO UPDATE SET
                tools_digest = EXCLUDED.tools_digest, healthy = EXCLUDED.healthy,
                failure_category = EXCLUDED.failure_category, observed_at = EXCLUDED.observed_at,
                expires_at = EXCLUDED.expires_at
        """)

        deadline = time.monotonic() + args.timeout
        result = {}
        while time.monotonic() < deadline:
            result = aggregate(args.base_url, server_id)
            if result.get("status") == "AVAILABLE":
                break
            time.sleep(1)
        assert_available(result, server_id, revision)

        postgres(args.namespace, args.postgres_pod, f"""
            UPDATE mcp_discovery_snapshots
               SET observed_at = now() - interval '10 minutes', expires_at = now() - interval '5 minutes'
             WHERE server_id = {sql_literal(server_id)}::uuid AND server_revision = {revision}
        """)
        expired = aggregate(args.base_url, server_id)
        if (expired.get("status") != "UNKNOWN" or expired.get("freshInstances") != 0
                or expired.get("healthyInstances") != 0):
            raise SmokeFailure("expired MCP observations were not reduced to UNKNOWN")

        print(f"KIND_MCP_DISCOVERY_OK server={server_id} revision={revision} instances=2 expired=UNKNOWN")
        return 0
    except Exception as error:
        print(f"KIND_MCP_DISCOVERY_FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if server_id:
            try:
                api_request(args.base_url, f"/api/v1/mcp-servers/{server_id}", "DELETE")
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
