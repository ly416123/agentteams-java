#!/usr/bin/env python3
"""Ensure restore refuses an unscoped backup root before database writes."""

from __future__ import annotations

import os
import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
RESTORE = ROOT / "deploy/backup/restore-kind.sh"
BACKUP = ROOT / "deploy/backup/backup-kind.sh"


class KindBackupRestorePairingTest(unittest.TestCase):
    @staticmethod
    def fake_kubectl_context_cases() -> str:
        return (
            "  *'config current-context'*) printf '%s\\n' \"${FAKE_KUBECTL_CONTEXT:-kind-agentteams}\" ;;\n"
            "  *'config view'*) printf '%s\\n' \"${FAKE_KUBECTL_SERVER:-https://127.0.0.1:49799}\" ;;\n"
        )

    def test_backup_rejects_a_healthy_unrelated_service_when_port_forward_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            backup_root = root / "backups"
            fake_kubectl = bin_dir / "kubectl"
            fake_kubectl.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                + self.fake_kubectl_context_cases()
                + "  *pg_dump*) printf 'custom-format-dump' ;;\n"
                "  *access-key*) printf 'ZmFrZS1hY2Nlc3M=' ;;\n"
                "  *secret-key*) printf 'ZmFrZS1zZWNyZXQ=' ;;\n"
                "  *'port-forward service/minio'*) exit 38 ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_mc = bin_dir / "mc"
            fake_mc.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_curl = bin_dir / "curl"
            fake_curl.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            for executable in (fake_kubectl, fake_mc, fake_curl):
                executable.chmod(0o700)

            result = subprocess.run(
                ["bash", str(BACKUP)],
                env={
                    **os.environ,
                    "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "AGENTTEAMS_BACKUP_DIR": str(backup_root),
                    "AGENTTEAMS_NAMESPACE": "agentteams",
                    "MC_BIN": "mc",
                    "TMPDIR": str(root),
                },
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            points = list(backup_root.glob("recovery-*"))
            self.assertEqual(len(points), 1)
            self.assertFalse((points[0] / "RECOVERY_COMPLETE").exists())

    def test_backup_refuses_a_non_kind_context_before_creating_a_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            backup_root = root / "backups"
            fake_kubectl = bin_dir / "kubectl"
            fake_kubectl.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                + self.fake_kubectl_context_cases()
                + "  *pg_dump*) touch \"$DUMP_CALLED\"; printf 'custom-format-dump' ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_mc = bin_dir / "mc"
            fake_mc.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            for executable in (fake_kubectl, fake_mc):
                executable.chmod(0o700)

            result = subprocess.run(
                ["bash", str(BACKUP)],
                env={
                    **os.environ,
                    "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "AGENTTEAMS_BACKUP_DIR": str(backup_root),
                    "AGENTTEAMS_NAMESPACE": "agentteams",
                    "AGENTTEAMS_KIND_CONTEXT": "kind-agentteams",
                    "FAKE_KUBECTL_CONTEXT": "production-admin",
                    "DUMP_CALLED": str(root / "dump-called"),
                    "MC_BIN": "mc",
                    "TMPDIR": str(root),
                },
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse((root / "dump-called").exists())
            self.assertFalse(backup_root.exists())

    @staticmethod
    def fake_environment(root: Path) -> tuple[dict[str, str], Path, Path]:
        bin_dir = root / "bin"
        bin_dir.mkdir()
        database_write = root / "database-restore-input"
        mc_calls = root / "mc-calls"
        mc_alias_import = root / "mc-alias-import.json"
        fake_kubectl = bin_dir / "kubectl"
        fake_kubectl.write_text(
            "#!/bin/sh\n"
            "case \"$*\" in\n"
            + KindBackupRestorePairingTest.fake_kubectl_context_cases()
            + "  *'get namespace agentteams'*) exit 0 ;;\n"
            + "  *'get deployments '*) [ \"${FAKE_PAUSE_QUERY_FAIL:-}\" != deployments ] || exit 81; cat \"$FAKE_DEPLOYMENTS_JSON\" ;;\n"
            + "  *'get workers.agentteams.io'*) [ \"${FAKE_PAUSE_QUERY_FAIL:-}\" != workers ] || exit 82; cat \"$FAKE_WORKERS_JSON\" ;;\n"
            + "  *'get pods -l app.kubernetes.io/name in ('*) [ \"${FAKE_PAUSE_QUERY_FAIL:-}\" != app-pods ] || exit 84; cat \"$FAKE_APP_PODS_JSON\" ;;\n"
            + "  *'get pods -l app.kubernetes.io/name=agentteams-worker'*) [ \"${FAKE_PAUSE_QUERY_FAIL:-}\" != pods ] || exit 83; cat \"$FAKE_WORKER_PODS_JSON\" ;;\n"
            "  *access-key*) printf 'ZmFrZS1hY2Nlc3M=' ;;\n"
            "  *secret-key*) printf 'ZmFrZS1zZWNyZXQ=' ;;\n"
            "  *'port-forward service/minio'*) printf 'Forwarding from 127.0.0.1:%s -> 9000\\n' \"${7%:*}\"; exec sleep 60 ;;\n"
            "  *'exec -i statefulset/postgresql'*) cat > \"$DATABASE_WRITE_LOG\" ;;\n"
            "  *) exit 0 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        fake_mc = bin_dir / "mc"
        fake_mc.write_text(
            "#!/bin/sh\n"
            "python3 -c 'import os,sys; sys.exit(0 if os.stat(sys.argv[1]).st_mode & 0o777 == 0o700 else 97)' \"$2\" || exit $?\n"
            "printf '%s\\n' \"$*\" >> \"$MC_CALLS_LOG\"\n"
            "if [ \"$3\" = alias ] && [ \"$4\" = import ]; then cat > \"$MC_ALIAS_IMPORT_LOG\"; fi\n"
            "if [ \"${FAKE_MC_PIPE_FAIL:-false}\" = true ] && [ \"$3\" = pipe ]; then exit 25; fi\n"
            "if [ \"${FAKE_MC_RM_FAIL:-false}\" = true ] && [ \"$3\" = rm ]; then exit 26; fi\n"
            "exit 0\n",
            encoding="utf-8",
        )
        fake_curl = bin_dir / "curl"
        fake_curl.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        fake_base64 = bin_dir / "base64"
        fake_base64.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
        deployments_json = root / "deployments.json"
        deployments_json.write_text(json.dumps({"items": [
            {"metadata": {"labels": {"app.kubernetes.io/name": name}},
             "spec": {"replicas": 0}, "status": {"replicas": 0}}
            for name in ("agentteams-control-plane", "agentteams-gateway", "agentteams-operator")
        ]}), encoding="utf-8")
        workers_json = root / "workers.json"
        workers_json.write_text('{"items": []}', encoding="utf-8")
        worker_pods_json = root / "worker-pods.json"
        worker_pods_json.write_text('{"items": []}', encoding="utf-8")
        app_pods_json = root / "app-pods.json"
        app_pods_json.write_text('{"items": []}', encoding="utf-8")
        for executable in (fake_kubectl, fake_mc, fake_curl, fake_base64):
            executable.chmod(0o700)
        environment = {
            **os.environ,
            "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
            "DATABASE_WRITE_LOG": str(database_write),
            "MC_CALLS_LOG": str(mc_calls),
            "MC_ALIAS_IMPORT_LOG": str(mc_alias_import),
            "FAKE_DEPLOYMENTS_JSON": str(deployments_json),
            "FAKE_WORKERS_JSON": str(workers_json),
            "FAKE_WORKER_PODS_JSON": str(worker_pods_json),
            "FAKE_APP_PODS_JSON": str(app_pods_json),
            "TMPDIR": str(root),
            "AGENTTEAMS_NAMESPACE": "agentteams",
            "MC_BIN": "mc",
        }
        return environment, database_write, mc_calls

    @staticmethod
    def create_recovery_point(root: Path) -> tuple[Path, Path]:
        point_id = "20261015T120000Z"
        point = root / f"recovery-{point_id}"
        object_dir = point / "minio"
        object_dir.mkdir(parents=True)
        (point / "agentteams.dump").write_text("verified-db", encoding="utf-8")
        (object_dir / "object.bin").write_text("verified-object", encoding="utf-8")
        inventory = {
            "objects": [{
                "path": "object.bin",
                "size_bytes": len(b"verified-object"),
                "sha256": hashlib.sha256(b"verified-object").hexdigest(),
            }],
        }
        (point / "OBJECT_INVENTORY.json").write_text(json.dumps(inventory), encoding="utf-8")
        manifest = {
            "schema_version": 1,
            "recovery_point_id": point_id,
            "backup_started_at_utc": "2026-10-15T12:00:00Z",
            "postgres": {
                "path": "agentteams.dump",
                "started_at_utc": "2026-10-15T12:00:00Z",
                "completed_at_utc": "2026-10-15T12:00:01Z",
            },
            "object_storage": {
                "path": "minio",
                "inventory_path": "OBJECT_INVENTORY.json",
                "started_at_utc": "2026-10-15T12:00:01Z",
                "completed_at_utc": "2026-10-15T12:00:02Z",
            },
            "cross_store_consistency": "UNVERIFIED",
        }
        (point / "RECOVERY_POINT").write_text(json.dumps(manifest), encoding="utf-8")
        checksums = subprocess.run(
            ["shasum", "-a", "256", "agentteams.dump", "RECOVERY_POINT",
             "OBJECT_INVENTORY.json", "minio/object.bin"],
            cwd=point, check=True, capture_output=True, text=True,
        )
        (point / "SHA256SUMS").write_text(checksums.stdout, encoding="utf-8")
        (point / "RECOVERY_COMPLETE").write_text(f"{point_id}\n", encoding="utf-8")
        return point, object_dir

    def test_restore_rejects_objects_added_after_the_backup_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            point, object_dir = self.create_recovery_point(root)
            (object_dir / "unlisted-object.bin").write_text("must-not-restore", encoding="utf-8")
            environment, database_write, mc_calls = self.fake_environment(root)

            result = subprocess.run(
                ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(database_write.exists(), "unlisted objects must be rejected before DB restore")
            self.assertFalse(mc_calls.exists(), "unlisted objects must be rejected before MinIO access")

    def test_restore_rejects_checksum_manifest_missing_a_required_root_file(self):
        for required_file in ("agentteams.dump", "RECOVERY_POINT", "OBJECT_INVENTORY.json"):
            with self.subTest(required_file=required_file), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                checksums = point / "SHA256SUMS"
                lines = checksums.read_text(encoding="utf-8").splitlines()
                checksums.write_text(
                    "\n".join(line for line in lines if not line.endswith(f"  {required_file}")) + "\n",
                    encoding="utf-8",
                )
                environment, database_write, mc_calls = self.fake_environment(root)

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), f"missing {required_file} checksum must fail closed")
                self.assertFalse(mc_calls.exists(), f"missing {required_file} checksum must fail before MinIO")

    def test_restore_rejects_non_kind_context_or_remote_api_before_data_access(self):
        for environment_change in (
            {"FAKE_KUBECTL_CONTEXT": "production-admin"},
            {"FAKE_KUBECTL_SERVER": "https://production.example:6443"},
        ):
            with self.subTest(environment_change=environment_change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                environment, database_write, mc_calls = self.fake_environment(root)
                environment.update(environment_change)

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), "remote context must be rejected before DB restore")
                self.assertFalse(mc_calls.exists(), "remote context must be rejected before MinIO access")

    def test_restore_requires_operator_maintenance_confirmation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            point, _ = self.create_recovery_point(root)
            environment, database_write, mc_calls = self.fake_environment(root)

            result = subprocess.run(
                ["bash", str(RESTORE), "--confirm", str(point)],
                env=environment, check=False, capture_output=True, text=True,
                errors="replace", timeout=20,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--maintenance-confirmed", result.stderr)
            self.assertFalse(database_write.exists())
            self.assertFalse(mc_calls.exists())

    def test_restore_rejects_running_deployments_workers_and_failed_pause_queries(self):
        scenarios = (
            ("required Deployment missing", "deployments", lambda data: data["items"].pop(0)),
            ("deployment desired replicas", "deployments", lambda data: data["items"][0]["spec"].update(replicas=1)),
            ("deployment actual replicas", "deployments", lambda data: data["items"][0]["status"].update(replicas=1)),
            ("optional Manager still running", "deployments", lambda data: data["items"].append({
                "metadata": {"labels": {"app.kubernetes.io/name": "agentteams-manager"}},
                "spec": {"replicas": 1}, "status": {"replicas": 1},
            })),
            ("application Pod is still terminating", "app-pods", lambda data: data["items"].append({
                "metadata": {"name": "control-plane-old", "deletionTimestamp": "2026-10-19T00:00:00Z"},
                "status": {"phase": "Running"},
            })),
            ("Worker custom resource replicas", "workers", lambda data: data["items"].append({"spec": {"replicas": 1}})),
            ("Worker Pod still exists", "pods", lambda data: data["items"].append({"metadata": {"name": "agentteams-worker-1"}})),
        )
        for name, resource, mutate in scenarios:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                environment, database_write, mc_calls = self.fake_environment(root)
                fixture_key = {
                    "deployments": "FAKE_DEPLOYMENTS_JSON",
                    "workers": "FAKE_WORKERS_JSON",
                    "pods": "FAKE_WORKER_PODS_JSON",
                    "app-pods": "FAKE_APP_PODS_JSON",
                }[resource]
                fixture = Path(environment[fixture_key])
                data = json.loads(fixture.read_text(encoding="utf-8"))
                mutate(data)
                fixture.write_text(json.dumps(data), encoding="utf-8")

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment, check=False, capture_output=True, text=True,
                    errors="replace", timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), "active writer must fail before PostgreSQL restore")
                self.assertFalse(mc_calls.exists(), "active writer must fail before MinIO access")

        for resource in ("deployments", "workers", "pods", "app-pods"):
            with self.subTest(name=f"{resource} query failure"), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                environment, database_write, mc_calls = self.fake_environment(root)
                environment["FAKE_PAUSE_QUERY_FAIL"] = resource

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment, check=False, capture_output=True, text=True,
                    errors="replace", timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), "unknown maintenance state must fail before DB restore")
                self.assertFalse(mc_calls.exists(), "unknown maintenance state must fail before MinIO access")

        for resource in ("deployments", "workers", "pods"):
            with self.subTest(name=f"{resource} malformed JSON"), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                environment, database_write, mc_calls = self.fake_environment(root)
                fixture_key = {
                    "deployments": "FAKE_DEPLOYMENTS_JSON",
                    "workers": "FAKE_WORKERS_JSON",
                    "pods": "FAKE_WORKER_PODS_JSON",
                    "app-pods": "FAKE_APP_PODS_JSON",
                }[resource]
                Path(environment[fixture_key]).write_text("{invalid", encoding="utf-8")

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment, check=False, capture_output=True, text=True,
                    errors="replace", timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), "malformed state must fail before PostgreSQL restore")
                self.assertFalse(mc_calls.exists(), "malformed state must fail before MinIO access")

    def test_restore_rejects_duplicate_checksum_entries_for_required_root_files(self):
        for required_file in ("agentteams.dump", "RECOVERY_POINT", "OBJECT_INVENTORY.json"):
            with self.subTest(required_file=required_file), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                checksums = point / "SHA256SUMS"
                lines = checksums.read_text(encoding="utf-8").splitlines()
                matching_line = next(line for line in lines if line.endswith(f"  {required_file}"))
                checksums.write_text("\n".join([*lines, matching_line]) + "\n", encoding="utf-8")
                environment, database_write, mc_calls = self.fake_environment(root)

                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), f"duplicate {required_file} checksum must fail closed")
                self.assertFalse(mc_calls.exists(), f"duplicate {required_file} checksum must fail before MinIO")

    def test_restore_proves_minio_write_access_before_database_restore(self):
        for command, failure_env in (("pipe", "FAKE_MC_PIPE_FAIL"), ("rm", "FAKE_MC_RM_FAIL")):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                point, _ = self.create_recovery_point(root)
                environment, database_write, mc_calls = self.fake_environment(root)
                environment[failure_env] = "true"
                result = subprocess.run(
                    ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                    env=environment,
                    check=False,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=20,
                )

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(database_write.exists(), f"MinIO {command} failure must happen before DB restore")
                self.assertIn(f"{command} agentteams-local/agentteams/",
                              mc_calls.read_text(encoding="utf-8"))

    def test_backup_cleans_temporary_mc_config_when_database_dump_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            backup_root = root / "backups"
            fake_kubectl = bin_dir / "kubectl"
            fake_kubectl.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                + self.fake_kubectl_context_cases()
                + "  *pg_dump*) exit 37 ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_mc = bin_dir / "mc"
            fake_mc.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_curl = bin_dir / "curl"
            fake_curl.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            for executable in (fake_kubectl, fake_mc, fake_curl):
                executable.chmod(0o700)

            result = subprocess.run(
                ["bash", str(BACKUP)],
                env={
                    **os.environ,
                    "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "AGENTTEAMS_BACKUP_DIR": str(backup_root),
                    "AGENTTEAMS_NAMESPACE": "agentteams",
                    "MC_BIN": "mc",
                    "TMPDIR": str(root),
                },
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(list(root.glob("agentteams-backup-mc.*")))
            incomplete_points = list(backup_root.glob("recovery-*"))
            self.assertEqual(len(incomplete_points), 1)
            self.assertFalse((incomplete_points[0] / "RECOVERY_COMPLETE").exists())

    def test_backup_creates_private_per_run_bundle_with_explicit_consistency_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            backup_root = root / "backups"
            fake_kubectl = bin_dir / "kubectl"
            fake_kubectl.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                + self.fake_kubectl_context_cases()
                + "  *access-key*) printf 'ZmFrZS1hY2Nlc3M=' ;;\n"
                "  *secret-key*) printf 'ZmFrZS1zZWNyZXQ=' ;;\n"
                "  *'port-forward service/minio'*) printf 'Forwarding from 127.0.0.1:%s -> 9000\\n' \"${7%:*}\"; exec sleep 60 ;;\n"
                "  *pg_dump*) printf 'custom-format-dump' ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_mc = bin_dir / "mc"
            fake_mc.write_text(
                "#!/bin/sh\n"
                "python3 -c 'import os,sys; sys.exit(0 if os.stat(sys.argv[1]).st_mode & 0o777 == 0o700 else 97)' \"$2\" || exit $?\n"
                "printf '%s\\n' \"$*\" >> \"$MC_CALLS_LOG\"\n"
                "if [ \"$3\" = alias ] && [ \"$4\" = import ]; then cat > \"$MC_ALIAS_IMPORT_LOG\"; fi\n"
                "if [ \"$3\" = mirror ]; then mkdir -p \"$6\"; printf 'object-bytes' > \"$6/object.bin\"; fi\n"
                "exit 0\n",
                encoding="utf-8",
            )
            fake_curl = bin_dir / "curl"
            fake_curl.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_base64 = bin_dir / "base64"
            fake_base64.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
            for executable in (fake_kubectl, fake_mc, fake_curl, fake_base64):
                executable.chmod(0o700)

            result = subprocess.run(
                ["bash", str(BACKUP)],
                env={
                    **os.environ,
                    "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "AGENTTEAMS_BACKUP_DIR": str(backup_root),
                    "AGENTTEAMS_NAMESPACE": "agentteams",
                    "MC_BIN": "mc",
                    "MC_CALLS_LOG": str(root / "mc-calls"),
                    "MC_ALIAS_IMPORT_LOG": str(root / "mc-alias-import.json"),
                    "TMPDIR": str(root),
                },
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            points = list(backup_root.glob("recovery-*"))
            self.assertEqual(len(points), 1)
            point = points[0]
            record = json.loads((point / "RECOVERY_POINT").read_text(encoding="utf-8"))
            self.assertEqual(record["cross_store_consistency"], "UNVERIFIED")
            self.assertEqual(record["postgres"]["path"], "agentteams.dump")
            self.assertEqual(record["object_storage"]["path"], "minio")
            inventory_path = record["object_storage"]["inventory_path"]
            self.assertEqual(inventory_path, "OBJECT_INVENTORY.json")
            inventory = json.loads((point / inventory_path).read_text(encoding="utf-8"))
            self.assertEqual(inventory, {"objects": [{
                "path": "object.bin",
                "size_bytes": len(b"object-bytes"),
                "sha256": hashlib.sha256(b"object-bytes").hexdigest(),
            }]})
            self.assertEqual((point / inventory_path).stat().st_mode & 0o777, 0o600)
            self.assertEqual((point / "RECOVERY_COMPLETE").read_text(encoding="utf-8"), record["recovery_point_id"] + "\n")
            checked = subprocess.run(
                ["shasum", "-a", "256", "-c", "SHA256SUMS"],
                cwd=point,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)
            self.assertEqual(point.stat().st_mode & 0o777, 0o700)
            self.assertEqual((point / "agentteams.dump").stat().st_mode & 0o777, 0o600)
            mc_args = (root / "mc-calls").read_text(encoding="utf-8")
            self.assertIn("alias import agentteams-local", mc_args)
            self.assertNotIn("alias set", mc_args)
            alias = json.loads((root / "mc-alias-import.json").read_text(encoding="utf-8"))
            self.assertEqual(alias["accessKey"], "fake-access")
            self.assertEqual(alias["secretKey"], "fake-secret")
            self.assertNotIn("fake-access", mc_args)
            self.assertNotIn("fake-secret", mc_args)
            self.assertNotIn("fake-access", result.stdout + result.stderr)
            self.assertNotIn("fake-secret", result.stdout + result.stderr)
            self.assertFalse(list(root.glob("agentteams-backup-mc.*")))

    def test_restore_refuses_shared_backup_root_before_database_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_root = root / "backups"
            backup_root.mkdir()
            (backup_root / "agentteams-A.dump").write_text("unverified-db", encoding="utf-8")
            (backup_root / "agentteams-B.dump").write_text("verified-db", encoding="utf-8")
            minio_a = backup_root / "minio-A"
            minio_b = backup_root / "minio-B"
            minio_a.mkdir()
            minio_b.mkdir()
            (minio_a / "object.bin").write_text("unverified-object", encoding="utf-8")
            (minio_b / "object.bin").write_text("verified-object", encoding="utf-8")

            checksums = subprocess.run(
                ["shasum", "-a", "256", "agentteams-B.dump", "minio-B/object.bin"],
                cwd=backup_root, check=True, capture_output=True, text=True,
            )
            (backup_root / "SHA256SUMS").write_text(checksums.stdout, encoding="utf-8")

            environment, database_write, _ = self.fake_environment(root)
            result = subprocess.run(
                ["bash", str(RESTORE), "--confirm", str(backup_root), "--maintenance-confirmed"],
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(database_write.exists(), "restore must reject the backup root before writing DB")

    def test_restore_uses_the_verified_dump_and_object_directory_in_selected_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            point, object_dir = self.create_recovery_point(root)

            environment, database_write, mc_calls = self.fake_environment(root)
            result = subprocess.run(
                ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(database_write.read_text(encoding="utf-8"), "verified-db")
            calls = mc_calls.read_text(encoding="utf-8")
            self.assertIn("alias import agentteams-local", calls)
            self.assertNotIn("alias set", calls)
            self.assertIn(f"mirror --overwrite --remove {object_dir.resolve()} agentteams-local/agentteams", calls)
            alias = json.loads((root / "mc-alias-import.json").read_text(encoding="utf-8"))
            self.assertEqual(alias["accessKey"], "fake-access")
            self.assertEqual(alias["secretKey"], "fake-secret")
            self.assertNotIn("fake-access", calls)
            self.assertNotIn("fake-secret", calls)
            self.assertNotIn("fake-access", result.stdout + result.stderr)
            self.assertNotIn("fake-secret", result.stdout + result.stderr)
            self.assertFalse(list(root.glob("agentteams-restore-mc.*")))

    def test_restore_rejects_healthy_unrelated_service_before_database_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            point, _ = self.create_recovery_point(root)

            environment, database_write, mc_calls = self.fake_environment(root)
            fake_kubectl = Path(environment["PATH"].split(os.pathsep)[0]) / "kubectl"
            fake_kubectl.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                + self.fake_kubectl_context_cases()
                + "  *'get namespace agentteams'*) exit 0 ;;\n"
                "  *access-key*) printf 'ZmFrZS1hY2Nlc3M=' ;;\n"
                "  *secret-key*) printf 'ZmFrZS1zZWNyZXQ=' ;;\n"
                "  *'port-forward service/minio'*) exit 38 ;;\n"
                "  *'exec -i statefulset/postgresql'*) cat > \"$DATABASE_WRITE_LOG\" ;;\n"
                "  *) exit 0 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_kubectl.chmod(0o700)

            result = subprocess.run(
                ["bash", str(RESTORE), "--confirm", str(point), "--maintenance-confirmed"],
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=20,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(database_write.exists(), "restore must fail before PostgreSQL writes")
            self.assertFalse(mc_calls.exists(), "restore must not connect to an unrelated local service")


if __name__ == "__main__":
    unittest.main()
