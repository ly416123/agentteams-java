#!/usr/bin/env python3
"""Validate the signed, digest-pinned release manifest used for promotion."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
from pathlib import Path
from typing import Any


COMPONENTS = ("control-plane", "gateway", "operator", "worker")
ENVIRONMENTS = {"staging", "production"}
SHA256_IMAGE = re.compile(r"^[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


class ManifestError(ValueError):
    """Raised when a release manifest violates the promotion contract."""


def required(mapping: dict[str, Any], key: str, path: str) -> Any:
    if key not in mapping:
        raise ManifestError(f"missing {path}")
    return mapping[key]


def https_reference(value: Any, path: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{path} must be a non-blank HTTPS URL")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ManifestError(f"{path} must be a credential-free HTTPS URL")


def validate_manifest(data: Any, expected_git_sha: str | None = None,
                     expected_environment: str | None = None) -> None:
    if not isinstance(data, dict):
        raise ManifestError("manifest must be a JSON object")

    if required(data, "schema_version", "schema_version") != 1:
        raise ManifestError("schema_version must be 1")

    git_sha = required(data, "git_sha", "git_sha")
    if not isinstance(git_sha, str) or not GIT_SHA.fullmatch(git_sha):
        raise ManifestError("git_sha must be 40 lowercase hexadecimal characters")
    if expected_git_sha and git_sha != expected_git_sha:
        raise ManifestError("git_sha does not match the requested commit")

    chart_version = required(data, "chart_version", "chart_version")
    if not isinstance(chart_version, str) or not SEMVER.fullmatch(chart_version):
        raise ManifestError("chart_version must be a stable x.y.z version")

    environment = required(data, "environment", "environment")
    if environment not in ENVIRONMENTS:
        raise ManifestError(f"environment must be one of {sorted(ENVIRONMENTS)}")
    if expected_environment and environment != expected_environment:
        raise ManifestError("environment does not match the requested environment")

    components = required(data, "components", "components")
    if not isinstance(components, dict) or set(components) != set(COMPONENTS):
        raise ManifestError(f"components must contain exactly {list(COMPONENTS)}")
    for component_name in COMPONENTS:
        component = components[component_name]
        if not isinstance(component, dict):
            raise ManifestError(f"components.{component_name} must be an object")
        image = required(component, "image", f"components.{component_name}.image")
        if not isinstance(image, str) or not SHA256_IMAGE.fullmatch(image):
            raise ManifestError(
                f"components.{component_name}.image must be pinned by a lowercase sha256 digest"
            )
        for field in ("sbom", "signature", "provenance"):
            https_reference(required(component, field, f"components.{component_name}.{field}"),
                            f"components.{component_name}.{field}")

    signature = required(data, "manifest_signature", "manifest_signature")
    if not isinstance(signature, dict):
        raise ManifestError("manifest_signature must be an object")
    for field in ("bundle", "issuer", "identity"):
        https_reference(required(signature, field, f"manifest_signature.{field}"),
                        f"manifest_signature.{field}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--git-sha")
    parser.add_argument("--environment", choices=sorted(ENVIRONMENTS))
    args = parser.parse_args()
    try:
        data = json.loads(args.manifest.read_text(encoding="utf-8"))
        validate_manifest(data, args.git_sha, args.environment)
    except (OSError, json.JSONDecodeError, ManifestError) as exc:
        print(f"RELEASE_MANIFEST_FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    print("RELEASE_MANIFEST_OK")


if __name__ == "__main__":
    main()
