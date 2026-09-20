#!/usr/bin/env python3
"""Verify the real Worker resourceBindings ACK and failure path in Kind.

The script uses the existing ConfigSnapshot -> ConfigChanged -> ConfigApplied
pipeline. It deliberately does not call a private Worker endpoint: the result
is read back from the durable binding status exposed by the Control Plane.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class SmokeFailure(RuntimeError):
    pass


def fail(message: str) -> None:
    raise SmokeFailure(message)


def api_request(base_url: str, path: str, method: str = "GET",
                body: dict | None = None, idempotency_key: str | None = None) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    bearer = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", "").strip()
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(base_url.rstrip("/") + path, data=payload,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            decoded = json.loads(response.read().decode("utf-8"))
            if not isinstance(decoded, dict):
                fail(f"expected JSON object from {method} {path}, got {decoded!r}")
            return decoded
    except urllib.error.HTTPError as error:
        response_body = error.read().decode("utf-8", errors="replace")[:4096]
        raise SmokeFailure(f"HTTP {error.code} {method} {path}: {response_body}") from error
    except (urllib.error.URLError, http.client.RemoteDisconnected, ConnectionResetError) as error:
        raise SmokeFailure(f"request failed {method} {path}: {error}") from error


def create_snapshot(base_url: str, subject: str, manifest: dict) -> dict:
    snapshot = api_request(base_url, "/api/v1/config/snapshots", "POST", {
        "subject": subject,
        "manifest": manifest,
        "actor": "kind-resource-binding-ack",
    }, f"kind-resource-binding-snapshot-{uuid.uuid4()}")
    if not snapshot.get("id") or not snapshot.get("version"):
        fail(f"snapshot response is missing id/version: {snapshot}")
    return snapshot


def wait_for_phase(base_url: str, binding_id: str, snapshot_id: str, version: int,
                   expected_phase: str, timeout: float, interval: float) -> dict:
    deadline = time.monotonic() + timeout
    path = f"/api/v1/config/bindings/{binding_id}"
    last_status: dict | None = None
    last_error = ""
    while time.monotonic() < deadline:
        try:
            last_status = api_request(base_url, path)
            binding = last_status.get("binding") or {}
            snapshot = last_status.get("desiredSnapshot") or {}
            apply = last_status.get("apply") or {}
            if (binding.get("snapshotId") == snapshot_id
                    and snapshot.get("id") == snapshot_id
                    and snapshot.get("version") == version
                    and apply.get("phase") == expected_phase):
                return last_status
            if expected_phase != "FAILED" and apply.get("phase") == "FAILED":
                fail(f"config revision {version} failed unexpectedly: {apply}")
        except SmokeFailure as error:
            last_error = str(error)
        time.sleep(interval)
    fail(f"timed out waiting for revision {version} to become {expected_phase}; "
         f"last_status={last_status!r}; last_error={last_error!r}")


def require_same_binding(deployment: dict, binding_id: str, label: str) -> None:
    if deployment.get("bindingId") != binding_id:
        fail(f"{label} changed binding id: {deployment}")


def validated_base_url(value: str) -> str:
    base_url = value.strip().rstrip("/")
    parsed = urllib.parse.urlsplit(base_url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        fail("base URL must be an http(s) URL without credentials, query, or fragment")
    return base_url


def validated_agent_id(value: str) -> str:
    agent_id = value.strip()
    try:
        uuid.UUID(agent_id)
    except (ValueError, AttributeError) as error:
        raise SmokeFailure("agent-id must be a UUID") from error
    return agent_id


def worker_pod_names(namespace: str, deployment: str) -> list[str]:
    deployment_json = json.loads(run("get", "deployment", deployment, "-o", "json", namespace=namespace))
    selector = deployment_json.get("spec", {}).get("selector", {}).get("matchLabels", {})
    if not selector:
        fail(f"Worker Deployment {deployment} has no selector")
    selector_value = ",".join(f"{key}={value}" for key, value in selector.items())
    pods = json.loads(run("get", "pods", "-l", selector_value, "-o", "json", namespace=namespace))
    return [item["metadata"]["name"] for item in pods.get("items", [])
            if item.get("status", {}).get("phase") == "Running"
            and not item.get("metadata", {}).get("deletionTimestamp")]


def deployment_ready(namespace: str, deployment: str, expected_replicas: int) -> bool:
    deployment_json = json.loads(run("get", "deployment", deployment, "-o", "json", namespace=namespace))
    status = deployment_json.get("status", {})
    return int(status.get("readyReplicas", 0)) == expected_replicas


def pod_logs(namespace: str, pod: str) -> str:
    result = subprocess.run(
        ["kubectl", "-n", namespace, "logs", pod, "--tail=120"],
        check=False, capture_output=True, text=True)
    output = result.stdout if result.returncode == 0 else result.stderr
    return "\n".join(line[:512] for line in output.splitlines()[-120:])


def wait_for_replacement_worker(namespace: str, deployment: str, old_pod: str,
                                expected_replicas: int, timeout: float,
                                interval: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            replacements = [pod for pod in worker_pod_names(namespace, deployment)
                            if pod != old_pod]
            if replacements and deployment_ready(namespace, deployment, expected_replicas):
                return replacements[0]
        except (SmokeFailure, json.JSONDecodeError):
            pass
        time.sleep(interval)
    fail(f"timed out waiting for replacement Worker Pod after deleting {old_pod}")


def wait_for_bootstrap_log(namespace: str, pod: str, agent_id: str,
                           timeout: float, interval: float) -> None:
    deadline = time.monotonic() + timeout
    received_prefix = f"ConfigChanged received agent={agent_id}"
    completed_prefix = f"ConfigChanged completed agent={agent_id}"
    while time.monotonic() < deadline:
        logs = pod_logs(namespace, pod)
        received = any(received_prefix in line and "sequence=0" in line
                        for line in logs.splitlines())
        completed = any(completed_prefix in line and "sequence=0" in line
                        and "applied=true" in line for line in logs.splitlines())
        if received and completed:
            return
        time.sleep(interval)
    fail(f"replacement Worker Pod {pod} did not log successful sequence=0 config bootstrap")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "").strip(),
                        help="Control Plane URL (or AGENTTEAMS_CONTROL_PLANE_URL)")
    parser.add_argument("--agent-id", default=os.environ.get("AGENTTEAMS_AGENT_ID", "").strip(),
                        help="Worker UUID (or AGENTTEAMS_AGENT_ID)")
    parser.add_argument("--worker-deployment", default="qwenpaw-real",
                        help="Worker Deployment managed by the Kind acceptance fixture")
    parser.add_argument("--timeout", type=float, default=240.0,
                        help="maximum seconds to wait for each Worker apply")
    parser.add_argument("--poll-interval", type=float, default=2.0)
    args = parser.parse_args()
    if not args.base_url:
        parser.error("--base-url or AGENTTEAMS_CONTROL_PLANE_URL is required")
    if not args.agent_id:
        parser.error("--agent-id or AGENTTEAMS_AGENT_ID is required")
    if args.timeout <= 0 or args.poll_interval <= 0:
        parser.error("--timeout and --poll-interval must be positive")
    args.base_url = validated_base_url(args.base_url)
    args.agent_id = validated_agent_id(args.agent_id)

    subject = f"kind-resource-binding-ack-{uuid.uuid4()}"
    binding_id = ""
    statuses: dict[str, dict | None] = {"legacy": None, "valid": None, "invalid": None}
    snapshots: dict[str, dict | None] = {"legacy": None, "valid": None, "invalid": None}
    context = {"base_url": args.base_url, "agent_id": args.agent_id, "subject": subject,
               "worker_deployment": args.worker_deployment}
    old_pod = ""
    replacement_pod = ""
    try:
        # QwenPaw's native /api/models/active configuration requires a model
        # even for the compatibility (no resourceBindings) manifest. Keep the
        # model deterministic so the real Worker can apply every revision in
        # the smoke test without relying on a pre-existing active selection.
        # CI default: "model": "agentteams-kind-mock". Local real QwenPaw can
        # override it with AGENTTEAMS_QWENPAW_MODEL.
        smoke_model = os.environ.get("AGENTTEAMS_QWENPAW_MODEL", "agentteams-kind-mock").strip()
        if not smoke_model:
            fail("AGENTTEAMS_QWENPAW_MODEL must not be blank")
        legacy = {"kind": "KindResourceBindingAckSmoke", "revision": "legacy",
                  "model": smoke_model, "marker": str(uuid.uuid4())}
        valid = {
            "kind": "KindResourceBindingAckSmoke",
            "revision": "resource-bindings-valid",
            "model": smoke_model,
            "marker": str(uuid.uuid4()),
            # This acceptance fixture intentionally exercises bindings that
            # can be applied without an external service. MCP runtime
            # activation requires serverId/transport/endpoint metadata and a
            # reachable MCP server; that path is covered by Worker tests and
            # must not be faked as a generic ACK success here.
            "resourceBindings": [
                {"type": "MODEL", "reference": "kind-model", "revision": "model-1",
                 "digest": "sha256:" + "1" * 64},
                {"type": "SKILL", "reference": "kind-skill", "revision": "skill-1",
                 "digest": "sha256:" + "2" * 64},
            ],
        }
        invalid = {
            "kind": "KindResourceBindingAckSmoke",
            "revision": "resource-bindings-invalid",
            "model": smoke_model,
            "marker": str(uuid.uuid4()),
            "resourceBindings": [
                {"type": "MODEL", "reference": "kind-model", "revision": "",
                 "digest": "sha256:" + "4" * 64},
            ],
        }

        snapshots["legacy"] = create_snapshot(args.base_url, subject, legacy)
        deployment = api_request(
            args.base_url, f"/api/v1/config/snapshots/{snapshots['legacy']['id']}/agents/{args.agent_id}",
            "POST", {}, f"kind-resource-binding-deploy-legacy-{uuid.uuid4()}")
        binding_id = str(deployment.get("bindingId", ""))
        if not binding_id:
            fail(f"legacy deployment did not return bindingId: {deployment}")
        statuses["legacy"] = wait_for_phase(
            args.base_url, binding_id, snapshots["legacy"]["id"], snapshots["legacy"]["version"],
            "APPLIED", args.timeout, args.poll_interval)
        if (statuses["legacy"].get("apply") or {}).get("error"):
            fail(f"legacy manifest ACK carried an error: {statuses['legacy']}")

        snapshots["valid"] = create_snapshot(args.base_url, subject, valid)
        deployment = api_request(
            args.base_url, f"/api/v1/config/snapshots/{snapshots['valid']['id']}/agents/{args.agent_id}",
            "POST", {}, f"kind-resource-binding-deploy-valid-{uuid.uuid4()}")
        require_same_binding(deployment, binding_id, "valid resource-binding deployment")
        statuses["valid"] = wait_for_phase(
            args.base_url, binding_id, snapshots["valid"]["id"], snapshots["valid"]["version"],
            "APPLIED", args.timeout, args.poll_interval)
        valid_apply = statuses["valid"].get("apply") or {}
        if valid_apply.get("error") or valid_apply.get("failureCode"):
            fail(f"valid resource bindings were not acknowledged cleanly: {valid_apply}")
        print("KIND_RESOURCE_BINDING_ACK_OK "
              f"subject={subject} binding={binding_id} revision={snapshots['valid']['version']}")

        expected_replicas = len(worker_pod_names(args.namespace, args.worker_deployment))
        if expected_replicas != 1:
            fail(f"expected one running Worker Pod before bootstrap restart, got {expected_replicas}")
        old_pod = worker_pod_names(args.namespace, args.worker_deployment)[0]
        run("delete", "pod", old_pod, "--wait=true", namespace=args.namespace)
        replacement_pod = wait_for_replacement_worker(
            args.namespace, args.worker_deployment, old_pod, expected_replicas,
            args.timeout, args.poll_interval)
        wait_for_bootstrap_log(args.namespace, replacement_pod, args.agent_id,
                               args.timeout, args.poll_interval)
        print("KIND_CONFIG_BOOTSTRAP_OK "
              f"agent={args.agent_id} old_pod={old_pod} replacement_pod={replacement_pod} "
              f"revision={snapshots['valid']['version']}")

        snapshots["invalid"] = create_snapshot(args.base_url, subject, invalid)
        deployment = api_request(
            args.base_url, f"/api/v1/config/snapshots/{snapshots['invalid']['id']}/agents/{args.agent_id}",
            "POST", {}, f"kind-resource-binding-deploy-invalid-{uuid.uuid4()}")
        require_same_binding(deployment, binding_id, "invalid resource-binding deployment")
        statuses["invalid"] = wait_for_phase(
            args.base_url, binding_id, snapshots["invalid"]["id"], snapshots["invalid"]["version"],
            "FAILED", args.timeout, args.poll_interval)
        invalid_apply = statuses["invalid"].get("apply") or {}
        error = str(invalid_apply.get("error") or "")
        if invalid_apply.get("failureCode") != "VALIDATION":
            fail(f"invalid resource binding was not classified as VALIDATION: {invalid_apply}")
        if not error.startswith("RESOURCE_BINDING_INVALID:") or "INVALID_REVISION" not in error:
            fail(f"invalid resource binding did not return stable error_message: {invalid_apply}")
        print("KIND_RESOURCE_BINDING_FAILURE_OK "
              f"subject={subject} binding={binding_id} failureCode={invalid_apply['failureCode']} "
              "error=RESOURCE_BINDING_INVALID")
        return 0
    except Exception as error:
        context.update({"binding_id": binding_id, "snapshots": snapshots, "statuses": statuses,
                        "old_pod": old_pod, "replacement_pod": replacement_pod})
        print(f"KIND_RESOURCE_BINDING_ACK_FAIL: {error}", file=sys.stderr)
        print("KIND_RESOURCE_BINDING_ACK_DIAGNOSTICS: "
              + json.dumps(context, sort_keys=True, default=str), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
