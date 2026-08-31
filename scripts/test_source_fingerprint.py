#!/usr/bin/env python3
"""Tests for the canonical source tree fingerprint command."""

from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/source-fingerprint.py"


class SourceFingerprintTest(unittest.TestCase):
    def run_fingerprint(self, root: Path, *args: str) -> subprocess.CompletedProcess[str]:
        command = list(args) if args else ["fingerprint"]
        return subprocess.run(
            [sys.executable, str(SCRIPT), *command, "--root", str(root)],
            text=True,
            capture_output=True,
            check=False,
        )

    def init_repo(self, root: Path) -> None:
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.name", "Fingerprint Test"], check=True)

    def track(self, root: Path, *paths: str) -> None:
        subprocess.run(["git", "-C", str(root), "add", *paths], check=True)

    def test_fingerprint_is_stable_and_follows_path_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.init_repo(root)
            (root / "z.txt").write_text("z\n", encoding="utf-8")
            (root / "a.txt").write_text("a\n", encoding="utf-8")
            self.track(root, "z.txt", "a.txt")

            first = self.run_fingerprint(root)
            second = self.run_fingerprint(root)

            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertRegex(first.stdout.strip(), r"^[0-9a-f]{64}$")
            self.assertEqual(first.stdout, second.stdout)

            entries = []
            for path in ("a.txt", "z.txt"):
                digest = hashlib.sha256((root / path).read_bytes()).hexdigest()
                entries.append(f"{path}\0{digest}\n".encode("utf-8"))
            expected = hashlib.sha256(b"".join(entries)).hexdigest()
            self.assertEqual(first.stdout.strip(), expected)

    def test_tracked_content_changes_fingerprint_but_untracked_content_does_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.init_repo(root)
            tracked = root / "tracked.txt"
            untracked = root / "untracked.txt"
            tracked.write_text("one", encoding="utf-8")
            self.track(root, "tracked.txt")
            before = self.run_fingerprint(root).stdout

            untracked.write_text("outside the index", encoding="utf-8")
            self.assertEqual(self.run_fingerprint(root).stdout, before)

            tracked.write_text("two", encoding="utf-8")
            self.assertNotEqual(self.run_fingerprint(root).stdout, before)

    def test_excluded_tracked_paths_do_not_change_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.init_repo(root)
            (root / "src").mkdir()
            (root / "src" / "main.txt").write_text("main", encoding="utf-8")
            for relative in ("target/noise.txt", "console/node_modules/noise.txt", ".local/noise.txt"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("first", encoding="utf-8")
            self.track(root, "src/main.txt", "target/noise.txt", "console/node_modules/noise.txt", ".local/noise.txt")
            initial = self.run_fingerprint(root)
            self.assertEqual(initial.returncode, 0, initial.stderr)
            self.assertRegex(initial.stdout.strip(), r"^[0-9a-f]{64}$")
            before = initial.stdout

            (root / "target/noise.txt").write_text("second", encoding="utf-8")
            (root / "console/node_modules/noise.txt").write_text("second", encoding="utf-8")
            (root / ".local/noise.txt").write_text("second", encoding="utf-8")
            self.assertEqual(self.run_fingerprint(root).stdout, before)

    def test_verify_returns_nonzero_for_a_mismatched_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.init_repo(root)
            (root / "tracked.txt").write_text("one", encoding="utf-8")
            self.track(root, "tracked.txt")
            actual = self.run_fingerprint(root).stdout.strip()

            self.assertEqual(self.run_fingerprint(root, "verify", actual).returncode, 0)
            mismatch = self.run_fingerprint(root, "verify", "0" * 64)
            self.assertNotEqual(mismatch.returncode, 0)
            self.assertIn("fingerprint mismatch", mismatch.stderr)

    def test_missing_root_fails_closed(self):
        result = self.run_fingerprint(Path(tempfile.gettempdir()) / "agentteams-missing-fingerprint-root")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("repository root", result.stderr)


if __name__ == "__main__":
    unittest.main()
