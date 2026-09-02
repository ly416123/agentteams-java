#!/usr/bin/env python3
"""Verify OIDC memory visibility across subjects, teams, projects and tenants in Kind.

This is a development-only acceptance script. It creates metadata-only memory
fixtures in the local PostgreSQL instance, calls the authenticated management
API with two non-production OIDC users, and removes exactly those fixtures in
the finally block. No memory content or credential material is printed.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from kind_test_support import KindTestError, PortForward, api_request, run, wait_until


ORG_ID = "00000000-0000-0000-0000-000000000060"
TENANT_A_ID = "00000000-0000-0000-0000-000000000061"
TENANT_B_ID = "00000000-0000-0000-0000-000000000062"


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run("exec", postgres_pod, "--", "psql", "-U", "agentteams", "-d", "agentteams",
               "-v", "ON_ERROR_STOP=1", "-At", "-c", statement, namespace=namespace)


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise KindTestError(f"{name} is required for real non-production OIDC acceptance")
    return value


def token(keycloak_url: str, username: str, password: str) -> str:
    payload = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "agentteams-api",
        "username": username,
        "password": password,
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/agentteams/protocol/openid-connect/token",
        data=payload,
        headers={"Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise KindTestError("non-production OIDC token request failed") from error
    value = result.get("access_token") if isinstance(result, dict) else None
    if not isinstance(value, str) or not value:
        raise KindTestError("OIDC token response did not contain an access token")
    return value


def token_subject(value: str) -> str:
    try:
        payload = value.split(".")[1]
        decoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        subject = json.loads(decoded).get("sub")
    except (IndexError, ValueError, json.JSONDecodeError, UnicodeDecodeError) as error:
        raise KindTestError("OIDC token subject could not be decoded") from error
    if not isinstance(subject, str) or not subject:
        raise KindTestError("OIDC token did not contain a subject")
    return subject


def authorized_get(base_url: str, bearer: str, path: str):
    previous = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN")
    os.environ["AGENTTEAMS_API_BEARER_TOKEN"] = bearer
    try:
        return api_request(f"{base_url.rstrip('/')}{path}")
    finally:
        if previous is None:
            os.environ.pop("AGENTTEAMS_API_BEARER_TOKEN", None)
        else:
            os.environ["AGENTTEAMS_API_BEARER_TOKEN"] = previous


def provision_scope(namespace: str, postgres_pod: str, alice_subject: str, admin_subject: str) -> None:
    for subject in (alice_subject, admin_subject):
        if not all(character.isalnum() or character in "._:@/-" for character in subject):
            raise KindTestError("OIDC subject contains unsupported characters")
    statement = f"""
        INSERT INTO organizations(id, external_key, display_name, status, created_at, updated_at, version)
        VALUES ({sql_literal(ORG_ID)}::uuid, 'kind-memory-org', 'Kind memory acceptance', 'ACTIVE', now(), now(), 0)
        ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', updated_at = now();
        INSERT INTO tenants(id, organization_id, external_key, display_name, status, created_at, updated_at, version)
        VALUES ({sql_literal(TENANT_A_ID)}::uuid, {sql_literal(ORG_ID)}::uuid, 'tenant-a', 'Kind Tenant A', 'ACTIVE', now(), now(), 0),
               ({sql_literal(TENANT_B_ID)}::uuid, {sql_literal(ORG_ID)}::uuid, 'tenant-b', 'Kind Tenant B', 'ACTIVE', now(), now(), 0)
        ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', updated_at = now();
        INSERT INTO legacy_tenant_mappings(legacy_tenant_key, organization_id, tenant_id, created_at)
        VALUES ('tenant-a', {sql_literal(ORG_ID)}::uuid, {sql_literal(TENANT_A_ID)}::uuid, now()),
               ('tenant-b', {sql_literal(ORG_ID)}::uuid, {sql_literal(TENANT_B_ID)}::uuid, now())
        ON CONFLICT (legacy_tenant_key) DO UPDATE SET organization_id = EXCLUDED.organization_id,
            tenant_id = EXCLUDED.tenant_id;
        INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
        VALUES ({sql_literal(ORG_ID)}::uuid, {sql_literal(alice_subject)}, 'MEMBER', now(), now()),
               ({sql_literal(ORG_ID)}::uuid, {sql_literal(admin_subject)}, 'ADMIN', now(), now())
        ON CONFLICT (organization_id, subject) DO UPDATE SET role = EXCLUDED.role, updated_at = now();
        INSERT INTO tenant_memberships(organization_id, tenant_id, subject, role, created_at, updated_at)
        VALUES ({sql_literal(ORG_ID)}::uuid, {sql_literal(TENANT_A_ID)}::uuid, {sql_literal(alice_subject)}, 'MEMBER', now(), now()),
               ({sql_literal(ORG_ID)}::uuid, {sql_literal(TENANT_A_ID)}::uuid, {sql_literal(admin_subject)}, 'ADMIN', now(), now())
        ON CONFLICT (tenant_id, subject) DO UPDATE SET role = EXCLUDED.role, updated_at = now();
        INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
        SELECT 'tenant-a', id, {sql_literal(alice_subject)}, 'DEVELOPER', 'ACTIVE', now(), now(), 0
          FROM projects WHERE tenant_id = 'tenant-a' AND name = 'project-a'
        ON CONFLICT (tenant_id, project_id, subject) DO UPDATE SET status = 'ACTIVE', updated_at = now();
        INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
        SELECT 'tenant-a', id, {sql_literal(admin_subject)}, 'ADMIN', 'ACTIVE', now(), now(), 0
          FROM projects WHERE tenant_id = 'tenant-a' AND name = 'project-a'
        ON CONFLICT (tenant_id, project_id, subject) DO UPDATE SET status = 'ACTIVE', updated_at = now();
    """
    sql(namespace, postgres_pod, statement)


def insert_fixtures(namespace: str, postgres_pod: str, alice_subject: str, admin_subject: str) -> list[str]:
    identifiers = [str(uuid.uuid4()) for _ in range(7)]
    rows = [
        (identifiers[0], ORG_ID, TENANT_A_ID, "project-a", "team-a", alice_subject, "USER_PRIVATE", "alice-private"),
        (identifiers[1], ORG_ID, TENANT_A_ID, "project-a", "team-a", admin_subject, "USER_PRIVATE", "admin-private"),
        (identifiers[2], ORG_ID, TENANT_A_ID, "project-a", None, None, "PROJECT_SHARED", "project-shared"),
        (identifiers[3], ORG_ID, TENANT_A_ID, None, "team-a", None, "TEAM_SHARED", "team-shared"),
        (identifiers[4], ORG_ID, TENANT_A_ID, None, None, None, "ORGANIZATION_SHARED", "organization-shared"),
        (identifiers[5], ORG_ID, TENANT_A_ID, "project-b", "team-a", None, "PROJECT_SHARED", "cross-project"),
        (identifiers[6], ORG_ID, TENANT_B_ID, "project-a", "team-a", None, "PROJECT_SHARED", "cross-tenant"),
    ]
    values = []
    for memory_id, organization, tenant, project, team, subject, scope, label in rows:
        values.append("(" + ", ".join([
            sql_literal(memory_id) + "::uuid", sql_literal(organization), sql_literal(tenant),
            "NULL" if project is None else sql_literal(project),
            "NULL" if team is None else sql_literal(team), "NULL", "NULL" if subject is None else sql_literal(subject),
            sql_literal(scope), sql_literal(f"secret://kind-memory/{label}"), sql_literal(label),
            "'NORMAL'", "'CONFIRMED'", "'kind-memory-scope'", "86400", "now() + interval '1 day'",
            "now()", "now()", "0", "'ACTIVE'",
        ]) + ")")
    sql(namespace, postgres_pod, """
        INSERT INTO memories
            (id, organization_id, tenant_id, project_id, team_id, task_id, subject_id, scope, content_ref,
             summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at,
             version, governance_status)
        VALUES
    """ + ",\n".join(values) + "\n        ON CONFLICT (id) DO NOTHING")
    return identifiers


def assert_visible(result, expected: set[str], forbidden: set[str], label: str) -> None:
    if result.status != 200 or not isinstance(result.payload, list):
        payload_type = type(result.payload).__name__
        if isinstance(result.payload, dict):
            detail = str(result.payload.get("code", "")) + ":" + str(result.payload.get("message", ""))
        else:
            detail = ""
        raise KindTestError(f"{label} memory API returned status={result.status} type={payload_type} detail={detail[:160]}")
    returned = {item.get("id") for item in result.payload if isinstance(item, dict)}
    if not expected.issubset(returned):
        raise KindTestError(f"{label} memory list omitted expected scoped records")
    leaked = returned & forbidden
    if leaked:
        raise KindTestError(f"{label} memory list leaked records across a scope boundary")
    for item in result.payload:
        if isinstance(item, dict) and ("contentRef" in item or "summary" in item):
            raise KindTestError(f"{label} memory API exposed content instead of metadata")
        if isinstance(item, dict) and "policy" in item and "subjectId" not in item["policy"]:
            raise KindTestError(f"{label} memory metadata omitted the scope subjectId field")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--postgres-pod", default=os.environ.get("AGENTTEAMS_POSTGRES_POD", "postgresql-0"))
    parser.add_argument("--control-plane-service", default="agentteams-agentteams-java-control-plane")
    parser.add_argument("--control-plane-port", type=int, default=18085)
    parser.add_argument("--keycloak-port", type=int, default=18082)
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    alice_username = required_env("AGENTTEAMS_E2E_USERNAME")
    alice_password = required_env("AGENTTEAMS_E2E_PASSWORD")
    admin_username = required_env("AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME")
    admin_password = required_env("AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD")
    keycloak_url = f"http://127.0.0.1:{args.keycloak_port}"
    memory_ids: list[str] = []
    keycloak_forward = None
    try:
        try:
            with urllib.request.urlopen(f"{keycloak_url}/realms/agentteams/.well-known/openid-configuration", timeout=2):
                pass
        except (urllib.error.URLError, TimeoutError):
            keycloak_forward = PortForward(args.namespace, "keycloak", args.keycloak_port, 8080).start()
        with PortForward(args.namespace, args.control_plane_service, args.control_plane_port, 8080):
            base_url = f"http://127.0.0.1:{args.control_plane_port}"
            wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health").status == 200,
                       timeout=args.timeout)
            alice_token = token(keycloak_url, alice_username, alice_password)
            admin_token = token(keycloak_url, admin_username, admin_password)
            alice_subject = token_subject(alice_token)
            admin_subject = token_subject(admin_token)
            provision_scope(args.namespace, args.postgres_pod, alice_subject, admin_subject)
            memory_ids = insert_fixtures(args.namespace, args.postgres_pod, alice_subject, admin_subject)

            alice_result = authorized_get(base_url, alice_token, "/api/v1/memory")
            admin_result = authorized_get(base_url, admin_token, "/api/v1/memory")
            alice_expected = {memory_ids[index] for index in (0, 2, 3, 4)}
            admin_expected = {memory_ids[index] for index in (1, 2, 3, 4)}
            forbidden = {memory_ids[index] for index in (5, 6)}
            assert_visible(alice_result, alice_expected, forbidden | {memory_ids[1]}, "Alice")
            assert_visible(admin_result, admin_expected, forbidden | {memory_ids[0]}, "Quota Admin")

        print("KIND_MEMORY_SCOPE_OK subjects=2 shared=3 private=2 cross_scope=2 metadata_only=true")
        return 0
    except Exception as error:
        print(f"KIND_MEMORY_SCOPE_FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if memory_ids:
            try:
                sql(args.namespace, args.postgres_pod,
                    "DELETE FROM memories WHERE id IN (" + ",".join(sql_literal(value) + "::uuid" for value in memory_ids) + ")")
            except Exception:
                pass
        if keycloak_forward is not None:
            keycloak_forward.close()


if __name__ == "__main__":
    raise SystemExit(main())
