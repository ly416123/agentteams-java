#!/usr/bin/env python3
"""Validate production external egress and Secret-free network configuration."""

from __future__ import annotations

import ipaddress
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / "deploy/helm/agentteams-java/values-production.example.yaml"
DEPENDENCIES = ("postgresql", "nats", "objectStorage", "otlp", "oidc", "matrix", "modelProviders")


def fail(message: str) -> None:
    print(f"PRODUCTION_NETWORK_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def required(mapping: dict, path: str):
    value = mapping
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            fail(f"missing {path}")
        value = value[part]
    return value


def validate_data(data: dict) -> None:
    if not isinstance(data, dict):
        fail("values must be a YAML mapping")

    policy = required(data, "networkPolicy")
    if policy.get("enabled") is not True:
        fail("networkPolicy.enabled must be true")
    if policy.get("egressMode") != "CIDR":
        fail("production currently requires networkPolicy.egressMode=CIDR")
    if policy.get("allowPublicInternet") is True:
        fail("allowPublicInternet must remain false")
    if policy.get("kubernetesApiAllowAllEgress") is True:
        fail("kubernetesApiAllowAllEgress must remain false")

    external = policy.get("external")
    if not isinstance(external, dict):
        fail("networkPolicy.external must be a mapping")
    for dependency in DEPENDENCIES:
        targets = external.get(dependency)
        if not isinstance(targets, list):
            fail(f"networkPolicy.external.{dependency} must be a list")
        for index, target in enumerate(targets):
            if not isinstance(target, dict):
                fail(f"networkPolicy.external.{dependency}[{index}] must be a mapping")
            cidr = target.get("cidr")
            port = target.get("port")
            try:
                network = ipaddress.ip_network(str(cidr), strict=False)
            except ValueError:
                fail(f"networkPolicy.external.{dependency}[{index}].cidr must be valid CIDR")
            if str(network) in {"0.0.0.0/0", "::/0"}:
                fail(f"networkPolicy.external.{dependency}[{index}] must not allow the public internet")
            if not isinstance(port, int) or not 1 <= port <= 65535:
                fail(f"networkPolicy.external.{dependency}[{index}].port must be 1..65535")

    required_targets = {
        "postgresql": True,
        "nats": True,
        "objectStorage": required(data, "storage.enabled") is True,
        "otlp": required(data, "observability.tracing.enabled") is True,
        "oidc": required(data, "controlPlane.security.oidc.enabled") is True,
    }
    for dependency, enabled in required_targets.items():
        if enabled and not external[dependency]:
            fail(f"enabled dependency {dependency} must have at least one external CIDR target")

    legacy_oidc = policy.get("oidcEgressCIDR")
    if legacy_oidc:
        try:
            legacy_network = ipaddress.ip_network(str(legacy_oidc), strict=False)
        except ValueError:
            fail("networkPolicy.oidcEgressCIDR must be valid CIDR when present")
        if str(legacy_network) in {"0.0.0.0/0", "::/0"}:
            fail("networkPolicy.oidcEgressCIDR must not allow the public internet")

    print("PRODUCTION_NETWORK_OK")


def main() -> None:
    if not VALUES.exists():
        fail(f"missing {VALUES}")
    validate_data(yaml.safe_load(VALUES.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
