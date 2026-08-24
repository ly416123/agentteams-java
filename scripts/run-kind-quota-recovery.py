#!/usr/bin/env python3
"""Verify project-quota retry, release, timeout, and scope isolation in Kind.

The test talks to the Gateway QuotaService over gRPC and reads the resulting
durable counters back through the Control Plane quota API.  It intentionally
uses unique projects per invocation, so daily counters from an earlier run do
not affect a later run.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import subprocess
import sys
import uuid

from kind_test_support import (HttpResult, KindTestError, PortForward, api_request,
                               grpcurl_call, query_url, run, wait_until)


ROOT = Path(__file__).resolve().parents[1]
QUOTA_METHOD = "io.agentteams.contracts.v1.QuotaService/{}"


@dataclass(frozen=True)
class Scope:
    tenant: str
    project: str


@dataclass(frozen=True)
class Snapshot:
    current_concurrent: int
    daily_calls: int
    daily_tokens: int


def require_status(result: HttpResult, expected: int, operation: str) -> dict:
    if result.status != expected or not isinstance(result.payload, dict):
        raise KindTestError(f"{operation} expected HTTP {expected}, got {result.status}: {result.payload!r}")
    return result.payload


def configure_quota(base_url: str, scope: Scope, args: argparse.Namespace) -> None:
    body = {
        "tenantId": scope.tenant,
        "projectId": scope.project,
        "maxConcurrentCalls": args.max_concurrent,
        "maxDailyCalls": args.max_daily_calls,
        "maxDailyTokens": args.max_daily_tokens,
    }
    require_status(api_request(f"{base_url}/api/v1/usage/quota", "PUT", body), 200,
                   f"configure quota {scope.tenant}/{scope.project}")


def snapshot(base_url: str, scope: Scope) -> Snapshot:
    payload = require_status(api_request(query_url(
        base_url, "/api/v1/usage/quota",
        {"tenantId": scope.tenant, "projectId": scope.project})), 200,
        f"read quota {scope.tenant}/{scope.project}")
    try:
        return Snapshot(int(payload["currentConcurrentCalls"]),
                        int(payload["dailyCalls"]), int(payload["dailyTokens"]))
    except (KeyError, TypeError, ValueError) as error:
        raise KindTestError(f"quota response has no stable counters: {payload!r}") from error


def future_deadline(seconds: int = 30) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def expired_deadline() -> str:
    return "2000-01-01T00:00:00Z"


def request_metadata() -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    }


def acquire_request(scope: Scope, key: str, estimated_tokens: int, deadline: str) -> dict:
    return {
        "metadata": request_metadata(),
        "protocolVersion": {"major": 2, "minor": 3},
        "tenantId": scope.tenant,
        "projectId": scope.project,
        "idempotencyKey": key,
        "estimatedTokens": estimated_tokens,
        "maxConcurrent": 1,
        "deadline": deadline,
    }


def release_request(scope: Scope, reservation_id: str, key: str, deadline: str) -> dict:
    return {
        "metadata": request_metadata(),
        "protocolVersion": {"major": 2, "minor": 3},
        "tenantId": scope.tenant,
        "projectId": scope.project,
        "reservationId": reservation_id,
        "idempotencyKey": key,
        "deadline": deadline,
    }


def grpc_call(args: argparse.Namespace, method: str, request: dict) -> dict:
    return grpcurl_call(
        f"127.0.0.1:{args.gateway_local_port}",
        ROOT / "contracts" / "src" / "main" / "proto",
        "quota.proto",
        QUOTA_METHOD.format(method),
        request,
        grpcurl=args.grpcurl,
        tls=args.gateway_tls,
        tls_ca=args.gateway_ca,
        tls_cert=args.gateway_client_cert,
        tls_key=args.gateway_client_key,
        tls_server_name=args.gateway_server_name,
        timeout=args.timeout,
    )


def wait_for_gateway_quota_service(args: argparse.Namespace) -> None:
    """Wait until the Gateway handler, not just its TCP port, is serving gRPC.

    A service port-forward can accept TCP connections while the Gateway is
    still starting its Spring/gRPC handlers. An empty Acquire request is
    rejected before any quota state is touched, so it is a safe readiness
    probe for the same RPC used by the acceptance below.
    """
    def ready() -> bool:
        try:
            response = grpc_call(args, "Acquire", {})
            return enum_value(response, "protocolError") == "QUOTA_PROTOCOL_ERROR_INVALID_ARGUMENT"
        except (KindTestError, OSError, subprocess.TimeoutExpired):
            return False

    wait_until("Gateway quota gRPC service", ready, timeout=args.timeout, interval=1.0)


def restart_control_plane_forward(args: argparse.Namespace, current: PortForward) -> PortForward:
    """Restart the Control Plane and reconnect the HTTP port-forward to a new Pod."""
    run("rollout", "restart", f"deployment/{args.control_plane_service}",
        namespace=args.namespace)
    run("rollout", "status", f"deployment/{args.control_plane_service}",
        f"--timeout={int(args.restart_timeout)}s", namespace=args.namespace,
        timeout=args.restart_timeout + 15)
    current.close()
    replacement = PortForward(args.namespace, args.control_plane_service,
                               args.control_plane_port, 8080)
    return replacement.start(timeout=args.restart_timeout)


def wait_for_idempotent_acquire(args: argparse.Namespace, scope: Scope,
                                idempotency_key: str, reservation_id: str) -> dict:
    """Tolerate a short Gateway-to-Control-Plane window after a restart.

    The request is intentionally retried with the same idempotency key. A
    transient internal response therefore cannot increment durable counters;
    the success condition remains the original reservation id.
    """
    last: dict = {}

    def ready() -> bool:
        response = grpc_call(args, "Acquire", acquire_request(
            scope, idempotency_key, 10, future_deadline()))
        last["response"] = response
        return accepted(response) and str(response.get("reservationId")) == reservation_id

    wait_until("idempotent acquire after Control Plane restart", ready,
               timeout=args.timeout, interval=1.0)
    return last["response"]


def accepted(response: dict) -> bool:
    return bool(response.get("accepted", False))


def enum_value(response: dict, field: str) -> str:
    value = response.get(field, "")
    return str(value).upper()


def assert_protocol(response: dict, expected: str, operation: str) -> None:
    actual = enum_value(response, "protocolError")
    if actual != expected:
        raise KindTestError(f"{operation} expected protocolError={expected}, got {actual or '<empty>'}: {response!r}")


def assert_rejection(response: dict, expected: str, operation: str) -> None:
    if accepted(response):
        raise KindTestError(f"{operation} unexpectedly accepted: {response!r}")
    actual = enum_value(response, "rejectionDimension")
    if not actual.endswith(expected):
        raise KindTestError(f"{operation} expected rejectionDimension={expected}, got {actual}: {response!r}")


def assert_snapshot(actual: Snapshot, baseline: Snapshot, current: int, calls: int,
                    tokens: int, operation: str) -> None:
    delta = Snapshot(actual.current_concurrent - baseline.current_concurrent,
                     actual.daily_calls - baseline.daily_calls,
                     actual.daily_tokens - baseline.daily_tokens)
    expected = Snapshot(current, calls, tokens)
    if delta != expected:
        raise KindTestError(f"{operation} counter delta expected {expected}, got {delta}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("KIND_NAMESPACE", "agentteams"))
    parser.add_argument("--control-plane-service",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_SERVICE",
                                               "agentteams-agentteams-java-control-plane"))
    parser.add_argument("--gateway-service",
                        default=os.environ.get("AGENTTEAMS_GATEWAY_SERVICE",
                                               "agentteams-agentteams-java-gateway"))
    parser.add_argument("--control-plane-port", type=int, default=18086)
    parser.add_argument("--gateway-local-port", type=int, default=19090)
    parser.add_argument("--gateway-port", type=int, default=9090)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_API_TENANT", "tenant-a"))
    parser.add_argument("--isolation-tenant", default=os.environ.get("AGENTTEAMS_ISOLATION_TENANT", "tenant-b"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--grpcurl", default=os.environ.get("GRPCURL_BIN"))
    parser.add_argument("--gateway-tls", action="store_true")
    mtls_dir = Path(os.environ.get("AGENTTEAMS_MTLS_DIR", str(ROOT / ".local" / "kind-mtls")))
    parser.add_argument("--gateway-ca", default=os.environ.get(
        "AGENTTEAMS_GATEWAY_TLS_CA_CERT",
        str(mtls_dir / "ca.crt") if (mtls_dir / "ca.crt").is_file() else ""))
    parser.add_argument("--gateway-client-cert", default=os.environ.get(
        "AGENTTEAMS_GATEWAY_TLS_CLIENT_CERT",
        str(mtls_dir / "worker.crt") if (mtls_dir / "worker.crt").is_file() else ""))
    parser.add_argument("--gateway-client-key", default=os.environ.get(
        "AGENTTEAMS_GATEWAY_TLS_CLIENT_KEY",
        str(mtls_dir / "worker.key") if (mtls_dir / "worker.key").is_file() else ""))
    parser.add_argument("--gateway-server-name", default=os.environ.get(
        "AGENTTEAMS_GATEWAY_TLS_SERVER_NAME",
        "agentteams-agentteams-java-gateway.agentteams.svc.cluster.local"))
    parser.add_argument("--max-concurrent", type=int, default=1)
    parser.add_argument("--max-daily-calls", type=int, default=20)
    parser.add_argument("--max-daily-tokens", type=int, default=1000)
    parser.add_argument("--restart-control-plane", action=argparse.BooleanOptionalAction, default=False,
                        help="restart Control Plane after the first acquire before retrying it")
    parser.add_argument("--restart-timeout", type=float, default=120.0,
                        help="maximum seconds for Control Plane rollout and port-forward recovery")
    args = parser.parse_args()
    if args.max_concurrent != 1 or args.max_daily_calls < 8 or args.max_daily_tokens < 40:
        parser.error("quota limits must be max-concurrent=1, daily-calls>=8 and daily-tokens>=40")

    run_id = uuid.uuid4().hex[:10]
    project_root = f"{args.project}-{run_id}"
    scope_a = Scope(args.tenant, f"{project_root}-a")
    scope_b = Scope(args.tenant, f"{project_root}-b")
    scope_tenant_b = Scope(args.isolation_tenant, f"{project_root}-a")
    reservations: list[tuple[Scope, str]] = []
    released: set[str] = set()
    control_plane_forward = PortForward(args.namespace, args.control_plane_service,
                                        args.control_plane_port, 8080)
    gateway_forward = PortForward(args.namespace, args.gateway_service,
                                  args.gateway_local_port, args.gateway_port)
    base_url = f"http://127.0.0.1:{args.control_plane_port}"
    try:
        control_plane_forward.start(timeout=args.timeout)
        gateway_forward.start(timeout=args.timeout)
        wait_until("Control Plane API", lambda: api_request(f"{base_url}/actuator/health").status == 200,
                   timeout=args.timeout)
        wait_for_gateway_quota_service(args)

        for scope in (scope_a, scope_b, scope_tenant_b):
            configure_quota(base_url, scope, args)
        baseline_a = snapshot(base_url, scope_a)
        baseline_b = snapshot(base_url, scope_b)
        baseline_tenant_b = snapshot(base_url, scope_tenant_b)

        acquire_key = f"kind-quota-acquire-{run_id}"
        first = grpc_call(args, "Acquire", acquire_request(scope_a, acquire_key, 10, future_deadline()))
        if not accepted(first) or not first.get("reservationId"):
            raise KindTestError(f"first acquire was not accepted: {first!r}")
        reservation_a = str(first["reservationId"])
        reservations.append((scope_a, reservation_a))

        if args.restart_control_plane:
            control_plane_forward = restart_control_plane_forward(args, control_plane_forward)
            wait_until("Control Plane API after restart",
                       lambda: api_request(f"{base_url}/actuator/health").status == 200,
                       timeout=args.timeout)

        retry = wait_for_idempotent_acquire(args, scope_a, acquire_key, reservation_a)
        after_acquire_retry = snapshot(base_url, scope_a)
        assert_snapshot(after_acquire_retry, baseline_a, 1, 1, 10, "acquire retry")
        print("KIND_QUOTA_ACQUIRE_IDEMPOTENCY_OK accepted=2 same_reservation=true current_delta=1 daily_calls_delta=1 daily_tokens_delta=10")

        rejected = grpc_call(args, "Acquire", acquire_request(scope_a, f"kind-quota-reject-{run_id}", 10, future_deadline()))
        assert_rejection(rejected, "CONCURRENT_CALLS", "concurrent quota acquire")
        assert_snapshot(snapshot(base_url, scope_a), baseline_a, 1, 1, 10, "quota rejection")
        print("KIND_QUOTA_REJECTION_OK dimension=CONCURRENT_CALLS counters_unchanged=true")

        timed_out = grpc_call(args, "Acquire", acquire_request(scope_a, f"kind-quota-timeout-{run_id}", 10, expired_deadline()))
        assert_protocol(timed_out, "QUOTA_PROTOCOL_ERROR_DEADLINE_EXCEEDED", "expired acquire")
        assert_snapshot(snapshot(base_url, scope_a), baseline_a, 1, 1, 10, "expired acquire")
        print("KIND_QUOTA_TIMEOUT_OK operation=ACQUIRE code=DEADLINE_EXCEEDED counters_unchanged=true")

        acquired_b = grpc_call(args, "Acquire", acquire_request(scope_b, f"kind-quota-project-{run_id}", 10, future_deadline()))
        if not accepted(acquired_b) or not acquired_b.get("reservationId"):
            raise KindTestError(f"same-tenant different-project acquire was rejected: {acquired_b!r}")
        reservation_b = str(acquired_b["reservationId"])
        reservations.append((scope_b, reservation_b))
        acquired_tenant_b = grpc_call(args, "Acquire", acquire_request(
            scope_tenant_b, f"kind-quota-tenant-{run_id}", 10, future_deadline()))
        if not accepted(acquired_tenant_b) or not acquired_tenant_b.get("reservationId"):
            raise KindTestError(f"different-tenant acquire was rejected: {acquired_tenant_b!r}")
        reservation_tenant_b = str(acquired_tenant_b["reservationId"])
        reservations.append((scope_tenant_b, reservation_tenant_b))

        wrong_project_release = grpc_call(args, "Release", release_request(
            scope_b, reservation_a, f"kind-quota-wrong-project-{run_id}", future_deadline()))
        assert_protocol(wrong_project_release, "QUOTA_PROTOCOL_ERROR_RESERVATION_NOT_FOUND", "cross-project release")
        wrong_tenant_release = grpc_call(args, "Release", release_request(
            scope_tenant_b, reservation_a, f"kind-quota-wrong-tenant-{run_id}", future_deadline()))
        assert_protocol(wrong_tenant_release, "QUOTA_PROTOCOL_ERROR_RESERVATION_NOT_FOUND", "cross-tenant release")
        assert_snapshot(snapshot(base_url, scope_a), baseline_a, 1, 1, 10, "scope isolation")
        assert_snapshot(snapshot(base_url, scope_b), baseline_b, 1, 1, 10, "project isolation")
        assert_snapshot(snapshot(base_url, scope_tenant_b), baseline_tenant_b, 1, 1, 10, "tenant isolation")
        print("KIND_QUOTA_SCOPE_ISOLATION_OK project_isolated=true tenant_isolated=true cross_release_rejected=true")

        expired_release = grpc_call(args, "Release", release_request(
            scope_b, reservation_b, f"kind-quota-expired-release-{run_id}", expired_deadline()))
        assert_protocol(expired_release, "QUOTA_PROTOCOL_ERROR_DEADLINE_EXCEEDED", "expired release")
        assert_snapshot(snapshot(base_url, scope_b), baseline_b, 1, 1, 10, "expired release")
        print("KIND_QUOTA_TIMEOUT_OK operation=RELEASE code=DEADLINE_EXCEEDED counters_unchanged=true")

        release_key = f"kind-quota-release-{run_id}"
        release_one = grpc_call(args, "Release", release_request(scope_a, reservation_a, release_key, future_deadline()))
        release_two = grpc_call(args, "Release", release_request(scope_a, reservation_a, release_key, future_deadline()))
        if not accepted(release_one) or not accepted(release_two):
            raise KindTestError(f"release retry was not accepted twice: {release_one!r}, {release_two!r}")
        released.add(reservation_a)
        after_release_retry = snapshot(base_url, scope_a)
        assert_snapshot(after_release_retry, baseline_a, 0, 1, 10, "release retry")
        release_new_key = grpc_call(args, "Release", release_request(
            scope_a, reservation_a, f"kind-quota-release-new-key-{run_id}", future_deadline()))
        if not accepted(release_new_key):
            raise KindTestError(f"release retry with a new transport key was not idempotent: {release_new_key!r}")
        assert_snapshot(snapshot(base_url, scope_a), baseline_a, 0, 1, 10, "release retry new key")
        print("KIND_QUOTA_RELEASE_IDEMPOTENCY_OK accepted=2 current_delta=0")
        print("KIND_QUOTA_RELEASE_RETRY_OK new_key_accepted=true current_delta=0")

        print("KIND_QUOTA_OK acquire_retry=deduplicated release_retry=deduplicated timeout=stable rejection=stable scope=isolated")
        return 0
    finally:
        for scope, reservation_id in reservations:
            if reservation_id in released:
                continue
            try:
                response = grpc_call(args, "Release", release_request(
                    scope, reservation_id, f"kind-quota-cleanup-{reservation_id}", future_deadline()))
                if accepted(response):
                    released.add(reservation_id)
            except (KindTestError, OSError):
                pass
        gateway_forward.close()
        control_plane_forward.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KindTestError, OSError, ValueError) as error:
        print(f"KIND_QUOTA_FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
