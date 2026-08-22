#!/usr/bin/env python3
"""Validate the non-secret Matrix AppService registration contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
REGISTRATION = ROOT / "deploy/production/matrix-appservice-registration.example.yaml"


def fail(message: str) -> None:
    print(f"MATRIX_REGISTRATION_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not REGISTRATION.exists():
        fail(f"missing {REGISTRATION}")
    text = REGISTRATION.read_text(encoding="utf-8")
    data = yaml.safe_load(text)
    if not isinstance(data, dict):
        fail("registration must be a YAML mapping")

    for key in ("id", "url", "as_token", "hs_token", "sender_localpart", "namespaces"):
        if not str(data.get(key, "")).strip():
            fail(f"missing {key}")
    if not str(data["url"]).startswith(("http://", "https://")):
        fail("url must be an HTTP(S) endpoint")
    for token_name in ("as_token", "hs_token"):
        if data[token_name] != "REPLACE_WITH_EXTERNAL_SECRET":
            fail(f"{token_name} must remain an external-secret placeholder")

    namespaces = data["namespaces"]
    if not isinstance(namespaces, dict):
        fail("namespaces must be a mapping")
    for namespace_name in ("users", "aliases", "rooms"):
        entries = namespaces.get(namespace_name)
        if not isinstance(entries, list) or not entries:
            fail(f"namespaces.{namespace_name} must contain at least one rule")
        for entry in entries:
            if not isinstance(entry, dict) or not str(entry.get("regex", "")).strip():
                fail(f"namespaces.{namespace_name} contains an invalid rule")

    if re.search(r"(?i)(password|api[_-]?key|private[_-]?key)\s*:\s*[^#\n]+", text):
        fail("registration must not contain inline credentials")
    print("MATRIX_REGISTRATION_OK")


if __name__ == "__main__":
    main()
