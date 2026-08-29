#!/usr/bin/env python3
"""Deterministic QwenPaw HTTP/SSE mock for Conversation runtime tests."""

from __future__ import annotations

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


DEFAULT_DELAY_SECONDS = max(0.0, float(os.environ.get("QWENPAW_CONVERSATION_MOCK_DELAY_SECONDS", "0")))
DEFAULT_DISCONNECT_AFTER = None
CONFIG_LOCK = threading.Lock()
DELAY_SECONDS = DEFAULT_DELAY_SECONDS
DISCONNECT_AFTER = DEFAULT_DISCONNECT_AFTER
SESSIONS: dict[str, dict[str, Any]] = {}
AUDIT: list[str] = []


def reset_state() -> None:
    global DELAY_SECONDS, DISCONNECT_AFTER
    with CONFIG_LOCK:
        DELAY_SECONDS = DEFAULT_DELAY_SECONDS
        DISCONNECT_AFTER = DEFAULT_DISCONNECT_AFTER
        SESSIONS.clear()
        AUDIT.clear()


def audit_log() -> list[str]:
    with CONFIG_LOCK:
        return list(AUDIT)


def configuration() -> dict[str, Any]:
    with CONFIG_LOCK:
        return {"delay_seconds": DELAY_SECONDS, "disconnect_after": DISCONNECT_AFTER}


def set_configuration(delay_seconds: float | None = None, disconnect_after: int | None = None) -> None:
    global DELAY_SECONDS, DISCONNECT_AFTER
    with CONFIG_LOCK:
        if delay_seconds is not None:
            if delay_seconds < 0:
                raise ValueError("delay_seconds must not be negative")
            DELAY_SECONDS = delay_seconds
        if disconnect_after is not None and disconnect_after < 1:
            raise ValueError("disconnect_after must be positive")
        DISCONNECT_AFTER = disconnect_after


def mark_cancelled(session_id: str) -> None:
    with CONFIG_LOCK:
        SESSIONS.setdefault(session_id, {"cursor": 0, "cancelled": False})["cancelled"] = True


def next_cursor(session_id: str) -> int:
    with CONFIG_LOCK:
        state = SESSIONS.setdefault(session_id, {"cursor": 0, "cancelled": False})
        state["cursor"] += 1
        return state["cursor"]


def is_cancelled(session_id: str) -> bool:
    with CONFIG_LOCK:
        return bool(SESSIONS.get(session_id, {}).get("cancelled", False))


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentTeamsQwenPawConversationMock/1.0"

    def log_message(self, format: str, *args: object) -> None:
        # Request bodies, authorization headers and message content are never logged.
        with CONFIG_LOCK:
            AUDIT.append(format % args)

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        path = self.path.rstrip("/")
        if path == "/health":
            self.send_json(200, {"status": "ok"})
        elif path == "/debug/config":
            self.send_json(200, configuration())
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        path = self.path.rstrip("/")
        if path == "/debug/config":
            self.update_configuration()
            return
        if path == "/api/console/cancel":
            self.cancel_session()
            return
        if path == "/api/console/chat":
            self.chat()
            return
        self.send_json(404, {"error": "not found"})

    def update_configuration(self) -> None:
        try:
            body = self.read_json()
            delay = body.get("delay_seconds")
            disconnect = body.get("disconnect_after", DISCONNECT_AFTER)
            if delay is not None:
                delay = float(delay)
            if disconnect is not None:
                disconnect = int(disconnect)
            set_configuration(delay, disconnect)
            self.send_json(200, configuration())
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid mock configuration", "detail": str(error)})

    def cancel_session(self) -> None:
        try:
            session_id = str(self.read_json()["session_id"])
            if not session_id:
                raise ValueError("session_id must not be empty")
            mark_cancelled(session_id)
            self.send_json(200, {"session_id": session_id, "status": "cancelled"})
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid cancel request", "detail": str(error)})

    def chat(self) -> None:
        try:
            request = self.read_json()
            session_id = str(request["session_id"])
            if not session_id:
                raise ValueError("session_id must not be empty")
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid chat request", "detail": str(error)})
            return

        with CONFIG_LOCK:
            AUDIT.append(f"chat session={session_id} agent={bool(self.headers.get('X-Agent-Id'))}")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        # A deliberately incomplete length makes disconnect tests observable to HTTP clients.
        if configuration()["disconnect_after"] is not None:
            self.send_header("Content-Length", "999999")
        self.end_headers()

        events = (
            ("conversation.started", {"status": "created", "object": "response"}),
            ("message.delta", {
                "status": "in_progress", "type": "message", "delta": True,
                "role": "assistant", "content": [{"text": "CONVERSATION_MOCK_DELTA"}],
            }),
            ("message.completed", {
                "status": "completed", "object": "response",
                "output": [{"type": "message", "role": "assistant",
                             "content": [{"text": "CONVERSATION_MOCK_OK"}]}],
            }),
        )
        emitted = 0
        try:
            for event_name, payload in events:
                delay = configuration()["delay_seconds"]
                if delay:
                    time.sleep(delay)
                if is_cancelled(session_id):
                    self.write_event(session_id, "conversation.cancelled",
                                     {"status": "cancelled", "object": "response"})
                    return
                payload = dict(payload)
                payload["cursor"] = next_cursor(session_id)
                self.write_event_payload(event_name, payload)
                emitted += 1
                disconnect_after = configuration()["disconnect_after"]
                if disconnect_after is not None and emitted >= disconnect_after:
                    return
        except (BrokenPipeError, ConnectionResetError):
            return
        finally:
            self.flush_stream()

    def write_event(self, session_id: str, event_name: str, payload: dict[str, Any]) -> None:
        payload = dict(payload)
        payload["cursor"] = next_cursor(session_id)
        self.write_event_payload(event_name, payload)

    def write_event_payload(self, event_name: str, payload: dict[str, Any]) -> None:
        encoded = (f"event: {event_name}\n"
                   f"data: {json.dumps(payload, separators=(',', ':'))}\n\n").encode("utf-8")
        self.wfile.write(encoded)
        self.wfile.flush()

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 1024 * 1024:
            raise ValueError("request body is too large")
        return json.loads(self.rfile.read(length) or b"{}")

    def flush_stream(self) -> None:
        try:
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
