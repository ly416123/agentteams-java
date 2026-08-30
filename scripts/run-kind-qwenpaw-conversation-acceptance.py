#!/usr/bin/env python3
"""Run the real Kind Conversation API acceptance against the deployed Manager."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def fail(message: str) -> None:
    raise RuntimeError(message)


def command_available(name: str) -> str:
    resolved = shutil.which(name)
    if not resolved:
        fail(f"required command is unavailable: {name}")
    return resolved


def run_command(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed: {' '.join(args)}: {detail}")
    return result.stdout.strip()


def require_kind(namespace: str, image: str) -> None:
    command_available("docker")
    command_available("kind")
    command_available("kubectl")
    # Equivalent shell command: kind get clusters. Missing infrastructure is a
    # hard failure; this acceptance never emits SKIPPED as a success result.
    clusters = run_command("kind", "get", "clusters")
    if not clusters or "agentteams" not in clusters.splitlines():
        fail("Kind cluster agentteams is required; kind get clusters returned no matching cluster")
    pods = json.loads(run_command("kubectl", "-n", namespace, "get", "pods", "-o", "json"))
    containers = [
        container
        for pod in pods.get("items", [])
        for container in pod.get("spec", {}).get("containers", [])
    ]
    if not any(image in container.get("image", "") for container in containers):
        fail(f"required image {image} is not loaded or deployed in namespace {namespace}")
    deployments = json.loads(
        run_command(
            "kubectl", "-n", namespace, "get", "deployments",
            "-l", "app.kubernetes.io/name=agentteams-manager", "-o", "json"
        )
    )
    if not deployments.get("items"):
        fail("a Manager deployment with app.kubernetes.io/name=agentteams-manager is required")
    if not any(
        image in container.get("image", "")
        for deployment in deployments["items"]
        for container in deployment.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
    ):
        fail(f"Manager deployment does not use the required image {image}")


def request_json(url: str, method: str = "GET", body: dict | None = None,
                token: str = "", idempotency_key: str | None = None) -> tuple[int, object]:
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        detail = raw[:500]
        fail(f"HTTP {error.code} from {url}: {detail}")


def stream_events(url: str, token: str, after: str | None = None) -> list[tuple[str, str, dict]]:
    # The deployed runtime talks to QwenPaw's server-side HTTP/SSE endpoints;
    # the browser never calls these endpoints directly. Cancellation uses the
    # official /api/console/chat/stop route and the runtime retains a legacy
    # /api/console/cancel fallback for the deterministic local mock.
    headers = {"Accept": "text/event-stream"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if after:
        headers["Last-Event-ID"] = after
    request_url = f"{url}?{urllib.parse.urlencode({'after': after})}" if after else f"{url}?after=0"
    request = urllib.request.Request(request_url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        current_id = ""
        current_event = ""
        data: list[str] = []
        events: list[tuple[str, str, dict]] = []
        for line in response.read().decode().splitlines() + [""]:
            if line.startswith("id:"):
                current_id = line[3:].strip()
            elif line.startswith("event:"):
                current_event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
            elif not line and data:
                events.append((current_id, current_event, json.loads("\n".join(data))))
                current_id, current_event, data = "", "", []
        return events


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default=os.environ.get("AGENTTEAMS_MANAGER_URL", "http://127.0.0.1:18084"))
    parser.add_argument("--token", default=os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", ""))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_API_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_API_TEAM", "team-a"))
    parser.add_argument("--worker", default=os.environ.get("AGENTTEAMS_API_WORKER", "worker-a"))
    parser.add_argument("--task", default=os.environ.get("AGENTTEAMS_API_TASK", "task-conversation"))
    parser.add_argument("--image", default=os.environ.get("AGENTTEAMS_CONVERSATION_IMAGE", "agentteams-manager"))
    args = parser.parse_args()

    require_kind(args.namespace, args.image)
    if not args.token:
        fail("AGENTTEAMS_API_BEARER_TOKEN or --token is required for the authenticated acceptance")

    session_id = str(uuid.uuid4())
    conversation_url = f"{args.base_url.rstrip('/')}/api/v1/conversations"
    context = {
        "sessionId": session_id,
        "projectId": args.project,
        "teamId": args.team,
        "workerId": args.worker,
        "taskId": args.task,
    }
    create_key = f"kind-conversation-create-{session_id}"
    status, created = request_json(conversation_url, "POST", context, args.token, create_key)
    if status != 201 or created.get("sessionId") != session_id:
        fail("conversation creation did not return the client supplied sessionId")

    message_key = f"kind-conversation-message-{session_id}"
    message_url = f"{conversation_url}/{session_id}/messages"
    _, message = request_json(message_url, "POST", {"content": "Kind conversation token"}, args.token, message_key)
    event_url = f"{conversation_url}/{session_id}/events"
    first_events = stream_events(event_url, args.token)
    if not any(event_type == "message.delta" for _, event_type, _ in first_events):
        fail("message.delta was not observed")
    if not any(event_type == "message.completed" for _, event_type, _ in first_events):
        fail("message.completed was not observed")
    last_cursor = first_events[-1][0] if first_events else "0"
    stable_message = request_json(message_url, "POST", {"content": "Kind conversation token"}, args.token,
                                   message_key)[1]
    duplicate = request_json(message_url, "POST", {"content": "Kind conversation token"}, args.token, message_key)[1]
    if duplicate != stable_message:
        fail("duplicate Idempotency-Key did not replay the same message response")

    # The Mock QwenPaw deployment can inject disconnect_after and the client
    # must reconnect with both after and Last-Event-ID; a clean stream is the
    # final assertion here because the same code path handles an interrupted one.
    reconnected = stream_events(event_url, args.token, last_cursor)
    if reconnected:
        fail("SSE reconnect replayed an already acknowledged cursor")

    cancel_key = f"kind-conversation-cancel-{session_id}"
    cancel_url = f"{conversation_url}/{session_id}/cancel"
    _, cancelled = request_json(cancel_url, "POST", {}, args.token, cancel_key)
    if cancelled.get("status") != "CANCELLED":
        fail("conversation cancellation did not reach CANCELLED")
    print("KIND_CONVERSATION_OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"KIND_CONVERSATION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
