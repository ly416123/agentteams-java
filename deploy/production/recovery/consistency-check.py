#!/usr/bin/env python3
"""Check recovery metadata references without inspecting business payloads."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping
from pathlib import Path


COLLECTIONS = (
    "tasks",
    "attempts",
    "artifacts",
    "config_bindings",
    "config_snapshots",
    "quota_reservations",
    "sandboxes",
    "outbox",
)

REFERENCE_FIELDS = {
    "task": ("task_id", "taskId"),
    "attempt": ("attempt_id", "attemptId"),
    "artifact": ("artifact_id", "artifactId"),
    "config_binding": ("config_binding_id", "configBindingId"),
    "config_snapshot": (
        "config_snapshot_id",
        "configSnapshotId",
        "snapshot_id",
        "snapshotId",
    ),
    "quota": ("quota_id", "quotaId", "quota_reservation_id", "quotaReservationId"),
    "sandbox": ("sandbox_id", "sandboxId"),
}

OUTBOX_TYPES = {
    "task": "tasks",
    "tasks": "tasks",
    "attempt": "attempts",
    "attempts": "attempts",
    "artifact": "artifacts",
    "artifacts": "artifacts",
    "configbinding": "config_bindings",
    "config_bindings": "config_bindings",
    "configsnapshot": "config_snapshots",
    "config_snapshots": "config_snapshots",
    "quota": "quota_reservations",
    "quotareservation": "quota_reservations",
    "quota_reservations": "quota_reservations",
    "sandbox": "sandboxes",
    "sandboxes": "sandboxes",
}

TARGETS = {
    "task": "tasks",
    "attempt": "attempts",
    "artifact": "artifacts",
    "config_binding": "config_bindings",
    "config_snapshot": "config_snapshots",
    "quota": "quota_reservations",
    "sandbox": "sandboxes",
}


class ConsistencyError(ValueError):
    """Internal sentinel; its details are never shown to the caller."""


def fail() -> int:
    print("RECOVERY_CONSISTENCY_FAIL", file=sys.stderr)
    return 1


def identifier(value: object) -> str:
    if not isinstance(value, str) or not value or any(ord(char) < 0x20 for char in value):
        raise ConsistencyError
    return value


def collection(metadata: Mapping[str, object], name: str) -> list[Mapping[str, object]]:
    value = metadata.get(name)
    if not isinstance(value, list):
        raise ConsistencyError
    records: list[Mapping[str, object]] = []
    for record in value:
        if not isinstance(record, Mapping):
            raise ConsistencyError
        records.append(record)
    return records


def index(records: list[Mapping[str, object]]) -> set[str]:
    result: set[str] = set()
    for record in records:
        current = identifier(record.get("id"))
        if current in result:
            raise ConsistencyError
        result.add(current)
    return result


def reference(record: Mapping[str, object], kind: str) -> str | None:
    present = [field for field in REFERENCE_FIELDS[kind] if field in record]
    if not present:
        return None
    values = [identifier(record[field]) for field in present]
    if len(set(values)) != 1:
        raise ConsistencyError
    return values[0]


def require_reference(record: Mapping[str, object], kind: str) -> str:
    value = reference(record, kind)
    if value is None:
        raise ConsistencyError
    return value


def check(metadata: Mapping[str, object]) -> None:
    records = {name: collection(metadata, name) for name in COLLECTIONS}
    ids = {name: index(items) for name, items in records.items()}

    for record in records["attempts"]:
        if require_reference(record, "task") not in ids["tasks"]:
            raise ConsistencyError

    for record in records["artifacts"]:
        if require_reference(record, "attempt") not in ids["attempts"]:
            raise ConsistencyError

    for record in records["config_bindings"]:
        if require_reference(record, "config_snapshot") not in ids["config_snapshots"]:
            raise ConsistencyError

    for record in records["quota_reservations"]:
        if require_reference(record, "task") not in ids["tasks"]:
            raise ConsistencyError

    for record in records["sandboxes"]:
        if require_reference(record, "task") not in ids["tasks"]:
            raise ConsistencyError
        attempt_id = reference(record, "attempt")
        if attempt_id is not None and attempt_id not in ids["attempts"]:
            raise ConsistencyError

    all_ids = set().union(*ids.values())
    for record in records["outbox"]:
        aggregate_id = identifier(record.get("aggregate_id", record.get("aggregateId")))
        aggregate_type = record.get("aggregate_type", record.get("aggregateType"))
        if aggregate_type is None:
            if aggregate_id not in all_ids:
                raise ConsistencyError
        else:
            normalized_type = identifier(aggregate_type).replace("-", "_").lower()
            target = OUTBOX_TYPES.get(normalized_type)
            if target is None or aggregate_id not in ids[target]:
                raise ConsistencyError

        # These are explicit references when present; payload and event data
        # are intentionally not traversed.
        for kind, target in TARGETS.items():
            value = reference(record, kind)
            if value is not None and value not in ids[target]:
                raise ConsistencyError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--input")
    try:
        args, unknown = parser.parse_known_args(argv)
    except SystemExit:
        return fail()
    if unknown or not args.input:
        return fail()
    try:
        path = Path(args.input)
        if not path.is_file():
            raise ConsistencyError
        with path.open("r", encoding="utf-8") as stream:
            metadata = json.load(stream)
        if not isinstance(metadata, Mapping):
            raise ConsistencyError
        check(metadata)
    except (OSError, ValueError, TypeError, json.JSONDecodeError, ConsistencyError):
        return fail()
    print("RECOVERY_CONSISTENCY_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
