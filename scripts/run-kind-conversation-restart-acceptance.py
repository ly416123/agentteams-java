#!/usr/bin/env python3
"""Verify conversation history survives a real Manager rollout in Kind."""

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

from kind_test_support import PortForward, KindTestError, api_request, run, wait_until


def fail(message: str) -> None:
    raise KindTestError(message)


def token_from_keycloak(port: int, username: str, password: str) -> str:
    import urllib.parse

    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "agentteams-api",
        "username": username, "password": password,
    }).encode()
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/realms/agentteams/protocol/openid-connect/token",
        data=body, headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read())["access_token"]
    except (urllib.error.HTTPError, urllib.error.URLError, KeyError, json.JSONDecodeError) as error:
        raise KindTestError("Keycloak token acquisition failed") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--manager-port", type=int, default=18084)
    parser.add_argument("--keycloak-port", type=int, default=18082)
    parser.add_argument("--image", default="ghcr.io/ly416123/agentteams-manager:latest")
    args = parser.parse_args()

    run("-n", args.namespace, "get", "deployment/agentteams-agentteams-java-manager")
    image = run("-n", args.namespace, "get", "deployment/agentteams-agentteams-java-manager",
                "-o", "jsonpath={.spec.template.spec.containers[0].image}")
    if args.image not in image:
        fail(f"Manager deployment does not use required image {args.image}")

    manager = PortForward(args.namespace, "agentteams-agentteams-java-manager", args.manager_port, 8080)
    keycloak = PortForward(args.namespace, "keycloak", args.keycloak_port, 8080)
    with keycloak, manager:
        token = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", "").strip()
        if not token:
            token = token_from_keycloak(args.keycloak_port,
                                        os.environ.get("AGENTTEAMS_API_USERNAME", "alice"),
                                        os.environ.get("AGENTTEAMS_API_PASSWORD", "alice-dev"))
        os.environ["AGENTTEAMS_API_BEARER_TOKEN"] = token
        base_url = f"http://127.0.0.1:{args.manager_port}"
        session_id = str(uuid.uuid4())
        conversation_url = f"{base_url}/api/v1/conversations"
        context = {"sessionId": session_id, "projectId": "project-a", "teamId": "team-a",
                   "workerId": "worker-a", "taskId": "task-conversation-restart"}
        created = api_request(conversation_url, "POST", context,
                              idempotency_key=f"restart-create-{session_id}")
        if created.status != 201:
            fail(f"conversation creation returned HTTP {created.status}: {created.payload}")
        message_url = f"{conversation_url}/{session_id}/messages"
        message_key = f"restart-message-{session_id}"
        sent = api_request(message_url, "POST", {"content": "restart history token"}, message_key)
        if sent.status != 200:
            fail(f"conversation message returned HTTP {sent.status}")
        # The Manager waits up to 30 seconds for an asynchronously processed
        # message before closing this finite SSE response. Keep the acceptance
        # client timeout above that server-side wait budget.
        completed = api_request(f"{conversation_url}/{session_id}/events", accept="text/event-stream", timeout=45.0)
        if completed.status != 200 or "message.completed" not in completed.payload:
            fail("conversation message did not reach a terminal event before restart")
        stable_replay = api_request(message_url, "POST", {"content": "restart history token"}, message_key)
        if stable_replay.status != 200:
            fail(f"conversation idempotent replay returned HTTP {stable_replay.status}")
        before = api_request(f"{conversation_url}/{session_id}/history")
        if before.status != 200:
            fail(f"history before restart returned HTTP {before.status}")

        run("-n", args.namespace, "rollout", "restart", "deployment/agentteams-agentteams-java-manager")
        run("-n", args.namespace, "rollout", "status", "deployment/agentteams-agentteams-java-manager",
            timeout=300)
        manager.close()
        manager.start()
        wait_until("Manager health after restart", lambda: _health(base_url), timeout=120)

        after = api_request(f"{conversation_url}/{session_id}/history")
        if after.status != 200 or after.payload != before.payload:
            fail("conversation history changed or disappeared after Manager restart")
        replay = api_request(message_url, "POST", {"content": "restart history token"}, message_key)
        if replay.status != 200 or replay.payload != stable_replay.payload:
            fail("message idempotency replay changed after Manager restart")
        print("KIND_CONVERSATION_RESTART_OK")
    return 0


def _health(base_url: str) -> bool:
    try:
        with urllib.request.urlopen(f"{base_url}/actuator/health", timeout=3) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError):
        return False


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KindTestError, subprocess.CalledProcessError) as error:
        print(f"KIND_CONVERSATION_RESTART_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
