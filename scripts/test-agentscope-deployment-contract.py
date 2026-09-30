#!/usr/bin/env python3
"""Validate the single AgentScope runtime deployment contract."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / "deploy/helm/agentteams-java/values.yaml"
TEMPLATE = ROOT / "deploy/helm/agentteams-java/templates/agent-runtime-config.yaml"
README = ROOT / "README.md"


class AgentScopeDeploymentContractTest(unittest.TestCase):
    def test_agentscope_is_the_only_runtime(self) -> None:
        text = VALUES.read_text(encoding="utf-8")
        self.assertIn("agentRuntime:", text)
        self.assertNotIn("agentScope:", text)
        self.assertNotIn("rolloutPercentage", text)

    def test_config_map_exports_only_non_secret_runtime_controls(self) -> None:
        text = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("AGENTTEAMS_ARTIFACT_UPLOAD_ENABLED", text)
        self.assertNotIn("AGENTTEAMS_RUNTIME_DEFAULT", text)
        self.assertNotIn("AGENTTEAMS_AGENTSCOPE_ROLLOUT_PERCENTAGE", text)
        self.assertNotIn("SECRET", text.upper())
        self.assertNotIn("TOKEN", text.upper())
        self.assertNotIn("API_KEY", text.upper())

    def test_documented_runtime_path_is_agentscope(self) -> None:
        text = README.read_text(encoding="utf-8")
        self.assertIn("AgentScope 当前开发路径", text)
        values = VALUES.read_text(encoding="utf-8")
        self.assertNotIn("rolloutPercentage", values)


if __name__ == "__main__":
    unittest.main()
