#!/usr/bin/env python3
"""Export database-owned object references using read-only PostgreSQL sessions."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Mapping
from pathlib import Path


CONTROL_PLANE_SQL = r"""
SELECT COALESCE(json_agg(row_to_json(refs) ORDER BY path), '[]'::json)
FROM (
    SELECT storage_key AS path, size_bytes, sha256
      FROM artifacts
     WHERE status = 'AVAILABLE' AND storage_key NOT LIKE 'memory://%'
    UNION ALL
    SELECT storage_key AS path, size_bytes, checksum AS sha256
      FROM config_files
    UNION ALL
    SELECT storage_key AS path, expected_size_bytes AS size_bytes,
           expected_checksum AS sha256
      FROM config_uploads
     WHERE status = 'COMPLETED'
    UNION ALL
    SELECT storage_key AS path, size_bytes, sha256
      FROM task_files
     WHERE role = 'OUTPUT' AND status = 'AVAILABLE' AND storage_key <> ''
    UNION ALL
    SELECT package_storage_key AS path, package_size_bytes AS size_bytes,
           package_sha256 AS sha256
      FROM skill_versions
     WHERE package_upload_status = 'COMPLETED'
    UNION ALL
    SELECT CASE WHEN result.source_task_id IS NULL THEN result.storage_ref
                ELSE task_file.storage_key END AS path,
           result.size_bytes, result.sha256
      FROM task_result_artifacts result
      LEFT JOIN task_files task_file
        ON task_file.task_id = result.source_task_id
       AND task_file.id = result.source_file_id
       AND task_file.role = 'OUTPUT'
       AND task_file.status = 'AVAILABLE'
     WHERE result.storage_ref <> '' AND result.storage_ref NOT LIKE 'memory://%'
) AS refs;
"""

MANAGER_SQL = r"""
SELECT COALESCE(json_agg(row_to_json(refs) ORDER BY path), '[]'::json)
FROM (
    SELECT storage_key AS path, size_bytes, NULL::text AS sha256
      FROM conversation_files
) AS refs;
"""

DATABASES = {
    "control_plane": CONTROL_PLANE_SQL,
    "manager": MANAGER_SQL,
}
PG_VARIABLES = ("HOST", "PORT", "DATABASE", "USER", "PASSWORD", "SSLMODE", "SSLROOTCERT",
                "SSLCERT", "SSLKEY", "CONNECT_TIMEOUT")
SHA256 = re.compile(r"^(?:sha256:)?([0-9a-fA-F]{64})$", re.IGNORECASE)
LIBPQ_CONNECTION_VARIABLES = (
    "PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGPASSFILE",
    "PGSERVICE", "PGSERVICEFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY",
    "PGCONNECT_TIMEOUT", "PGOPTIONS",
)


class ExportError(ValueError):
    """Invalid metadata or unavailable export dependencies."""


def fail() -> int:
    print("RECOVERY_REFERENCE_EXPORT_FAIL", file=sys.stderr)
    return 1


def pg_environment(kind: str) -> tuple[str, dict[str, str]]:
    prefix = f"RECOVERY_{kind.upper()}_PG"
    values = {key: os.environ.get(f"{prefix}{suffix}", "")
              for suffix, key in (("HOST", "PGHOST"), ("PORT", "PGPORT"),
                                  ("DATABASE", "PGDATABASE"), ("USER", "PGUSER"),
                                  ("PASSWORD", "PGPASSWORD"), ("SSLMODE", "PGSSLMODE"),
                                  ("SSLROOTCERT", "PGSSLROOTCERT"), ("SSLCERT", "PGSSLCERT"),
                                  ("SSLKEY", "PGSSLKEY"), ("CONNECT_TIMEOUT", "PGCONNECT_TIMEOUT"))}
    if not values["PGHOST"] or not values["PGDATABASE"] or not values["PGUSER"]:
        raise ExportError
    return values["PGDATABASE"], {key: value for key, value in values.items() if value}


def query_database(kind: str) -> list[Mapping[str, object]]:
    database, pg_env = pg_environment(kind)
    command = ["psql", "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
               "--set=ON_ERROR_STOP=1", "--dbname", database,
               "--command", DATABASES[kind]]
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
        rows = json.loads(lines[0])
    except (ValueError, TypeError) as error:
        raise ExportError from error
    if not isinstance(rows, list) or any(not isinstance(row, Mapping) for row in rows):
        raise ExportError
    return rows


def object_record(value: object) -> tuple[str, int, str | None]:
    if not isinstance(value, Mapping) or set(value.keys()) != {"path", "size_bytes", "sha256"}:
        raise ExportError
    path = value.get("path")
    size = value.get("size_bytes")
    digest = value.get("sha256")
    if (not isinstance(path, str) or not path or path.startswith("/") or "\\" in path
            or any(ord(char) < 0x20 or ord(char) == 0x7f for char in path)
            or any(part in {"", ".", ".."} for part in path.split("/"))):
        raise ExportError
    if type(size) is not int or size < 0:
        raise ExportError
    if digest is None:
        return path, size, None
    if not isinstance(digest, str):
        raise ExportError
    match = SHA256.fullmatch(digest.strip())
    if not match:
        raise ExportError
    return path, size, match.group(1).lower()


def load_source_inventory(path: Path) -> dict[str, tuple[int, str]]:
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
            raise ExportError
        with path.open("r", encoding="utf-8") as stream:
            inventory = json.load(stream)
    except (OSError, ValueError, TypeError) as error:
        raise ExportError from error
    if not isinstance(inventory, Mapping) or set(inventory.keys()) != {"objects"}:
        raise ExportError
    raw_objects = inventory.get("objects")
    if not isinstance(raw_objects, list):
        raise ExportError
    result: dict[str, tuple[int, str]] = {}
    for raw in raw_objects:
        record = dict(raw) if isinstance(raw, Mapping) else raw
        key, size, digest = object_record(record)
        if digest is None or key in result:
            raise ExportError
        result[key] = (size, digest)
    return result


def collect_references(source: Mapping[str, tuple[int, str]]) -> list[dict[str, object]]:
    refs: dict[str, tuple[int, str]] = {}
    for kind in DATABASES:
        for row in query_database(kind):
            path, size, database_digest = object_record(row)
            source_record = source.get(path)
            if source_record is None or source_record[0] != size:
                raise ExportError
            if database_digest is not None and database_digest != source_record[1]:
                raise ExportError
            value = (size, source_record[1])
            if path in refs and refs[path] != value:
                raise ExportError
            refs[path] = value
    return [{"path": path, "size_bytes": value[0], "sha256": value[1]}
            for path, value in sorted(refs.items())]


def write_output(path: Path, references: list[dict[str, object]]) -> None:
    if path.exists() or path.is_symlink() or not path.parent.is_dir():
        raise ExportError
    descriptor, temporary = tempfile.mkstemp(prefix=".recovery-references-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump({"object_references": references}, stream, ensure_ascii=False,
                      separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path, follow_symlinks=False)
    except OSError as error:
        raise ExportError from error
    finally:
        try:
            os.unlink(temporary)
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
        source = load_source_inventory(Path(args.source_inventory))
        references = collect_references(source)
        write_output(Path(args.output), references)
    except (OSError, ValueError, TypeError, RuntimeError, ExportError):
        return fail()
    print(f"RECOVERY_REFERENCE_EXPORT_OK references={len(references)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
