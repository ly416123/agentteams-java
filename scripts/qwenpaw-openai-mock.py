#!/usr/bin/env python3
"""Small deterministic OpenAI-compatible server for the Kind QwenPaw smoke test."""

from __future__ import annotations

import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODEL = "agentteams-kind-mock"
RESPONSE_TEXT = "KIND_LEASE_RECOVERY_OK"


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
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self.send_json(404, {"error": {"message": "not found", "type": "invalid_request_error"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        response_id = f"chatcmpl-{uuid.uuid4()}"
        completion = {
            "id": response_id,
            "object": "chat.completion",
            "created": int(time.time()),
            "model": request.get("model", MODEL),
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": RESPONSE_TEXT},
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
                    "choices": [{"index": 0, "delta": {"role": "assistant", "content": RESPONSE_TEXT},
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


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
