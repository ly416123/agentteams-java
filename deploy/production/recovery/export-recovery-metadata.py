#!/usr/bin/env python3
"""Export checker-ready recovery relations and object references."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from collections.abc import Mapping
from pathlib import Path


RECOVERY_DIR = Path(__file__).resolve().parent
OBJECT_EXPORTER = RECOVERY_DIR / "export-object-references.py"
CONSISTENCY_CHECKER = RECOVERY_DIR / "consistency-check.py"
COLLECTIONS = (
    "tasks", "attempts", "artifacts", "config_bindings", "config_snapshots",
    "quota_reservations", "sandboxes", "outbox",
)

RELATION_SQL = r"""
SELECT json_build_object(
    'tasks', COALESCE((SELECT json_agg(json_build_object('id', id::text) ORDER BY id)
                         FROM tasks), '[]'::json),
    'attempts', COALESCE((SELECT json_agg(json_build_object('id', id::text, 'task_id', task_id::text)
                            ORDER BY id) FROM task_attempts), '[]'::json),
    'artifacts', COALESCE((SELECT json_agg(
                              CASE WHEN attempt_id IS NULL
                                   THEN json_build_object('id', id::text)
                                   ELSE json_build_object('id', id::text, 'attempt_id', attempt_id::text)
                              END ORDER BY id)
                            FROM artifacts), '[]'::json),
    'config_bindings', COALESCE((SELECT json_agg(json_build_object('id', id::text, 'snapshot_id', snapshot_id::text)
                                  ORDER BY id) FROM config_bindings), '[]'::json),
    'config_snapshots', COALESCE((SELECT json_agg(json_build_object('id', id::text) ORDER BY id)
                                    FROM config_snapshots), '[]'::json),
    'quota_reservations', COALESCE((SELECT json_agg(
                                      CASE WHEN task_id IS NULL
                                           THEN json_build_object('id', id::text)
                                           ELSE json_build_object('id', id::text, 'task_id', task_id::text)
                                      END ORDER BY id)
                                    FROM token_ledger_reservations), '[]'::json),
    'sandboxes', COALESCE((SELECT json_agg(json_build_object(
                              'id', id::text, 'task_id', task_id::text, 'attempt_id', attempt_id::text)
                              ORDER BY id) FROM task_sandboxes), '[]'::json),
    'outbox', COALESCE((SELECT json_agg(json_build_object(
                            'id', id::text, 'aggregate_id', aggregate_id::text,
                            'aggregate_type', aggregate_type) ORDER BY id)
                          FROM outbox_events
                         WHERE lower(replace(aggregate_type, '-', '_')) IN (
                             'task', 'tasks', 'attempt', 'attempts', 'artifact', 'artifacts',
                             'configbinding', 'config_bindings', 'configsnapshot', 'config_snapshots',
                             'quota', 'quotareservation', 'quota_reservations', 'sandbox', 'sandboxes'
                         )), '[]'::json)
);
"""

PG_VARIABLES = (
    ("HOST", "PGHOST"), ("PORT", "PGPORT"), ("DATABASE", "PGDATABASE"),
    ("USER", "PGUSER"), ("PASSWORD", "PGPASSWORD"), ("SSLMODE", "PGSSLMODE"),
    ("SSLROOTCERT", "PGSSLROOTCERT"), ("SSLCERT", "PGSSLCERT"),
    ("SSLKEY", "PGSSLKEY"), ("CONNECT_TIMEOUT", "PGCONNECT_TIMEOUT"),
)
LIBPQ_CONNECTION_VARIABLES = (
    "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGPASSFILE",
    "PGSERVICE", "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY",
    "PGCONNECT_TIMEOUT", "PGOPTIONS",
)


class ExportError(ValueError):
    """Invalid export inputs or unavailable read-only database source."""


def fail() -> int:
    print("RECOVERY_METADATA_EXPORT_FAIL", file=sys.stderr)
    return 1


def control_plane_environment() -> tuple[str, dict[str, str]]:
    prefix = "RECOVERY_CONTROL_PLANE_PG"
    values = {key: os.environ.get(f"{prefix}{suffix}", "") for suffix, key in PG_VARIABLES}
    if not values["PGHOST"] or not values["PGDATABASE"] or not values["PGUSER"]:
        raise ExportError
    return values["PGDATABASE"], {key: value for key, value in values.items() if value}


def read_relations() -> dict[str, object]:
    database, pg_env = control_plane_environment()
    command = ["psql", "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
               "--set=ON_ERROR_STOP=1", "--dbname", database, "--command", RELATION_SQL]
    environment = os.environ.copy()
    for variable in LIBPQ_CONNECTION_VARIABLES:
        environment.pop(variable, None)
    environment.update(pg_env)
    environment["PGOPTIONS"] = "-c default_transaction_read_only=on -c statement_timeout=30000"
    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", env=environment)
    except OSError as error:
        raise ExportError from error
    if result.returncode != 0:
        raise ExportError
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise ExportError
    try:
        data = json.loads(lines[0])
    except (ValueError, TypeError) as error:
        raise ExportError from error
    if not isinstance(data, Mapping) or set(data.keys()) != set(COLLECTIONS):
        raise ExportError
    return dict(data)


def run_object_export(source_inventory: Path, output: Path) -> dict[str, object]:
    environment = os.environ.copy()
    result = subprocess.run(
        [sys.executable, str(OBJECT_EXPORTER), "--source-inventory", str(source_inventory),
         "--output", str(output)], check=False, capture_output=True, text=True,
        encoding="utf-8", errors="replace", env=environment,
    )
    if result.returncode != 0:
        raise ExportError
    try:
        with output.open("r", encoding="utf-8") as stream:
            data = json.load(stream)
    except (OSError, ValueError, TypeError) as error:
        raise ExportError from error
    if not isinstance(data, Mapping) or set(data.keys()) != {"object_references"}:
        raise ExportError
    return dict(data)


def validate(metadata_path: Path, object_inventory: Path) -> None:
    result = subprocess.run(
        [sys.executable, str(CONSISTENCY_CHECKER), "--input", str(metadata_path),
         "--object-inventory", str(object_inventory)], check=False,
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise ExportError


def write_metadata(path: Path, metadata: Mapping[str, object], source_inventory: Path) -> None:
    if path.exists() or path.is_symlink() or not path.parent.is_dir():
        raise ExportError
    temporary_name: str | None = None
    descriptor, temporary_name = tempfile.mkstemp(prefix=".recovery-metadata-", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(metadata, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        validate(temporary, source_inventory)
        os.link(temporary, path, follow_symlinks=False)
    except OSError as error:
        raise ExportError from error
    finally:
        try:
            temporary.unlink()
        except OSError:
            pass
    os.chmod(path, 0o600, follow_symlinks=False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--source-inventory")
    parser.add_argument("--output")
    try:
        args, unknown = parser.parse_known_args(argv)
        if unknown or not args.source_inventory or not args.output:
            return fail()
        source_inventory = Path(args.source_inventory)
        output = Path(args.output)
        if output.exists() or output.is_symlink() or not output.parent.is_dir():
            raise ExportError
        with tempfile.TemporaryDirectory(prefix=".recovery-reference-fragment-", dir=output.parent) as directory:
            refs_path = Path(directory) / "references.json"
            object_metadata = run_object_export(source_inventory, refs_path)
            metadata = read_relations()
            metadata.update(object_metadata)
            write_metadata(output, metadata, source_inventory)
    except (OSError, ValueError, TypeError, RuntimeError, ExportError):
        return fail()
    print("RECOVERY_METADATA_EXPORT_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
