#!/usr/bin/env python3
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


if __name__ == "__main__":
    unittest.main()
