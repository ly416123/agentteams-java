#!/usr/bin/env python3
import subprocess
import shutil
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / "deploy/helm/l5-values.yaml"
PUBLIC_ISSUER = "http://192.168.122.55:30082/realms/agentteams"


class L5ConsoleOidcContractTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(VALUES.exists(), f"missing L5 Helm overlay: {VALUES}")

    def test_l5_oidc_issuer_is_browser_reachable_and_consistent(self):
        values = yaml.safe_load(VALUES.read_text(encoding="utf-8"))

        self.assertEqual(values["console"]["config"]["oidcIssuer"], PUBLIC_ISSUER)
        self.assertEqual(values["manager"]["security"]["issuerUri"], PUBLIC_ISSUER)
        self.assertEqual(
            values["controlPlane"]["security"]["oidc"]["issuerUri"],
            PUBLIC_ISSUER,
        )
        self.assertNotIn("127.0.0.1", values["console"]["config"]["oidcIssuer"])
        self.assertNotIn("127.0.0.1", values["manager"]["security"]["issuerUri"])
        self.assertNotIn(
            "127.0.0.1", values["controlPlane"]["security"]["oidc"]["issuerUri"]
        )

    def test_l5_jwks_stays_on_the_cluster_internal_service(self):
        values = yaml.safe_load(VALUES.read_text(encoding="utf-8"))

        self.assertEqual(
            values["manager"]["security"]["jwkSetUri"],
            "http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs",
        )
        self.assertEqual(
            values["controlPlane"]["security"]["oidc"]["jwkSetUri"],
            "http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs",
        )

    def test_l5_console_uses_the_fixed_public_node_port(self):
        values = yaml.safe_load(VALUES.read_text(encoding="utf-8"))

        self.assertEqual(values["console"]["service"]["type"], "NodePort")
        self.assertEqual(values["console"]["service"]["nodePort"], 30080)

    @unittest.skipUnless(shutil.which("helm"), "helm is unavailable")
    def test_l5_values_pass_chart_schema_validation(self):
        result = subprocess.run(
            [
                "helm",
                "lint",
                str(ROOT / "deploy/helm/agentteams-java"),
                "-f",
                str(VALUES),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_l5_keycloak_realm_uses_the_current_host_address(self):
        realm = yaml.safe_load(
            (ROOT / "deploy/keycloak/agentteams-realm.json").read_text(
                encoding="utf-8"
            )
        )
        client = next(
            client for client in realm["clients"]
            if client["clientId"] == "agentteams-api"
        )

        self.assertIn("http://192.168.122.55:30080/auth/callback", client["redirectUris"])
        self.assertIn("http://192.168.122.55:30080", client["webOrigins"])

    def test_l5_render_exposes_only_console_as_node_port(self):
        rendered = subprocess.run(
            [
                "helm",
                "template",
                "agentteams",
                str(ROOT / "deploy/helm/agentteams-java"),
                "-f",
                str(ROOT / "deploy/helm/kind-values.yaml"),
                "-f",
                str(ROOT / "deploy/helm/kind-oidc-values.yaml"),
                "-f",
                str(VALUES),
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        services = {
            document["metadata"]["name"]: document
            for document in yaml.safe_load_all(rendered)
            if document and document.get("kind") == "Service"
        }

        console = services["agentteams-agentteams-java-console"]
        self.assertEqual(console["spec"]["type"], "NodePort")
        self.assertEqual(console["spec"]["ports"][0]["nodePort"], 30080)
        self.assertEqual(
            services["agentteams-agentteams-java-control-plane"]["spec"].get(
                "type", "ClusterIP"
            ),
            "ClusterIP",
        )
        self.assertEqual(
            services["agentteams-agentteams-java-gateway"]["spec"].get(
                "type", "ClusterIP"
            ),
            "ClusterIP",
        )


if __name__ == "__main__":
    unittest.main()
