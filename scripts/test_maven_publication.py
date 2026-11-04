#!/usr/bin/env python3
"""Contract tests for idempotent Maven package publication."""

import subprocess
import sys
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CLASSIFIER = ROOT / "scripts/check-maven-publication.py"
RELEASE_WORKFLOW = ROOT / ".github/workflows/release.yml"


class MavenPublicationContractTest(unittest.TestCase):
    def run_classifier(self, *published):
        return subprocess.run(
            [sys.executable, str(CLASSIFIER), "--expected", "parent:pom",
             "contracts:pom", "contracts:jar", "runtime:pom", "runtime:jar",
             "--published", *published],
            capture_output=True,
            text=True,
        )

    def test_no_existing_packages_allows_first_publish(self):
        result = self.run_classifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "PUBLISH")

    def test_complete_existing_release_skips_republish(self):
        result = self.run_classifier("runtime:jar", "parent:pom", "contracts:jar",
                                     "runtime:pom", "contracts:pom")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "SKIP")

    def test_partial_existing_release_fails_with_recovery_signal(self):
        result = self.run_classifier("parent:pom", "contracts:pom")
        self.assertEqual(result.returncode, 1)
        self.assertIn("MAVEN_PUBLISH_PARTIAL", result.stderr)
        self.assertIn("new patch tag", result.stderr)
        self.assertNotIn("Delete", result.stderr)

    def test_release_workflow_checks_package_state_before_deploying(self):
        workflow = yaml.safe_load(RELEASE_WORKFLOW.read_text(encoding="utf-8"))
        steps = workflow["jobs"]["release"]["steps"]
        publish = next(step for step in steps
                       if step.get("name") == "Publish versioned shared core Maven artifacts")
        script = publish["run"]
        self.assertIn("check-maven-publication.py", script)
        self.assertLess(script.index("check-maven-publication.py"),
                        script.index("deploy"))
        self.assertIn("maven.pkg.github.com/ly416123/agentteams-java", script)
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY"', script)
        self.assertIn("$maven_repository/io/agentteams/$package/$release_version", script)
        self.assertIn("agentteams-runtime:jar", script)
        self.assertIn("artifact_file=", script)
        self.assertIn("curl --silent --show-error --location --max-redirs 3", script)
        self.assertIn("--proto-redir '=https' --netrc-file /dev/null", script)
        self.assertNotIn("--location-trusted", script)
        self.assertIn("200)", script)
        self.assertNotIn("200|302)", script)
        self.assertIn("agentteams-java", script)
        self.assertIn("GITHUB_TOKEN", publish["env"])
        self.assertIn("GH_TOKEN", publish["env"])
        self.assertEqual(workflow["permissions"]["packages"], "write")


if __name__ == "__main__":
    unittest.main()
