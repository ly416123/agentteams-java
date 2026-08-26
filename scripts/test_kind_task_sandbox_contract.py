#!/usr/bin/env python3
import shutil
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
HELM = shutil.which("helm")


def rendered_manifests():
    if HELM is None:
        raise unittest.SkipTest("helm is unavailable")
    result = subprocess.run(
        [HELM, "template", "agentteams", str(CHART), "--namespace", "agentteams"],
        check=True,
        capture_output=True,
        text=True,
    )
    return [manifest for manifest in yaml.safe_load_all(result.stdout) if manifest]


class TaskSandboxHelmContractTest(unittest.TestCase):
    def test_sandbox_is_opt_in_and_runtime_classes_are_deployment_configuration(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
        sandbox = values["sandbox"]
        self.assertFalse(sandbox["enabled"])
        self.assertEqual("NONE", sandbox["defaultProfile"])
        self.assertEqual("fake", sandbox["provider"])
        self.assertEqual("gvisor", sandbox["runtimeClasses"]["isolated"])
        self.assertEqual("kata-qemu", sandbox["runtimeClasses"]["hardened"])
        self.assertEqual(1800, sandbox["defaultTtlSeconds"])
        self.assertEqual(86400, sandbox["maxTtlSeconds"])

    def test_task_sandbox_crd_is_installed_with_bounded_profile_and_ttl(self):
        crd = yaml.safe_load((CHART / "crds/task-sandboxes.yaml").read_text(encoding="utf-8"))
        schema = crd["spec"]["versions"][0]["schema"]["openAPIV3Schema"]["properties"]["spec"]["properties"]
        self.assertEqual(["ISOLATED", "HARDENED"], schema["profile"]["enum"])
        self.assertEqual(60, schema["ttlSeconds"]["minimum"])
        self.assertEqual(86400, schema["ttlSeconds"]["maximum"])

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_operator_has_only_namespace_scoped_sandbox_job_permissions(self):
        manifests = rendered_manifests()
        roles = [manifest for manifest in manifests if manifest.get("kind") == "Role"]
        bindings = [manifest for manifest in manifests if manifest.get("kind") == "RoleBinding"]

        operator_role = next(
            role for role in roles if role["metadata"]["name"].endswith("-operator")
        )
        operator_binding = next(
            binding for binding in bindings if binding["metadata"]["name"].endswith("-operator")
        )
        rules = operator_role["rules"]
        main_rule = next(rule for rule in rules if "workers" in rule["resources"])
        status_rule = next(rule for rule in rules if "workers/status" in rule["resources"])
        jobs_rule = next(rule for rule in rules if "jobs" in rule["resources"])

        self.assertEqual(
            ["workers", "teams", "tasksandboxes"], main_rule["resources"]
        )
        self.assertEqual(["get", "list", "watch"], main_rule["verbs"])
        self.assertEqual(
            ["workers/status", "teams/status", "tasksandboxes/status"],
            status_rule["resources"],
        )
        self.assertEqual(["get", "patch", "update"], status_rule["verbs"])
        self.assertEqual(["jobs", "jobs/status"], jobs_rule["resources"])
        self.assertEqual(
            ["get", "list", "watch", "create", "update", "patch", "delete"],
            jobs_rule["verbs"],
        )
        self.assertEqual("agentteams", operator_role["metadata"]["namespace"])
        self.assertEqual("agentteams", operator_binding["metadata"]["namespace"])
        self.assertNotIn(
            "pods", [resource for rule in rules for resource in rule["resources"]]
        )
        self.assertNotIn(
            "secrets", [resource for rule in rules for resource in rule["resources"]]
        )
        self.assertFalse(any(manifest.get("kind") == "ClusterRole" for manifest in manifests))
        self.assertFalse(any(manifest.get("kind") == "ClusterRoleBinding" for manifest in manifests))

    def test_operator_receives_runtime_class_configuration(self):
        operator = (CHART / "templates/operator.yaml").read_text(encoding="utf-8")
        for required in (
                "AGENTTEAMS_SANDBOX_RUNTIMECLASS_ISOLATED",
                "AGENTTEAMS_SANDBOX_RUNTIMECLASS_HARDENED",
                "AGENTTEAMS_SANDBOX_PROVIDER",
                "AGENTTEAMS_SANDBOX_ENABLED"):
            self.assertIn(required, operator)

    def test_kind_values_keep_real_runtime_classes_disabled(self):
        kind_values = yaml.safe_load((ROOT / "deploy/helm/kind-values.yaml").read_text(encoding="utf-8"))
        self.assertFalse(kind_values.get("sandbox", {}).get("enabled", False))


if __name__ == "__main__":
    unittest.main()
