#!/usr/bin/env python3
"""Verify PostgreSQL object references exist in MinIO with matching metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import subprocess
import sys
import uuid


MINIO_IMAGE = "minio/mc:RELEASE.2025-07-21T05-28-08Z"
MINIO_BUCKET = "agentteams"
ARTIFACT_CONTENT = b"agentteams-object-reference-artifact-v1\n"
CONFIG_CONTENT = b"agentteams-object-reference-config-v1\n"


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


def sql(namespace: str, postgres_pod: str, statement: str) -> str:
    return run_kubectl(namespace, "exec", postgres_pod, "--", "psql", "-U", "agentteams",
                       "-d", "agentteams", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def helper_manifest(namespace: str, pod_name: str) -> str:
    return f"""apiVersion: v1
kind: Pod
metadata:
  name: {pod_name}
  namespace: {namespace}
  labels:
    app.kubernetes.io/part-of: agentteams
    agentteams.io/purpose: object-reference-integrity
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


def reference_rows(namespace: str, postgres_pod: str) -> list[dict]:
    rows = sql(namespace, postgres_pod, """
        select coalesce(json_agg(ref_json order by kind, reference_id), '[]'::json)
          from (
            select 'artifact' as kind, id::text as reference_id,
                   json_build_object('kind', 'artifact', 'id', id::text,
                                     'storage_key', storage_key,
                                     'size_bytes', size_bytes,
                                     'checksum', sha256) as ref_json
              from artifacts
            union all
            select 'config_file' as kind, id::text as reference_id,
                   json_build_object('kind', 'config_file', 'id', id::text,
                                     'storage_key', storage_key,
                                     'size_bytes', size_bytes,
                                     'checksum', checksum) as ref_json
              from config_files
          ) ref_rows;
    """)
    parsed = json.loads(rows or "[]")
    if not isinstance(parsed, list):
        fail(f"unexpected object reference query result: {parsed!r}")
    return parsed


def normalized_checksum(value: str) -> str:
    checksum = value.strip().lower()
    return checksum.removeprefix("sha256:")


def verify_reference(namespace: str, pod_name: str, reference: dict) -> tuple[int, str]:
    object_key = str(reference.get("storage_key", ""))
    if not object_key:
        fail(f"{reference.get('kind')} {reference.get('id')} has an empty storage_key")
    quoted_source = shlex.quote(f"local/{MINIO_BUCKET}/{object_key}")
    output = helper_exec(
        namespace,
        pod_name,
        f"mc cp {quoted_source} /backup/reference.bin >/dev/null; "
        "printf '%s|' \"$(wc -c < /backup/reference.bin)\"; "
        "sha256sum /backup/reference.bin | cut -d ' ' -f1",
    )
    parts = output.split("|", 1)
    if len(parts) != 2:
        fail(f"unexpected MinIO checksum result for {object_key}: {output!r}")
    actual_size = int(parts[0].strip())
    actual_checksum = normalized_checksum(parts[1])
    expected_size = int(reference["size_bytes"])
    expected_checksum = normalized_checksum(str(reference["checksum"]))
    if actual_size != expected_size or actual_checksum != expected_checksum:
        fail(
            f"object metadata mismatch for {reference['kind']} {reference['id']} "
            f"key={object_key}: expected size={expected_size} sha256={expected_checksum} "
            f"actual size={actual_size} sha256={actual_checksum}"
        )
    return actual_size, actual_checksum


def insert_test_references(namespace: str, postgres_pod: str, artifact_id: str,
                           config_snapshot_id: str, config_file_id: str,
                           task_id: str, attempt_id: str, artifact_key: str,
                           artifact_checksum: str, artifact_size: int,
                           config_key: str, config_checksum: str, config_size: int) -> None:
    statement = f"""
        insert into artifacts
            (id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
             sha256, status, metadata, created_at, updated_at, version)
        values ('{artifact_id}', '{task_id}', '{attempt_id}', 'ci-object-integrity-artifact',
                '{artifact_key}', 'application/octet-stream', {artifact_size},
                '{artifact_checksum}', 'COMPLETED', '{{}}'::jsonb, now(), now(), 0);
        insert into config_snapshots
            (id, subject, version, manifest, checksum, actor, created_at)
        values ('{config_snapshot_id}', 'ci-object-integrity-{config_snapshot_id}', 1,
                '{{}}'::jsonb, 'ci-object-integrity-snapshot-{config_snapshot_id}',
                'ci-object-integrity', now());
        insert into config_files
            (id, snapshot_id, path, storage_key, checksum, size_bytes, content_type)
        values ('{config_file_id}', '{config_snapshot_id}', 'ci-object-integrity.yaml',
                '{config_key}', '{config_checksum}', {config_size}, 'application/yaml');
    """
    sql(namespace, postgres_pod, statement)


def delete_test_references(namespace: str, postgres_pod: str, artifact_id: str,
                           config_snapshot_id: str, config_file_id: str) -> None:
    statement = f"""
        delete from config_files where id = '{config_file_id}';
        delete from config_snapshots where id = '{config_snapshot_id}';
        delete from artifacts where id = '{artifact_id}';
    """
    sql(namespace, postgres_pod, statement)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default=os.environ.get("AGENTTEAMS_NAMESPACE", "agentteams"))
    parser.add_argument("--postgres-pod", default=os.environ.get("AGENTTEAMS_POSTGRES_POD", "postgresql-0"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    pod_name = f"object-integrity-{uuid.uuid4().hex[:12]}"
    prefix = f"ci-recovery/object-integrity/{uuid.uuid4().hex}"
    artifact_key = f"{prefix}/artifact.bin"
    config_key = f"{prefix}/config.yaml"
    artifact_id = str(uuid.uuid4())
    config_snapshot_id = str(uuid.uuid4())
    config_file_id = str(uuid.uuid4())
    artifact_checksum = hashlib.sha256(ARTIFACT_CONTENT).hexdigest()
    config_checksum = hashlib.sha256(CONFIG_CONTENT).hexdigest()
    inserted = False
    pod_created = False
    try:
        run_kubectl(args.namespace, "get", "statefulset/minio")
        task_row = sql(args.namespace, args.postgres_pod,
                       "select id::text from tasks order by created_at desc limit 1;")
        attempt_row = sql(args.namespace, args.postgres_pod,
                          "select id::text from task_attempts order by created_at desc limit 1;")
        if not task_row or not attempt_row:
            fail("Kind object reference validation requires an existing task and task attempt")
        task_id = task_row.splitlines()[0].strip()
        attempt_id = attempt_row.splitlines()[0].strip()

        run_kubectl(args.namespace, "apply", "-f", "-",
                    input_text=helper_manifest(args.namespace, pod_name))
        pod_created = True
        run_kubectl(args.namespace, "wait", "--for=condition=Ready", f"pod/{pod_name}", "--timeout=120s")
        helper_exec(
            args.namespace,
            pod_name,
            "printf '%b' 'agentteams-object-reference-artifact-v1\\n' > /backup/artifact-source.bin; "
            "printf '%b' 'agentteams-object-reference-config-v1\\n' > /backup/config-source.bin; "
            f"mc cp /backup/artifact-source.bin {shlex.quote(f'local/{MINIO_BUCKET}/{artifact_key}')} >/dev/null; "
            f"mc cp /backup/config-source.bin {shlex.quote(f'local/{MINIO_BUCKET}/{config_key}')} >/dev/null",
        )
        insert_test_references(
            args.namespace, args.postgres_pod, artifact_id, config_snapshot_id, config_file_id,
            task_id, attempt_id, artifact_key, artifact_checksum, len(ARTIFACT_CONTENT),
            config_key, config_checksum, len(CONFIG_CONTENT),
        )
        inserted = True
        references = reference_rows(args.namespace, args.postgres_pod)
        validated = 0
        for reference in references:
            verify_reference(args.namespace, pod_name, reference)
            validated += 1

        helper_exec(args.namespace, pod_name,
                    f"mc rm --force {shlex.quote(f'local/{MINIO_BUCKET}/{artifact_key}')} >/dev/null")
        missing_detected = False
        try:
            verify_reference(
                args.namespace,
                pod_name,
                {"kind": "artifact", "id": artifact_id, "storage_key": artifact_key,
                 "size_bytes": len(ARTIFACT_CONTENT), "checksum": artifact_checksum},
            )
        except RuntimeError:
            missing_detected = True
        if not missing_detected:
            fail("object reference validation did not detect a deleted MinIO object")

        helper_exec(
            args.namespace,
            pod_name,
            f"mc cp /backup/artifact-source.bin {shlex.quote(f'local/{MINIO_BUCKET}/{artifact_key}')} >/dev/null",
        )
        for reference in reference_rows(args.namespace, args.postgres_pod):
            verify_reference(args.namespace, pod_name, reference)
        print(f"KIND_OBJECT_REFERENCE_INTEGRITY_OK references={validated} negative_missing_object=detected")
        return 0
    finally:
        if inserted:
            try:
                delete_test_references(args.namespace, args.postgres_pod,
                                       artifact_id, config_snapshot_id, config_file_id)
            except RuntimeError as cleanup_error:
                print(f"failed to remove object reference test rows: {cleanup_error}", file=sys.stderr)
        if pod_created:
            try:
                helper_exec(args.namespace, pod_name,
                            f"mc rm --recursive --force {shlex.quote(f'local/{MINIO_BUCKET}/{prefix}')} || true")
            except RuntimeError as cleanup_error:
                print(f"failed to remove object reference test objects: {cleanup_error}", file=sys.stderr)
            try:
                run_kubectl(args.namespace, "delete", "pod", pod_name, "--ignore-not-found",
                            "--wait=true", "--timeout=60s")
            except RuntimeError as cleanup_error:
                print(f"failed to remove object reference validation Pod: {cleanup_error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_OBJECT_REFERENCE_INTEGRITY_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
