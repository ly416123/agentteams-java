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
    bootstrap = find("Job", "nats-stream-bootstrap")
    bootstrap_args = " ".join(
        bootstrap["spec"]["template"]["spec"]["containers"][0].get("args", []))
    for subject in ("task.events.*", "agent.events.*", "control.events", "deadletter.events"):
        if subject not in bootstrap_args:
            fail(f"nats-stream-bootstrap must create a stream bound to {subject}; "
                 f"without it the outbox relay cannot publish those aggregates and the "
                 f"dead-letter fallback loops forever")
    qwenpaw = find("Deployment", "qwenpaw")
    image = qwenpaw["spec"]["template"]["spec"]["containers"][0].get("image")
    expected_image = "agentscope/qwenpaw@sha256:1132da56170f49c63aa583dd1ea3b09c19ce1ab76a1983813b8ad2f220771bcd"
    if image != expected_image:
        fail(f"QwenPaw image must be pinned to {expected_image}, got {image}")
    service = find("Service", "qwenpaw")
    ports = {port.get("port") for port in service["spec"].get("ports", [])}
    if 8088 not in ports:
        fail("QwenPaw Service must expose port 8088")
    mounts = {
        mount.get("mountPath")
        for mount in qwenpaw["spec"]["template"]["spec"]["containers"][0].get("volumeMounts", [])
    }
    expected = {"/app/working", "/app/working.secret", "/app/working.backups"}
    if not expected.issubset(mounts):
        fail(f"QwenPaw must mount {sorted(expected)}, got {sorted(mounts)}")
    for pvc in ("qwenpaw-working", "qwenpaw-secret", "qwenpaw-backups"):
        find("PersistentVolumeClaim", pvc)
    print("KIND_INFRA_OK")


if __name__ == "__main__":
    main()
