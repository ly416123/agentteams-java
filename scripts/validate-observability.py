#!/usr/bin/env python3
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "deploy" / "kind-observability.yaml"


def fail(message):
    print(f"OBSERVABILITY_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    if not MANIFEST.exists():
        fail("manifest does not exist")
    try:
        resources = [item for item in yaml.safe_load_all(MANIFEST.read_text()) if item]
    except Exception as exc:
        fail(f"cannot parse manifest: {exc}")
    by_name = {(item.get("kind"), item.get("metadata", {}).get("name")): item for item in resources}
    required = [
        ("ConfigMap", "prometheus-config"),
        ("Deployment", "prometheus"),
        ("Service", "prometheus"),
        ("PersistentVolumeClaim", "prometheus-data"),
        ("Deployment", "grafana"),
        ("Service", "grafana"),
        ("PersistentVolumeClaim", "grafana-data"),
        ("ConfigMap", "grafana-datasources"),
    ]
    for key in required:
        if key not in by_name:
            fail(f"missing {key[0]}/{key[1]}")
    config = by_name[("ConfigMap", "prometheus-config")]["data"].get("prometheus.yml", "")
    for target in ("agentteams-agentteams-java-control-plane:8080", "agentteams-agentteams-java-gateway:8080"):
        if target not in config:
            fail(f"Prometheus config missing target {target}")
    if "/actuator/prometheus" not in config:
        fail("Prometheus config must scrape /actuator/prometheus")
    datasource = by_name[("ConfigMap", "grafana-datasources")]["data"].get("datasources.yaml", "")
    if "http://prometheus:9090" not in datasource:
        fail("Grafana datasource must point to Prometheus")
    print("OBSERVABILITY_OK")


if __name__ == "__main__":
    main()
