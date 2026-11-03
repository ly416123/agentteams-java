#!/usr/bin/env python3
"""Contract tests for production ingress, egress and recovery safeguards."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CHART = ROOT / "deploy/helm/agentteams-java"
RECOVERY_DIR = ROOT / "deploy/production/recovery"
PREFLIGHT = RECOVERY_DIR / "preflight.sh"
CONSISTENCY = RECOVERY_DIR / "consistency-check.py"
RESTORE = RECOVERY_DIR / "restore.sh"
INVENTORY = RECOVERY_DIR / "object-inventory.py"
VALIDATOR_PATH = ROOT / "scripts/validate-production-network.py"
VALIDATOR_SPEC = importlib.util.spec_from_file_location("validate_production_network", VALIDATOR_PATH)
VALIDATOR = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR)


class BatchBRecoveryContractTest(unittest.TestCase):
    @staticmethod
    def recovery_metadata(object_references=None):
        return {
            "tasks": [], "attempts": [], "artifacts": [], "config_bindings": [],
            "config_snapshots": [], "quota_reservations": [], "sandboxes": [], "outbox": [],
            "object_references": object_references or [],
        }

    def run_consistency_check(self, metadata, inventory):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata_path = root / "metadata.json"
            inventory_path = root / "objects.json"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
            inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(CONSISTENCY), "--input", str(metadata_path),
                 "--object-inventory", str(inventory_path)],
                capture_output=True, text=True,
            )

    def test_recovery_consistency_matches_database_refs_to_object_inventory(self):
        reference = {"path": "tasks/task-1/result.pdf", "size_bytes": 4, "sha256": "a" * 64}
        result = self.run_consistency_check(
            self.recovery_metadata([reference]), {"objects": [reference]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RECOVERY_CONSISTENCY_OK", result.stdout)

    def test_recovery_consistency_rejects_missing_objects_and_metadata_mismatch(self):
        reference = {"path": "private/object-key", "size_bytes": 4, "sha256": "a" * 64}
        cases = (
            {"objects": []},
            {"objects": [{**reference, "size_bytes": 5}]},
            {"objects": [{**reference, "sha256": "b" * 64}]},
        )
        for inventory in cases:
            with self.subTest(inventory=inventory):
                result = self.run_consistency_check(
                    self.recovery_metadata([reference]), inventory)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("RECOVERY_CONSISTENCY_FAIL", result.stderr)
                self.assertNotIn(reference["path"], result.stderr + result.stdout)
                self.assertNotIn(reference["sha256"], result.stderr + result.stdout)

    def test_recovery_consistency_rejects_duplicate_or_unsafe_inventory_paths(self):
        reference = {"path": "tasks/a.bin", "size_bytes": 4, "sha256": "a" * 64}
        duplicate = self.run_consistency_check(
            self.recovery_metadata([reference]), {"objects": [reference, reference]})
        traversal_reference = {**reference, "path": "../private"}
        traversal = self.run_consistency_check(
            self.recovery_metadata([traversal_reference]), {"objects": [traversal_reference]})
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertNotEqual(traversal.returncode, 0)
        self.assertNotIn("../private", duplicate.stderr + duplicate.stdout + traversal.stderr + traversal.stdout)

    def test_recovery_consistency_rejects_conflicting_refs_and_unexpected_object_fields(self):
        reference = {"path": "tasks/a.bin", "size_bytes": 4, "sha256": "a" * 64}
        conflicting = {**reference, "size_bytes": 5}
        result = self.run_consistency_check(
            self.recovery_metadata([reference, conflicting]), {"objects": [reference]})
        self.assertNotEqual(result.returncode, 0)
        secret = {**reference, "prompt": "must-not-be-accepted"}
        result = self.run_consistency_check(
            self.recovery_metadata([secret]), {"objects": [reference]})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("must-not-be-accepted", result.stdout + result.stderr)

    def test_recovery_consistency_rejects_payload_fields_in_reference_metadata(self):
        safe_metadata = self.recovery_metadata()
        safe_metadata["tasks"] = [{"id": "task-1", "prompt": "private task source"}]
        result = self.run_consistency_check(safe_metadata, {"objects": []})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("private task source", result.stdout + result.stderr)

        extra_top_level = self.recovery_metadata()
        extra_top_level["credentials"] = "private credential"
        result = self.run_consistency_check(extra_top_level, {"objects": []})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("private credential", result.stdout + result.stderr)

    def test_recovery_consistency_accepts_schema_optional_artifact_and_quota_task_refs(self):
        metadata = self.recovery_metadata()
        metadata["artifacts"] = [{"id": "artifact-without-attempt"}]
        metadata["quota_reservations"] = [{"id": "quota-without-task"}]

        result = self.run_consistency_check(metadata, {"objects": []})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RECOVERY_CONSISTENCY_OK", result.stdout)

    def test_object_inventory_hashes_files_without_emitting_names_or_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "restored"
            root.mkdir()
            (root / "sensitive-name.bin").write_bytes(b"sensitive payload")
            output = Path(directory) / "objects.json"
            result = subprocess.run(
                [sys.executable, str(INVENTORY), "--root", str(root), "--output", str(output)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn("sensitive-name.bin", result.stdout + result.stderr)
            self.assertNotIn("sensitive payload", result.stdout + result.stderr)
            manifest = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(manifest["objects"], [{
                "path": "sensitive-name.bin", "size_bytes": 17,
                "sha256": hashlib.sha256(b"sensitive payload").hexdigest(),
            }])
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_object_inventory_refuses_symlinks_and_output_inside_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "restored"
            root.mkdir()
            target = Path(directory) / "target"
            target.write_text("source", encoding="utf-8")
            (root / "link").symlink_to(target)
            outside = Path(directory) / "objects.json"
            result = subprocess.run(
                [sys.executable, str(INVENTORY), "--root", str(root), "--output", str(outside)],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("RECOVERY_OBJECT_INVENTORY_FAIL", result.stderr)
            self.assertNotIn(str(target), result.stderr + result.stdout)
            (root / "link").unlink()
            inside = root / "objects.json"
            result = subprocess.run(
                [sys.executable, str(INVENTORY), "--root", str(root), "--output", str(inside)],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)

    def test_recovery_orchestrator_is_explicit_and_fail_closed(self):
        self.assertTrue(RESTORE.is_file())
        restore = RESTORE.read_text(encoding="utf-8")
        self.assertIn("--execute", restore)
        self.assertIn("RECOVERY_APPROVAL_ID", restore)
        self.assertIn("consistency-check.py", restore)
        self.assertIn("RECOVERY_CLOSE_ENTRYPOINT_COMMAND", restore)
        self.assertIn("RECOVERY_RESTORE_FAIL", restore)
        self.assertIn("RECOVERY_RESTORE_OK", restore)
        self.assertLess(restore.index("RECOVERY_CLOSE_ENTRYPOINT_COMMAND"),
                        restore.index("RECOVERY_OPEN_ENTRYPOINT_COMMAND"))

    def test_recovery_orchestrator_dry_run_validates_metadata_without_hooks(self):
        metadata = {
            "tasks": [{"id": "task-1"}],
            "attempts": [{"id": "attempt-1", "task_id": "task-1"}],
            "artifacts": [{"id": "artifact-1", "attempt_id": "attempt-1"}],
            "config_bindings": [{"id": "binding-1", "snapshot_id": "snapshot-1"}],
            "config_snapshots": [{"id": "snapshot-1"}],
            "quota_reservations": [{"id": "quota-1", "task_id": "task-1"}],
            "sandboxes": [{"id": "sandbox-1", "task_id": "task-1"}],
            "outbox": [{"id": "event-1", "aggregate_id": "task-1"}],
            "object_references": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            metadata_path = Path(directory) / "metadata.json"
            inventory_path = Path(directory) / "objects.json"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
            inventory_path.write_text(json.dumps({"objects": []}), encoding="utf-8")
            result = subprocess.run(
                [str(RESTORE), "--environment", "production", "--backup-id", "pg-20260827-0100",
                 "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
                 "s3://backup.example/agentteams", "--manifest-digest", "sha256:" + "a" * 64,
                 "--metadata", str(metadata_path), "--object-inventory", str(inventory_path),
                 "--approval-id", "change-123"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("RECOVERY_PLAN_OK", result.stdout)

    def test_recovery_orchestrator_closes_entrypoint_when_a_phase_fails(self):
        metadata = {
            "tasks": [{"id": "task-1"}],
            "attempts": [{"id": "attempt-1", "task_id": "task-1"}],
            "artifacts": [{"id": "artifact-1", "attempt_id": "attempt-1"}],
            "config_bindings": [{"id": "binding-1", "snapshot_id": "snapshot-1"}],
            "config_snapshots": [{"id": "snapshot-1"}],
            "quota_reservations": [{"id": "quota-1", "task_id": "task-1"}],
            "sandboxes": [{"id": "sandbox-1", "task_id": "task-1"}],
            "outbox": [{"id": "event-1", "aggregate_id": "task-1"}],
            "object_references": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata_path = root / "recovered-metadata.json"
            marker = root / "entrypoint-closed"
            inventory_path = root / "objects.json"
            hook = root / "hook.sh"
            hook.write_text(
                "#!/bin/sh\n"
                f"[ \"$RECOVERY_PHASE\" = close-entrypoint ] && touch '{marker}'\n"
                "[ \"$RECOVERY_PHASE\" != restore-postgres ]\n",
                encoding="utf-8",
            )
            hook.chmod(0o700)
            hook_variables = [
                "RECOVERY_PAUSE_ENTRYPOINT_COMMAND", "RECOVERY_PAUSE_SCHEDULER_COMMAND",
                "RECOVERY_RESTORE_POSTGRES_COMMAND", "RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND",
                "RECOVERY_RESTORE_NATS_COMMAND", "RECOVERY_FLYWAY_VALIDATE_COMMAND",
                "RECOVERY_CONSISTENCY_CHECK_COMMAND", "RECOVERY_START_CONTROL_PLANE_COMMAND",
                "RECOVERY_START_GATEWAY_COMMAND", "RECOVERY_START_OPERATOR_COMMAND",
                "RECOVERY_START_WORKER_COMMAND", "RECOVERY_REPLAY_OUTBOX_COMMAND",
                "RECOVERY_SMOKE_COMMAND", "RECOVERY_OPEN_ENTRYPOINT_COMMAND",
                "RECOVERY_CLOSE_ENTRYPOINT_COMMAND",
            ]
            environment = {name: str(hook) for name in hook_variables}
            environment["RECOVERY_APPROVAL_ID"] = "change-123"
            result = subprocess.run(
                [str(RESTORE), "--environment", "production", "--backup-id", "pg-20260827-0100",
                 "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
                 "s3://backup.example/agentteams", "--manifest-digest", "sha256:" + "a" * 64,
                 "--metadata", str(metadata_path), "--object-inventory", str(inventory_path),
                 "--approval-id", "change-123", "--execute"],
                env={**os.environ, **environment}, capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("RECOVERY_RESTORE_FAIL", result.stderr)
            self.assertTrue(marker.exists())

    def test_recovery_orchestrator_exports_and_checks_restored_objects_before_startup(self):
        reference = {"path": "tasks/result.pdf", "size_bytes": 4, "sha256": "a" * 64}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata_source = root / "source-metadata.json"
            inventory_source = root / "source-objects.json"
            metadata_source.write_text(json.dumps(self.recovery_metadata([reference])), encoding="utf-8")
            inventory_source.write_text(json.dumps({"objects": [reference]}), encoding="utf-8")
            metadata_path = root / "recovered-metadata.json"
            inventory_path = root / "recovered-objects.json"
            closed = root / "closed"
            started = root / "started"
            opened = root / "opened"
            hook = root / "hook.sh"
            hook.write_text(
                "#!/bin/sh\n"
                "if [ \"$RECOVERY_PHASE\" = consistency-check ]; then\n"
                f"  cp '{metadata_source}' \"$RECOVERY_METADATA_FILE\"\n"
                f"  cp '{inventory_source}' \"$RECOVERY_OBJECT_INVENTORY_FILE\"\n"
                "fi\n"
                f"[ \"$RECOVERY_PHASE\" != close-entrypoint ] || touch '{closed}'\n"
                f"[ \"$RECOVERY_PHASE\" != start-control-plane ] || touch '{started}'\n"
                f"[ \"$RECOVERY_PHASE\" != open-entrypoint ] || touch '{opened}'\n",
                encoding="utf-8",
            )
            hook.chmod(0o700)
            hook_variables = [
                "RECOVERY_PAUSE_ENTRYPOINT_COMMAND", "RECOVERY_PAUSE_SCHEDULER_COMMAND",
                "RECOVERY_RESTORE_POSTGRES_COMMAND", "RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND",
                "RECOVERY_RESTORE_NATS_COMMAND", "RECOVERY_FLYWAY_VALIDATE_COMMAND",
                "RECOVERY_CONSISTENCY_CHECK_COMMAND", "RECOVERY_START_CONTROL_PLANE_COMMAND",
                "RECOVERY_START_GATEWAY_COMMAND", "RECOVERY_START_OPERATOR_COMMAND",
                "RECOVERY_START_WORKER_COMMAND", "RECOVERY_REPLAY_OUTBOX_COMMAND",
                "RECOVERY_SMOKE_COMMAND", "RECOVERY_OPEN_ENTRYPOINT_COMMAND",
                "RECOVERY_CLOSE_ENTRYPOINT_COMMAND",
            ]
            environment = {**os.environ, **{name: str(hook) for name in hook_variables}}
            environment["RECOVERY_APPROVAL_ID"] = "change-123"
            result = subprocess.run(
                [str(RESTORE), "--environment", "production", "--backup-id", "pg-20260827-0100",
                 "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
                 "s3://backup.example/agentteams", "--manifest-digest", "sha256:" + "a" * 64,
                 "--metadata", str(metadata_path), "--object-inventory", str(inventory_path),
                 "--approval-id", "change-123", "--execute"],
                env=environment, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(started.exists())
            self.assertTrue(opened.exists())
            self.assertFalse(closed.exists())
            self.assertEqual(metadata_path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(inventory_path.stat().st_mode & 0o777, 0o600)

    def test_recovery_orchestrator_closes_entrypoint_on_restored_object_mismatch(self):
        reference = {"path": "tasks/private.bin", "size_bytes": 4, "sha256": "a" * 64}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata_source = root / "source-metadata.json"
            inventory_source = root / "source-objects.json"
            metadata_source.write_text(json.dumps(self.recovery_metadata([reference])), encoding="utf-8")
            inventory_source.write_text(json.dumps({"objects": [{**reference, "size_bytes": 5}]}),
                                        encoding="utf-8")
            metadata_path = root / "recovered-metadata.json"
            inventory_path = root / "recovered-objects.json"
            closed = root / "closed"
            started = root / "started"
            opened = root / "opened"
            hook = root / "hook.sh"
            hook.write_text(
                "#!/bin/sh\n"
                "if [ \"$RECOVERY_PHASE\" = consistency-check ]; then\n"
                f"  cp '{metadata_source}' \"$RECOVERY_METADATA_FILE\"\n"
                f"  cp '{inventory_source}' \"$RECOVERY_OBJECT_INVENTORY_FILE\"\n"
                "fi\n"
                f"[ \"$RECOVERY_PHASE\" != close-entrypoint ] || touch '{closed}'\n"
                f"[ \"$RECOVERY_PHASE\" != start-control-plane ] || touch '{started}'\n"
                f"[ \"$RECOVERY_PHASE\" != open-entrypoint ] || touch '{opened}'\n",
                encoding="utf-8",
            )
            hook.chmod(0o700)
            hook_variables = [
                "RECOVERY_PAUSE_ENTRYPOINT_COMMAND", "RECOVERY_PAUSE_SCHEDULER_COMMAND",
                "RECOVERY_RESTORE_POSTGRES_COMMAND", "RECOVERY_RESTORE_OBJECT_STORAGE_COMMAND",
                "RECOVERY_RESTORE_NATS_COMMAND", "RECOVERY_FLYWAY_VALIDATE_COMMAND",
                "RECOVERY_CONSISTENCY_CHECK_COMMAND", "RECOVERY_START_CONTROL_PLANE_COMMAND",
                "RECOVERY_START_GATEWAY_COMMAND", "RECOVERY_START_OPERATOR_COMMAND",
                "RECOVERY_START_WORKER_COMMAND", "RECOVERY_REPLAY_OUTBOX_COMMAND",
                "RECOVERY_SMOKE_COMMAND", "RECOVERY_OPEN_ENTRYPOINT_COMMAND",
                "RECOVERY_CLOSE_ENTRYPOINT_COMMAND",
            ]
            environment = {**os.environ, **{name: str(hook) for name in hook_variables}}
            environment["RECOVERY_APPROVAL_ID"] = "change-123"
            result = subprocess.run(
                [str(RESTORE), "--environment", "production", "--backup-id", "pg-20260827-0100",
                 "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
                 "s3://backup.example/agentteams", "--manifest-digest", "sha256:" + "a" * 64,
                 "--metadata", str(metadata_path), "--object-inventory", str(inventory_path),
                 "--approval-id", "change-123", "--execute"],
                env=environment, capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("RECOVERY_RESTORE_FAIL", result.stderr)
            self.assertTrue(closed.exists())
            self.assertFalse(started.exists())
            self.assertFalse(opened.exists())

    def test_public_entrypoints_are_optional_and_never_expose_internal_paths(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
        self.assertFalse(values["ingress"]["enabled"])
        self.assertEqual(values["ingress"]["mode"], "INGRESS")
        ingress = (CHART / "templates/ingress.yaml").read_text(encoding="utf-8")
        gateway_api = (CHART / "templates/gateway-api.yaml").read_text(encoding="utf-8")
        for template in (ingress, gateway_api):
            self.assertNotIn("/internal", template)
            self.assertNotIn("actuator", template)
        self.assertIn("port: {{ .Values.controlPlane.port }}", gateway_api)
        self.assertNotIn("gateway.port", gateway_api)

    def test_network_validator_supports_proxy_and_platform_modes(self):
        values = yaml.safe_load((CHART / "values-production.example.yaml").read_text(encoding="utf-8"))
        proxy = copy.deepcopy(values)
        proxy["networkPolicy"]["egressMode"] = "PROXY"
        proxy["networkPolicy"]["external"] = {key: [] for key in proxy["networkPolicy"]["external"]}
        proxy["networkPolicy"]["proxy"] = {
            "host": "egress.example.internal",
            "port": 8443,
            "cidr": "198.51.105.0/24",
        }
        VALIDATOR.validate_data(proxy)

        platform = copy.deepcopy(values)
        platform["networkPolicy"]["egressMode"] = "PLATFORM"
        platform["networkPolicy"]["external"] = {key: [] for key in platform["networkPolicy"]["external"]}
        platform["networkPolicy"]["platformPolicyArtifact"] = "https://platform.example/policies/agentteams.yaml"
        VALIDATOR.validate_data(platform)

        invalid = copy.deepcopy(proxy)
        invalid["networkPolicy"]["proxy"]["cidr"] = "0.0.0.0/0"
        with self.assertRaises(SystemExit):
            VALIDATOR.validate_data(invalid)

    def test_recovery_preflight_rejects_secret_bearing_inputs(self):
        valid = subprocess.run(
            [str(PREFLIGHT), "--environment", "production", "--backup-id", "pg-20260827-0100",
             "--restore-point", "2026-08-27T01:00:00Z", "--endpoint", "s3://backup.example/agentteams",
             "--manifest-digest", "sha256:" + "a" * 64],
            capture_output=True, text=True,
        )
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assertIn("RECOVERY_PREFLIGHT_OK", valid.stdout)
        invalid = subprocess.run(
            [str(PREFLIGHT), "--environment", "production", "--backup-id", "pg-20260827-0100",
             "--restore-point", "2026-08-27T01:00:00Z", "--endpoint",
             "https://user:password@example/backup", "--manifest-digest", "sha256:" + "a" * 64],
            capture_output=True, text=True,
        )
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("RECOVERY_PREFLIGHT_FAIL", invalid.stderr)

    def test_consistency_check_detects_orphaned_references_without_reading_content(self):
        valid = {
            "tasks": [{"id": "task-1"}],
            "attempts": [{"id": "attempt-1", "task_id": "task-1"}],
            "artifacts": [{"id": "artifact-1", "attempt_id": "attempt-1"}],
            "config_bindings": [{"id": "binding-1", "snapshot_id": "snapshot-1"}],
            "config_snapshots": [{"id": "snapshot-1"}],
            "quota_reservations": [{"id": "quota-1", "task_id": "task-1"}],
            "sandboxes": [{"id": "sandbox-1", "task_id": "task-1"}],
            "outbox": [{"id": "event-1", "aggregate_id": "task-1"}],
            "object_references": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metadata.json"
            inventory_path = Path(directory) / "objects.json"
            path.write_text(json.dumps(valid), encoding="utf-8")
            inventory_path.write_text(json.dumps({"objects": []}), encoding="utf-8")
            result = subprocess.run([sys.executable, str(CONSISTENCY), "--input", str(path),
                                     "--object-inventory", str(inventory_path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            invalid = copy.deepcopy(valid)
            invalid["artifacts"][0]["attempt_id"] = "missing-attempt"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            result = subprocess.run([sys.executable, str(CONSISTENCY), "--input", str(path),
                                     "--object-inventory", str(inventory_path)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("RECOVERY_CONSISTENCY_FAIL", result.stderr)


if __name__ == "__main__":
    unittest.main()
