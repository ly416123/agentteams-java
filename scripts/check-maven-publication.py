#!/usr/bin/env python3
"""Classify whether a tagged Maven release can be published or retried."""

from __future__ import annotations

import argparse
import sys


def classify(expected: set[str], published: set[str]) -> str:
    if not expected:
        raise ValueError("expected package set must not be empty")
    if published - expected:
        raise ValueError("published package set contains unexpected names")
    if not published:
        return "PUBLISH"
    if published == expected:
        return "SKIP"
    missing = ",".join(sorted(expected - published))
    present = ",".join(sorted(published))
    raise RuntimeError(
        "MAVEN_PUBLISH_PARTIAL: release version exists for "
        f"[{present}] but is missing [{missing}]. Preserve the published "
        "artifacts and publish a new patch tag after fixing the release cause."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", nargs="+", required=True)
    parser.add_argument("--published", nargs="*", default=[])
    args = parser.parse_args()
    try:
        state = classify(set(args.expected), set(args.published))
    except ValueError as exc:
        parser.error(str(exc))
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(state)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
