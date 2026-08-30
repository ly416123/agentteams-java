import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "deploy/install-kind-oidc.sh"


class InstallKindOidcContractTest(unittest.TestCase):
    def test_local_dashboard_reader_has_usage_permission(self):
        realm = json.loads((ROOT / "deploy/keycloak/agentteams-realm.json").read_text())
        self.assertIn("usage:read", [role["name"] for role in realm["roles"]["realm"]])
        alice = next(user for user in realm["users"] if user["username"] == "alice")
        self.assertIn("usage:read", alice["realmRoles"])

    def test_oidc_smoke_seeds_project_membership_before_acceptance(self):
        install_content = SCRIPT.read_text()
        smoke_content = (ROOT / "scripts/smoke-kind-oidc.sh").read_text()
        self.assertIn("INSERT INTO projects", install_content)
        self.assertIn("tenant-a', 'project-a'", install_content)
        self.assertIn("TOKEN_SUBJECT=", smoke_content)
        self.assertIn("INSERT INTO project_memberships", smoke_content)
        self.assertIn("'${TOKEN_SUBJECT}', 'DEVELOPER', 'ACTIVE'", smoke_content)
        self.assertLess(smoke_content.index("INSERT INTO project_memberships"),
                        smoke_content.index('scripts/validate-oidc-acceptance.sh'))


if __name__ == "__main__":
    unittest.main()
