#!/usr/bin/env python3
"""Contract tests for signed, digest-pinned release promotion."""

import json
import re
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALID = ROOT / "scripts/fixtures/release-manifest-valid.json"
INVALID = ROOT / "scripts/fixtures/release-manifest-invalid.json"
VALIDATOR = ROOT / "scripts/validate-release-manifest.py"


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
        self.assertRegex(manifest["chart_version"], r"^\d+\.\d+\.\d+$")
        self.assertEqual(set(manifest["components"]), {"control-plane", "gateway", "operator", "worker"})
        for component in manifest["components"].values():
            self.assertRegex(component["image"], r"@sha256:[0-9a-f]{64}$")
            for field in ("sbom", "signature", "provenance"):
                self.assertRegex(component[field], r"^https://")

    def test_release_and_promote_workflows_are_restricted_and_sha_pinned(self):
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        promote = (ROOT / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn('tags: ["v*.*.*"]', release)
        self.assertIn("id-token: write", release)
        self.assertIn("packages: write", release)
        self.assertIn("cosign sign", release)
        self.assertIn("attest", release)
        self.assertIn("validate-release-manifest.py", release)
        self.assertIn("workflow_dispatch:", promote)
        self.assertIn("environment:", promote)
        self.assertIn("validate-release-manifest.py", promote)
        for workflow in (release, promote):
            for reference in re.findall(r"uses:\s*[^@\s]+@([^\s#]+)", workflow):
                self.assertRegex(reference, r"^[0-9a-f]{40}$", reference)


if __name__ == "__main__":
    unittest.main()
