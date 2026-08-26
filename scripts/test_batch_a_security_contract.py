#!/usr/bin/env python3
import re
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


class BatchASecurityContractTest(unittest.TestCase):
    def test_workloads_fail_closed_at_pod_and_container_boundaries(self):
        values = read_chart("values.yaml")
        for required in (
            "podSecurityContext:",
            "runAsNonRoot: true",
            "type: RuntimeDefault",
            "containerSecurityContext:",
            "allowPrivilegeEscalation: false",
            "readOnlyRootFilesystem: true",
            "drop:\n      - ALL",
        ):
            self.assertIn(required, values)
        for workload in ("control-plane.yaml", "gateway.yaml", "operator.yaml"):
            template = read_chart(f"templates/{workload}")
            self.assertIn("toYaml .Values.podSecurityContext", template, workload)
            self.assertIn("toYaml .Values.containerSecurityContext", template, workload)
            self.assertNotIn("privileged: true", template, workload)
            self.assertNotIn("hostNetwork: true", template, workload)
            self.assertNotIn("hostPID: true", template, workload)

    def test_workloads_spread_by_hostname_without_overridable_pod_selection(self):
        for workload in ("control-plane.yaml", "gateway.yaml", "operator.yaml"):
            template = read_chart(f"templates/{workload}")
            self.assertIn("topologySpreadConstraints:", template, workload)
            self.assertIn("with .Values.topologySpreadConstraints", template, workload)
            self.assertIn("toYaml . | nindent 8", template, workload)

    def test_gateway_and_control_plane_use_token_minimization(self):
        gateway = read_chart("templates/gateway.yaml")
        control_plane = read_chart("templates/control-plane.yaml")

        self.assertIn("automountServiceAccountToken: false", gateway)
        self.assertIn(
            "automountServiceAccountToken: {{ .Values.controlPlane.teamSync.enabled }}",
            control_plane,
        )

    def test_operator_permissions_are_limited_to_namespace_resources(self):
        rbac = read_chart("templates/rbac.yaml")
        operator_role = rbac.split("{{- if .Values.controlPlane.teamSync.enabled }}", 1)[0]

        self.assertIn("namespace: {{ .Release.Namespace }}", operator_role)
        self.assertIn('resources: ["workers", "teams", "tasksandboxes"]', operator_role)
        self.assertIn('resources: ["jobs", "jobs/status"]', operator_role)
        self.assertNotIn('resources: ["secrets"]', operator_role)
        self.assertNotIn('resources: ["pods"]', operator_role)

    def test_operator_separates_main_resources_from_status_permissions(self):
        rbac = read_chart("templates/rbac.yaml")
        operator_role = rbac.split("{{- if .Values.controlPlane.teamSync.enabled }}", 1)[0]

        self.assertIn(
            'resources: ["workers", "teams", "tasksandboxes"]', operator_role
        )
        self.assertIn('verbs: ["get", "list", "watch"]', operator_role)
        self.assertIn(
            'resources: ["workers/status", "teams/status", "tasksandboxes/status"]',
            operator_role,
        )
        self.assertIn('verbs: ["get", "patch", "update"]', operator_role)
        main_resources = operator_role.split(
            'resources: ["workers", "teams", "tasksandboxes"]', 1
        )[1].split("  - apiGroups:", 1)[0]
        self.assertNotRegex(main_resources, r'verbs: \[[^\]]*(patch|update)')

    def test_control_plane_has_no_pod_job_or_secret_write_permission(self):
        rbac = read_chart("templates/rbac.yaml")
        control_plane_role = rbac.split("-control-plane-team-sync", 1)[1]

        self.assertNotRegex(control_plane_role, r"resources:.*\bpods\b")
        self.assertNotRegex(control_plane_role, r"resources:.*\bjobs\b")
        self.assertNotRegex(control_plane_role, r"resources:.*\bsecrets\b")
        self.assertNotRegex(control_plane_role, r"verbs:.*\b(create|update|patch|delete)\b")

    def test_network_policies_do_not_allow_unbounded_default_egress(self):
        policy = read_chart("templates/networkpolicy.yaml")

        self.assertIn("egress:", policy)
        self.assertIn("kubernetes.io/metadata.name: kube-system", policy)
        self.assertIn("protocol: UDP, port: 53", policy)
        self.assertIn("protocol: TCP, port: 53", policy)
        self.assertIn("kubernetesApiCIDR", policy)
        self.assertIn("oidcEgressCIDR", policy)
        self.assertNotIn("podSelector: {}", policy)
        self.assertNotIn("namespaceSelector: {}", policy)

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_rendered_network_policies_have_non_empty_selectors(self):
        docs = render_chart(
            "--set", "sandbox.enabled=true",
            "--set", "sandbox.provider=kubernetes",
            "--set", "sandbox.defaultProfile=ISOLATED",
        )
        policies = [
            doc for doc in docs
            if doc.get("kind") == "NetworkPolicy"
            and doc["metadata"]["name"].endswith(
                ("-task-sandbox", "-control-plane", "-gateway", "-operator")
            )
        ]

        self.assertEqual(len(policies), 4)
        for policy in policies:
            self.assertTrue(policy["spec"]["podSelector"].get("matchLabels"))
            for rule_name in ("ingress", "egress"):
                for rule in policy["spec"].get(rule_name, []):
                    for peer in rule.get("from", []) + rule.get("to", []):
                        if "podSelector" in peer:
                            self.assertTrue(peer["podSelector"].get("matchLabels"))
                        if "namespaceSelector" in peer:
                            self.assertTrue(peer["namespaceSelector"].get("matchLabels"))

    def test_oidc_default_is_fail_closed(self):
        values = yaml.safe_load(read_chart("values.yaml"))
        policy = values["networkPolicy"]

        self.assertNotEqual(policy["oidcEgressCIDR"], "0.0.0.0/0")
        self.assertEqual(policy["oidcEgressCIDR"], "")
        template = read_chart("templates/networkpolicy.yaml")
        self.assertIn("if .Values.networkPolicy.oidcEgressCIDR", template)
        self.assertIn('not": { "const": "0.0.0.0/0" }', read_chart("values.schema.json"))

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_rendered_oidc_policy_never_uses_public_cidr(self):
        docs = render_chart("-f", str(CHART / "../kind-oidc-values.yaml"))
        policies = [doc for doc in docs if doc.get("kind") == "NetworkPolicy"]
        control_plane = next(
            policy for policy in policies if policy["metadata"]["name"].endswith("-control-plane")
        )
        cidrs = [
            peer["ipBlock"]["cidr"]
            for rule in control_plane["spec"]["egress"]
            for peer in rule.get("to", [])
            if "ipBlock" in peer
        ]
        self.assertNotIn("0.0.0.0/0", cidrs)

    def test_sandbox_job_security_contract_is_present_in_operator_path(self):
        factory = (ROOT / "operator/src/main/java/io/agentteams/operator/TaskSandboxResourceFactory.java").read_text(
            encoding="utf-8"
        )
        for required in (
            ".withPrivileged(false)",
            ".withAllowPrivilegeEscalation(false)",
            ".withReadOnlyRootFilesystem(true)",
            ".withRunAsNonRoot(true)",
            ".withAutomountServiceAccountToken(false)",
            ".withHostNetwork(false)",
            ".withHostPID(false)",
        ):
            self.assertIn(required, factory)
        self.assertNotIn("withHostPath", factory)

    def test_pdb_is_guarded_and_selectors_match_deployments(self):
        pdb = read_chart("templates/poddisruptionbudget.yaml")
        for component in ("control-plane", "gateway", "operator"):
            deployment = read_chart(f"templates/{component}.yaml")
            match = re.search(
                rf"matchLabels:\s+\{{ app\.kubernetes\.io/name: agentteams-{re.escape(component)} \}}",
                deployment,
            )
            self.assertIsNotNone(match, component)
            self.assertIn(
                f"app.kubernetes.io/name: agentteams-{component}", pdb
            )


if __name__ == "__main__":
    unittest.main()
