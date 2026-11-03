#!/usr/bin/env python3
"""Contract tests for signed, digest-pinned release promotion."""

import json
import importlib.util
import os
import re
import subprocess
import sys
import tempfile
import yaml
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALID = ROOT / "scripts/fixtures/release-manifest-valid.json"
INVALID = ROOT / "scripts/fixtures/release-manifest-invalid.json"
VALIDATOR = ROOT / "scripts/validate-release-manifest.py"
PROMOTION_GATE = ROOT / "scripts/check-promotion-gate.py"
PROMOTION_POLICY = ROOT / "deploy/production/promotion/canary-policy.json"

GATE_SPEC = importlib.util.spec_from_file_location("check_promotion_gate", PROMOTION_GATE)
GATE = importlib.util.module_from_spec(GATE_SPEC)
GATE_SPEC.loader.exec_module(GATE)


class BatchBReleaseContractTest(unittest.TestCase):
    def test_validator_accepts_valid_manifest_and_rejects_invalid_manifest(self):
        valid = subprocess.run(
            [sys.executable, str(VALIDATOR), "--manifest", str(VALID)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assertIn("RELEASE_MANIFEST_OK", valid.stdout)

        invalid = subprocess.run(
            [sys.executable, str(VALIDATOR), "--manifest", str(INVALID)],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("RELEASE_MANIFEST_FAIL", invalid.stderr)

    def test_valid_manifest_contains_all_signed_digest_pinned_components(self):
        manifest = json.loads(VALID.read_text(encoding="utf-8"))
        self.assertRegex(manifest["git_sha"], r"^[0-9a-f]{40}$")
        self.assertRegex(manifest["source_fingerprint"], r"^[0-9a-f]{64}$")
        self.assertEqual(manifest["chart_source_fingerprint"], manifest["source_fingerprint"])
        self.assertRegex(manifest["chart_version"], r"^\d+\.\d+\.\d+$")
        self.assertEqual(set(manifest["components"]), {"control-plane", "gateway", "operator", "worker"})
        for component in manifest["components"].values():
            self.assertRegex(component["image"], r"@sha256:[0-9a-f]{64}$")
            self.assertEqual(component["source_fingerprint"], manifest["source_fingerprint"])
            for field in ("sbom", "signature", "provenance"):
                self.assertRegex(component[field], r"^https://")

    def test_manifest_source_fingerprint_is_required_and_must_match_checkout(self):
        manifest = json.loads(VALID.read_text(encoding="utf-8"))
        missing = dict(manifest)
        del missing["source_fingerprint"]
        with self.subTest("missing"):
            path = ROOT / "scripts/fixtures/release-manifest-missing-source-fingerprint.json"
            path.write_text(json.dumps(missing), encoding="utf-8")
            try:
                result = subprocess.run(
                    [sys.executable, str(VALIDATOR), "--manifest", str(path)],
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("source_fingerprint", result.stderr)
            finally:
                path.unlink(missing_ok=True)

        mismatch = subprocess.run(
            [sys.executable, str(VALIDATOR), "--manifest", str(VALID),
             "--source-fingerprint", "0" * 64],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("source_fingerprint does not match", mismatch.stderr)

    def test_release_and_promote_workflows_are_restricted_and_sha_pinned(self):
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn('tags: ["v*.*.*"]', release)
        self.assertIn("id-token: write", release)
        self.assertIn("packages: write", release)
        self.assertIn("cosign sign", release)
        self.assertIn("attest", release)
        self.assertIn("source-fingerprint.py", release)
        self.assertIn("git diff --exit-code", release)
        self.assertIn("git ls-files --others --exclude-standard", release)
        self.assertIn("io.agentteams.source-fingerprint", release)
        self.assertIn("validate-release-manifest.py", release)
        self.assertRegex(release, r"uses: actions/setup-python@[0-9a-f]{40}")
        self.assertIn("python-version: '3.12'", release)
        self.assertIn("pip install --disable-pip-version-check", release)
        release_python_input = (ROOT / "requirements/release-ci-py312.in").read_text(encoding="utf-8")
        release_python_lock = (ROOT / "requirements/release-ci-py312.lock").read_text(encoding="utf-8")
        self.assertIn("-r ../eval/requirements-deepeval.txt", release_python_input)
        self.assertIn("-r ../deploy/chatgpt-plan/requirements.txt", release_python_input)
        self.assertIn("pypdf==6.19.0", release_python_lock)
        self.assertIn(
            "cosign sign-blob --yes --bundle release-manifest.json.sig release-manifest.json",
            release,
        )
        self.assertIn('--bundle "$chart.sig"', release)
        self.assertIn('--bundle "$SIGNATURE"', promote)
        self.assertIn('--bundle "$RELEASE_DIR/agentteams-java-${chart_version}.tgz.sig"', promote)
        self.assertIn('"/releases/download/" + $release_tag + "/release-manifest.json.sig"', release)
        self.assertIn("workflow_dispatch:", promote)
        self.assertIn("environment:", promote)
        self.assertIn("validate-release-manifest.py", promote)
        for workflow in (release, promote):
            for reference in re.findall(r"uses:\s*[^@\s]+@([^\s#]+)", workflow):
                self.assertRegex(reference, r"^[0-9a-f]{40}$", reference)

    def test_release_rejects_non_stable_tags_before_building_images(self):
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        tag_validation = release.find("RELEASE_TAG_INVALID")
        verification_gates = release.find("Run release verification gates")
        image_build = release.find("Build, sign, and attest service images")

        self.assertGreaterEqual(tag_validation, 0)
        self.assertLess(tag_validation, verification_gates)
        self.assertLess(tag_validation, image_build)
        self.assertIn(
            'if [[ ! "$RELEASE_TAG" =~ ^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$ ]]; then',
            release,
        )

    def test_release_prepares_java_21_and_helm_before_verification(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["release"]["steps"]
        gate_index = next(i for i, step in enumerate(steps)
                          if step.get("name") == "Run release verification gates")
        java_index = next(i for i, step in enumerate(steps)
                          if step.get("name") == "Set up Java 21")
        helm_index = next(i for i, step in enumerate(steps)
                          if step.get("name") == "Set up Helm")
        self.assertLess(java_index, gate_index)
        self.assertLess(helm_index, gate_index)
        self.assertEqual(steps[java_index]["with"]["java-version"], "21")
        self.assertEqual(steps[helm_index]["with"]["version"], "v4.2.4")

    def test_release_installs_python_verification_dependencies_from_version_locked_file(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["release"]["steps"]
        install = next(step for step in steps
                       if step.get("name") == "Install Python verification dependencies")
        self.assertIn("pip install --disable-pip-version-check", install["run"])
        self.assertIn("requirements/release-ci-py312.lock", install["run"])
        self.assertNotIn("eval/requirements-deepeval.txt", install["run"])

        lock_path = ROOT / "requirements/release-ci-py312.lock"
        self.assertTrue(lock_path.is_file(), "release Python lock file must be committed")
        lock = lock_path.read_text(encoding="utf-8")
        requirements = [line.strip().rstrip("\\").strip() for line in lock.splitlines()
                        if re.match(r"^[A-Za-z0-9_.-]+==", line)]
        self.assertTrue(requirements, "release Python lock must contain pinned requirements")
        self.assertTrue(all(re.fullmatch(r"[A-Za-z0-9_.-]+==[^\s;]+(?:\s*;\s*.+)?", line)
                            for line in requirements),
                        requirements)

    def test_release_builds_pinned_minio_test_images_before_integration_tests(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["release"]["steps"]
        gate_index = next(i for i, step in enumerate(steps)
                          if step.get("name") == "Run release verification gates")
        go_index = next(i for i, step in enumerate(steps)
                        if step.get("name") == "Set up Go for MinIO test images")
        image_index = next(i for i, step in enumerate(steps)
                           if step.get("name") == "Build pinned MinIO test images")
        self.assertLess(go_index, image_index)
        self.assertLess(image_index, gate_index)
        self.assertEqual(steps[go_index]["with"]["go-version"], "1.24.6")
        self.assertIn("./scripts/build-kind-minio-images.sh", steps[image_index]["run"])

    def test_release_authenticates_to_ghcr_before_pushing_images(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["release"]["steps"]
        login_index = next(i for i, step in enumerate(steps)
                           if step.get("name") == "Log in to GitHub Container Registry")
        build_index = next(i for i, step in enumerate(steps)
                           if step.get("name") == "Build, sign, and attest service images")
        login = steps[login_index]
        self.assertLess(login_index, build_index)
        self.assertEqual(login["uses"], "docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9")
        self.assertEqual(login["with"]["registry"], "ghcr.io")
        self.assertEqual(login["with"]["username"], "${{ github.actor }}")
        self.assertEqual(login["with"]["password"], "${{ github.token }}")

    def test_promote_trusts_only_this_repository_release_workflow_for_stable_tag(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8"))
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        tag_validation = promote.find("RELEASE_TAG_INVALID")
        download = promote.find("Download the existing release manifest")
        self.assertGreaterEqual(tag_validation, 0)
        self.assertLess(tag_validation, download)
        self.assertIn(
            'if [[ ! "$RELEASE_TAG" =~ ^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$ ]]; then',
            promote,
        )
        self.assertIn(
            'RELEASE_IDENTITY: ${{ github.server_url }}/${{ github.repository }}/.github/workflows/release.yml@refs/tags/${{ inputs.release_tag }}',
            promote,
        )
        self.assertGreaterEqual(promote.count('--certificate-identity "$RELEASE_IDENTITY"'), 5)
        self.assertNotIn("--certificate-identity-regexp", promote)

        step = next(step for step in workflow["jobs"]["promote"]["steps"]
                    if step.get("name") == "Validate stable release tag")
        for tag, expected_returncode in (("v1.2.3", 0), ("v01.2.3", 1), ("v1.2.3-rc.1", 1)):
            with self.subTest(tag=tag):
                result = subprocess.run(
                    ["bash", "-e", "-c", step["run"]],
                    env={**os.environ, "RELEASE_TAG": tag},
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(result.returncode, expected_returncode, result.stderr)

    def test_promote_executes_only_trusted_checkout_and_deploys_verified_chart_package(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8"))
        promote = workflow["jobs"]["promote"]
        self.assertEqual(promote.get("if"), "github.ref == 'refs/heads/main'")
        steps = promote["steps"]
        validation_index = next(i for i, step in enumerate(steps)
                                if step.get("name") == "Validate stable release tag")
        checkout_index = next(i for i, step in enumerate(steps)
                              if step.get("name") == "Check out deployment definitions")
        self.assertLess(validation_index, checkout_index)
        checkout = steps[checkout_index]
        self.assertEqual(checkout["with"].get("ref"), "main")

        download = next(step for step in steps
                        if step.get("name") == "Download the existing release manifest")
        self.assertIn('chart_version="${RELEASE_TAG#v}"', download["run"])
        self.assertNotIn("jq -er '.chart_version'", download["run"])
        manifest_signature = next(step for step in steps
                                  if step.get("name") == "Verify release manifest signature")
        self.assertIn('chart_version="${RELEASE_TAG#v}"', manifest_signature["run"])

        manifest_validation = next(step for step in steps
                                   if step.get("name") == "Validate manifest and target environment")
        self.assertIn('refs/tags/$RELEASE_TAG:refs/tags/$RELEASE_TAG', manifest_validation["run"])
        self.assertIn('git rev-parse "$RELEASE_TAG^{commit}"', manifest_validation["run"])
        self.assertIn('git merge-base --is-ancestor "$release_git_sha" origin/main',
                      manifest_validation["run"])

        preflight = next(step for step in steps
                         if step.get("name") == "Render and preflight digest-only deployment")
        deployment = next(step for step in steps
                          if step.get("name") == "Promote without rebuilding images")
        self.assertIn('helm lint "$chart"', preflight["run"])
        self.assertIn('helm template agentteams "$chart"', preflight["run"])
        self.assertIn('helm upgrade --install "$PROMOTION_RELEASE" "$RELEASE_CHART"',
                      deployment["run"])
        self.assertNotIn("deploy/helm/agentteams-java", "\n".join(s.get("run", "") for s in steps))

    def test_promotion_gate_accepts_healthy_metrics_and_rejects_missing_or_breached_metrics(self):
        policy = json.loads(PROMOTION_POLICY.read_text(encoding="utf-8"))
        healthy = {
            "error_rate": 0.001,
            "p95_latency_seconds": 0.4,
            "outbox_backlog": 0,
            "ready_replicas": {"control-plane": 3, "gateway": 3, "operator": 2},
        }
        self.assertEqual([], GATE.evaluate_metrics(healthy, policy))

        missing = dict(healthy)
        del missing["outbox_backlog"]
        self.assertTrue(any("outbox_backlog is missing" in breach
                            for breach in GATE.evaluate_metrics(missing, policy)))

        breached = dict(healthy)
        breached["error_rate"] = policy["max_error_rate"] + 0.001
        self.assertTrue(any("error_rate exceeds" in breach
                            for breach in GATE.evaluate_metrics(breached, policy)))

    def test_canary_policy_has_http_histograms_and_zero_5xx_fallback(self):
        policy = json.loads(PROMOTION_POLICY.read_text(encoding="utf-8"))
        self.assertIn("or vector(0)", policy["prometheus_queries"]["error_rate"])
        self.assertIn("http_server_requests_seconds_bucket",
                      policy["prometheus_queries"]["p95_latency_seconds"])
        for module in ("control-plane", "agent-gateway"):
            application = yaml.safe_load(
                (ROOT / module / "src/main/resources/application.yml").read_text(encoding="utf-8"))
            histogram = application["management"]["metrics"]["distribution"][
                "percentiles-histogram"]["http.server.requests"]
            self.assertIs(histogram, True, f"{module} must publish HTTP latency histogram buckets")

    def test_promote_workflow_has_fail_closed_gate_and_automatic_rollback(self):
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn("check-promotion-gate.py", promote)
        self.assertIn("canary-policy.json", promote)
        self.assertIn("PROMETHEUS_URL", promote)
        self.assertIn("helm history", promote)
        self.assertIn("helm rollback", promote)
        self.assertIn("if !", promote)
        self.assertIn("PROMOTION_GATE_FAIL", promote)

    def test_promote_workflow_preflights_observability_before_helm_upgrade(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8"))
        step = next(step for step in workflow["jobs"]["promote"]["steps"]
                    if step.get("name") == "Promote without rebuilding images")
        script = step["run"]
        self.assertIn("PROMOTION_PREFLIGHT_FAIL", script)
        self.assertLess(script.index("PROMOTION_PREFLIGHT_FAIL"), script.index("helm upgrade"))

    def test_promote_workflow_runs_helm_rollback_when_canary_gate_fails(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8"))
        step = next(step for step in workflow["jobs"]["promote"]["steps"]
                    if step.get("name") == "Promote without rebuilding images")
        script = step["run"].replace("${{ inputs.environment }}", "production")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            runner_temp = root / "runner-temp"
            release_dir = runner_temp / "release"
            release_dir.mkdir(parents=True)
            manifest = release_dir / "release-manifest.json"
            manifest.write_text(json.dumps({
                "environment": "production",
                "git_sha": "a" * 40,
                "components": {
                    "control-plane": {"image": "example/control-plane@sha256:" + "a" * 64},
                    "gateway": {"image": "example/gateway@sha256:" + "b" * 64},
                    "operator": {"image": "example/operator@sha256:" + "c" * 64},
                    "worker": {"image": "example/worker@sha256:" + "d" * 64},
                },
            }), encoding="utf-8")
            helm_rollback = root / "helm-rollback-called"
            helm_upgrade = root / "helm-upgrade-called"
            gate_calls = root / "gate-calls"
            (bin_dir / "helm").write_text(
                "#!/bin/sh\n"
                "case \"$1\" in\n"
                "  history) printf '[{\"id\":7,\"status\":\"deployed\"}]\\n' ;;\n"
                "  upgrade) touch \"$FAKE_HELM_UPGRADE_MARKER\" ;;\n"
                "  rollback) [ \"$3\" = 7 ] && touch \"$FAKE_HELM_ROLLBACK_MARKER\" ;;\n"
                "  *) exit 2 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            (bin_dir / "kubectl").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_python = bin_dir / "python3"
            fake_python.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                "  *scripts/check-promotion-gate.py*)\n"
                "    if [ \"${FAKE_PREFLIGHT_FAIL:-false}\" = true ]; then echo PROMOTION_GATE_FAIL >&2; exit 1; fi\n"
                "    calls=0; [ ! -f \"$FAKE_GATE_CALLS\" ] || calls=$(cat \"$FAKE_GATE_CALLS\"); calls=$((calls + 1)); echo \"$calls\" > \"$FAKE_GATE_CALLS\"\n"
                "    if [ \"$calls\" -eq 1 ]; then echo PROMOTION_GATE_OK; exit 0; fi\n"
                "    echo PROMOTION_GATE_FAIL >&2; exit 1 ;;\n"
                "  *) exit 2 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            for executable in (bin_dir / "helm", bin_dir / "kubectl", fake_python):
                executable.chmod(0o700)
            environment = {
                **os.environ,
                "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                "RUNNER_TEMP": str(runner_temp),
                "MANIFEST": str(manifest),
                "KUBECONFIG_B64": "ZmFrZQ==",
                "PROMETHEUS_URL": "http://127.0.0.1:1",
                "PROMOTION_NAMESPACE": "agentteams",
                "PROMOTION_RELEASE": "agentteams",
                "RELEASE_CHART": str(release_dir / "agentteams-java-1.2.3.tgz"),
                "RELEASE_VALUES": str(release_dir / "values-production.example.yaml"),
                "FAKE_HELM_UPGRADE_MARKER": str(helm_upgrade),
                "FAKE_HELM_ROLLBACK_MARKER": str(helm_rollback),
                "FAKE_GATE_CALLS": str(gate_calls),
            }
            preflight_environment = {**environment, "FAKE_PREFLIGHT_FAIL": "true"}
            preflight = subprocess.run(["bash", "-e", "-c", script], env=preflight_environment,
                                       capture_output=True, text=True)
            self.assertNotEqual(preflight.returncode, 0)
            self.assertIn("PROMOTION_PREFLIGHT_FAIL", preflight.stderr)
            self.assertFalse(helm_upgrade.exists())
            self.assertFalse(helm_rollback.exists())

            result = subprocess.run(["bash", "-e", "-c", script], env=environment,
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(helm_upgrade.exists())
            self.assertTrue(helm_rollback.exists())
            self.assertIn("PROMOTION_ROLLBACK_OK", result.stderr)
            self.assertNotIn("PROMOTION_OK", result.stdout)

    def test_promote_workflow_uses_signed_worker_digest_as_provisioner_image(self):
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn("worker=\"$(jq -er '.components.worker.image' \"$MANIFEST\")\"", promote)
        self.assertGreaterEqual(
            promote.count('--set-string "controlPlane.workerProvisioner.image=$worker"'), 3,
            "worker digest must be used by lint, render, and promotion",
        )


if __name__ == "__main__":
    unittest.main()
