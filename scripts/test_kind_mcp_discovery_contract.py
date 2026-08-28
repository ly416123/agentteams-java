#!/usr/bin/env python3
"""Guard the Kind MCP discovery aggregation acceptance contract."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindMcpDiscoveryContractTest(unittest.TestCase):
    def test_acceptance_script_exists_and_checks_safe_revision_aggregate(self):
        script = (ROOT / "scripts/run-kind-mcp-discovery.py").read_text(encoding="utf-8")
        for required in (
                "Idempotency-Key", "mcp_discovery_snapshots", "/discovery",
                "AVAILABLE", "UNKNOWN", "serverRevision", "healthyInstances",
                "freshInstances", "toolsDigest", "KIND_MCP_DISCOVERY_OK"):
            self.assertIn(required, script)

    def test_acceptance_script_does_not_print_sensitive_mcp_fields(self):
        script = (ROOT / "scripts/run-kind-mcp-discovery.py").read_text(encoding="utf-8")
        self.assertNotIn("credentialRef", script)
        self.assertNotIn("Authorization", script)
        self.assertNotIn("tools正文", script)

    def test_ci_runs_discovery_after_resource_binding_ack(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("Run Kind MCP discovery aggregation acceptance", workflow)
        self.assertLess(
            workflow.index("Run Kind resource binding ACK acceptance"),
            workflow.index("Run Kind MCP discovery aggregation acceptance"),
        )


if __name__ == "__main__":
    unittest.main()
