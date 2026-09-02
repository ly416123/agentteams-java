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
        self.assertNotIn("userProfile", realm)
        quota_admin = next(user for user in realm["users"] if user["username"] == "quota-admin")
        self.assertEqual(set(quota_admin["attributes"]), {"tenant", "project", "team"})
        realm_roles = [role["name"] for role in realm["roles"]["realm"]]
        for role in ("team:read", "team:write"):
            self.assertIn(role, realm_roles)
        self.assertEqual(quota_admin["attributes"]["tenant"], ["tenant-a"])
        self.assertEqual(quota_admin["attributes"]["project"], ["project-a"])
        for role in (
            "team:write",
            "task:create", "task:cancel", "task:retry", "task:pause", "task:approve", "task:reject",
            "task:read",
            "agent:write",
            "quota:write",
            "usage:read",
            "platform:organization:create",
            "platform:organization:read",
            "organization:admin",
            "organization:write",
            "integration:manage",
            "credential:manage",
            "provisioning-policy:manage",
            "external-user:manage",
            "user:manage",
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

    def test_oidc_smoke_seeds_quota_acceptance_user_membership(self):
        smoke_content = (ROOT / "scripts/smoke-kind-oidc.sh").read_text()
        self.assertIn('TOKEN_QUOTA_ADMIN="$(token_for quota-admin quota-admin-dev)"', smoke_content)
        self.assertIn('QUOTA_ADMIN_SUBJECT=', smoke_content)
        self.assertIn("'${QUOTA_ADMIN_SUBJECT}', 'ADMIN', 'ACTIVE'", smoke_content)
        self.assertIn("'MODEL_PROVIDER'", smoke_content)
        self.assertIn("'MODEL'", smoke_content)

    def test_kind_browser_and_api_use_the_same_external_issuer(self):
        kind_values = (ROOT / "deploy/helm/kind-values.yaml").read_text()
        oidc_values = (ROOT / "deploy/helm/kind-oidc-values.yaml").read_text()
        keycloak = (ROOT / "deploy/kind-keycloak.yaml").read_text()
        external_issuer = "http://127.0.0.1:18082/realms/agentteams"
        internal_jwks = "http://keycloak:8080/realms/agentteams/protocol/openid-connect/certs"

        self.assertIn(f"oidcIssuer: {external_issuer}", kind_values)
        self.assertIn(f"issuerUri: {external_issuer}", kind_values)
        self.assertIn(f"issuerUri: {external_issuer}", oidc_values)
        self.assertIn(f"jwkSetUri: {internal_jwks}", oidc_values)
        self.assertIn("value: http://127.0.0.1:18082", keycloak)

    def test_oidc_smoke_port_defaults_match_the_browser_visible_issuer(self):
        for name in ("scripts/smoke-kind-oidc.sh", "scripts/smoke-kind-oidc-rotation.sh"):
            content = (ROOT / name).read_text()
            self.assertIn("KIND_KEYCLOAK_LOCAL_PORT:-18082", content)

    def test_oidc_installer_takes_ownership_of_local_helm_conflicts(self):
        self.assertIn("--force-conflicts", SCRIPT.read_text())

    def test_oidc_installer_allows_a_local_control_plane_image_override(self):
        self.assertIn("AGENTTEAMS_CONTROL_PLANE_IMAGE", SCRIPT.read_text())

    def test_oidc_installer_restarts_control_plane_when_using_a_local_image(self):
        content = SCRIPT.read_text()
        self.assertIn("rollout restart", content)
        self.assertIn("deployment/agentteams-agentteams-java-control-plane", content)
        self.assertLess(content.index("helm upgrade --install agentteams"), content.index("rollout restart"))


if __name__ == "__main__":
    unittest.main()
