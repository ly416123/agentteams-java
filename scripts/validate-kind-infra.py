#!/usr/bin/env python3
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "deploy" / "kind-dev-infra.yaml"


def fail(message: str) -> None:
    print(f"KIND_INFRA_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def resources():
    try:
        return [item for item in yaml.safe_load_all(MANIFEST.read_text(encoding="utf-8")) if item]
    except Exception as exc:
        fail(f"cannot parse {MANIFEST}: {exc}")


def find(kind, name):
    for item in resources():
        if item.get("kind") == kind and item.get("metadata", {}).get("name") == name:
            return item
    fail(f"missing {kind}/{name}")


def has_persistent_data(workload):
    spec = workload.get("spec", {}).get("template", {}).get("spec", {})
    volumes = spec.get("volumes", [])
    if any("emptyDir" in volume for volume in volumes):
        return False
    return bool(workload.get("spec", {}).get("volumeClaimTemplates"))


def main():
    if not MANIFEST.exists():
        fail("manifest does not exist")
    for name in ("postgresql", "nats", "minio"):
        workload = find("StatefulSet", name)
        if not has_persistent_data(workload):
            fail(f"{name} must use a volumeClaimTemplate instead of emptyDir")
    nats = find("StatefulSet", "nats")
    args = nats["spec"]["template"]["spec"]["containers"][0].get("args", [])
    if "--store_dir" not in args or "/data/jetstream" not in args:
        fail("NATS must persist JetStream data under /data/jetstream")
    minio = find("StatefulSet", "minio")
    minio_image = minio["spec"]["template"]["spec"]["containers"][0].get("image")
    expected_minio_image = "docker.io/minio/minio:RELEASE.2024-11-07T00-52-20Z"
    if minio_image != expected_minio_image:
        fail(f"MinIO must use {expected_minio_image}, got {minio_image}")
    minio_bootstrap = find("Job", "minio-bucket-bootstrap")
    minio_client_image = minio_bootstrap["spec"]["template"]["spec"]["containers"][0].get("image")
    expected_minio_client_image = "docker.io/minio/mc:RELEASE.2025-07-21T05-28-08Z"
    if minio_client_image != expected_minio_client_image:
        fail(f"MinIO bootstrap must use {expected_minio_client_image}, got {minio_client_image}")
    bootstrap = find("Job", "nats-stream-bootstrap")
    bootstrap_args = " ".join(
        bootstrap["spec"]["template"]["spec"]["containers"][0].get("args", []))
    for subject in ("task.events.*", "agent.events.*", "control.events", "deadletter.events"):
        if subject not in bootstrap_args:
            fail(f"nats-stream-bootstrap must create a stream bound to {subject}; "
                 f"without it the outbox relay cannot publish those aggregates and the "
                 f"dead-letter fallback loops forever")
    # AgentScope is embedded in the Java Worker through the Harness API. The
    # Kind infra manifest must not start a second native AgentScope service;
    # WorkerResourceFactory owns the actual Worker Deployment and its limits.
    for item in resources():
        if item.get("metadata", {}).get("name") in {"agentscope", "qwenpaw"}:
            fail("Kind infra must not deploy a second native runtime service")
    print("KIND_INFRA_OK")


if __name__ == "__main__":
    main()
