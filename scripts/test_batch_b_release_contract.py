#!/usr/bin/env python3
"""Contract tests for signed, digest-pinned release promotion."""

import json
import importlib.util
import re
import subprocess
import sys
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
        self.assertIn("workflow_dispatch:", promote)
        self.assertIn("environment:", promote)
        self.assertIn("validate-release-manifest.py", promote)
        self.assertIn("source-fingerprint.py", promote)
        self.assertIn("--source-fingerprint", promote)
        for workflow in (release, promote):
            for reference in re.findall(r"uses:\s*[^@\s]+@([^\s#]+)", workflow):
                self.assertRegex(reference, r"^[0-9a-f]{40}$", reference)

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

    def test_promote_workflow_has_fail_closed_gate_and_automatic_rollback(self):
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn("check-promotion-gate.py", promote)
        self.assertIn("canary-policy.json", promote)
        self.assertIn("PROMETHEUS_URL", promote)
        self.assertIn("helm history", promote)
        self.assertIn("helm rollback", promote)
        self.assertIn("if !", promote)
        self.assertIn("PROMOTION_GATE_FAIL", promote)


if __name__ == "__main__":
    unittest.main()
