#!/usr/bin/env python3
"""Deterministic in-cluster HTTP receiver for Kind Dashboard alert tests."""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentTeamsDashboardAlertReceiver/1.0"

    def log_message(self, format: str, *args: object) -> None:
        print(format % args, flush=True)

    def _respond(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path.rstrip("/") in {"", "/healthz", "/readyz"}:
            self._respond(200, {"status": "ok"})
            return
        self._respond(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        mode = os.environ.get("DASHBOARD_ALERT_RECEIVER_MODE", "success").strip().lower()
        if mode == "fail":
            self._respond(500, {"status": "failed"})
            return
        if mode != "success":
            self._respond(400, {"status": "invalid receiver mode"})
            return
        self._respond(200, {"status": "accepted"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
