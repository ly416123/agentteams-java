#!/usr/bin/env python3
from pathlib import Path
import sys
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
    team_crd = (ROOT / "deploy/helm/agentteams-java/crds/teams.yaml").read_text(encoding="utf-8")
    for required in ("allowedRuntimes", "requiredCapabilities", "x-kubernetes-list-type: set"):
        if required not in team_crd:
            fail(f"Team CRD schema missing {required}")
    worker_crd = (ROOT / "deploy/helm/agentteams-java/crds/workers.yaml").read_text(encoding="utf-8")
    if "tlsSecret" not in worker_crd:
        fail("Worker CRD schema missing tlsSecret")
    qwenpaw_mock_manifest = ROOT / "deploy/kind-qwenpaw-openai-mock.yaml"
    qwenpaw_mock_script = ROOT / "scripts/qwenpaw-openai-mock.py"
    if not qwenpaw_mock_manifest.exists() or not qwenpaw_mock_script.exists():
        fail("Kind QwenPaw deterministic model mock assets are missing")
    qwenpaw_mock_manifest_text = qwenpaw_mock_manifest.read_text(encoding="utf-8")
    qwenpaw_mock_script_text = qwenpaw_mock_script.read_text(encoding="utf-8")
    for required in ("qwenpaw-openai-mock", "python:3.12-alpine", "server.py"):
        if required not in qwenpaw_mock_manifest_text:
            fail(f"QwenPaw mock manifest missing {required}")
    for required in ("/chat/completions", "KIND_LEASE_RECOVERY_OK", "stream"):
        if required not in qwenpaw_mock_script_text:
            fail(f"QwenPaw mock server missing {required}")
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
    auth_filter = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthenticationFilter.java").read_text(encoding="utf-8")
    auth_policy = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthorizationPolicy.java").read_text(encoding="utf-8")
    authorization = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/AuthorizationService.java").read_text(encoding="utf-8")
    task_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java").read_text(encoding="utf-8")
    agent_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/AgentController.java").read_text(encoding="utf-8")
    config_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ConfigController.java").read_text(encoding="utf-8")
    config_file_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ConfigFileController.java").read_text(encoding="utf-8")
    for required in ("ApiAuthorizationPolicy", "SC_FORBIDDEN", "Permission.TASK_READ", "Permission.CONFIG_WRITE",
                     "requireScope", "request.spec", "task.specJson", "request.metadata", "snapshot.manifestJson",
                     "requireSnapshotScope"):
        if required not in auth_filter + auth_policy + authorization + task_controller + agent_controller + config_controller + config_file_controller:
            fail(f"OIDC API authorization missing {required}")
    print("KIND_MANIFESTS_OK")


if __name__ == "__main__":
    main()
