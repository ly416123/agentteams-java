#!/usr/bin/env python3
"""Check recovery metadata references without inspecting business payloads."""

from __future__ import annotations

import argparse
import json
import re
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

COLLECTION_FIELDS = {
    "tasks": {"id"},
    "attempts": {"id", *REFERENCE_FIELDS["task"]},
    "artifacts": {"id", *REFERENCE_FIELDS["attempt"]},
    "config_bindings": {"id", *REFERENCE_FIELDS["config_snapshot"]},
    "config_snapshots": {"id"},
    "quota_reservations": {"id", *REFERENCE_FIELDS["task"]},
    "sandboxes": {"id", *REFERENCE_FIELDS["task"], *REFERENCE_FIELDS["attempt"]},
    "outbox": {
        "id", "aggregate_id", "aggregateId", "aggregate_type", "aggregateType",
        *(field for fields in REFERENCE_FIELDS.values() for field in fields),
    },
}


class ConsistencyError(ValueError):
    """Internal sentinel; its details are never shown to the caller."""


SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


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
        if not isinstance(record, Mapping) or not set(record.keys()).issubset(COLLECTION_FIELDS[name]):
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
    raw_values = [record[field] for field in present]
    if all(value is None for value in raw_values):
        return None
    if any(value is None for value in raw_values):
        raise ConsistencyError
    values = [identifier(value) for value in raw_values]
    if len(set(values)) != 1:
        raise ConsistencyError
    return values[0]


def require_reference(record: Mapping[str, object], kind: str) -> str:
    value = reference(record, kind)
    if value is None:
        raise ConsistencyError
    return value


def check(metadata: Mapping[str, object]) -> None:
    if set(metadata.keys()) != {*COLLECTIONS, "object_references"}:
        raise ConsistencyError
    records = {name: collection(metadata, name) for name in COLLECTIONS}
    ids = {name: index(items) for name, items in records.items()}

    for record in records["attempts"]:
        if require_reference(record, "task") not in ids["tasks"]:
            raise ConsistencyError

    for record in records["artifacts"]:
        attempt_id = reference(record, "attempt")
        if attempt_id is not None and attempt_id not in ids["attempts"]:
            raise ConsistencyError

    for record in records["config_bindings"]:
        if require_reference(record, "config_snapshot") not in ids["config_snapshots"]:
            raise ConsistencyError

    for record in records["quota_reservations"]:
        task_id = reference(record, "task")
        if task_id is not None and task_id not in ids["tasks"]:
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


def object_records(value: object) -> list[Mapping[str, object]]:
    if not isinstance(value, list):
        raise ConsistencyError
    records: list[Mapping[str, object]] = []
    for record in value:
        if not isinstance(record, Mapping):
            raise ConsistencyError
        records.append(record)
    return records


def object_path(value: object) -> str:
    if (not isinstance(value, str) or not value or value.startswith("/") or "\\" in value
            or any(ord(char) < 0x20 or ord(char) == 0x7f for char in value)):
        raise ConsistencyError
    if any(part in {"", ".", ".."} for part in value.split("/")):
        raise ConsistencyError
    return value


def object_metadata(record: Mapping[str, object]) -> tuple[str, int, str]:
    if set(record.keys()) != {"path", "size_bytes", "sha256"}:
        raise ConsistencyError
    path = object_path(record.get("path"))
    size = record.get("size_bytes")
    digest = record.get("sha256")
    if type(size) is not int or size < 0:
        raise ConsistencyError
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ConsistencyError
    return path, size, digest.lower()


def check_objects(metadata: Mapping[str, object], inventory: Mapping[str, object]) -> None:
    if set(inventory.keys()) != {"objects"}:
        raise ConsistencyError
    raw_references = metadata.get("object_references")
    raw_objects = inventory.get("objects")
    references: dict[str, tuple[int, str]] = {}
    objects: dict[str, tuple[int, str]] = {}

    for record in object_records(raw_references):
        path, size, digest = object_metadata(record)
        value = (size, digest)
        if path in references and references[path] != value:
            raise ConsistencyError
        references[path] = value

    for record in object_records(raw_objects):
        path, size, digest = object_metadata(record)
        if path in objects:
            raise ConsistencyError
        objects[path] = (size, digest)

    for path, value in references.items():
        if objects.get(path) != value:
            raise ConsistencyError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--input")
    parser.add_argument("--object-inventory")
    try:
        args, unknown = parser.parse_known_args(argv)
    except SystemExit:
        return fail()
    if unknown or not args.input or not args.object_inventory:
        return fail()
    try:
        path = Path(args.input)
        if not path.is_file():
            raise ConsistencyError
        with path.open("r", encoding="utf-8") as stream:
            metadata = json.load(stream)
        if not isinstance(metadata, Mapping):
            raise ConsistencyError
        inventory_path = Path(args.object_inventory)
        if not inventory_path.is_file():
            raise ConsistencyError
        with inventory_path.open("r", encoding="utf-8") as stream:
            inventory = json.load(stream)
        if not isinstance(inventory, Mapping):
            raise ConsistencyError
        check(metadata)
        check_objects(metadata, inventory)
    except (OSError, ValueError, TypeError, RuntimeError, ConsistencyError):
        return fail()
    print("RECOVERY_CONSISTENCY_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
