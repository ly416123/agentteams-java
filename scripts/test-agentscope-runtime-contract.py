#!/usr/bin/env python3
"""Check that CI never injects real model credentials into the Fake Model path."""

from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path


FORBIDDEN_MODEL_KEY_NAMES = ("DEEPSEEK_API_KEY", "QWENPAW_API_KEY")
SECRET_REFERENCE = re.compile(r"\$\{\{\s*secrets\.[^}]+\}\}", re.IGNORECASE)
SECRET_ENV_REFERENCE = re.compile(
    r"(?:env|environment)\s*[:=][^\n]*\b(?:api[_-]?key|token|secret|password)\b",
    re.IGNORECASE,
)
AUTHORIZATION_SECRET_REFERENCE = re.compile(
    r"authorization\s*:\s*(?:bearer\s+)?(?:\$\{\{|\$[A-Za-z_][A-Za-z0-9_]*|"
    r"\$\([A-Za-z_][A-Za-z0-9_]*\))",
    re.IGNORECASE,
)


def _is_local_script(path: Path, root: Path) -> bool:
    """Local scripts may read developer-provided credentials and are not CI config."""

    try:
        path.relative_to(root / "scripts")
        return True
    except ValueError:
        return False


def ci_configuration_files(root: Path) -> list[Path]:
    """Return workflow and executable CI configuration files, excluding local scripts."""

    candidates: set[Path] = set()
    workflow_dir = root / ".github" / "workflows"
    if workflow_dir.is_dir():
        candidates.update(path for path in workflow_dir.rglob("*") if path.suffix in {".yml", ".yaml"})

    standard_names = {
        ".gitlab-ci.yml",
        ".travis.yml",
        "azure-pipelines.yml",
        "azure-pipelines.yaml",
        "Jenkinsfile",
        "Makefile",
        "GNUmakefile",
        "makefile",
    }
    candidates.update(path for path in root.iterdir() if path.name in standard_names and path.is_file())

    for directory_name in (".circleci", ".ci", "ci", "build"):
        directory = root / directory_name
        if directory.is_dir():
            candidates.update(path for path in directory.rglob("*") if path.is_file() and path.stat().st_mode & 0o111)

    return sorted(path for path in candidates if not _is_local_script(path, root))


def credential_findings(root: Path) -> list[str]:
    findings: list[str] = []
    for path in ci_configuration_files(root):
        text = path.read_text(encoding="utf-8")
        for key_name in FORBIDDEN_MODEL_KEY_NAMES:
            if key_name in text:
                findings.append(f"{path.relative_to(root)} mentions forbidden {key_name}")
        if SECRET_REFERENCE.search(text):
            findings.append(f"{path.relative_to(root)} references a GitHub secret")
        if SECRET_ENV_REFERENCE.search(text):
            findings.append(f"{path.relative_to(root)} injects a credential-like environment value")
        if AUTHORIZATION_SECRET_REFERENCE.search(text):
            findings.append(f"{path.relative_to(root)} injects an Authorization secret")
    return findings


class AgentScopeRuntimeContractTest(unittest.TestCase):
    def test_repository_ci_has_no_real_model_credential_injection(self) -> None:
        root = Path(__file__).resolve().parents[1]
        self.assertEqual([], credential_findings(root))

    def test_local_script_key_reads_are_not_scanned_as_ci(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github" / "workflows"
            workflow.mkdir(parents=True)
            (workflow / "ci.yml").write_text("run: python scripts/local-model.py\n", encoding="utf-8")
            scripts = root / "scripts"
            scripts.mkdir()
            (scripts / "local-model.py").write_text(
                "key = os.environ.get('DEEPSEEK_API_KEY')\n", encoding="utf-8"
            )

            self.assertEqual([], credential_findings(root))

    def test_workflow_secret_and_authorization_injection_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github" / "workflows"
            workflow.mkdir(parents=True)
            (workflow / "ci.yml").write_text(
                """
                env:
                  MODEL_TOKEN: ${{ secrets.MODEL_TOKEN }}
                run: curl -H 'Authorization: Bearer $MODEL_TOKEN' https://example.invalid
                """,
                encoding="utf-8",
            )

            findings = credential_findings(root)
            self.assertEqual(2, len(findings))
            self.assertTrue(any("GitHub secret" in finding for finding in findings))
            self.assertTrue(any("Authorization" in finding for finding in findings))


if __name__ == "__main__":
    unittest.main()
