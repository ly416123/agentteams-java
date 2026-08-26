#!/usr/bin/env python3
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"


def read_chart(path):
    return (CHART / path).read_text(encoding="utf-8")


class BatchAHelmContractTest(unittest.TestCase):
    def test_values_schema_restricts_runtime_and_sandbox_enums(self):
        schema = json.loads(read_chart("values.schema.json"))
        sandbox = schema["properties"]["sandbox"]["properties"]
        runtime = schema["properties"]["agentRuntime"]["properties"]

        self.assertEqual(sandbox["provider"]["enum"], ["fake", "kubernetes"])
        self.assertEqual(
            sandbox["defaultProfile"]["enum"], ["NONE", "ISOLATED", "HARDENED"]
        )
        self.assertEqual(runtime["default"]["enum"], ["QWENPAW", "AGENTSCOPE"])
        self.assertEqual(
            runtime["agentScope"]["properties"]["rolloutPercentage"]["maximum"],
            100,
        )

    def test_values_schema_rejects_unknown_controlled_keys(self):
        schema = json.loads(read_chart("values.schema.json"))

        self.assertFalse(schema["additionalProperties"])
        self.assertFalse(
            schema["properties"]["sandbox"]["additionalProperties"]
        )
        self.assertFalse(
            schema["properties"]["agentRuntime"]["properties"]["agentScope"][
                "additionalProperties"
            ]
        )

    def test_common_values_define_secure_pod_defaults_and_spread(self):
        values = read_chart("values.yaml")

        for required in (
            "podSecurityContext:",
            "runAsNonRoot: true",
            "type: RuntimeDefault",
            "containerSecurityContext:",
            "allowPrivilegeEscalation: false",
            "readOnlyRootFilesystem: true",
            "drop:",
            "- ALL",
            "topologySpreadConstraints:",
            "maxSkew: 1",
            "topologyKey: kubernetes.io/hostname",
            "whenUnsatisfiable: ScheduleAnyway",
        ):
            self.assertIn(required, values)

    def test_agent_runtime_config_is_secret_free_and_complete(self):
        config = read_chart("templates/agent-runtime-config.yaml")

        for required in (
            "AGENTTEAMS_RUNTIME_DEFAULT",
            "AGENTTEAMS_AGENTSCOPE_ENABLED",
            "AGENTTEAMS_AGENTSCOPE_ROLLOUT_PERCENTAGE",
            "AGENTTEAMS_AGENTSCOPE_AGENT_ALLOWLIST",
            "AGENTTEAMS_AGENTSCOPE_TEAM_ALLOWLIST",
            "AGENTTEAMS_AGENTSCOPE_TENANT_ALLOWLIST",
        ):
            self.assertIn(required, config)
        self.assertNotRegex(config, r"(?i)(password|token|secret|private[_-]?key)")

    def test_each_workload_uses_common_security_and_spread_contract(self):
        for workload in ("control-plane.yaml", "gateway.yaml", "operator.yaml"):
            template = read_chart(f"templates/{workload}")
            self.assertIn("podSecurityContext", template, workload)
            self.assertIn("containerSecurityContext", template, workload)
            self.assertIn("topologySpreadConstraints", template, workload)
            self.assertIn("name: tmp", template, workload)

    def test_network_policy_has_default_deny_sandbox_and_operator(self):
        policy = read_chart("templates/networkpolicy.yaml")

        self.assertIn("-task-sandbox", policy)
        self.assertIn("ingress: []", policy)
        self.assertIn("-operator", policy)
        self.assertIn("policyTypes: [Ingress, Egress]", policy)
        self.assertIn("port: 53", policy)
        self.assertNotIn("egress:\n    - {}", policy)

    def test_rbac_is_namespace_scoped_and_control_plane_is_read_only(self):
        rbac = read_chart("templates/rbac.yaml")

        self.assertNotIn("ClusterRole", rbac)
        self.assertNotIn("ClusterRoleBinding", rbac)
        self.assertGreaterEqual(rbac.count("kind: Role\n"), 2)
        self.assertIn('resources: ["teams"]', rbac)
        self.assertIn('verbs: ["get", "list", "watch"]', rbac)
        control_plane_role = rbac.split("-control-plane-team-sync", 1)[1]
        self.assertNotRegex(control_plane_role, r"resources:.*(pods|jobs|secrets)")

    def test_pdb_covers_all_replicated_components(self):
        pdb = read_chart("templates/poddisruptionbudget.yaml")

        for component in ("control-plane", "gateway", "operator"):
            self.assertIn(f"-{component}", pdb)
            self.assertIn(f"app.kubernetes.io/name: agentteams-{component}", pdb)
        self.assertIn("podDisruptionBudget.enabled", pdb)
        self.assertIn("podDisruptionBudget.minAvailable", pdb)


if __name__ == "__main__":
    unittest.main()
