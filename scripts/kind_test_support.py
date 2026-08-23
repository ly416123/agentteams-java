"""Small, dependency-free helpers shared by Kind acceptance scripts."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


class KindTestError(RuntimeError):
    """An expected Kind acceptance failure with a safe, actionable message."""


def run(*args: str, namespace: str | None = None, timeout: float = 60.0) -> str:
    """Run kubectl and return trimmed stdout."""
    command = ["kubectl"]
    if namespace:
        command += ["-n", namespace]
    command += list(args)
    result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise KindTestError(f"command failed ({result.returncode}): {' '.join(command)}; {detail}")
    return result.stdout.strip()


@dataclass(frozen=True)
class HttpResult:
    status: int
    payload: dict | list | str | None


def api_request(url: str, method: str = "GET", body: dict | None = None,
                idempotency_key: str | None = None, timeout: float = 15.0) -> HttpResult:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    bearer = os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", "").strip()
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return HttpResult(response.status, _decode_json(response.read()))
    except urllib.error.HTTPError as error:
        return HttpResult(error.code, _decode_json(error.read()))
    except urllib.error.URLError as error:
        raise KindTestError(f"HTTP request failed: {url}: {error.reason}") from error


def _decode_json(raw: bytes) -> dict | list | str | None:
    if not raw:
        return None
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def wait_until(description: str, predicate, timeout: float = 180.0, interval: float = 1.0):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except (KindTestError, OSError, urllib.error.URLError):
            pass
        time.sleep(interval)
    raise KindTestError(f"timed out waiting for {description}; last={last!r}")


class PortForward:
    """Own a kubectl service port-forward and stop it on close."""

    def __init__(self, namespace: str, service: str, local_port: int, remote_port: int):
        self.namespace = namespace
        self.service = service
        self.local_port = local_port
        self.remote_port = remote_port
        self.process: subprocess.Popen | None = None

    def start(self, timeout: float = 30.0) -> "PortForward":
        self.process = subprocess.Popen(
            ["kubectl", "-n", self.namespace, "port-forward", f"service/{self.service}",
             f"{self.local_port}:{self.remote_port}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        wait_until(f"port-forward {self.service}:{self.remote_port}",
                   lambda: self._ready(), timeout=timeout, interval=0.25)
        return self

    def _ready(self) -> bool:
        if self.process is None:
            return False
        if self.process.poll() is not None:
            raise KindTestError(f"port-forward exited for service {self.service}")
        try:
            with socket.create_connection(("127.0.0.1", self.local_port), timeout=0.5):
                return True
        except OSError:
            return False

    def close(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)

    def __enter__(self) -> "PortForward":
        return self.start()

    def __exit__(self, _type, _value, _traceback) -> None:
        self.close()


def grpcurl_call(endpoint: str, proto_root: Path, proto_file: str, method: str,
                 request: dict, grpcurl: str | None = None, tls: bool = False,
                 timeout: float = 20.0) -> dict:
    """Call a local-proto gRPC method through grpcurl's JSON codec."""
    binary = grpcurl or os.environ.get("GRPCURL_BIN", "grpcurl")
    resolved = shutil.which(binary) or (binary if Path(binary).is_file() else None)
    if not resolved:
        raise KindTestError("grpcurl is required; install it or set GRPCURL_BIN")
    command = [resolved]
    if not tls:
        command.append("-plaintext")
    command += ["-import-path", str(proto_root), "-proto", proto_file,
                "-format", "json", "-d", "@", endpoint, method]
    result = subprocess.run(command, input=json.dumps(request), check=False,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise KindTestError(f"grpcurl failed ({result.returncode}) for {method}: {detail}")
    try:
        decoded = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise KindTestError(f"grpcurl returned invalid JSON for {method}") from error
    if not isinstance(decoded, dict):
        raise KindTestError(f"grpcurl returned a non-object response for {method}")
    return decoded


def query_url(base_url: str, path: str, params: dict[str, str]) -> str:
    return f"{base_url.rstrip('/')}{path}?{urllib.parse.urlencode(params)}"
