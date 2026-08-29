#!/usr/bin/env python3
from pathlib import Path
import sys
import urllib.parse
import yaml

ROOT = Path(__file__).resolve().parents[1]


def fail(message):
    print(f"KIND_MANIFESTS_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    kind_config = yaml.safe_load((ROOT / "deploy/kind-config.yaml").read_text(encoding="utf-8"))
    mappings = kind_config.get("nodes", [])[0].get("extraPortMappings", [])
    pairs = {(mapping.get("hostPort"), mapping.get("containerPort")) for mapping in mappings}
    if (8080, 30080) not in pairs or (8443, 30443) not in pairs:
        fail("Kind config must map host 8080/8443 to ingress NodePorts 30080/30443")
    ingress_path = ROOT / "deploy/kind-ingress.yaml"
    if not ingress_path.exists():
        fail("kind ingress manifest does not exist")
    resources = [item for item in yaml.safe_load_all(ingress_path.read_text(encoding="utf-8")) if item]
    if len([item for item in resources if item.get("kind") == "Ingress"]) != 1:
        fail("expected one local Ingress resource")
    text = ingress_path.read_text(encoding="utf-8")
    for service in ("agentteams-agentteams-java-control-plane", "agentteams-agentteams-java-gateway",
                    "qwenpaw", "prometheus", "grafana"):
        if service not in text:
            fail(f"Ingress missing backend service {service}")
    installer = ROOT / "deploy/install-kind-dev.sh"
    if not installer.exists():
        fail("kind installer does not exist")
    installer_text = installer.read_text(encoding="utf-8")
    order = ["kind-dev-infra.yaml", "kind-observability.yaml", "kind-ingress.yaml",
             "build-images.sh", "crds/teams.yaml", "crds/workers.yaml", "helm upgrade --install agentteams",
             "bootstrap-kind-qwenpaw-worker.sh"]
    positions = [installer_text.find(value) for value in order]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        fail("installer steps must be ordered infra, observability, ingress, images, CRD, Helm, Worker bootstrap")
    for required in (
            "rollout restart deployment/prometheus",
            "rollout status deployment/otel-collector",
            "deployment/agentteams-agentteams-java-control-plane",
            "deployment/agentteams-agentteams-java-gateway"):
        if required not in installer_text:
            fail(f"installer must refresh stable-tagged workload {required}")
    build_script = ROOT / "deploy/build-images.sh"
    if not build_script.exists():
        fail("build image script does not exist")
    build_text = build_script.read_text(encoding="utf-8")
    for base_image in ("maven:3.9.16-eclipse-temurin-17", "eclipse-temurin:17-jre"):
        if base_image not in build_text:
            fail(f"build image script must prepare base image {base_image}")
    if "docker image inspect" not in build_text or "docker tag" not in build_text:
        fail("build image script must support inspecting and tagging proxy-fetched base images")
    worker_bootstrap = ROOT / "deploy/bootstrap-kind-qwenpaw-worker.sh"
    if not worker_bootstrap.exists():
        fail("qwenpaw worker bootstrap script does not exist")
    worker_text = worker_bootstrap.read_text(encoding="utf-8")
    for required in ("Idempotency-Key", "api/v1/agents", "deploy/examples/qwenpaw-worker.yaml",
                     "kubectl apply -f -", "AGENTTEAMS_AGENT_ID"):
        if required not in worker_text:
            fail(f"qwenpaw worker bootstrap script missing {required}")
    mtls_script = ROOT / "deploy/bootstrap-kind-mtls.sh"
    if not mtls_script.exists():
        fail("Kind mTLS bootstrap script does not exist")
    mtls_text = mtls_script.read_text(encoding="utf-8")
    for required in ("openssl", "agentteams-gateway-mtls", "agentteams-worker-mtls",
                     "gateway.tls.enabled=true", "AGENTTEAMS_GATEWAY_TLS_ENABLED"):
        if required not in mtls_text:
            fail(f"Kind mTLS bootstrap script missing {required}")
    smoke_script = ROOT / "scripts/smoke-kind-qwenpaw-deepseek.sh"
    if not smoke_script.exists():
        fail("qwenpaw DeepSeek smoke script does not exist")
    smoke_text = smoke_script.read_text(encoding="utf-8")
    for required in ("QWENPAW_DEEPSEEK_SMOKE_OK", "kubectl logs", "Task result"):
        if required not in smoke_text:
            fail(f"qwenpaw DeepSeek smoke script must verify {required}")
    prometheus_rule_validator = ROOT / "scripts/validate-prometheus-rule.py"
    if not prometheus_rule_validator.exists():
        fail("PrometheusRule validator does not exist")
    team_crd = (ROOT / "deploy/helm/agentteams-java/crds/teams.yaml").read_text(encoding="utf-8")
    for required in ("allowedRuntimes", "requiredCapabilities", "x-kubernetes-list-type: set"):
        if required not in team_crd:
            fail(f"Team CRD schema missing {required}")
    worker_crd = (ROOT / "deploy/helm/agentteams-java/crds/workers.yaml").read_text(encoding="utf-8")
    for required in ("tlsSecret", "specDigest", "configRevision", "secretGeneration",
                     "observedSpecDigest", "observedRuntime", "observedConfigRevision",
                     "observedSecretGeneration"):
        if required not in worker_crd:
            fail(f"Worker CRD schema missing {required}")
    qwenpaw_mock_manifest = ROOT / "deploy/kind-qwenpaw-openai-mock.yaml"
    qwenpaw_mock_script = ROOT / "scripts/qwenpaw-openai-mock.py"
    if not qwenpaw_mock_manifest.exists() or not qwenpaw_mock_script.exists():
        fail("Kind QwenPaw deterministic model mock assets are missing")
    qwenpaw_mock_manifest_text = qwenpaw_mock_manifest.read_text(encoding="utf-8")
    qwenpaw_mock_script_text = qwenpaw_mock_script.read_text(encoding="utf-8")
    for required in ("qwenpaw-openai-mock", "python:3.12-alpine", "server.py"):
        if required not in qwenpaw_mock_manifest_text:
            fail(f"QwenPaw mock manifest missing {required}")
    for required in ("/chat/completions", "/debug/delay", "KIND_LEASE_RECOVERY_OK", "KIND_WORKER_RESTART_OK",
                     "QWENPAW_MOCK_RESPONSE_DELAY_SECONDS", "stream"):
        if required not in qwenpaw_mock_script_text:
            fail(f"QwenPaw mock server missing {required}")
    otel_validator = ROOT / "scripts/validate-kind-otel.py"
    if not otel_validator.exists():
        fail("Kind OTel validator does not exist")
    observability_manifest = (ROOT / "deploy/kind-observability.yaml").read_text(encoding="utf-8")
    for required in ("otel-collector", "otel/opentelemetry-collector-contrib:0.123.0"):
        if required not in observability_manifest:
            fail(f"Kind OTel collector manifest missing {required}")
    control_plane = (ROOT / "deploy/helm/agentteams-java/templates/control-plane.yaml").read_text(encoding="utf-8")
    for required in ("controlPlaneServiceAccountName", "AGENTTEAMS_TEAM_SYNC_ENABLED",
                     "AGENTTEAMS_TEAM_SYNC_NAMESPACE", "AGENTTEAMS_SECURITY_OIDC_ENABLED",
                     "AGENTTEAMS_SECURITY_OIDC_JWK_SET_URI",
                     "automountServiceAccountToken: {{ .Values.controlPlane.teamSync.enabled }}"):
        if required not in control_plane:
            fail(f"Control Plane security/team sync manifest missing {required}")
    rbac = (ROOT / "deploy/helm/agentteams-java/templates/rbac.yaml").read_text(encoding="utf-8")
    for required in ("control-plane-team-sync", 'verbs: ["get", "list", "watch"]'):
        if required not in rbac:
            fail(f"Team sync RBAC missing {required}")
    helpers = (ROOT / "deploy/helm/agentteams-java/templates/_helpers.tpl").read_text(encoding="utf-8")
    for required in ("operatorServiceAccountName", "gatewayServiceAccountName"):
        if required not in helpers:
            fail(f"Dedicated service-account helper missing {required}")
    operator = (ROOT / "deploy/helm/agentteams-java/templates/operator.yaml").read_text(encoding="utf-8")
    operator_pdb = (ROOT / "deploy/helm/agentteams-java/templates/poddisruptionbudget.yaml").read_text(encoding="utf-8")
    kind_values = (ROOT / "deploy/helm/kind-values.yaml").read_text(encoding="utf-8")
    if "replicas: {{ .Values.operator.replicas }}" not in operator:
        fail("Operator replicas must be configurable from Helm values")
    if "-operator" not in operator_pdb or "app.kubernetes.io/name: agentteams-operator" not in operator_pdb:
        fail("Operator must have a PodDisruptionBudget")
    if "operator:\n  replicas: 2" not in kind_values:
        fail("Kind HA values must run two Operator replicas")
    if 'serviceAccountName: {{ include "agentteams-java.operatorServiceAccountName" .' not in operator:
        fail("Operator must use its dedicated service account")
    gateway_manifest = (ROOT / "deploy/helm/agentteams-java/templates/gateway.yaml").read_text(encoding="utf-8")
    if ('serviceAccountName: {{ include "agentteams-java.gatewayServiceAccountName" .' not in gateway_manifest
            or "automountServiceAccountToken: false" not in gateway_manifest):
        fail("Gateway must use a dedicated tokenless service account")
    if 'name: {{ include "agentteams-java.operatorServiceAccountName" .' not in rbac:
        fail("Operator RBAC must bind the dedicated service account")
    operator_rbac = rbac.split("{{- if .Values.controlPlane.teamSync.enabled }}", 1)[0]
    if "kind: ClusterRole" in rbac or "kind: ClusterRoleBinding" in rbac:
        fail("Operator RBAC must be namespace-scoped")
    for required in ("kind: Role", "kind: RoleBinding", "namespace: {{ .Release.Namespace }}"):
        if required not in operator_rbac:
            fail(f"Operator namespace RBAC missing {required}")
    oidc_installer = (ROOT / "deploy/install-kind-oidc.sh")
    keycloak_bootstrap = (ROOT / "deploy/bootstrap-kind-keycloak.sh")
    keycloak_manifest = (ROOT / "deploy/kind-keycloak.yaml")
    oidc_smoke = (ROOT / "scripts/smoke-kind-oidc.sh")
    rotation_smoke = (ROOT / "scripts/smoke-kind-oidc-rotation.sh")
    matrix_installer = (ROOT / "deploy/install-kind-matrix.sh")
    matrix_manifest = (ROOT / "deploy/kind-tuwunel.yaml")
    matrix_smoke = (ROOT / "scripts/smoke-kind-matrix.sh")
    for required_path in (oidc_installer, keycloak_bootstrap, keycloak_manifest,
                          oidc_smoke, rotation_smoke, matrix_installer,
                          matrix_manifest, matrix_smoke):
        if not required_path.exists():
            fail(f"OIDC Kind asset missing {required_path.relative_to(ROOT)}")
    oidc_workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    api_contract = ROOT / "scripts/validate-api-contract.py"
    if not api_contract.exists() or "validate-api-contract.py" not in oidc_workflow:
        fail("CI must execute the API contract validation")
    task_api_smoke = ROOT / "scripts/smoke-kind-task-api.sh"
    if not task_api_smoke.exists():
        fail("Kind Task and Artifact API smoke script does not exist")
    task_api_smoke_text = task_api_smoke.read_text(encoding="utf-8")
    for required in ("approve", "pause", "reject", "/artifacts", "KIND_TASK_API_OK"):
        if required not in task_api_smoke_text:
            fail(f"Kind Task and Artifact API smoke missing {required}")
    if "smoke-kind-task-api.sh" not in oidc_workflow:
        fail("CI must execute the Kind Task and Artifact API smoke")
    config_rollback_script = ROOT / "scripts/run-kind-config-rollback.py"
    if not config_rollback_script.exists():
        fail("Kind config rollback acceptance script does not exist")
    config_rollback_text = config_rollback_script.read_text(encoding="utf-8")
    for required in ("AGENTTEAMS_CONTROL_PLANE_URL", "AGENTTEAMS_AGENT_ID",
                     "/api/v1/config/snapshots", "/rollback", "APPLIED",
                     "KIND_CONFIG_ROLLBACK_OK", '"model": "agentteams-kind-mock"'):
        if required not in config_rollback_text:
            fail(f"Kind config rollback acceptance missing {required}")
    if "run-kind-config-rollback.py" not in oidc_workflow:
        fail("CI must execute the Kind config rollback acceptance")
    if "validate-prometheus-rule.py /tmp/agentteams-prometheusrule.yaml" not in oidc_workflow:
        fail("CI must validate the rendered PrometheusRule")
    nats_recovery_script = ROOT / "scripts/run-kind-nats-outbox-recovery.py"
    if not nats_recovery_script.exists():
        fail("Kind NATS Outbox recovery script does not exist")
    nats_recovery_text = nats_recovery_script.read_text(encoding="utf-8")
    for required in ("statefulset", "PENDING", "PUBLISHED", "KIND_NATS_OUTBOX_RECOVERY_OK"):
        if required not in nats_recovery_text:
            fail(f"Kind NATS Outbox recovery script missing {required}")
    if "run-kind-nats-outbox-recovery.py --agent-id" not in oidc_workflow:
        fail("CI must execute the Kind NATS Outbox recovery test")
    idempotency_script = ROOT / "scripts/run-kind-idempotency.py"
    if not idempotency_script.exists():
        fail("Kind task idempotency script does not exist")
    idempotency_text = idempotency_script.read_text(encoding="utf-8")
    for required in ("idempotency_keys", "task_attempts", "KIND_IDEMPOTENCY_OK"):
        if required not in idempotency_text:
            fail(f"Kind task idempotency validation missing {required}")
    if "run-kind-idempotency.py" not in oidc_workflow:
        fail("CI must execute the Kind task idempotency test")
    gateway_replay_script = ROOT / "scripts/run-kind-gateway-replay.py"
    if not gateway_replay_script.exists():
        fail("Kind Gateway replay recovery script does not exist")
    gateway_replay_text = gateway_replay_script.read_text(encoding="utf-8")
    for required in ("gateway_commands", "gateway_command_deliveries", "delete", "gateway_pod_names",
                     "KIND_GATEWAY_POD_FAILOVER_OK", "KIND_GATEWAY_REPLAY_OK"):
        if required not in gateway_replay_text:
            fail(f"Kind Gateway replay recovery script missing {required}")
    if "run-kind-gateway-replay.py --agent-id" not in oidc_workflow:
        fail("CI must execute the Kind Gateway replay recovery test")
    worker_restart_script = ROOT / "scripts/run-kind-worker-restart.py"
    if not worker_restart_script.exists():
        fail("Kind in-flight Worker restart recovery script does not exist")
    worker_restart_text = worker_restart_script.read_text(encoding="utf-8")
    for required in ("RUNNING", "delete", "agent_leases", "clear_mock_delay_runtime", "KIND_WORKER_RESTART_OK"):
        if required not in worker_restart_text:
            fail(f"Kind in-flight Worker restart script missing {required}")
    if "run-kind-worker-restart.py --agent-id" not in oidc_workflow:
        fail("CI must execute the Kind in-flight Worker restart recovery test")
    postgres_restore_script = ROOT / "scripts/run-kind-postgres-restore.py"
    if not postgres_restore_script.exists():
        fail("Kind PostgreSQL restore validation script does not exist")
    postgres_restore_text = postgres_restore_script.read_text(encoding="utf-8")
    for required in ("pg_dump", "pg_restore", "agentteams_restore", "KIND_POSTGRES_RESTORE_OK"):
        if required not in postgres_restore_text:
            fail(f"Kind PostgreSQL restore validation missing {required}")
    if "run-kind-postgres-restore.py" not in oidc_workflow:
        fail("CI must execute the Kind PostgreSQL restore validation")
    minio_restore_script = ROOT / "scripts/run-kind-minio-restore.py"
    if not minio_restore_script.exists():
        fail("Kind MinIO restore validation script does not exist")
    minio_restore_text = minio_restore_script.read_text(encoding="utf-8")
    for required in ("minio/mc", "mc mirror", "sha256", "KIND_MINIO_RESTORE_OK"):
        if required not in minio_restore_text:
            fail(f"Kind MinIO restore validation missing {required}")
    if "run-kind-minio-restore.py" not in oidc_workflow:
        fail("CI must execute the Kind MinIO restore validation")
    object_reference_script = ROOT / "scripts/run-kind-object-reference-integrity.py"
    if not object_reference_script.exists():
        fail("Kind object reference integrity script does not exist")
    object_reference_text = object_reference_script.read_text(encoding="utf-8")
    for required in ("artifacts", "config_files", "config_uploads", "COMPLETED", "PENDING", "DELETED",
                     "sha256", "negative_missing_object",
                     "KIND_OBJECT_REFERENCE_INTEGRITY_OK"):
        if required not in object_reference_text:
            fail(f"Kind object reference integrity validation missing {required}")
    if "run-kind-object-reference-integrity.py" not in oidc_workflow:
        fail("CI must execute the Kind object reference integrity validation")
    if ("Configure deterministic QwenPaw model" not in oidc_workflow
            or "kind-qwenpaw-openai-mock.yaml" not in oidc_workflow
            or "agentteams-kind-mock" not in oidc_workflow
            or "/api/models/custom-providers" not in oidc_workflow):
        fail("CI recovery job must configure the deterministic QwenPaw model mock")
    oidc_values = (ROOT / "deploy/helm/kind-oidc-values.yaml").read_text(encoding="utf-8")
    matrix_values = (ROOT / "deploy/helm/kind-matrix-values.yaml").read_text(encoding="utf-8")
    if "kind-oidc:" not in oidc_workflow:
        fail("CI must define the isolated kind-oidc job")
    combined = (oidc_installer.read_text(encoding="utf-8")
                + oidc_smoke.read_text(encoding="utf-8")
                + rotation_smoke.read_text(encoding="utf-8")
                + matrix_installer.read_text(encoding="utf-8")
                + matrix_smoke.read_text(encoding="utf-8")
                + oidc_values + matrix_values
                + matrix_manifest.read_text(encoding="utf-8") + control_plane)
    for required in ("KIND_OIDC_SMOKE_OK", "OIDC_JWKS_ROTATION_OK", "components",
                     "KIND_MATRIX_APPSERVICE_OK", "KIND_MATRIX_E2E_OK", "KIND_MATRIX_LIFECYCLE_E2E_OK",
                     "KIND_MATRIX_DUPLICATE_MUTATION_OK",
                     "AGENTTEAMS_SECURITY_OIDC_ENABLED",
                     "AGENTTEAMS_MATRIX_APPSERVICE_AUTH_ENABLED", "hs_token"):
        if required not in combined:
            fail(f"OIDC Kind automation missing {required}")
    operator_app = (ROOT / "operator/src/main/java/io/agentteams/operator/AgentTeamsOperatorApplication.java").read_text(encoding="utf-8")
    for required in ("AGENTTEAMS_OPERATOR_NAMESPACE", "settingNamespace"):
        if required not in operator_app:
            fail(f"Operator namespace scoping missing {required}")
    network_policy = (ROOT / "deploy/helm/agentteams-java/templates/networkpolicy.yaml").read_text(encoding="utf-8")
    kind_values = (ROOT / "deploy/helm/kind-values.yaml").read_text(encoding="utf-8")
    for required in ("kubernetesApiCIDR", "kubernetesApiEndpointCIDR", "kubernetesApiEndpointPort",
                     "kubernetesApiAllowAllEgress"):
        if required not in kind_values:
            fail(f"Kind values missing {required}")
    if ("kubernetesApiCIDR" not in network_policy or "kubernetesApiEndpointCIDR" not in network_policy
            or "ipBlock:" not in network_policy or "port: 443" not in network_policy
            or "kubernetesApiEndpointPort" not in network_policy
            or "kubernetesApiAllowAllEgress" not in network_policy):
        fail("Control Plane NetworkPolicy must allow Kubernetes API Service and endpoint traffic")
    gateway = (ROOT / "deploy/helm/agentteams-java/templates/gateway.yaml").read_text(encoding="utf-8")
    for required in ("AGENTTEAMS_GATEWAY_GRPC_TLS_ENABLED", "gateway.tls.enabled", "gateway.tls.secretName",
                     "secret.reloader.stakater.com/reload"):
        if required not in gateway:
            fail(f"Gateway mTLS manifest missing {required}")
    worker_factory = (ROOT / "operator/src/main/java/io/agentteams/operator/WorkerResourceFactory.java").read_text(encoding="utf-8")
    if "secret.reloader.stakater.com/reload" not in worker_factory:
        fail("Worker TLS deployment must expose the Secret rotation annotation")
    worker_example = (ROOT / "deploy/examples/qwenpaw-worker.yaml").read_text(encoding="utf-8")
    for required in ("AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED",
                     "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY",
                     "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT",
                     "AGENTTEAMS_OBSERVABILITY_SERVICE_NAME"):
        if required not in worker_example:
            fail(f"Worker example missing OTel configuration {required}")
    worker_document = yaml.safe_load(worker_example)
    worker_spec = worker_document.get("spec", {}) if isinstance(worker_document, dict) else {}
    worker_env = worker_spec.get("env", {}) if isinstance(worker_spec, dict) else {}
    if worker_document.get("kind") != "Worker" or not isinstance(worker_env, dict):
        fail("Worker example must define a Worker spec.env object")
    manifest_url = str(worker_env.get("AGENTTEAMS_CONFIG_MANIFEST_BASE_URL", "")).strip()
    parsed_manifest_url = urllib.parse.urlsplit(manifest_url)
    if (parsed_manifest_url.scheme not in {"http", "https"}
            or not parsed_manifest_url.hostname
            or parsed_manifest_url.username is not None
            or parsed_manifest_url.password is not None
            or parsed_manifest_url.fragment):
        fail("Worker config manifest URL must be a credential-free http(s) URL")
    if str(worker_env.get("AGENTTEAMS_QUOTA_REMOTE_ENABLED", "")).strip().lower() != "false":
        fail("Worker example must keep AGENTTEAMS_QUOTA_REMOTE_ENABLED=false by default")
    try:
        quota_timeout = int(str(worker_env.get("AGENTTEAMS_QUOTA_TIMEOUT_SECONDS", "")))
    except ValueError:
        fail("Worker quota timeout must be a positive integer")
    if quota_timeout <= 0:
        fail("Worker quota timeout must be a positive integer")
    scope_tenant = str(worker_env.get("AGENTTEAMS_SCOPE_TENANT", "")).strip()
    scope_project = str(worker_env.get("AGENTTEAMS_SCOPE_PROJECT", "")).strip()
    if bool(scope_tenant) != bool(scope_project):
        fail("Worker quota scope must provide tenant and project together")
    resource_binding_script = ROOT / "scripts/run-kind-resource-binding-ack.py"
    if not resource_binding_script.exists():
        fail("Kind resource binding ACK script does not exist")
    resource_binding_text = resource_binding_script.read_text(encoding="utf-8")
    for required in ("AGENTTEAMS_CONTROL_PLANE_URL", "AGENTTEAMS_AGENT_ID",
                     "validated_base_url", "validated_agent_id", "/api/v1/config/snapshots",
                     "resourceBindings", "KIND_RESOURCE_BINDING_ACK_OK",
                     "KIND_RESOURCE_BINDING_FAILURE_OK"):
        if required not in resource_binding_text:
            fail(f"Kind resource binding ACK script missing {required}")
    mcp_discovery_script = ROOT / "scripts/run-kind-mcp-discovery.py"
    if not mcp_discovery_script.exists():
        fail("Kind MCP discovery aggregation script does not exist")
    mcp_discovery_text = mcp_discovery_script.read_text(encoding="utf-8")
    for required in ("mcp_discovery_snapshots", "/discovery", "AVAILABLE", "UNKNOWN",
                     "KIND_MCP_DISCOVERY_OK"):
        if required not in mcp_discovery_text:
            fail(f"Kind MCP discovery script missing {required}")
    quota_step = oidc_workflow.find("python scripts/run-kind-quota-recovery.py")
    ack_step = oidc_workflow.find("python scripts/run-kind-resource-binding-ack.py")
    mcp_step = oidc_workflow.find("python scripts/run-kind-mcp-discovery.py")
    if quota_step < 0 or ack_step < 0 or quota_step >= ack_step:
        fail("Kind recovery must run quota recovery before resource binding ACK")
    if mcp_step < 0 or ack_step >= mcp_step:
        fail("Kind recovery must run MCP discovery aggregation after resource binding ACK")
    ack_step_start = oidc_workflow.rfind("- name:", 0, ack_step)
    ack_step_end = oidc_workflow.find("\n      - name:", ack_step)
    ack_block = oidc_workflow[ack_step_start:ack_step_end if ack_step_end >= 0 else None]
    for required in ("AGENTTEAMS_CONTROL_PLANE_URL", "AGENTTEAMS_AGENT_ID",
                     "--base-url", "--agent-id"):
        if required not in ack_block:
            fail(f"Kind resource binding ACK workflow step missing {required}")
    auth_filter = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthenticationFilter.java").read_text(encoding="utf-8")
    auth_policy = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthorizationPolicy.java").read_text(encoding="utf-8")
    authorization = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/AuthorizationService.java").read_text(encoding="utf-8")
    task_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java").read_text(encoding="utf-8")
    agent_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java").read_text(encoding="utf-8")
    config_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ConfigController.java").read_text(encoding="utf-8")
    config_file_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ConfigFileController.java").read_text(encoding="utf-8")
    artifact_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ArtifactController.java").read_text(encoding="utf-8")
    for required in ("ApiAuthorizationPolicy", "SC_FORBIDDEN", "Permission.TASK_READ", "Permission.CONFIG_WRITE",
                     "requireScope", "request.spec", "task.specJson", "request.metadata", "snapshot.manifestJson",
                     "requireSnapshotScope", "ArtifactController", "@PostMapping(\"/complete\")",
                     "Permission.ARTIFACT_WRITE"):
        if required not in (auth_filter + auth_policy + authorization + task_controller + agent_controller
                            + config_controller + config_file_controller + artifact_controller):
            fail(f"OIDC API authorization missing {required}")
    print("KIND_MANIFESTS_OK")


if __name__ == "__main__":
    main()
