#!/usr/bin/env python3
"""Verify that a Kind MinIO mirror restores object content losslessly."""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
import uuid


MINIO_IMAGE = "minio/mc:RELEASE.2025-07-21T05-28-08Z"
MINIO_BUCKET = "agentteams"
OBJECT_CONTENT = b"agentteams-kind-minio-restore-v1\n"


def fail(message: str) -> None:
    raise RuntimeError(message)


def kubectl_command(namespace: str, *args: str) -> list[str]:
    return ["kubectl", "-n", namespace, *args]


def run_kubectl(namespace: str, *args: str, input_text: str | None = None) -> str:
    command = kubectl_command(namespace, *args)
    result = subprocess.run(command, check=False, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", input=input_text)
    if result.returncode != 0:
        fail(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def helper_manifest(namespace: str, pod_name: str) -> str:
    return f"""apiVersion: v1
kind: Pod
metadata:
  name: {pod_name}
  namespace: {namespace}
  labels:
    app.kubernetes.io/part-of: agentteams
    agentteams.io/purpose: minio-restore-validation
spec:
  restartPolicy: Never
  containers:
    - name: mc
      image: {MINIO_IMAGE}
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", "sleep 600"]
      env:
        - name: MINIO_ROOT_USER
          valueFrom:
            secretKeyRef:
              name: agentteams-storage
              key: access-key
        - name: MINIO_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: agentteams-storage
              key: secret-key
      volumeMounts:
        - name: backup
          mountPath: /backup
  volumes:
    - name: backup
      emptyDir: {{}}
"""


def helper_exec(namespace: str, pod_name: str, statement: str) -> str:
    command = (
        "set -eu; "
        "mc alias set local http://minio:9000 \"$MINIO_ROOT_USER\" "
        "\"$MINIO_ROOT_PASSWORD\" >/dev/null; "
        f"{statement}"
    )
    return run_kubectl(namespace, "exec", pod_name, "--", "sh", "-c", command)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default=os.environ.get("AGENTTEAMS_NAMESPACE", "agentteams"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    pod_name = f"minio-restore-{uuid.uuid4().hex[:12]}"
    object_prefix = f"ci-recovery/{uuid.uuid4().hex}"
    object_key = f"{object_prefix}/restore-check.bin"
    expected_sha256 = hashlib.sha256(OBJECT_CONTENT).hexdigest()
    pod_created = False
    try:
        run_kubectl(args.namespace, "get", "statefulset/minio")
        run_kubectl(args.namespace, "apply", "-f", "-",
                    input_text=helper_manifest(args.namespace, pod_name))
        pod_created = True
        run_kubectl(args.namespace, "wait", f"--for=condition=Ready", f"pod/{pod_name}", "--timeout=120s")

        helper_exec(
            args.namespace,
            pod_name,
            "printf '%b' 'agentteams-kind-minio-restore-v1\\n' > /backup/source.bin; "
            "sha256sum /backup/source.bin > /backup/expected.sha256; "
            f"mc cp /backup/source.bin local/{MINIO_BUCKET}/{object_key} >/dev/null; "
            f"mc mirror --overwrite local/{MINIO_BUCKET}/{object_prefix} /backup/snapshot",
        )
        helper_exec(
            args.namespace,
            pod_name,
            f"mc rm --recursive --force local/{MINIO_BUCKET}/{object_prefix}; "
            f"if mc stat local/{MINIO_BUCKET}/{object_key} >/dev/null 2>&1; then "
            "echo 'object still exists after simulated loss' >&2; exit 1; fi",
        )
        helper_exec(
            args.namespace,
            pod_name,
            f"mc mirror --overwrite /backup/snapshot local/{MINIO_BUCKET}/{object_prefix}; "
            f"mc cp local/{MINIO_BUCKET}/{object_key} /backup/restored.bin >/dev/null",
        )
        restored_sha256 = helper_exec(args.namespace, pod_name, "sha256sum /backup/restored.bin").split()[0]
        if restored_sha256 != expected_sha256:
            fail(f"restored object checksum mismatch: expected={expected_sha256} actual={restored_sha256}")
        print(f"KIND_MINIO_RESTORE_OK key={object_key} sha256={restored_sha256} bytes={len(OBJECT_CONTENT)}")
        return 0
    finally:
        if pod_created:
            try:
                helper_exec(args.namespace, pod_name,
                            f"mc rm --recursive --force local/{MINIO_BUCKET}/{object_prefix} || true")
            except RuntimeError as cleanup_error:
                print(f"failed to remove MinIO validation object: {cleanup_error}", file=sys.stderr)
            try:
                run_kubectl(args.namespace, "delete", "pod", pod_name, "--ignore-not-found", "--wait=true",
                            "--timeout=60s")
            except RuntimeError as cleanup_error:
                print(f"failed to remove MinIO validation pod: {cleanup_error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_MINIO_RESTORE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
