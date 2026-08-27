import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "deploy/install-kind-oidc.sh"


class InstallKindOidcContractTest(unittest.TestCase):
    def test_oidc_smoke_seeds_project_membership_before_acceptance(self):
        content = SCRIPT.read_text()
        self.assertIn("INSERT INTO projects", content)
        self.assertIn("tenant-a', 'project-a'", content)
        self.assertIn("INSERT INTO project_memberships", content)
        self.assertIn("'alice', 'DEVELOPER', 'ACTIVE'", content)
        self.assertLess(content.index("INSERT INTO project_memberships"),
                        content.index('scripts/smoke-kind-oidc.sh')
                        if 'scripts/smoke-kind-oidc.sh' in content else len(content))


if __name__ == "__main__":
    unittest.main()
