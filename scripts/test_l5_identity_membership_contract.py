from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class L5IdentityMembershipContractTest(unittest.TestCase):
    def test_l5_acceptance_scripts_restore_full_execution_scope_membership(self):
        scripts = (
            ROOT / "scripts/run-l5-subtask-delegation.py",
            ROOT / "scripts/run-l5-task-file-delivery.py",
            ROOT / "scripts/run-l5-task-review-lifecycle.py",
        )
        for script in scripts:
            source = script.read_text(encoding="utf-8")
            with self.subTest(script=script.name):
                self.assertIn("INSERT INTO organization_memberships", source)
                self.assertIn("INSERT INTO tenant_memberships", source)
                self.assertIn("INSERT INTO project_memberships", source)
                self.assertIn("FROM legacy_tenant_mappings", source)


if __name__ == "__main__":
    unittest.main()
