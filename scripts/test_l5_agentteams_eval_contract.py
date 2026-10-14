import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]


class L5AgentTeamsEvalContractTest(unittest.TestCase):
    def test_entrypoint_requires_environment_token_and_has_no_credentials(self):
        source = (ROOT / "scripts/run-l5-agentteams-eval.py").read_text(encoding="utf-8")
        self.assertIn('default="AGENTTEAMS_EVAL_TOKEN"', source)
        self.assertIn('AGENTTEAMS_EVAL_TENANT', source)
        self.assertIn('AGENTTEAMS_EVAL_PROJECT', source)
        self.assertIn('AGENTTEAMS_EVAL_TEAM', source)
        self.assertIn("assert_scope_access", source)
        self.assertIn("validate_report_safety", source)
        self.assertIn("assert_workers_ready", source)
        self.assertIn("deepeval_export_dir", source)
        self.assertIn('"--evidence-output-dir"', source)
        self.assertIn('"--pdf-artifact-output-dir"', source)
        self.assertNotIn("alice-dev", source)
        self.assertNotIn("ssssss", source)
        self.assertNotIn("password=", source)

    def test_entrypoint_compiles(self):
        compile((ROOT / "scripts/run-l5-agentteams-eval.py").read_text(encoding="utf-8"),
                "run-l5-agentteams-eval.py", "exec")


if __name__ == "__main__":
    unittest.main()
