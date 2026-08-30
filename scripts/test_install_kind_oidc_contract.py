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

    def test_local_quota_writer_has_quota_write_permission(self):
        realm = json.loads((ROOT / "deploy/keycloak/agentteams-realm.json").read_text())
        self.assertIn("quota:write", [role["name"] for role in realm["roles"]["realm"]])
        alice = next(user for user in realm["users"] if user["username"] == "alice")
        self.assertIn("quota:write", alice["realmRoles"])

    def test_local_oidc_quota_acceptance_user_has_scoped_admin_roles(self):
        realm = json.loads((ROOT / "deploy/keycloak/agentteams-realm.json").read_text())
        profile_names = {attribute["name"] for attribute in realm["userProfile"]["attributes"]}
        self.assertTrue({"tenant", "project", "team"}.issubset(profile_names))
        self.assertEqual(realm["userProfile"]["unmanagedAttributePolicy"], "DISABLED")
        realm_roles = [role["name"] for role in realm["roles"]["realm"]]
        for role in ("team:read", "team:write"):
            self.assertIn(role, realm_roles)
        quota_admin = next(user for user in realm["users"] if user["username"] == "quota-admin")
        self.assertEqual(quota_admin["attributes"]["tenant"], ["tenant-a"])
        self.assertEqual(quota_admin["attributes"]["project"], ["project-a"])
        for role in (
            "team:write",
            "task:create",
            "task:read",
            "agent:write",
            "quota:write",
            "usage:read",
        ):
            self.assertIn(role, quota_admin["realmRoles"])

    def test_oidc_worker_quota_acceptance_seeds_membership_and_delegates(self):
        script = (ROOT / "scripts/run-kind-oidc-worker-quota-admission.sh").read_text()
        for required in (
                "/protocol/openid-connect/token", "project_memberships", "resource_scopes", "'ADMIN'",
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "AGENTTEAMS_SCOPE_TENANT", "rollout status",
                "must be a UUID",
                "run-kind-worker-quota-admission.py", "KIND_OIDC_WORKER_QUOTA_ADMISSION_OK"):
            self.assertIn(required, script)

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
