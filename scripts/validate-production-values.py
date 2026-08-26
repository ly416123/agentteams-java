#!/usr/bin/env python3
"""Validate the committed production Helm values contract without secrets."""

from __future__ import annotations

import re
import sys
import urllib.parse
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / "deploy/helm/agentteams-java/values-production.example.yaml"


def fail(message: str) -> None:
    print(f"PRODUCTION_VALUES_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def required(mapping: dict, path: str):
    value = mapping
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            fail(f"missing {path}")
        value = value[part]
    return value


def validate_endpoint(value: str, path: str, schemes: set[str]) -> None:
    parsed_value = value[5:] if value.startswith("jdbc:") else value
    parsed = urllib.parse.urlsplit(parsed_value)
    if (parsed.scheme.lower() not in schemes or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.fragment or parsed.query):
        fail(f"{path} must be a credential-free endpoint using {sorted(schemes)}")


def main() -> None:
    if not VALUES.exists():
        fail(f"missing {VALUES}")
    text = VALUES.read_text(encoding="utf-8")
    if re.search(r"(?i)(password|api[_-]?key|token|private[_-]?key)\s*:\s*[^#\n]+", text):
        fail("production example must not contain inline credentials")
    data = yaml.safe_load(text)
    if not isinstance(data, dict):
        fail("values must be a YAML mapping")

    for path in ("controlPlane.security.apiEnabled", "controlPlane.security.oidc.enabled",
                 "gateway.tls.enabled", "storage.enabled", "availability.podDisruptionBudget.enabled",
                 "observability.tracing.enabled", "observability.serviceMonitor.enabled",
                 "observability.prometheusRule.enabled"):
        if required(data, path) is not True:
            fail(f"{path} must be true in the production example")

    for path in ("controlPlane.security.oidc.issuerUri", "controlPlane.security.oidc.jwkSetUri",
                 "controlPlane.security.oidc.audience", "controlPlane.matrix.appservice.hsTokenSecret",
                 "gateway.tls.secretName", "database.existingSecret", "storage.existingSecret",
                 "observability.tracing.otlpEndpoint"):
        value = required(data, path)
        if not isinstance(value, str) or not value.strip():
            fail(f"{path} must be non-blank")

    tls_mount = required(data, "gateway.tls.mountPath")
    for path in ("gateway.tls.certificateChainPath", "gateway.tls.privateKeyPath",
                 "gateway.tls.trustCertificateCollectionPath"):
        value = required(data, path)
        if not isinstance(value, str) or not value.startswith(f"{tls_mount}/"):
            fail(f"{path} must be mounted below gateway.tls.mountPath")

    for path in ("controlPlane.security.oidc.tenantClaim", "controlPlane.security.oidc.projectClaim",
                 "controlPlane.security.oidc.teamClaim", "controlPlane.security.oidc.permissionsClaim",
                 "controlPlane.matrix.appservice.hsTokenKey"):
        value = required(data, path)
        if not isinstance(value, str) or not value.strip():
            fail(f"{path} must be non-blank")

    for path, minimum in (("controlPlane.replicas", 2), ("gateway.replicas", 2),
                          ("operator.replicas", 2), ("availability.podDisruptionBudget.minAvailable", 1)):
        value = required(data, path)
        if not isinstance(value, int) or value < minimum:
            fail(f"{path} must be at least {minimum}")

    for path in ("database.existingSecret", "storage.existingSecret", "gateway.tls.secretName",
                 "controlPlane.matrix.appservice.hsTokenSecret"):
        if "example" in str(required(data, path)).lower():
            fail(f"{path} must reference an externally managed Secret")

    validate_endpoint(required(data, "database.jdbcUrl"), "database.jdbcUrl", {"postgresql"})
    validate_endpoint(required(data, "nats.url"), "nats.url", {"nats", "tls"})
    validate_endpoint(required(data, "gateway.env.AGENTTEAMS_GATEWAY_NATS_URL"),
                      "gateway.env.AGENTTEAMS_GATEWAY_NATS_URL", {"nats", "tls"})
    validate_endpoint(required(data, "storage.endpoint"), "storage.endpoint", {"http", "https"})
    validate_endpoint(required(data, "controlPlane.security.oidc.issuerUri"),
                      "controlPlane.security.oidc.issuerUri", {"http", "https"})
    validate_endpoint(required(data, "controlPlane.security.oidc.jwkSetUri"),
                      "controlPlane.security.oidc.jwkSetUri", {"http", "https"})
    validate_endpoint(required(data, "observability.tracing.otlpEndpoint"),
                      "observability.tracing.otlpEndpoint", {"http", "https"})

    images = required(data, "images")
    for component in ("controlPlane", "gateway", "operator"):
        image = required(images, component)
        if not isinstance(image, str) or not image.strip() or image.rstrip().lower().endswith(":latest"):
            fail(f"images.{component} must not use latest")

    if "RELEASE_TAG" not in text:
        fail("image pins must make the release tag replacement explicit")
    print("PRODUCTION_VALUES_OK")


if __name__ == "__main__":
    main()
