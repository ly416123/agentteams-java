#!/usr/bin/env python3
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]


def fail(message):
    print(f"KIND_MANIFESTS_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    kind_config = yaml.safe_load((ROOT / "deploy/kind-config.yaml").read_text())
    mappings = kind_config.get("nodes", [])[0].get("extraPortMappings", [])
    pairs = {(mapping.get("hostPort"), mapping.get("containerPort")) for mapping in mappings}
    if (8080, 30080) not in pairs or (8443, 30443) not in pairs:
        fail("Kind config must map host 8080/8443 to ingress NodePorts 30080/30443")
    ingress_path = ROOT / "deploy/kind-ingress.yaml"
    if not ingress_path.exists():
        fail("kind ingress manifest does not exist")
    resources = [item for item in yaml.safe_load_all(ingress_path.read_text()) if item]
    if len([item for item in resources if item.get("kind") == "Ingress"]) != 1:
        fail("expected one local Ingress resource")
    text = ingress_path.read_text()
    for service in ("agentteams-agentteams-java-control-plane", "agentteams-agentteams-java-gateway",
                    "qwenpaw", "prometheus", "grafana"):
        if service not in text:
            fail(f"Ingress missing backend service {service}")
    installer = ROOT / "deploy/install-kind-dev.sh"
    if not installer.exists():
        fail("kind installer does not exist")
    installer_text = installer.read_text()
    order = ["kind-dev-infra.yaml", "kind-observability.yaml", "kind-ingress.yaml",
             "build-images.sh", "helm upgrade --install agentteams"]
    positions = [installer_text.find(value) for value in order]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        fail("installer steps must be ordered infra, observability, ingress, images, Helm")
    print("KIND_MANIFESTS_OK")


if __name__ == "__main__":
    main()
