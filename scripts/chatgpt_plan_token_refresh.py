#!/usr/bin/env python3
"""Rotate a ChatGPT OAuth session stored in one namespaced Kubernetes Secret."""
from __future__ import annotations

import base64
import json
import os
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
RESOURCE = "https://api.openai.com/v1"
SERVICE_ACCOUNT = "/var/run/secrets/kubernetes.io/serviceaccount"
REFRESH_WINDOW_SECONDS = 15 * 60


def refresh_due(session: dict[str, str], now: int) -> bool:
    expires_at = int(session.get("expires-at", "0"))
    earliest = int(session.get("earliest-refresh-at", "0"))
    return now >= expires_at - REFRESH_WINDOW_SECONDS and now >= earliest


def rotated_session(session: dict[str, str], response: dict, now: int) -> dict[str, str]:
    access = response.get("access_token")
    refresh = response.get("refresh_token")
    expires = response.get("expires_in")
    scopes = set((response.get("scope") or session.get("scopes", "")).split())
    if not access or not refresh or not isinstance(expires, int) or expires < 60:
        raise RuntimeError("OAuth refresh response is incomplete")
    if "chatgpt.tokens.use.direct" not in scopes:
        raise RuntimeError("OAuth refresh response lacks the ChatGPT plan scope")
    updated = dict(session)
    updated.update({
        "access-token": access,
        "refresh-token": refresh,
        "expires-at": str(now + expires),
        "earliest-refresh-at": str(int(response.get("earliest_refresh_at", 0))),
        "scopes": " ".join(sorted(scopes)),
    })
    return updated


def service_account_ssl() -> ssl.SSLContext:
    ca = os.path.join(SERVICE_ACCOUNT, "ca.crt")
    return ssl.create_default_context(cafile=ca if os.path.exists(ca) else None)


def kubernetes_request(method: str, path: str, payload: dict | None = None) -> dict:
    root = SERVICE_ACCOUNT
    namespace = os.environ["POD_NAMESPACE"]
    with open(os.path.join(root, "token"), encoding="utf-8") as token_file:
        token = token_file.read().strip()
    url = "https://kubernetes.default.svc/api/v1/namespaces/" + urllib.parse.quote(namespace) + path
    body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/merge-patch+json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, context=service_account_ssl(), timeout=15) as response:
            text = response.read()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Kubernetes API HTTP {error.code}") from None
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Kubernetes API request failed: {type(error).__name__}") from None


def post_refresh(session: dict[str, str]) -> dict:
    form = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "client_id": session["client-id"],
        "refresh_token": session["refresh-token"],
        "resource": RESOURCE,
    }).encode()
    request = urllib.request.Request(TOKEN_ENDPOINT, data=form,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"OAuth refresh HTTP {error.code}") from None
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"OAuth refresh failed: {type(error).__name__}") from None


def decode_session(secret: dict) -> dict[str, str]:
    data = secret.get("data", {})
    try:
        return {key: base64.b64decode(value).decode("utf-8") for key, value in data.items()}
    except Exception:
        raise RuntimeError("OAuth session Secret data is invalid") from None


def patch_session(secret_name: str, secret: dict, expected_refresh_token: str,
                 session: dict[str, str]) -> None:
    encoded = {key: base64.b64encode(value.encode()).decode("ascii") for key, value in session.items()}
    path = "/secrets/" + urllib.parse.quote(secret_name)
    patch = {"metadata": {"resourceVersion": secret.get("metadata", {}).get("resourceVersion", "")},
             "data": encoded}
    try:
        kubernetes_request("PATCH", path, patch)
        return
    except RuntimeError:
        # A connection error can happen after the API server committed the patch.
        # Re-read before retrying so we never overwrite a newer user authorization.
        latest = kubernetes_request("GET", path)
        current = decode_session(latest)
        if (current.get("refresh-token") == session.get("refresh-token")
                and current.get("access-token") == session.get("access-token")):
            return
        if current.get("refresh-token") != expected_refresh_token:
            raise RuntimeError("OAuth session changed while refreshing") from None
        patch["metadata"]["resourceVersion"] = latest.get("metadata", {}).get("resourceVersion", "")
        kubernetes_request("PATCH", path, patch)


def refresh_once(now: int | None = None) -> str:
    now = int(time.time()) if now is None else now
    name = os.environ["CHATGPT_OAUTH_SESSION_SECRET"]
    secret = kubernetes_request("GET", "/secrets/" + urllib.parse.quote(name))
    session = decode_session(secret)
    if not refresh_due(session, now):
        return "not-due"
    token_response = post_refresh(session)
    updated = rotated_session(session, token_response, now)
    patch_session(name, secret, session.get("refresh-token", ""), updated)
    return "refreshed"


def main() -> int:
    try:
        result = refresh_once()
        print("ChatGPT OAuth session refresh " + result)
        return 0
    except (KeyError, RuntimeError, ValueError) as error:
        # All helpers use fixed, non-secret error descriptions.
        print("ChatGPT OAuth session refresh failed: " + str(error), file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
