#!/usr/bin/env python3
"""Guard the External Secrets resolver and least-privilege chart contract."""

import json
import shutil
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
HELM = shutil.which("helm")


def read(path):
    return path.read_text(encoding="utf-8")


def render_chart(*args):
    if HELM is None:
        raise unittest.SkipTest("helm is unavailable")
    result = subprocess.run(
        [HELM, "template", "agentteams", str(CHART), "--namespace", "agentteams", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc]


class BatchBSecretContractTest(unittest.TestCase):
    def test_kind_acceptance_exercises_real_external_secrets_convergence(self):
        script_path = ROOT / "scripts/run-kind-external-secrets.py"
        self.assertTrue(script_path.is_file())
        script = read(script_path)
        for required in (
            "external-secrets.io/v1",
            "kind: SecretStore",
            "kind: ExternalSecret",
            "observedGeneration",
            "Ready",
            "KIND_EXTERNAL_SECRETS_OK",
        ):
            self.assertIn(required, script)

        workflow = read(ROOT / ".github/workflows/ci.yml")
        self.assertRegex(
            workflow,
            r"external-secrets/external-secrets[\s\S]*--version [0-9]+\.[0-9]+\.[0-9]+",
        )
        self.assertIn("run-kind-external-secrets.py", workflow)

    def test_external_secret_reader_uses_current_crd_api_version(self):
        reader = read(
            ROOT
            / "control-plane/src/main/java/io/agentteams/controlplane/security/ExternalSecretStatusReader.java"
        )
        self.assertIn('withVersion("v1")', reader)
        self.assertNotIn('withVersion("v1beta1")', reader)

    def test_backend_is_explicit_and_secret_free_by_default(self):
        values = yaml.safe_load(read(CHART / "values.yaml"))
        self.assertEqual(
            values["controlPlane"]["security"]["secretResolver"]["backend"],
            "VALIDATION_ONLY",
        )
        schema = json.loads(read(CHART / "values.schema.json"))
        self.assertEqual(
            schema["properties"]["controlPlane"]["properties"]["security"][
                "properties"
            ]["secretResolver"]["properties"]["backend"]["enum"],
            ["VALIDATION_ONLY", "KUBERNETES", "EXTERNAL_SECRETS"],
        )
        template = read(CHART / "templates/control-plane.yaml")
        self.assertIn("AGENTTEAMS_SECURITY_SECRET_RESOLVER_BACKEND", template)
        self.assertNotRegex(template, r"(?i)value:\s*['\"]?(?:password|token|private[_-]?key)['\"]?")

    def test_external_secrets_rbac_is_namespace_scoped_and_read_only(self):
        rbac = read(CHART / "templates/rbac.yaml")
        self.assertIn('apiGroups: ["external-secrets.io"]', rbac)
        self.assertIn("- externalsecrets", rbac)
        self.assertIn("- externalsecrets/status", rbac)
        self.assertIn("- secrets", rbac)
        self.assertIn('verbs: ["get"]', rbac)
        secret_block = rbac.split("- secrets", 1)[1].split("{{- end }}", 1)[0]
        self.assertNotIn('verbs: ["list", "watch"]', secret_block)
        self.assertNotIn("ClusterRole", rbac)

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_external_secrets_backend_renders_only_conditional_secret_get(self):
        docs = render_chart("--set", "controlPlane.security.secretResolver.backend=EXTERNAL_SECRETS")
        roles = [doc for doc in docs if doc.get("kind") == "Role"]
        control_plane = next(role for role in roles if role["metadata"]["name"].endswith("-control-plane-team-sync"))
        rules = control_plane["rules"]
        secret_rules = [rule for rule in rules if "secrets" in rule.get("resources", [])]
        self.assertEqual(secret_rules, [{"apiGroups": [""], "resources": ["secrets"], "verbs": ["get"]}])

    def test_reader_contract_does_not_expose_secret_values(self):
        reader = read(ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/KubernetesSecretMetadataReader.java")
        self.assertIn("getData().keySet()", reader)
        self.assertNotIn("getData().values()", reader)
        self.assertNotIn("return secret", reader)


if __name__ == "__main__":
    unittest.main()
