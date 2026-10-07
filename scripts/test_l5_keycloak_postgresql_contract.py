from pathlib import Path
import unittest
import yaml


ROOT = Path(__file__).resolve().parents[1]


class KeycloakDatabaseBootstrapContractTest(unittest.TestCase):
    def test_bootstrap_uses_separate_database_and_secret(self):
        script = (ROOT / "deploy/production/bootstrap-l5-keycloak-db.sh").read_text()
        self.assertIn("keycloak-database", script)
        self.assertIn("CREATE DATABASE keycloak", script)
        self.assertIn("create secret generic keycloak-database", script)
        self.assertIn("openssl rand", script)
        self.assertNotIn("echo $KEYCLOAK_DB_PASSWORD", script)
        self.assertNotIn("agentteams-dev", script)
        self.assertIn('[[ "$DB_PASSWORD" =~ ^[A-Za-z0-9+/=]+$ ]] || {', script)
        self.assertIn("Database password must contain only base64 characters.", script)


class KeycloakPostgresqlManifestContractTest(unittest.TestCase):
    def test_l5_manifest_uses_persistent_postgresql_and_preserves_oidc_endpoint(self):
        manifest = ROOT / "deploy/production/l5-keycloak-postgresql.yaml"
        text = manifest.read_text()
        self.assertIn("KC_DB", text)
        self.assertIn("postgres", text)
        self.assertIn("jdbc:postgresql://postgresql:5432/keycloak", text)
        self.assertIn("keycloak-database", text)
        self.assertIn("KC_HTTP_ENABLED", text)
        self.assertIn("http://192.168.1.13:30082", text)
        self.assertIn("nodePort: 30082", text)
        self.assertIn("nodePort: 30083", text)
        self.assertIn("quay.io/keycloak/keycloak:25.0.6", text)
        self.assertNotIn("start-dev", text)
        self.assertNotIn("--import-realm", text)
        self.assertNotIn("kind: Secret", text)
        self.assertNotIn("password:", text.lower())

    def test_keycloak_network_policy_allows_only_dns_and_postgresql_egress(self):
        documents = list(yaml.safe_load_all(
            (ROOT / "deploy/production/l5-keycloak-postgresql.yaml").read_text()
        ))
        policies = [doc for doc in documents if doc["kind"] == "NetworkPolicy"]
        self.assertEqual(len(policies), 1)
        policy = policies[0]
        self.assertEqual(policy["metadata"]["name"], "keycloak-postgresql-egress")
        self.assertEqual(policy["spec"]["policyTypes"], ["Egress"])
        self.assertEqual(policy["spec"]["podSelector"], {
            "matchLabels": {"app.kubernetes.io/name": "keycloak"}
        })
        self.assertEqual(policy["spec"]["egress"], [
            {
                "to": [{
                    "namespaceSelector": {
                        "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                    },
                    "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
                }],
                "ports": [
                    {"protocol": "UDP", "port": 53},
                    {"protocol": "TCP", "port": 53},
                ],
            },
            {
                "to": [{"podSelector": {
                    "matchLabels": {"app.kubernetes.io/name": "postgresql"}
                }}],
                "ports": [{"protocol": "TCP", "port": 5432}],
            },
        ])


if __name__ == "__main__":
    unittest.main()
