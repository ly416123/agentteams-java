#!/usr/bin/env python3
"""Verify that a Kind PostgreSQL logical backup restores the durable state."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile


PRIMARY_DATABASE = "agentteams"
RESTORE_DATABASE = "agentteams_restore"
POSTGRES_USER = "agentteams"
# Agent rows receive heartbeat/version updates while the backup is being
# validated. Compare stable identity and lifecycle state instead of treating
# that expected liveness churn as restore corruption.
POSTGRES_TABLE_SIGNATURES = """
select 'agents|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(id::text || '|' || name || '|' || phase,
                             ',' order by id), ''))
  from agents
union all
select 'tasks|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(id::text || '|' || phase || '|' || version::text,
                             ',' order by id), ''))
  from tasks
union all
select 'task_attempts|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(id::text || '|' || task_id::text || '|' || phase || '|' || version::text,
                             ',' order by id), ''))
  from task_attempts
union all
select 'agent_leases|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(id::text || '|' || agent_id::text || '|' || status || '|' || version::text,
                             ',' order by id), ''))
  from agent_leases
union all
select 'domain_events|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(event_id::text || '|' || event_type || '|' || aggregate_version::text,
                             ',' order by event_id), ''))
  from domain_events
union all
select 'outbox_events|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(event_id::text || '|' || status || '|' || attempts::text || '|' || version::text,
                             ',' order by event_id), ''))
  from outbox_events
union all
select 'gateway_commands|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(agent_id || '|' || sequence::text || '|' || event_id,
                             ',' order by agent_id, sequence), ''))
  from gateway_commands
union all
select 'gateway_command_deliveries|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(agent_id || '|' || connection_id::text || '|' || sequence::text,
                             ',' order by agent_id, connection_id, sequence), ''))
  from gateway_command_deliveries
union all
select 'gateway_ack_cursors|' || count(*)::text || '|' ||
       md5(coalesce(string_agg(agent_id || '|' || last_ack_sequence::text,
                             ',' order by agent_id), ''))
  from gateway_ack_cursors
order by 1;
"""


def fail(message: str) -> None:
    raise RuntimeError(message)


def kubectl_command(namespace: str, *args: str) -> list[str]:
    return ["kubectl", "-n", namespace, *args]


def run_kubectl(namespace: str, *args: str) -> str:
    command = kubectl_command(namespace, *args)
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def run_psql(namespace: str, postgres_pod: str, database: str, statement: str) -> str:
    return run_kubectl(
        namespace,
        "exec",
        postgres_pod,
        "--",
        "psql",
        "-U",
        POSTGRES_USER,
        "-d",
        database,
        "-v",
        "ON_ERROR_STOP=1",
        "-At",
        "-F",
        "|",
        "-c",
        statement,
    )


def dump_primary_database(namespace: str, postgres_pod: str, dump_path: Path) -> None:
    command = kubectl_command(
        namespace,
        "exec",
        postgres_pod,
        "--",
        "pg_dump",
        "--format=custom",
        "--no-owner",
        "-U",
        POSTGRES_USER,
        "-d",
        PRIMARY_DATABASE,
    )
    with dump_path.open("wb") as dump_file:
        result = subprocess.run(command, check=False, stdout=dump_file, stderr=subprocess.PIPE)
    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        fail(f"pg_dump failed ({result.returncode}): {stderr}")
    if dump_path.stat().st_size == 0:
        fail("pg_dump produced an empty dump")


def restore_database(namespace: str, postgres_pod: str, dump_path: Path) -> None:
    command = kubectl_command(
        namespace,
        "exec",
        "-i",
        postgres_pod,
        "--",
        "pg_restore",
        "--clean",
        "--if-exists",
        "--no-owner",
        "-U",
        POSTGRES_USER,
        "-d",
        RESTORE_DATABASE,
    )
    with dump_path.open("rb") as dump_file:
        result = subprocess.run(command, check=False, stdin=dump_file,
                                capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"pg_restore failed ({result.returncode}): {result.stderr.strip()}")


def signatures(namespace: str, postgres_pod: str, database: str) -> dict[str, tuple[int, str]]:
    rows = run_psql(namespace, postgres_pod, database, POSTGRES_TABLE_SIGNATURES)
    parsed: dict[str, tuple[int, str]] = {}
    for row in rows.splitlines():
        parts = row.split("|", 2)
        if len(parts) != 3:
            fail(f"unexpected PostgreSQL signature row for {database}: {row!r}")
        table, count, digest = parts
        parsed[table] = (int(count), digest)
    expected = {
        "agents",
        "tasks",
        "task_attempts",
        "agent_leases",
        "domain_events",
        "outbox_events",
        "gateway_commands",
        "gateway_command_deliveries",
        "gateway_ack_cursors",
    }
    if set(parsed) != expected:
        fail(f"unexpected PostgreSQL signature tables for {database}: {sorted(parsed)}")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default=os.environ.get("AGENTTEAMS_NAMESPACE", "agentteams"))
    parser.add_argument("--postgres-pod", default=os.environ.get("AGENTTEAMS_POSTGRES_POD", "postgresql-0"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    restore_created = False
    dump_path: Path | None = None
    try:
        run_kubectl(args.namespace, "get", "pod", args.postgres_pod)
        with tempfile.NamedTemporaryFile(prefix="agentteams-", suffix=".dump", delete=False) as dump_file:
            dump_path = Path(dump_file.name)
        dump_primary_database(args.namespace, args.postgres_pod, dump_path)
        source_signatures = signatures(args.namespace, args.postgres_pod, PRIMARY_DATABASE)

        run_psql(args.namespace, args.postgres_pod, "postgres",
                 f"drop database if exists {RESTORE_DATABASE};")
        run_psql(args.namespace, args.postgres_pod, "postgres",
                 f"create database {RESTORE_DATABASE};")
        restore_created = True
        restore_database(args.namespace, args.postgres_pod, dump_path)
        restored_signatures = signatures(args.namespace, args.postgres_pod, RESTORE_DATABASE)
        if source_signatures != restored_signatures:
            differences = {
                table: (source_signatures[table], restored_signatures[table])
                for table in source_signatures
                if source_signatures[table] != restored_signatures[table]
            }
            fail(f"PostgreSQL restore changed durable table signatures: {differences}")

        dump_bytes = dump_path.stat().st_size
        print(f"KIND_POSTGRES_RESTORE_OK tables={len(source_signatures)} dump_bytes={dump_bytes}")
        return 0
    finally:
        if restore_created:
            try:
                run_psql(args.namespace, args.postgres_pod, "postgres",
                         f"drop database if exists {RESTORE_DATABASE};")
            except RuntimeError as cleanup_error:
                print(f"failed to drop temporary restore database: {cleanup_error}", file=sys.stderr)
        if dump_path is not None:
            dump_path.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"KIND_POSTGRES_RESTORE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
