#!/usr/bin/env python3
"""Authorize one ChatGPT plan account and store its session in a Kubernetes Secret."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

AUTH_BASE = "https://auth.openai.com"
RESOURCE = "https://api.openai.com/v1"
TOKEN_ENDPOINT = AUTH_BASE + "/api/accounts/oauth/token"
DISCOVERY_URL = AUTH_BASE + "/.well-known/openid-configuration"
MODEL_ENDPOINT = RESOURCE + "/models"
REQUIRED_PLAN_SCOPE = "chatgpt.tokens.use.direct"
CALLBACK_PATH = "/auth/callback"
CALLBACK_PORT = 1455
HOST_ID_PATH = Path.home() / ".config" / "agentteams" / "chatgpt-plan-host-id"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def http_json(url: str, data: bytes | None = None, headers: dict[str, str] | None = None) -> dict:
    request = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"OAuth/Responses HTTP {error.code}") from None
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"OAuth/Responses request failed: {type(error).__name__}") from None


def verify_id_token(token: str, issued_client_id: str, expected_nonce: str) -> dict:
    try:
        import cryptography  # noqa: F401
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding, rsa

        segments = token.split(".")
        if len(segments) != 3:
            raise ValueError("not a JWT")
        header = json.loads(base64.urlsafe_b64decode(segments[0] + "=="))
        claims = json.loads(base64.urlsafe_b64decode(segments[1] + "=="))
        signature = base64.urlsafe_b64decode(segments[2] + "==")
        if header.get("alg") != "RS256" or not header.get("kid"):
            raise ValueError("unsupported signature")
        metadata = http_json(DISCOVERY_URL)
        jwks = http_json(metadata["jwks_uri"])
        key = next((candidate for candidate in jwks.get("keys", [])
                    if candidate.get("kid") == header["kid"] and candidate.get("kty") == "RSA"), None)
        if key is None:
            raise ValueError("signing key not found")
        modulus = int.from_bytes(base64.urlsafe_b64decode(key["n"] + "=="), "big")
        exponent = int.from_bytes(base64.urlsafe_b64decode(key["e"] + "=="), "big")
        public_key = rsa.RSAPublicNumbers(exponent, modulus).public_key()
        public_key.verify(signature, f"{segments[0]}.{segments[1]}".encode(), padding.PKCS1v15(), hashes.SHA256())
        audiences = claims.get("aud", [])
        if isinstance(audiences, str):
            audiences = [audiences]
        now = int(time.time())
        if claims.get("iss") != metadata.get("issuer", AUTH_BASE):
            raise ValueError("issuer mismatch")
        if issued_client_id not in audiences or int(claims.get("exp", 0)) <= now:
            raise ValueError("audience or expiry mismatch")
        if claims.get("nonce") != expected_nonce or not claims.get("sub"):
            raise ValueError("nonce or subject mismatch")
        return claims
    except Exception as error:
        if isinstance(error, RuntimeError):
            raise
        raise RuntimeError(f"ID token validation failed: {type(error).__name__}") from None


def run_kubectl(args: list[str], input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["kubectl", *args], input=input_text, text=True, capture_output=True, check=False)
    return result


def secret_data(context: str | None, namespace: str, name: str) -> dict | None:
    args = ["-n", namespace]
    if context:
        args += ["--context", context]
    result = run_kubectl(args + ["get", "secret", name, "-o", "json"])
    if result.returncode:
        if "NotFound" in result.stderr:
            return None
        raise RuntimeError("Could not read the configured OAuth session Secret")
    try:
        encoded = json.loads(result.stdout).get("data", {})
        return {key: base64.b64decode(value).decode("utf-8") for key, value in encoded.items()}
    except Exception:
        raise RuntimeError("Configured OAuth session Secret is invalid") from None


def apply_session_secret(context: str | None, namespace: str, name: str, session: dict) -> None:
    env_file = "".join(f"{key}={value}\n" for key, value in session.items())
    args = ["-n", namespace]
    if context:
        args += ["--context", context]
    create = run_kubectl(args + ["create", "secret", "generic", name,
                                "--from-env-file=/dev/stdin", "--dry-run=client", "-o", "yaml"], env_file)
    if create.returncode:
        raise RuntimeError("Could not prepare the OAuth session Secret")
    apply = run_kubectl(args + ["apply", "-f", "-"], create.stdout)
    if apply.returncode:
        raise RuntimeError("Could not update the OAuth session Secret")


def host_id() -> str:
    HOST_ID_PATH.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        current = HOST_ID_PATH.read_text().strip()
        if current:
            return current
    except FileNotFoundError:
        pass
    value = "urn:uuid:" + str(uuid.uuid4())
    descriptor = os.open(HOST_ID_PATH, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(value + "\n")
    return value


def authorize(args: argparse.Namespace) -> None:
    context = args.context
    host = host_id()
    old = secret_data(context, args.namespace, args.secret_name)
    client_id = old.get("client-id") if old else "dynamic_agent_client"
    verifier = b64url(secrets.token_bytes(48))
    state = b64url(secrets.token_bytes(32))
    nonce = b64url(secrets.token_bytes(32))
    redirect_uri = f"http://127.0.0.1:{CALLBACK_PORT}{CALLBACK_PATH}"
    params = {
        "client_id": client_id,
        "ext_agent_host_id": host,
        "response_type": "code",
        "redirect_uri": redirect_uri,
        "scope": "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
        "resource": RESOURCE,
        "state": state,
        "nonce": nonce,
        "code_challenge_method": "S256",
        "code_challenge": b64url(hashlib.sha256(verifier.encode()).digest()),
    }
    if client_id == "dynamic_agent_client":
        params["agent_name_hint"] = "AgentTeams L5 Evaluation"
    elif old and old.get("id-token"):
        params["id_token_hint"] = old["id-token"]
    authorization_url = AUTH_BASE + "/api/accounts/authorize?" + urllib.parse.urlencode(params)
    callback: dict[str, str] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != CALLBACK_PATH:
                self.send_error(404)
                return
            callback.update({key: values[-1] for key, values in urllib.parse.parse_qs(parsed.query).items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"<!doctype html><title>AgentTeams</title><p>Authorization received. You may close this tab.</p>")

        def log_message(self, _format, *_args):
            return

    try:
        server = HTTPServer(("127.0.0.1", CALLBACK_PORT), Handler)
    except OSError:
        raise RuntimeError(f"OAuth callback port {CALLBACK_PORT} is unavailable") from None
    server.timeout = 300
    print("Complete ChatGPT authorization in the browser window.", flush=True)
    webbrowser.open(authorization_url)
    try:
        handled = server.handle_request()
    finally:
        server.server_close()
    if handled is None and not callback:
        raise RuntimeError("OAuth callback timed out after 300 seconds")
    if callback.get("state") != state:
        raise RuntimeError("OAuth callback state validation failed")
    if callback.get("error"):
        raise RuntimeError("ChatGPT authorization was declined or failed")
    code = callback.get("code")
    callback_client = callback.get("client_id")
    if not code:
        raise RuntimeError("OAuth callback did not contain an authorization code")
    if client_id == "dynamic_agent_client":
        if not callback_client:
            raise RuntimeError("New OAuth registration did not return an issued client ID")
        client_id = callback_client
    elif callback_client and callback_client != client_id:
        raise RuntimeError("OAuth callback client ID did not match the saved registration")
    form = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": client_id,
        "code": code, "code_verifier": verifier, "redirect_uri": redirect_uri, "resource": RESOURCE,
    }).encode()
    token_response = http_json(TOKEN_ENDPOINT, form, {"Content-Type": "application/x-www-form-urlencoded"})
    granted = set((token_response.get("scope") or callback.get("scope") or "").split())
    if REQUIRED_PLAN_SCOPE not in granted:
        raise RuntimeError("ChatGPT plan usage scope was not granted")
    id_token = token_response.get("id_token")
    if not id_token:
        raise RuntimeError("OAuth token response did not include an ID token")
    claims = verify_id_token(id_token, client_id, nonce)
    if old and old.get("subject") and old["subject"] != claims["sub"]:
        raise RuntimeError("Authorized ChatGPT account did not match the saved session")
    if not token_response.get("access_token") or not token_response.get("refresh_token"):
        raise RuntimeError("OAuth token response did not include renewable credentials")
    now = int(time.time())
    session = {
        "client-id": client_id,
        "host-id": host,
        "subject": claims["sub"],
        "id-token": id_token,
        "access-token": token_response["access_token"],
        "refresh-token": token_response["refresh_token"],
        "expires-at": str(now + int(token_response.get("expires_in", 3600))),
        "earliest-refresh-at": str(int(token_response.get("earliest_refresh_at", 0))),
        "scopes": " ".join(sorted(granted)),
    }
    apply_session_secret(context, args.namespace, args.secret_name, session)
    print("ChatGPT OAuth session stored in the selected Kubernetes Secret.")


def list_models(args: argparse.Namespace) -> None:
    session = secret_data(args.context, args.namespace, args.secret_name)
    if not session or not session.get("access-token"):
        raise RuntimeError("OAuth access token is not available in the selected Kubernetes Secret")
    response = http_json(MODEL_ENDPOINT, headers={"Authorization": "Bearer " + session["access-token"]})
    models = response.get("models", [])
    if not isinstance(models, list):
        raise RuntimeError("ChatGPT model catalog response is invalid")
    for model in models:
        if model.get("visibility") == "list" and model.get("slug"):
            print(f"{model.get('display_name', model['slug'])}\t{model['slug']}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    for name, function in (("authorize", authorize), ("models", list_models)):
        command = sub.add_parser(name)
        command.add_argument("--context", help="kubectl context for the L5 cluster")
        command.add_argument("--namespace", default="agentteams")
        command.add_argument("--secret-name", default="agentteams-chatgpt-oauth-session")
        command.set_defaults(function=function)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.function(args)
        return 0
    except RuntimeError as error:
        print(str(error), file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
