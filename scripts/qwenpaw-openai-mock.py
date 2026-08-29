#!/usr/bin/env python3
"""Small deterministic OpenAI-compatible server for the Kind QwenPaw smoke test."""

from __future__ import annotations

import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODEL = "agentteams-kind-mock"
RESPONSE_TEXT = "KIND_LEASE_RECOVERY_OK"
RESTART_RESPONSE_TEXT = "KIND_WORKER_RESTART_OK"
RESPONSE_DELAY_SECONDS = max(0.0, float(os.environ.get("QWENPAW_MOCK_RESPONSE_DELAY_SECONDS", "0")))
DELAY_LOCK = threading.Lock()
IN_FLIGHT_REQUESTS = 0
IN_FLIGHT_LOCK = threading.Lock()


def set_response_delay(seconds: float) -> None:
    global RESPONSE_DELAY_SECONDS
    if seconds < 0:
        raise ValueError("seconds must not be negative")
    with DELAY_LOCK:
        RESPONSE_DELAY_SECONDS = seconds


def response_delay() -> float:
    with DELAY_LOCK:
        return RESPONSE_DELAY_SECONDS


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentTeamsQwenPawMock/1.0"

    def log_message(self, format: str, *args: object) -> None:
        print(format % args, flush=True)

    def send_json(self, status: int, payload: dict) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path.rstrip("/") == "/debug/inflight":
            with IN_FLIGHT_LOCK:
                inflight = IN_FLIGHT_REQUESTS
            self.send_json(200, {"inflight": inflight})
            return
        if self.path.rstrip("/") == "/debug/delay":
            self.send_json(200, {"seconds": response_delay()})
            return
        if self.path.rstrip("/") in {"/v1/models", "/models"}:
            self.send_json(
                200,
                {
                    "object": "list",
                    "data": [{"id": MODEL, "object": "model", "owned_by": "agentteams"}],
                },
            )
            return
        self.send_json(404, {"error": {"message": "not found", "type": "invalid_request_error"}})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path.rstrip("/") == "/debug/delay":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                set_response_delay(float(body["seconds"]))
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                self.send_json(400, {"error": {"message": str(error), "type": "invalid_request_error"}})
                return
            self.send_json(200, {"seconds": response_delay()})
            return
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self.send_json(404, {"error": {"message": "not found", "type": "invalid_request_error"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        request_text = json.dumps(request, separators=(",", ":"))
        response_text = (RESTART_RESPONSE_TEXT if "KIND_WORKER_RESTART_OK" in request_text
                         else RESPONSE_TEXT)
        global IN_FLIGHT_REQUESTS
        with IN_FLIGHT_LOCK:
            IN_FLIGHT_REQUESTS += 1
        try:
            delay = response_delay()
            if delay:
                time.sleep(delay)
            response_id = f"chatcmpl-{uuid.uuid4()}"
            completion = {
                "id": response_id,
                "object": "chat.completion",
                "created": int(time.time()),
                "model": request.get("model", MODEL),
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": response_text},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }
            if request.get("stream"):
                encoded = json.dumps(
                    {
                        "id": response_id,
                        "object": "chat.completion.chunk",
                        "created": completion["created"],
                        "model": completion["model"],
                        "choices": [{"index": 0, "delta": {"role": "assistant", "content": response_text},
                                     "finish_reason": "stop"}],
                    },
                    separators=(",", ":"),
                )
                body = f"data: {encoded}\n\ndata: [DONE]\n\n".encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_json(200, completion)
        finally:
            with IN_FLIGHT_LOCK:
                IN_FLIGHT_REQUESTS -= 1


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
