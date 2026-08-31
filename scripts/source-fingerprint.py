#!/usr/bin/env python3
"""Calculate and verify a deterministic fingerprint of the tracked source tree."""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path
from stat import S_ISREG


EXCLUDED_PREFIXES = (
    ".local/",
    "target/",
    "console/node_modules/",
    "console/dist/",
)


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(description=__doc__)
    subparsers = command.add_subparsers(dest="command")

    fingerprint = subparsers.add_parser("fingerprint", help="print the source fingerprint")
    fingerprint.set_defaults(command="fingerprint")
    verify = subparsers.add_parser("verify", help="verify an expected source fingerprint")
    verify.add_argument("expected")
    verify.set_defaults(command="verify")
    for subparser in (fingerprint, verify):
        subparser.add_argument("--root", required=True, type=Path)
    return command


def repository_root(root: Path) -> Path:
    candidate = root.expanduser().resolve()
    if not candidate.is_dir():
        raise ValueError(f"repository root does not exist: {candidate}")
    try:
        output = subprocess.check_output(
            ["git", "-C", str(candidate), "rev-parse", "--show-toplevel"],
            text=True,
            stderr=subprocess.PIPE,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"repository root is not a Git worktree: {candidate}") from error
    resolved = Path(output).resolve()
    if resolved != candidate:
        raise ValueError(f"repository root must be the Git worktree root: {resolved}")
    return resolved


def tracked_paths(root: Path) -> list[str]:
    try:
        output = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "--cached", "-z"],
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError("unable to read tracked source files") from error

    paths = []
    for encoded in output.split(b"\0"):
        if not encoded:
            continue
        relative = os.fsdecode(encoded).replace(os.sep, "/")
        if any(relative == prefix[:-1] or relative.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
            continue
        path = root / Path(relative)
        if not path.exists():
            raise ValueError(f"tracked source file is missing: {relative}")
        if not S_ISREG(path.stat().st_mode):
            continue
        paths.append(relative)
    return sorted(paths)


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def calculate(root: Path) -> str:
    entries = [
        f"{relative}\0{file_digest(root / Path(relative))}\n".encode("utf-8")
        for relative in tracked_paths(root)
    ]
    digest = hashlib.sha256()
    for entry in entries:
        digest.update(entry)
    return digest.hexdigest()


def main(argv: list[str]) -> int:
    arguments = parser().parse_args(argv)
    if arguments.command is None:
        parser().error("a command is required: fingerprint or verify")
    try:
        root = repository_root(arguments.root)
        actual = calculate(root)
        if arguments.command == "verify":
            expected = arguments.expected.strip().lower()
            if len(expected) != 64 or any(character not in "0123456789abcdef" for character in expected):
                raise ValueError("expected fingerprint must be 64 lowercase hexadecimal characters")
            if actual != expected:
                print(f"fingerprint mismatch: expected={expected} actual={actual}", file=sys.stderr)
                return 1
        print(actual)
        return 0
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
