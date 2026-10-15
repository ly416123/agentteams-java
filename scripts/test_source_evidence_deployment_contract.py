"""Offline checks for the source-evidence MCP credential boundary."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MCP_ROOT = ROOT.parent / "agentteams-mcp-tools"


class SourceEvidenceDeploymentContractTest(unittest.TestCase):
    def test_control_plane_mcp_credentials_are_secret_backed(self):
        template = (ROOT / "deploy/helm/agentteams-java/templates/control-plane.yaml").read_text()
        block = template.split("{{- if .Values.controlPlane.mcpCredential.secretName }}", 1)[1]
        block = block.split("{{- end }}", 1)[0]

        self.assertIn("name: AGENTTEAMS_TASK_MCP_TOKEN", block)
        self.assertIn("valueFrom:", block)
        self.assertIn("secretKeyRef:", block)
        self.assertNotIn("value: {{ .Values.controlPlane.mcpCredential", block)

    def test_mcp_service_token_is_secret_backed_and_not_in_l5_patch(self):
        deployment_path = MCP_ROOT / "deploy/k8s.yaml"
        l5_patch_path = MCP_ROOT / "deploy/l5/deployment-patch.yaml"
        if not deployment_path.is_file() or not l5_patch_path.is_file():
            self.skipTest(
                "私有 agentteams-mcp-tools 仓库未检出；本地联检环境会执行此契约"
            )
        deployment = deployment_path.read_text()
        l5_patch = l5_patch_path.read_text()
        block = deployment.split("- name: MCP_TOOLS_TOKEN", 1)[1].split("- name:", 1)[0]

        self.assertIn("secretKeyRef:", block)
        self.assertIn("name: agentteams-mcp-tools", block)
        self.assertIn("key: mcp-tools-token", block)
        self.assertNotIn("MCP_TOOLS_TOKEN", l5_patch)
        self.assertNotIn("mcp-tools-token:", l5_patch)

    def test_control_plane_ingress_has_only_a_narrow_mcp_source_rule(self):
        policy = (ROOT / "deploy/helm/agentteams-java/templates/networkpolicy.yaml").read_text()
        control_plane = policy.split("kind: NetworkPolicy\nmetadata:\n  name: {{ include \"agentteams-java.fullname\" . }}-control-plane", 1)[1]
        ingress = control_plane.split("  ingress:", 1)[1].split("  egress:", 1)[0]
        mcp_rule = ingress.split("# The standalone MCP service", 1)[1].split("# Workers fetch", 1)[0]

        self.assertIn("app.kubernetes.io/name: agentteams-mcp-tools", mcp_rule)
        self.assertNotIn("0.0.0.0/0", mcp_rule)
        self.assertNotIn("namespaceSelector:", mcp_rule)

    def test_evaluation_serializes_bounded_sanitized_source_excerpts(self):
        evaluator = (ROOT / "scripts/agentteams_eval.py").read_text()
        serializer = evaluator.split("def _safe_source_evidence", 1)[1].split("\ndef ", 1)[0]

        self.assertIn('"sourceRefs"', serializer)
        self.assertIn('"taskId"', serializer)
        self.assertIn('"runId"', serializer)
        self.assertIn('("title", 500)', serializer)
        self.assertIn('("url", 2_048)', serializer)
        self.assertIn('("snippet", 2_000)', serializer)
        self.assertIn('_sanitize_value(raw_value.strip()[:limit])', serializer)

    def test_source_evidence_migration_runs_after_existing_l5_schema_history(self):
        migrations = list((ROOT / "control-plane/src/main/resources/db/migration").glob(
            "V*__task_source_evidence.sql"))

        self.assertEqual(["V116__task_source_evidence.sql"], [path.name for path in migrations])


if __name__ == "__main__":
    unittest.main()
