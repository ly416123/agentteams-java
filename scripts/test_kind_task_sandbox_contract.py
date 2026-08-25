#!/usr/bin/env python3
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"


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

    def test_operator_has_only_namespace_scoped_sandbox_job_permissions(self):
        rbac = (CHART / "templates/rbac.yaml").read_text(encoding="utf-8")
        self.assertIn('"tasksandboxes", "tasksandboxes/status"', rbac)
        self.assertIn('resources: ["jobs", "jobs/status"]', rbac)
        self.assertNotIn('resources: ["pods"]', rbac)
        self.assertNotIn('resources: ["secrets"]', rbac)
        self.assertNotIn("kind: ClusterRole", rbac)
        self.assertNotIn("kind: ClusterRoleBinding", rbac)

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
