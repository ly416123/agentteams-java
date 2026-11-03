#!/usr/bin/env python3
"""Contract tests for full normalized recovery metadata export."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPORTER = ROOT / "deploy/production/recovery/export-recovery-metadata.py"


class RecoveryMetadataExportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake_psql = self.bin / "psql"
        fake_psql.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "args=sys.argv[1:]\n"
            "db=args[args.index('--dbname')+1]\n"
            "sql=args[args.index('--command')+1]\n"
            "if 'FROM tasks' in sql:\n"
            "    print(os.environ['FAKE_RELATIONS'])\n"
            "elif db == 'manager-db':\n"
            "    print(os.environ['FAKE_MANAGER_REFS'])\n"
            "else:\n"
            "    print(os.environ['FAKE_CONTROL_PLANE_REFS'])\n",
            encoding="utf-8",
        )
        fake_psql.chmod(0o700)
        self.source_inventory = self.root / "source-inventory.json"
        self.source_inventory.write_text(json.dumps({"objects": [{
            "path": "artifacts/result.pdf", "size_bytes": 4, "sha256": "a" * 64,
        }]}), encoding="utf-8")
        self.source_inventory.chmod(0o600)
        self.env = {
            **os.environ,
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "RECOVERY_CONTROL_PLANE_PGHOST": "control-plane-db.internal",
            "RECOVERY_CONTROL_PLANE_PGDATABASE": "control-plane-db",
            "RECOVERY_CONTROL_PLANE_PGUSER": "restore_reader",
            "RECOVERY_CONTROL_PLANE_PGPASSWORD": "do-not-print-control-plane-secret",
            "RECOVERY_MANAGER_PGHOST": "manager-db.internal",
            "RECOVERY_MANAGER_PGDATABASE": "manager-db",
            "RECOVERY_MANAGER_PGUSER": "restore_reader",
            "RECOVERY_MANAGER_PGPASSWORD": "do-not-print-manager-secret",
            "FAKE_CONTROL_PLANE_REFS": json.dumps([
                {"path": "artifacts/result.pdf", "size_bytes": 4, "sha256": "a" * 64},
            ]),
            "FAKE_MANAGER_REFS": "[]",
            "FAKE_RELATIONS": json.dumps({
                "tasks": [{"id": "task-1"}],
                "attempts": [{"id": "attempt-1", "task_id": "task-1"}],
                "artifacts": [{"id": "artifact-1", "attempt_id": "attempt-1"}],
                "config_bindings": [{"id": "binding-1", "snapshot_id": "snapshot-1"}],
                "config_snapshots": [{"id": "snapshot-1"}],
                "quota_reservations": [{"id": "quota-1", "task_id": "task-1"}],
                "sandboxes": [{"id": "sandbox-1", "task_id": "task-1", "attempt_id": "attempt-1"}],
                "outbox": [{"id": "event-1", "aggregate_id": "task-1", "aggregate_type": "task"}],
            }),
        }

    def run_export(self, output: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(EXPORTER), "--source-inventory", str(self.source_inventory),
             "--output", str(output)],
            check=False,
            capture_output=True,
            text=True,
            env=self.env,
        )

    def test_exports_relations_and_object_refs_as_checker_ready_metadata(self) -> None:
        output = self.root / "metadata.json"

        result = self.run_export(output)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("RECOVERY_METADATA_EXPORT_OK\n", result.stdout)
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        metadata = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual({
            "tasks", "attempts", "artifacts", "config_bindings", "config_snapshots",
            "quota_reservations", "sandboxes", "outbox", "object_references",
        }, set(metadata))
        self.assertEqual("artifact-1", metadata["artifacts"][0]["id"])
        self.assertEqual("artifacts/result.pdf", metadata["object_references"][0]["path"])
        rendered = result.stdout + result.stderr + output.read_text(encoding="utf-8")
        self.assertNotIn("do-not-print", rendered)

    def test_fails_closed_for_unknown_aggregate_types_or_payload_fields(self) -> None:
        relations = json.loads(self.env["FAKE_RELATIONS"])
        relations["outbox"] = [{"id": "event-2", "aggregate_id": "someone", "aggregate_type": "agent"}]
        self.env["FAKE_RELATIONS"] = json.dumps(relations)
        output = self.root / "metadata.json"

        result = self.run_export(output)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("RECOVERY_METADATA_EXPORT_FAIL\n", result.stderr)
        self.assertFalse(output.exists())
        self.assertNotIn("someone", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
