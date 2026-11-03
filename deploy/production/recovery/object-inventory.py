#!/usr/bin/env python3
"""Create a metadata-only SHA-256 inventory for a restored object-store mirror."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
import tempfile
from pathlib import Path


class InventoryError(ValueError):
    """Internal sentinel; details are intentionally not printed."""


def fail() -> int:
    print("RECOVERY_OBJECT_INVENTORY_FAIL", file=sys.stderr)
    return 1


def digest_file(path: Path) -> tuple[int, str]:
    try:
        before = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(before.st_mode):
            raise InventoryError
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns) != (
                    before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns):
                raise InventoryError
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
                size += len(chunk)
            after = os.fstat(stream.fileno())
        if (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) != (
                before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns):
            raise InventoryError
        if size != before.st_size:
            raise InventoryError
        return size, digest.hexdigest()
    except OSError as error:
        raise InventoryError from error


def inventory(root: Path, output: Path) -> list[dict[str, object]]:
    if root.is_symlink() or not root.is_dir():
        raise InventoryError
    resolved_root = root.resolve(strict=True)
    output_parent = output.parent.resolve(strict=True)
    output_path = output_parent / output.name
    try:
        output_path.relative_to(resolved_root)
    except ValueError:
        pass
    else:
        raise InventoryError
    if output_path.is_symlink():
        raise InventoryError

    records: list[dict[str, object]] = []
    pending = [resolved_root]
    while pending:
        current = pending.pop()
        try:
            with os.scandir(current) as iterator:
                entries = sorted(iterator, key=lambda entry: entry.name)
        except OSError as error:
            raise InventoryError from error
        for entry in entries:
            path = Path(entry.path)
            if entry.is_symlink():
                raise InventoryError
            if entry.is_dir(follow_symlinks=False):
                pending.append(path)
                continue
            if not entry.is_file(follow_symlinks=False):
                raise InventoryError
            size, digest = digest_file(path)
            records.append({
                "path": path.relative_to(resolved_root).as_posix(),
                "size_bytes": size,
                "sha256": digest,
            })
    records.sort(key=lambda record: str(record["path"]))
    return records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--root")
    parser.add_argument("--output")
    try:
        args, unknown = parser.parse_known_args(argv)
    except SystemExit:
        return fail()
    if unknown or not args.root or not args.output:
        return fail()

    temporary: str | None = None
    try:
        root = Path(args.root)
        output = Path(args.output)
        records = inventory(root, output)
        descriptor, temporary = tempfile.mkstemp(prefix=".recovery-inventory-", dir=output.parent)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump({"objects": records}, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
        temporary = None
        os.chmod(output, 0o600, follow_symlinks=False)
        total_bytes = sum(int(record["size_bytes"]) for record in records)
    except (OSError, ValueError, TypeError, RuntimeError, InventoryError):
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        return fail()
    print(f"RECOVERY_OBJECT_INVENTORY_OK objects={len(records)} bytes={total_bytes}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
