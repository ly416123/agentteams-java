#!/usr/bin/env python3
"""Verify a real Worker applies a new config revision and then rolls back."""

from __future__ import annotations

import argparse
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid


class SmokeFailure(RuntimeError):
    pass


def fail(message: str) -> None:
    raise SmokeFailure(message)


def api_request(base_url: str, path: str, method: str = "GET", body: dict | None = None,
                idempotency_key: str | None = None) -> dict:
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
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        response_body = error.read().decode("utf-8", errors="replace")[:4096]
        raise SmokeFailure(f"HTTP {error.code} {method} {path}: {response_body}") from error
    except (urllib.error.URLError, http.client.RemoteDisconnected, ConnectionResetError) as error:
        raise SmokeFailure(f"request failed {method} {path}: {error}") from error


def wait_for_binding(base_url: str, binding_id: str, expected_snapshot_id: str,
                     expected_version: int, timeout: float, interval: float) -> dict:
    deadline = time.monotonic() + timeout
    last_status: dict | None = None
    last_error = ""
    path = f"/api/v1/config/bindings/{binding_id}"
    while time.monotonic() < deadline:
        try:
            last_status = api_request(base_url, path)
            binding = last_status.get("binding") or {}
            snapshot = last_status.get("desiredSnapshot") or {}
            apply = last_status.get("apply") or {}
            if (binding.get("snapshotId") == expected_snapshot_id
                    and snapshot.get("id") == expected_snapshot_id
                    and snapshot.get("version") == expected_version
                    and apply.get("phase") == "APPLIED"):
                return last_status
            if apply.get("phase") == "FAILED":
                fail(f"config revision {expected_version} failed: {apply}")
        except SmokeFailure as error:
            last_error = str(error)
        time.sleep(interval)
    fail(f"timed out waiting for binding {binding_id} revision {expected_version} "
         f"to become APPLIED; last_status={last_status!r}; last_error={last_error!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=240.0,
                        help="maximum seconds to wait for each Worker apply")
    parser.add_argument("--poll-interval", type=float, default=2.0)
    args = parser.parse_args()

    base_url = os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "").strip().rstrip("/")
    agent_id = os.environ.get("AGENTTEAMS_AGENT_ID", "").strip()
    if not base_url:
        parser.error("AGENTTEAMS_CONTROL_PLANE_URL is required")
    if not agent_id:
        parser.error("AGENTTEAMS_AGENT_ID is required")

    subject = f"kind-config-rollback-{uuid.uuid4()}"
    old_snapshot: dict | None = None
    new_snapshot: dict | None = None
    binding_id = ""
    last_status: dict | None = None
    context = {"base_url": base_url, "agent_id": agent_id, "subject": subject}
    try:
        # QwenPaw's native /api/models/active configuration requires a model
        # for every manifest applied by the real Worker. Keep both revisions
        # deterministic and independent of any pre-existing local selection.
        # CI default: "model": "agentteams-kind-mock". Local real QwenPaw can
        # override it with AGENTTEAMS_QWENPAW_MODEL.
        smoke_model = os.environ.get("AGENTTEAMS_QWENPAW_MODEL", "agentteams-kind-mock").strip()
        if not smoke_model:
            fail("AGENTTEAMS_QWENPAW_MODEL must not be blank")
        old_manifest = {
            "kind": "KindConfigRollbackSmoke",
            "revision": "stable",
            "model": smoke_model,
            "marker": str(uuid.uuid4()),
        }
        new_manifest = {
            "kind": "KindConfigRollbackSmoke",
            "revision": "candidate",
            "model": smoke_model,
            "marker": str(uuid.uuid4()),
        }
        old_snapshot = api_request(
            base_url, "/api/v1/config/snapshots", "POST",
            {"subject": subject, "manifest": old_manifest, "actor": "kind-recovery"},
            f"kind-config-rollback-old-snapshot-{uuid.uuid4()}")
        new_snapshot = api_request(
            base_url, "/api/v1/config/snapshots", "POST",
            {"subject": subject, "manifest": new_manifest, "actor": "kind-recovery"},
            f"kind-config-rollback-new-snapshot-{uuid.uuid4()}")
        if old_snapshot.get("id") == new_snapshot.get("id"):
            fail("config revisions unexpectedly share a snapshot id")
        if old_snapshot.get("version") == new_snapshot.get("version"):
            fail("config revisions unexpectedly share a version")

        old_deployment = api_request(
            base_url, f"/api/v1/config/snapshots/{old_snapshot['id']}/agents/{agent_id}", "POST", {},
            f"kind-config-rollback-old-deploy-{uuid.uuid4()}")
        binding_id = old_deployment["bindingId"]
        # Establish a stable APPLIED revision before introducing the candidate.
        last_status = wait_for_binding(base_url, binding_id, old_snapshot["id"],
                                       old_snapshot["version"], args.timeout, args.poll_interval)

        new_deployment = api_request(
            base_url, f"/api/v1/config/snapshots/{new_snapshot['id']}/agents/{agent_id}", "POST", {},
            f"kind-config-rollback-new-deploy-{uuid.uuid4()}")
        if new_deployment.get("bindingId") != binding_id:
            fail(f"new revision changed binding id: {new_deployment}")
        last_status = wait_for_binding(base_url, binding_id, new_snapshot["id"],
                                       new_snapshot["version"], args.timeout, args.poll_interval)

        rollback = api_request(
            base_url, f"/api/v1/config/bindings/{binding_id}/rollback", "POST", {},
            f"kind-config-rollback-rollback-{uuid.uuid4()}")
        if rollback.get("bindingId") != binding_id:
            fail(f"rollback returned an unexpected binding id: {rollback}")
        last_status = wait_for_binding(base_url, binding_id, old_snapshot["id"],
                                       old_snapshot["version"], args.timeout, args.poll_interval)
        apply = last_status.get("apply") or {}
        if apply.get("rollback") is not True:
            fail(f"rollback apply was APPLIED without rollback=true: {last_status}")
        print("KIND_CONFIG_ROLLBACK_OK "
              f"subject={subject} binding={binding_id} "
              f"stable_revision={old_snapshot['version']} candidate_revision={new_snapshot['version']}")
        return 0
    except Exception as error:
        context.update({"old_snapshot": old_snapshot, "new_snapshot": new_snapshot,
                        "binding_id": binding_id, "last_status": last_status})
        print(f"KIND_CONFIG_ROLLBACK_FAIL: {error}", file=sys.stderr)
        print("KIND_CONFIG_ROLLBACK_DIAGNOSTICS: "
              + json.dumps(context, sort_keys=True, default=str), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
