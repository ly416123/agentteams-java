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


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def render_chart(*args):
    if HELM is None:
        raise unittest.SkipTest("helm is unavailable")
    result = subprocess.run(
        [HELM, "template", "agentteams", str(CHART), "--namespace", "agentteams", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return [document for document in yaml.safe_load_all(result.stdout) if document]


class ConsoleDeploymentContractTest(unittest.TestCase):
    def test_console_image_builds_static_assets_and_runs_unprivileged_nginx(self):
        dockerfile = read("deploy/docker/console.Dockerfile")
        nginx = read("deploy/docker/console-nginx.conf")

        for required in (
            "FROM node:",
            "AS build",
            "COPY console/package*.json",
            "npm ci",
            "npm run build",
            "FROM nginxinc/nginx-unprivileged:",
            "COPY --from=build /workspace/console/dist",
            "USER 101",
        ):
            self.assertIn(required, dockerfile)
        self.assertIn("listen 8080", nginx)
        self.assertIn("try_files $uri $uri/ /index.html;", nginx)
        self.assertNotIn("proxy_pass", nginx)
        self.assertIn('<script src="/config.js"></script>', read("console/index.html"))
        self.assertIn("__AGENTTEAMS_CONFIG__", read("console/src/auth/oidc.ts"))

    def test_console_values_are_explicit_and_public_only(self):
        values = read("deploy/helm/agentteams-java/values.yaml")
        schema = json.loads(read("deploy/helm/agentteams-java/values.schema.json"))

        self.assertIn("console:\n", values)
        for required in ("enabled: false", "image:", "config:", "apiBasePath:", "oidcIssuer:", "oidcClientId:"):
            self.assertIn(required, values)
        console_schema = schema["properties"]["console"]
        self.assertFalse(console_schema["additionalProperties"])
        self.assertEqual(
            set(console_schema["properties"]),
            {"enabled", "image", "config"},
        )
        self.assertEqual(
            set(console_schema["properties"]["config"]["properties"]),
            {"apiBasePath", "oidcIssuer", "oidcClientId"},
        )

    def test_console_helm_template_is_optional_secret_free_workload(self):
        template = read("deploy/helm/agentteams-java/templates/console.yaml")

        for required in (
            "if .Values.console.enabled",
            "kind: ConfigMap",
            "kind: Deployment",
            "kind: Service",
            "window.__AGENTTEAMS_CONFIG__",
            "containerSecurityContext",
            "podSecurityContext",
            "automountServiceAccountToken: false",
            "mountPath: /usr/share/nginx/html/config.js",
            "subPath: config.js",
            "port: 8080",
        ):
            self.assertIn(required, template)
        self.assertNotRegex(template, r"(?i)(secretKeyRef|secretName|password|private[_-]?key)")

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_enabled_console_renders_configmap_deployment_service_without_secret(self):
        documents = render_chart(
            "--set", "console.enabled=true",
            "--set", "ingress.enabled=true",
            "--set", "ingress.host=api.agentteams.localhost",
        )
        console_documents = [
            document
            for document in documents
            if document.get("metadata", {}).get("name", "").endswith("-console")
        ]
        kinds = {document["kind"] for document in console_documents}
        self.assertEqual(kinds, {"ConfigMap", "Deployment", "Service"})
        deployment = next(document for document in console_documents if document["kind"] == "Deployment")
        container = deployment["spec"]["template"]["spec"]["containers"][0]
        volumes = deployment["spec"]["template"]["spec"]["volumes"]
        self.assertTrue(deployment["spec"]["template"]["spec"]["securityContext"]["runAsNonRoot"])
        self.assertTrue(container["securityContext"]["readOnlyRootFilesystem"])
        self.assertIn({"name": "tmp", "emptyDir": {}}, volumes)
        self.assertIn({"name": "tmp", "mountPath": "/tmp"}, container["volumeMounts"])
        self.assertFalse(any("secret" in json.dumps(document).lower() for document in console_documents))

    def test_helm_and_kind_ingress_route_api_before_console(self):
        helm_ingress = read("deploy/helm/agentteams-java/templates/ingress.yaml")
        gateway_api = read("deploy/helm/agentteams-java/templates/gateway-api.yaml")
        kind_ingress = list(yaml.safe_load_all(read("deploy/kind-ingress.yaml")))[0]
        api_rule = kind_ingress["spec"]["rules"][0]
        paths = api_rule["http"]["paths"]

        self.assertIn("path: /", helm_ingress)
        self.assertIn("-console", helm_ingress)
        self.assertIn("path: /api", helm_ingress)
        self.assertIn("value: /api", gateway_api)
        self.assertIn("value: /", gateway_api)
        self.assertIn("-console", gateway_api)
        self.assertEqual(paths[0]["path"], "/api")
        self.assertEqual(paths[0]["backend"]["service"]["name"], "agentteams-agentteams-java-control-plane")
        self.assertEqual(
            next(path for path in paths if path["path"] == "/api/v1/conversations")
            ["backend"]["service"]["name"],
            "agentteams-agentteams-java-manager",
        )
        self.assertEqual(
            next(path for path in paths if path["path"] == "/api/v1/manager")
            ["backend"]["service"]["name"],
            "agentteams-agentteams-java-manager",
        )
        self.assertEqual(paths[-1]["path"], "/")
        self.assertEqual(paths[-1]["backend"]["service"]["name"], "agentteams-agentteams-java-console")

    def test_ci_runs_console_checks_only_when_console_exists_and_validates_manifests(self):
        workflow = read(".github/workflows/ci.yml")

        helm_setup = workflow.index("Set up Helm for Console contracts")
        console_checks = workflow.index("Console npm checks")
        validator = workflow.index("python3 scripts/validate-console-manifests.py")
        contract_test = workflow.index("python3 -m unittest scripts/test_console_manifests_contract.py -v")
        render = workflow.index("--set console.enabled=true")
        self.assertLess(helm_setup, console_checks)
        self.assertLess(helm_setup, validator)
        self.assertLess(helm_setup, contract_test)
        self.assertLess(helm_setup, render)
        self.assertIn("if [[ ! -d console ]]; then", workflow)
        for command in ("npm --prefix console test", "npm --prefix console run build", "npm --prefix console run lint"):
            self.assertIn(command, workflow)
        self.assertIn("python3 -m unittest scripts/test_console_manifests_contract.py -v", workflow)
        self.assertIn("Render Console-enabled manifests", workflow)
        self.assertIn("python3 scripts/validate-console-manifests.py", workflow)

    def test_kind_install_has_console_image_and_service_before_applying_ingress(self):
        kind_values = yaml.safe_load(read("deploy/helm/kind-values.yaml"))
        build_script = read("deploy/build-images.sh")
        installer = read("deploy/install-kind-dev.sh")

        self.assertTrue(kind_values["console"]["enabled"])
        self.assertIn("deploy/docker/console.Dockerfile|ghcr.io/ly416123/agentteams-console:latest", build_script)
        self.assertIn("if [[ -d console ]]; then", build_script)
        ingress_position = installer.index('kubectl apply -f "$ROOT/deploy/kind-ingress.yaml"')
        helm_position = installer.index('helm upgrade --install agentteams')
        rollout_position = installer.index('kubectl -n "$NAMESPACE" wait --for=condition=available')
        self.assertGreater(ingress_position, helm_position)
        self.assertGreater(ingress_position, rollout_position)

    @unittest.skipUnless(HELM, "helm is unavailable")
    def test_kind_values_render_console_service_before_kind_ingress_uses_it(self):
        documents = render_chart(
            "-f", str(ROOT / "deploy/helm/kind-values.yaml"),
            "--set", "ingress.enabled=true",
            "--set", "ingress.host=api.agentteams.localhost",
        )
        console = [
            document
            for document in documents
            if document.get("metadata", {}).get("name", "").endswith("-console")
        ]
        self.assertIn("Service", {document["kind"] for document in console})
        ingress = next(document for document in documents if document.get("kind") == "Ingress")
        paths = ingress["spec"]["rules"][0]["http"]["paths"]
        self.assertEqual(
            next(path for path in paths if path["path"] == "/")["backend"]["service"]["name"],
            "agentteams-agentteams-java-console",
        )

    def test_env_files_are_ignored_and_console_validator_scans_tracked_env_files(self):
        gitignore = read(".gitignore")
        validator = read("scripts/validate-console-manifests.py")

        self.assertIn(".env*", gitignore)
        self.assertIn("ls-files", validator)
        self.assertIn("Secret", validator)

    def test_manifest_validator_exists_and_reports_missing_console_build_inputs(self):
        validator = read("scripts/validate-console-manifests.py")

        for required in (
            "console.Dockerfile",
            "console-nginx.conf",
            "kind-ingress.yaml",
            "try_files $uri $uri/ /index.html;",
            "console.enabled",
            "Deployment",
            "Service",
            "Ingress",
            "console/",
            "helm",
        ):
            self.assertIn(required, validator)


if __name__ == "__main__":
    unittest.main()
