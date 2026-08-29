#!/usr/bin/env python3
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def render(*args):
    result = subprocess.run(
        ["helm", "template", "agentteams", str(CHART), "--namespace", "agentteams", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return [document for document in yaml.safe_load_all(result.stdout) if document]


class ConsoleDeploymentContractTest(unittest.TestCase):
    def test_console_switch_selects_build_and_ingress_mode(self):
        helper = ROOT / "deploy/console-deployment.sh"
        self.assertTrue(helper.exists())
        for present, expected_enabled, expected_ingress in (
            (False, "false", "kind-ingress-api-only.yaml"),
            (True, "true", "kind-ingress.yaml"),
        ):
            with self.subTest(console_source=present), tempfile.TemporaryDirectory() as directory:
                if present:
                    Path(directory, "console").mkdir()
                result = subprocess.run(
                    ["bash", "-c", "ROOT=\"$ROOT_UNDER_TEST\"; source \"$HELPER\"; printf '%s\\n%s' \"$CONSOLE_ENABLED\" \"$CONSOLE_INGRESS_MANIFEST\""],
                    env={
                        **os.environ,
                        "ROOT_UNDER_TEST": directory,
                        "HELPER": str(helper),
                    },
                    check=True,
                    capture_output=True,
                    text=True,
                )
                enabled, ingress = result.stdout.splitlines()
                self.assertEqual(enabled, expected_enabled)
                self.assertTrue(ingress.endswith(expected_ingress))

    def test_kind_installer_restarts_console_after_loading_image(self):
        installer = read("deploy/install-kind-dev.sh")
        restart = installer[installer.index('kubectl -n "$NAMESPACE" rollout restart'):]
        self.assertIn("deployment/agentteams-agentteams-java-console", restart)
        self.assertIn('"$CONSOLE_INGRESS_MANIFEST"', installer)

    def test_console_config_checksum_changes_with_runtime_config(self):
        base = render(
            "--set", "console.enabled=true",
            "--set", "ingress.enabled=true",
            "--set", "ingress.host=api.agentteams.localhost",
        )
        changed = render(
            "--set", "console.enabled=true",
            "--set", "console.config.oidcIssuer=https://idp.example.test/realms/agentteams",
            "--set", "ingress.enabled=true",
            "--set", "ingress.host=api.agentteams.localhost",
        )

        def checksum(documents):
            deployment = next(
                document
                for document in documents
                if document.get("kind") == "Deployment"
                and document.get("metadata", {}).get("name", "").endswith("-console")
            )
            return deployment["spec"]["template"]["metadata"]["annotations"]["checksum/config"]

        self.assertRegex(checksum(base), r"^[0-9a-f]{64}$")
        self.assertNotEqual(checksum(base), checksum(changed))

    def test_ci_disables_console_without_source_and_builds_it_when_present(self):
        workflow = read(".github/workflows/ci.yml")
        self.assertIn("console_enabled=false", workflow)
        self.assertIn('if [[ -d console ]]; then', workflow)
        self.assertIn('--set "console.enabled=$console_enabled"', workflow)

    def test_all_kind_helm_entrypoints_use_source_derived_console_switch(self):
        for path in (
            "deploy/install-kind-dev.sh",
            "deploy/install-kind-oidc.sh",
            "deploy/install-kind-matrix.sh",
            "deploy/bootstrap-kind-mtls.sh",
        ):
            source = read(path)
            with self.subTest(path=path):
                self.assertIn('source "$ROOT/deploy/console-deployment.sh"', source)
                self.assertIn('console.enabled=$CONSOLE_ENABLED', source)

    def test_dockerignore_excludes_local_sensitive_inputs(self):
        dockerignore = read(".dockerignore")
        for pattern in (".env*", "**/.env*", "**/.npmrc", "**/*.pem", "**/*.key", "**/id_rsa*"):
            self.assertIn(pattern, dockerignore)


if __name__ == "__main__":
    unittest.main()
