#!/usr/bin/env python3
"""Validate rendered PrometheusRule resources and catch malformed alert nesting."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

import yaml


def fail(message: str) -> None:
    print(f"PROMETHEUSRULE_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def scalar_map(value: object, location: str) -> dict:
    if value is None:
        return {}
    if not isinstance(value, dict):
        fail(f"{location} must be a mapping")
    if any(not isinstance(key, str) or not isinstance(item, (str, int, float, bool))
           for key, item in value.items()):
        fail(f"{location} must contain only scalar values")
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        resources = [item for item in yaml.safe_load_all(args.manifest.read_text(encoding="utf-8")) if item]
    except Exception as exc:  # pragma: no cover - message is the useful result for CI
        fail(f"cannot parse {args.manifest}: {exc}")

    rules = [item for item in resources if item.get("kind") == "PrometheusRule"]
    if len(rules) != 1:
        fail(f"expected exactly one PrometheusRule, found {len(rules)}")
    rule_resource = rules[0]
    if rule_resource.get("apiVersion") != "monitoring.coreos.com/v1":
        fail("PrometheusRule must use monitoring.coreos.com/v1")
    groups = rule_resource.get("spec", {}).get("groups")
    if not isinstance(groups, list) or not groups:
        fail("PrometheusRule spec.groups must be a non-empty list")

    alert_names: set[str] = set()
    alert_count = 0
    for group_index, group in enumerate(groups):
        group_rules = group.get("rules") if isinstance(group, dict) else None
        if not isinstance(group_rules, list) or not group_rules:
            fail(f"spec.groups[{group_index}].rules must be a non-empty list")
        for rule_index, rule in enumerate(group_rules):
            location = f"spec.groups[{group_index}].rules[{rule_index}]"
            if not isinstance(rule, dict):
                fail(f"{location} must be a mapping")
            alert = rule.get("alert")
            if not isinstance(alert, str) or not alert.strip():
                fail(f"{location}.alert must be a non-empty string")
            if alert in alert_names:
                fail(f"duplicate alert name: {alert}")
            alert_names.add(alert)
            for field in ("expr", "for"):
                if not isinstance(rule.get(field), str) or not rule[field].strip():
                    fail(f"{location}.{field} must be a non-empty string")
            labels = scalar_map(rule.get("labels"), f"{location}.labels")
            annotations = scalar_map(rule.get("annotations"), f"{location}.annotations")
            if "annotations" in labels or "labels" in annotations:
                fail(f"{location} has nested labels/annotations; they must be sibling mappings")
            if not annotations.get("summary") or not annotations.get("description"):
                fail(f"{location}.annotations must include summary and description")
            alert_count += 1

    print(f"PROMETHEUSRULE_OK alerts={alert_count}")


if __name__ == "__main__":
    main()
