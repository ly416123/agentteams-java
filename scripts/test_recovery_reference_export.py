#!/usr/bin/env python3
"""Contract tests for read-only recovery object-reference export."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPORTER = ROOT / "deploy/production/recovery/export-object-references.py"


class RecoveryReferenceExportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.sql_log = self.root / "sql.log"
        self.source_inventory = self.root / "source-inventory.json"
        fake_psql = self.bin / "psql"
        fake_psql.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "with open(os.environ['FAKE_SQL_LOG'], 'a', encoding='utf-8') as stream:\n"
            "    stream.write(os.environ.get('PGOPTIONS', '') + ' :: ' + ' '.join(sys.argv[1:]) + '\\\\n')\n"
            "db = os.environ.get('PGDATABASE')\n"
            "print(os.environ['FAKE_' + db.upper().replace('-', '_') + '_ROWS'])\n",
            encoding="utf-8",
        )
        fake_psql.chmod(0o700)
        self.env = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "FAKE_SQL_LOG": str(self.sql_log),
            "RECOVERY_CONTROL_PLANE_PGDATABASE": "control-plane-db",
            "RECOVERY_CONTROL_PLANE_PGHOST": "control-plane-db.internal",
            "RECOVERY_CONTROL_PLANE_PGUSER": "restore_reader",
            "RECOVERY_CONTROL_PLANE_PGPASSWORD": "do-not-print-control-plane-secret",
            "RECOVERY_MANAGER_PGDATABASE": "manager-db",
            "RECOVERY_MANAGER_PGHOST": "manager-db.internal",
            "RECOVERY_MANAGER_PGUSER": "restore_reader",
            "RECOVERY_MANAGER_PGPASSWORD": "do-not-print-manager-secret",
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

    def test_exports_all_database_owned_references_without_exposing_credentials(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = json.dumps([
            {"path": "artifacts/a.pdf", "size_bytes": 4, "sha256": "A" * 64},
            {"path": "skills/x.zip", "size_bytes": 2, "sha256": "b" * 64},
        ])
        self.env["FAKE_MANAGER_DB_ROWS"] = json.dumps([
            {"path": "conversations/s/files/f.txt", "size_bytes": 1, "sha256": "c" * 64},
        ])
        self.write_inventory([
            {"path": "artifacts/a.pdf", "size_bytes": 4, "sha256": "a" * 64},
            {"path": "skills/x.zip", "size_bytes": 2, "sha256": "b" * 64},
            {"path": "conversations/s/files/f.txt", "size_bytes": 1, "sha256": "c" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("RECOVERY_REFERENCE_EXPORT_OK references=3\n", result.stdout)
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertEqual({
            "object_references": [
                {"path": "artifacts/a.pdf", "size_bytes": 4, "sha256": "a" * 64},
                {"path": "conversations/s/files/f.txt", "size_bytes": 1, "sha256": "c" * 64},
                {"path": "skills/x.zip", "size_bytes": 2, "sha256": "b" * 64},
            ]
        }, json.loads(output.read_text(encoding="utf-8")))
        combined = result.stdout + result.stderr + output.read_text(encoding="utf-8")
        self.assertNotIn("do-not-print", combined)
        sql = self.sql_log.read_text(encoding="utf-8")
        for table in ("artifacts", "config_files", "config_uploads", "task_files",
                      "skill_versions", "task_result_artifacts", "conversation_files"):
            self.assertIn(table, sql)
        self.assertNotIn("do-not-print", sql)
        self.assertEqual(2, sql.count("default_transaction_read_only=on"))
        self.assertEqual(2, sql.count("statement_timeout=30000"))

    def test_resolves_task_file_result_references_to_owned_output_object_keys(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = json.dumps([
            {"path": "tasks/child/files/file/report.pdf", "size_bytes": 4, "sha256": "a" * 64},
        ])
        self.env["FAKE_MANAGER_DB_ROWS"] = "[]"
        self.write_inventory([
            {"path": "tasks/child/files/file/report.pdf", "size_bytes": 4, "sha256": "a" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertEqual(0, result.returncode, result.stderr)
        sql = self.sql_log.read_text(encoding="utf-8")
        self.assertIn("CASE WHEN result.source_task_id IS NULL THEN result.storage_ref", sql)
        self.assertIn("LEFT JOIN task_files", sql)
        self.assertIn("task_file.task_id = result.source_task_id", sql)
        self.assertIn("task_file.id = result.source_file_id", sql)
        self.assertIn("source_file_id", sql)
        self.assertIn("storage_key", sql)
        self.assertIn("role = 'OUTPUT'", sql)
        self.assertIn("status = 'AVAILABLE'", sql)

    def test_binds_legacy_manager_reference_to_matching_source_inventory_hash(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = "[]"
        self.env["FAKE_MANAGER_DB_ROWS"] = json.dumps([
            {"path": "conversations/s/files/legacy.txt", "size_bytes": 9, "sha256": None},
        ])
        self.write_inventory([
            {"path": "conversations/s/files/legacy.txt", "size_bytes": 9, "sha256": "d" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("RECOVERY_REFERENCE_EXPORT_OK references=1\n", result.stdout)
        self.assertEqual(
            {"object_references": [{"path": "conversations/s/files/legacy.txt",
                                     "size_bytes": 9, "sha256": "d" * 64}]},
            json.loads(output.read_text(encoding="utf-8")))

    def test_rejects_conflicting_duplicate_keys_and_unsafe_paths(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = json.dumps([
            {"path": "../escape", "size_bytes": 1, "sha256": "a" * 64},
        ])
        self.env["FAKE_MANAGER_DB_ROWS"] = "[]"
        self.write_inventory([
            {"path": "artifacts/safe", "size_bytes": 1, "sha256": "a" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("RECOVERY_REFERENCE_EXPORT_FAIL\n", result.stderr)
        self.assertFalse(output.exists())
        self.assertNotIn("escape", result.stdout + result.stderr)

    def test_rejects_database_hash_that_disagrees_with_source_backup_inventory(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = json.dumps([
            {"path": "artifacts/a.pdf", "size_bytes": 4, "sha256": "b" * 64},
        ])
        self.env["FAKE_MANAGER_DB_ROWS"] = "[]"
        self.write_inventory([
            {"path": "artifacts/a.pdf", "size_bytes": 4, "sha256": "a" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("RECOVERY_REFERENCE_EXPORT_FAIL\n", result.stderr)
        self.assertFalse(output.exists())

    def test_rejects_conflicting_duplicate_reference_metadata(self) -> None:
        self.env["FAKE_CONTROL_PLANE_DB_ROWS"] = json.dumps([
            {"path": "shared/object.bin", "size_bytes": 4, "sha256": "a" * 64},
        ])
        self.env["FAKE_MANAGER_DB_ROWS"] = json.dumps([
            {"path": "shared/object.bin", "size_bytes": 3, "sha256": None},
        ])
        self.write_inventory([
            {"path": "shared/object.bin", "size_bytes": 4, "sha256": "a" * 64},
        ])
        output = self.root / "references.json"

        result = self.run_export(output)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("RECOVERY_REFERENCE_EXPORT_FAIL\n", result.stderr)
        self.assertFalse(output.exists())

    def write_inventory(self, objects: list[dict[str, object]]) -> None:
        self.source_inventory.write_text(
            json.dumps({"objects": objects}), encoding="utf-8")
        self.source_inventory.chmod(0o600)


if __name__ == "__main__":
    unittest.main()
