#!/usr/bin/env python3
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "deploy" / "kind-observability.yaml"
RUNBOOK = ROOT / "deploy" / "production" / "observability-runbook.md"
NETWORK_POLICY = ROOT / "deploy" / "helm" / "agentteams-java" / "templates" / "networkpolicy.yaml"


def fail(message):
    print(f"OBSERVABILITY_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    if not MANIFEST.exists():
        fail("manifest does not exist")
    if not RUNBOOK.exists():
        fail("production observability runbook does not exist")
    if not NETWORK_POLICY.exists():
        fail("Helm network policy does not exist")
    network_policy = NETWORK_POLICY.read_text(encoding="utf-8")
    for required in ("observability.tracing.enabled", "otel-collector", "4318"):
        if required not in network_policy:
            fail(f"Helm network policy missing OTLP egress rule {required}")
    otel_collector_validator = ROOT / "scripts" / "validate-kind-otel.py"
    if not otel_collector_validator.exists():
        fail("Kind OTel collector validator does not exist")
    runbook = RUNBOOK.read_text(encoding="utf-8")
    for required in (
            "AgentTeamsOutboxStalled", "AgentTeamsOutboxDeadLettered",
            "AgentTeamsLeaseRecovery", "AgentTeamsGatewayRejectedEvents",
            "run-kind-postgres-restore.py", "OTLP", "traceparent"):
        if required not in runbook:
            fail(f"production observability runbook missing {required}")
    for module in ("control-plane", "agent-gateway"):
        application = ROOT / module / "src" / "main" / "resources" / "application.yml"
        if not application.exists():
            fail(f"{module} application configuration does not exist")
        application_text = application.read_text(encoding="utf-8")
        for required in (
                "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED",
                "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY",
                "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT",
                "traceId", "spanId", "type: w3c"):
            if required not in application_text:
                fail(f"{module} tracing configuration missing {required}")
    worker_example = ROOT / "deploy" / "examples" / "agentscope-worker.yaml"
    worker_text = worker_example.read_text(encoding="utf-8")
    for required in (
            "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED",
            "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY",
            "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT",
            "AGENTTEAMS_OBSERVABILITY_SERVICE_NAME"):
        if required not in worker_text:
            fail(f"Worker tracing configuration missing {required}")
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
        ("ServiceAccount", "kube-state-metrics"),
        ("Role", "kube-state-metrics"),
        ("RoleBinding", "kube-state-metrics"),
        ("Deployment", "kube-state-metrics"),
        ("Service", "kube-state-metrics"),
        ("PersistentVolumeClaim", "prometheus-data"),
        ("Deployment", "grafana"),
        ("Service", "grafana"),
        ("PersistentVolumeClaim", "grafana-data"),
        ("ConfigMap", "grafana-datasources"),
        ("ConfigMap", "otel-collector-config"),
        ("Deployment", "otel-collector"),
        ("Service", "otel-collector"),
    ]
    for key in required:
        if key not in by_name:
            fail(f"missing {key[0]}/{key[1]}")
    config = by_name[("ConfigMap", "prometheus-config")]["data"].get("prometheus.yml", "")
    prometheus_config = yaml.safe_load(config)
    alerts = by_name[("ConfigMap", "prometheus-config")]["data"].get("agentteams-alerts.yml", "")
    for job, label in (("control-plane", "agentteams-control-plane"), ("gateway", "agentteams-gateway")):
        if f"job_name: {job}" not in config or "kubernetes_sd_configs:" not in config:
            fail(f"Prometheus config missing Kubernetes discovery for {job}")
        if label not in config:
            fail(f"Prometheus config missing pod label {label}")
        scrape_job = next((item for item in prometheus_config["scrape_configs"]
                           if item.get("job_name") == job), {})
        if not any(item.get("source_labels") == ["__meta_kubernetes_pod_phase"]
                   and item.get("regex") == "Running" and item.get("action") == "keep"
                   for item in scrape_job.get("relabel_configs", [])):
            fail(f"Prometheus {job} scrape must keep only Running pods")
    ksm = by_name[("Deployment", "kube-state-metrics")]["spec"]["template"]["spec"]
    ksm_container = ksm.get("containers", [{}])[0]
    if ksm.get("serviceAccountName") != "kube-state-metrics" or \
            "--resources=deployments" not in ksm_container.get("args", []) or \
            "--namespaces=agentteams" not in ksm_container.get("args", []):
        fail("kube-state-metrics must be scoped to agentteams Deployments")
    ksm_role = by_name[("Role", "kube-state-metrics")].get("rules", [])
    if ksm_role != [{"apiGroups": ["apps"], "resources": ["deployments"],
                     "verbs": ["get", "list", "watch"]}]:
        fail("kube-state-metrics RBAC must be read-only for Deployments")
    ksm_job = next((item for item in prometheus_config["scrape_configs"]
                    if item.get("job_name") == "kube-state-metrics"), {})
    if "kube-state-metrics.agentteams.svc:8080" not in str(ksm_job) or \
            "kube_deployment_status_replicas_available" not in str(ksm_job):
        fail("Prometheus must scrape kube-state-metrics deployment availability")
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
    collector = by_name[("ConfigMap", "otel-collector-config")]["data"].get("config.yaml", "")
    for required in ("health_check", "otlp", "4318", "debug", "file/ci",
                     "/var/lib/otelcol/traces.jsonl"):
        if required not in collector:
            fail(f"OTLP collector configuration missing {required}")
    print("OBSERVABILITY_OK")


if __name__ == "__main__":
    main()
