#!/usr/bin/env python3
"""Validate safe defaults and explicit rollback boundaries for AgentScope rollout."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / "deploy/helm/agentteams-java/values.yaml"
TEMPLATE = ROOT / "deploy/helm/agentteams-java/templates/agent-runtime-config.yaml"
README = ROOT / "README.md"


class AgentScopeDeploymentContractTest(unittest.TestCase):
    def test_rollout_is_disabled_by_default(self) -> None:
        text = VALUES.read_text(encoding="utf-8")
        self.assertIn("agentRuntime:", text)
        self.assertIn("default: QWENPAW", text)
        self.assertIn("enabled: false", text)
        self.assertIn("rolloutPercentage: 0", text)

    def test_config_map_exports_only_non_secret_rollout_controls(self) -> None:
        text = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("AGENTTEAMS_RUNTIME_DEFAULT", text)
        self.assertIn("AGENTTEAMS_AGENTSCOPE_ENABLED", text)
        self.assertIn("AGENTTEAMS_AGENTSCOPE_ROLLOUT_PERCENTAGE", text)
        self.assertNotIn("SECRET", text.upper())
        self.assertNotIn("TOKEN", text.upper())
        self.assertNotIn("API_KEY", text.upper())

    def test_documented_rollback_is_qwenpaw(self) -> None:
        text = README.read_text(encoding="utf-8")
        self.assertIn("AgentScope 灰度与回滚", text)
        self.assertIn("rolloutPercentage: 0", text)


if __name__ == "__main__":
    unittest.main()
