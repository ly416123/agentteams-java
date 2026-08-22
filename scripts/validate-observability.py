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
        resources = [item for item in yaml.safe_load_all(MANIFEST.read_text(encoding="utf-8")) if item]
    except Exception as exc:
        fail(f"cannot parse manifest: {exc}")
    by_name = {(item.get("kind"), item.get("metadata", {}).get("name")): item for item in resources}
    required = [
        ("ConfigMap", "prometheus-config"),
        ("Deployment", "prometheus"),
        ("Service", "prometheus"),
        ("ServiceAccount", "prometheus"),
        ("Role", "prometheus-discovery"),
        ("RoleBinding", "prometheus-discovery"),
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
    alerts = by_name[("ConfigMap", "prometheus-config")]["data"].get("agentteams-alerts.yml", "")
    for job, label in (("control-plane", "agentteams-control-plane"), ("gateway", "agentteams-gateway")):
        if f"job_name: {job}" not in config or "kubernetes_sd_configs:" not in config:
            fail(f"Prometheus config missing Kubernetes discovery for {job}")
        if label not in config:
            fail(f"Prometheus config missing pod label {label}")
    if "/actuator/prometheus" not in config:
        fail("Prometheus config must scrape /actuator/prometheus")
    for required in (
            "AgentTeamsOutboxStalled", "agentteams_outbox_oldest_pending_age_seconds",
            "AgentTeamsGatewayConnectionChurn", "agentteams_gateway_connections_replaced_total"):
        if required not in alerts:
            fail(f"alert rules missing {required}")
    dashboard = by_name[("ConfigMap", "grafana-dashboards")]["data"].get("agentteams-overview.json", "")
    for required in ("agentteams_outbox_oldest_pending_age_seconds", "agentteams_gateway_connections_replaced_total"):
        if required not in dashboard:
            fail(f"Grafana dashboard missing {required}")
    deployment = by_name[("Deployment", "prometheus")]
    pod_spec = deployment["spec"]["template"]["spec"]
    if pod_spec.get("serviceAccountName") != "prometheus":
        fail("Prometheus deployment must use the discovery service account")
    datasource = by_name[("ConfigMap", "grafana-datasources")]["data"].get("datasources.yaml", "")
    if "http://prometheus:9090" not in datasource:
        fail("Grafana datasource must point to Prometheus")
    print("OBSERVABILITY_OK")


if __name__ == "__main__":
    main()
