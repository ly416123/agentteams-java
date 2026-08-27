#!/usr/bin/env python3
"""Verify External Secrets Operator convergence without exposing secret values."""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from textwrap import dedent


def fail(message):
    print(f"KIND_EXTERNAL_SECRETS_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(*args, input_text=None, capture=False):
    try:
        return subprocess.run(
            ["kubectl", *args],
            input=input_text,
            text=True,
            check=True,
            capture_output=capture,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        detail = ""
        if capture and isinstance(error, subprocess.CalledProcessError):
            detail = error.stderr.strip()[:240]
        fail(f"kubectl {' '.join(args)} failed{': ' + detail if detail else ''}")


def resource_json(namespace, kind, name):
    result = run("-n", namespace, "get", kind, name, "-o", "json", capture=True)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        fail(f"{kind}/{name} returned invalid JSON: {error}")


def cleanup(namespace, names):
    for kind, name in (
        ("externalsecret", names["external_secret"]),
        ("secretstore", names["store"]),
        ("rolebinding", names["binding"]),
        ("role", names["role"]),
        ("serviceaccount", names["service_account"]),
        ("secret", names["target"]),
        ("secret", names["source"]),
    ):
        subprocess.run(
            ["kubectl", "-n", namespace, "delete", kind, name,
             "--ignore-not-found=true", "--wait=false"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )


def resource_names(run_id):
    suffix = re.sub(r"[^a-z0-9-]", "-", run_id.lower()).strip("-") or "local"
    suffix = suffix[:24].rstrip("-")
    return {
        "source": f"kind-eso-source-{suffix}",
        "target": f"kind-eso-target-{suffix}",
        "store": f"kind-eso-store-{suffix}",
        "role": f"kind-eso-reader-{suffix}",
        "binding": f"kind-eso-reader-{suffix}",
        "service_account": f"kind-eso-reader-{suffix}",
        "external_secret": f"kind-eso-{suffix}",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default=os.environ.get("KIND_NAMESPACE", "agentteams"))
    parser.add_argument(
        "--run-id",
        default=os.environ.get("KIND_EXTERNAL_SECRETS_RUN_ID", f"local-{os.getpid()}"),
    )
    args = parser.parse_args()
    if shutil.which("kubectl") is None:
        fail("kubectl is unavailable")

    names = resource_names(args.run_id)
    try:
        run("get", "crd", "externalsecrets.external-secrets.io", capture=True)
        run("-n", args.namespace, "get", "namespace", args.namespace, capture=True)

        # Keep the generated manifest in memory so the source value never
        # appears in process output or CI logs.
        source_manifest = run(
            "-n", args.namespace, "create", "secret", "generic", names["source"],
            "--from-literal=password=kind-external-secret-value",
            "--dry-run=client", "-o", "yaml", capture=True,
        ).stdout
        run("apply", "-f", "-", input_text=source_manifest)

        manifest = dedent(
            f"""
            apiVersion: v1
            kind: ServiceAccount
            metadata:
              name: {names['service_account']}
              namespace: {args.namespace}
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              name: {names['role']}
              namespace: {args.namespace}
            rules:
              - apiGroups: [""]
                resources: ["secrets"]
                resourceNames: ["{names['source']}"]
                verbs: ["get"]
              - apiGroups: ["authorization.k8s.io"]
                resources: ["selfsubjectrulesreviews"]
                verbs: ["create"]
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: RoleBinding
            metadata:
              name: {names['binding']}
              namespace: {args.namespace}
            subjects:
              - kind: ServiceAccount
                name: {names['service_account']}
                namespace: {args.namespace}
            roleRef:
              apiGroup: rbac.authorization.k8s.io
              kind: Role
              name: {names['role']}
            ---
            apiVersion: external-secrets.io/v1
            kind: SecretStore
            metadata:
              name: {names['store']}
              namespace: {args.namespace}
            spec:
              provider:
                kubernetes:
                  remoteNamespace: {args.namespace}
                  server:
                    url: https://kubernetes.default.svc
                    caProvider:
                      type: ConfigMap
                      name: kube-root-ca.crt
                      key: ca.crt
                  auth:
                    serviceAccount:
                      name: {names['service_account']}
            ---
            apiVersion: external-secrets.io/v1
            kind: ExternalSecret
            metadata:
              name: {names['external_secret']}
              namespace: {args.namespace}
            spec:
              refreshInterval: 1m
              secretStoreRef:
                name: {names['store']}
                kind: SecretStore
              target:
                name: {names['target']}
                creationPolicy: Owner
              data:
                - secretKey: password
                  remoteRef:
                    key: {names['source']}
                    property: password
            """
        ).lstrip()
        run("apply", "-f", "-", input_text=manifest)

        checks = [
            ("get", "externalsecret", names["external_secret"]),
            ("get", "secretstore", names["store"]),
        ]
        for check in checks:
            run("-n", args.namespace, *check, capture=True)

        run(
            "-n", args.namespace, "auth", "can-i", "get", f"secret/{names['source']}",
            "--as", f"system:serviceaccount:{args.namespace}:{names['service_account']}",
        )
        run(
            "-n", args.namespace, "wait", "--for=condition=Ready",
            f"externalsecret/{names['external_secret']}", "--timeout=180s",
        )

        external = resource_json(args.namespace, "externalsecret", names["external_secret"])
        secret = resource_json(args.namespace, "secret", names["target"])
        conditions = external.get("status", {}).get("conditions", [])
        ready = next((item for item in conditions if item.get("type") == "Ready"), None)
        if not ready or str(ready.get("status", "")).lower() != "true":
            fail("ExternalSecret did not converge to Ready=True")
        generation = str(external.get("metadata", {}).get("generation", ""))
        observed = str(external.get("status", {}).get("observedGeneration", ""))
        synced_resource_version = str(
            external.get("status", {}).get("syncedResourceVersion", "")
        )
        # ESO v1's published status schema uses syncedResourceVersion rather
        # than observedGeneration. Accept an observed generation when a
        # controller exposes it, otherwise require ESO's sync marker.
        if observed:
            if not generation or generation != observed:
                fail("ExternalSecret observedGeneration is stale")
        elif not synced_resource_version:
            fail("ExternalSecret has neither observedGeneration nor syncedResourceVersion")
        keys = secret.get("data", {})
        if "password" not in keys:
            fail("target Secret is missing the expected key")
        if secret.get("metadata", {}).get("resourceVersion", "") == "":
            fail("target Secret metadata has no resourceVersion")
        print(
            "KIND_EXTERNAL_SECRETS_OK "
            f"external_secret={names['external_secret']} "
            f"target_secret={names['target']} "
            f"observed_generation={observed or 'not-published'} "
            f"synced_resource_version={synced_resource_version or 'not-published'}"
        )
    finally:
        cleanup(args.namespace, names)


if __name__ == "__main__":
    main()
