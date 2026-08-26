#!/usr/bin/env python3
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"


def read_chart(path):
    return (CHART / path).read_text(encoding="utf-8")


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
        self.assertIn('resources: ["workers", "workers/status", "teams", "teams/status",', operator_role)
        self.assertIn('resources: ["jobs", "jobs/status"]', operator_role)
        self.assertNotIn('resources: ["secrets"]', operator_role)
        self.assertNotIn('resources: ["pods"]', operator_role)

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
        self.assertIn("namespaceSelector: {}", policy)
        self.assertIn("protocol: UDP, port: 53", policy)
        self.assertIn("protocol: TCP, port: 53", policy)
        self.assertIn("kubernetesApiCIDR", policy)
        self.assertIn("oidcEgressCIDR", policy)
        self.assertNotRegex(policy, r"to:\n\s+- \{\}")

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
