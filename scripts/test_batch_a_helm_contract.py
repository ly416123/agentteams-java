#!/usr/bin/env python3
import json
import shutil
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
HELM = shutil.which("helm")


def read_chart(path):
    return (CHART / path).read_text(encoding="utf-8")


def render_chart(*args):
    if HELM is None:
        raise unittest.SkipTest("helm is unavailable")
    result = subprocess.run(
        [HELM, "template", "agentteams", str(CHART), "--namespace", "agentteams", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc]


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

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_rendered_runtime_config_matches_operator_worker_injection(self):
        docs = render_chart()
        configmaps = [doc for doc in docs if doc.get("kind") == "ConfigMap"]
        runtime_configs = [
            doc for doc in configmaps
            if doc["metadata"]["name"] == "agentteams-java-agent-runtime"
        ]
        self.assertEqual(len(runtime_configs), 1)
        runtime_config = runtime_configs[0]
        factory = (ROOT / "operator/src/main/java/io/agentteams/operator/WorkerResourceFactory.java").read_text(
            encoding="utf-8"
        )

        # WorkerResourceFactory intentionally resolves the ConfigMap from the
        # Helm/release binding instead of copying a removed hard-coded constant.
        self.assertEqual(runtime_config["metadata"]["name"], "agentteams-java-agent-runtime")
        self.assertIn("String runtimeConfigMap = runtimeConfigMap(worker, spec);", factory)
        self.assertIn(".withName(runtimeConfigMap)", factory)
        self.assertIn('RUNTIME_CONFIG_MAP_ENV = "AGENTTEAMS_RUNTIME_CONFIG_MAP"', factory)
        self.assertIn('RUNTIME_CONFIG_MAP_ANNOTATION = "agentteams.io/runtime-config-map"', factory)
        self.assertIn('configured = release.trim() + "-agentteams-java-agent-runtime";', factory)
        self.assertNotRegex(factory, r"RUNTIME_CONFIG_MAP\s*=")
        self.assertIn("withEnvFrom", factory)
        self.assertIn("withConfigMapRef", factory)
        self.assertIn("new LinkedHashMap<>(spec.env())", factory)
        self.assertIn("environment.put(\"AGENTTEAMS_AGENT_ID\", spec.agentId())", factory)
        self.assertIn("environment.put(\"AGENTTEAMS_RUNTIME\", spec.runtime())", factory)

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_rendered_pdbs_match_workload_selectors_and_replica_budget(self):
        docs = render_chart()
        deployments = {
            doc["metadata"]["name"]: doc
            for doc in docs
            if doc.get("kind") == "Deployment"
            and doc["metadata"]["name"].endswith(("-control-plane", "-gateway", "-operator"))
        }
        pdbs = [
            doc for doc in docs
            if doc.get("kind") == "PodDisruptionBudget"
            and doc["metadata"]["name"].endswith(("-control-plane", "-gateway", "-operator"))
        ]

        self.assertEqual(len(pdbs), 3)
        for pdb in pdbs:
            component = pdb["metadata"]["name"].rsplit("-", 1)[-1]
            deployment = next(
                deployment
                for name, deployment in deployments.items()
                if name.endswith(f"-{component}")
            )
            self.assertEqual(
                pdb["spec"]["selector"],
                {"matchLabels": deployment["spec"]["selector"]["matchLabels"]},
            )
            min_available = pdb["spec"]["minAvailable"]
            if isinstance(min_available, int):
                self.assertLessEqual(min_available, deployment["spec"]["replicas"])

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
