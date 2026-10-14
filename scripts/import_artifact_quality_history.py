#!/usr/bin/env python3
"""Build privacy-bounded JSONL facts for the Control Plane ledger importer."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any


QUALITY_TYPES = {"artifact_pdf", "artifact_pdf_quality", "artifact_integrity", "source_citation"}
PLAN_ACTION = re.compile(r"修复|修正|改进|优化|\bfix(?:ed)?\b|\brepair(?:ed)?\b", re.IGNORECASE)
CHECKED = re.compile(r"^\s*[-*]\s+\[x\]\s+(.+)$", re.IGNORECASE)
TASK_ID = re.compile(r"\btaskId\s*[:=]\s*([0-9a-fA-F-]{36})\b")
RUN_ID = re.compile(r"\brunId\s*[:=]\s*([0-9a-fA-F-]{36})\b")
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")


def _read_json(path: Path) -> tuple[dict[str, Any] | None, str]:
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, digest
    return (value if isinstance(value, dict) else None), digest


def _valid_uuid(value: Any) -> str | None:
    if not isinstance(value, str) or not UUID_PATTERN.fullmatch(value):
        return None
    try:
        return str(uuid.UUID(value))
    except ValueError:
        return None


def _timestamp(value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.isoformat().replace("+00:00", "Z")


def _status(assertion: dict[str, Any]) -> str:
    value = assertion.get("status")
    if isinstance(value, str) and value.upper() in {"PASSED", "FAILED", "INCONCLUSIVE"}:
        return value.upper()
    passed = assertion.get("passed")
    return "PASSED" if passed is True else "FAILED" if passed is False else "INCONCLUSIVE"


def _event_id(key: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "agentteams-artifact-quality:" + key))


def _single_pdf_metadata(case: dict[str, Any]) -> tuple[str | None, str | None]:
    artifacts = case.get("pdfArtifacts")
    if not isinstance(artifacts, list):
        evidence = case.get("evidenceBundle")
        artifacts = evidence.get("pdfArtifacts") if isinstance(evidence, dict) else None
    if not isinstance(artifacts, list):
        return None, None
    pdfs = [item for item in artifacts if isinstance(item, dict)
            and (str(item.get("name", "")).lower().endswith(".pdf")
                 or str(item.get("contentType", "")).lower() == "application/pdf")]
    if len(pdfs) != 1:
        return None, None
    item = pdfs[0]
    name = item.get("name")
    digest = item.get("sha256")
    safe_name = name if isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name) else None
    safe_digest = digest.lower() if isinstance(digest, str) and re.fullmatch(r"[a-fA-F0-9]{64}", digest) else None
    return safe_name, safe_digest


def _base_event(scope: dict[str, str], task_id: str | None, run_id: str | None, sequence: int,
                event_type: str, status: str, summary: str, source_ref: str, key: str,
                occurred_at: str, confidence: str, category: str | None = None,
                check_item: str | None = None, artifact_name: str | None = None,
                sha256: str | None = None, action_source: str | None = None) -> dict[str, Any]:
    return {
        "id": _event_id(key), "scope": scope, "taskId": task_id, "runId": run_id,
        "sequence": sequence, "eventType": event_type, "artifactName": artifact_name,
        "fileId": None, "manifestRef": None, "sha256": sha256, "categoryCode": category,
        "checkItem": check_item, "actionSource": action_source, "status": status,
        "score": None, "scoreDelta": None, "summary": summary, "sourceType": "HISTORICAL",
        "sourceRef": source_ref, "confidence": confidence, "idempotencyKey": key,
        "occurredAt": occurred_at,
    }


def build_history_events(reports_dir: Path, plans_dir: Path) -> tuple[list[dict[str, Any]], dict[str, int]]:
    events: list[dict[str, Any]] = []
    scope_by_execution: dict[tuple[str, str], set[tuple[str, str, str]]] = {}
    stats = {"reports": 0, "plans": 0, "skipped": 0}

    for path in sorted(reports_dir.rglob("*.json")) if reports_dir.exists() else []:
        report, digest = _read_json(path)
        if report is None:
            stats["skipped"] += 1
            continue
        scope_value = report.get("targetScope")
        if not isinstance(scope_value, dict):
            stats["skipped"] += 1
            continue
        tenant = scope_value.get("tenant")
        project = scope_value.get("project")
        finished_at = _timestamp(report.get("finishedAt")) or _timestamp(report.get("startedAt"))
        cases = report.get("cases")
        if not isinstance(tenant, str) or not tenant.strip() or not isinstance(project, str) or not project.strip() \
                or finished_at is None or not isinstance(cases, list):
            stats["skipped"] += 1
            continue
        scope = {"tenant": tenant.strip(), "project": project.strip()}
        stats["reports"] += 1
        for case_index, case in enumerate(cases):
            if not isinstance(case, dict):
                stats["skipped"] += 1
                continue
            raw_task = _valid_uuid(case.get("taskId"))
            evidence = case.get("evidenceBundle")
            raw_run = _valid_uuid(case.get("runId")) or (
                _valid_uuid(evidence.get("executionRunId")) if isinstance(evidence, dict) else None)
            task_id, run_id = (raw_task, raw_run) if raw_task and raw_run else (None, None)
            confidence = "HIGH" if task_id and run_id else "LOW"
            case_key = re.sub(r"[^A-Za-z0-9_.:-]", "_", str(case.get("caseId") or f"case-{case_index}"))[:100]
            if task_id and run_id:
                scope_by_execution.setdefault((task_id, run_id), set()).add(
                    (scope["tenant"], scope["project"], finished_at))
            assertions = case.get("assertions")
            if not isinstance(assertions, list):
                stats["skipped"] += 1
                continue
            artifact_name, artifact_sha = _single_pdf_metadata(case)
            case_checks: list[tuple[int, str]] = []
            for assertion_index, assertion in enumerate(assertions):
                if not isinstance(assertion, dict):
                    continue
                assertion_type = assertion.get("type")
                if not isinstance(assertion_type, str) or assertion_type.lower() not in QUALITY_TYPES:
                    continue
                status = _status(assertion)
                category = re.sub(r"[^a-zA-Z0-9_.:-]", "_", assertion_type.lower())[:64]
                key = f"history:{digest}:case:{case_key}:assertion:{assertion_index}"
                event = _base_event(scope, task_id, run_id, assertion_index, "QUALITY_CHECK", status,
                                    f"Historical report records {status.lower()} for {category}.",
                                    f"history:sha256:{digest}#case:{case_key}:assertion:{assertion_index}", key,
                                    finished_at, confidence, category, category, artifact_name, artifact_sha)
                events.append(event)
                case_checks.append((assertion_index, status))
            if case_checks:
                statuses = [status for _, status in case_checks]
                final_status = "FAILED" if "FAILED" in statuses else (
                    "INCONCLUSIVE" if "INCONCLUSIVE" in statuses else "PASSED")
                sequence = max(index for index, _ in case_checks) + 1
                key = f"history:{digest}:case:{case_key}:final-status"
                events.append(_base_event(scope, task_id, run_id, sequence, "FINAL_STATUS", final_status,
                    f"Historical artifact checks summarize to {final_status.lower()}.",
                    f"history:sha256:{digest}#case:{case_key}:final-status", key, finished_at, confidence,
                    "artifact_quality"))

    for path in sorted(plans_dir.rglob("*.md")) if plans_dir.exists() else []:
        try:
            raw = path.read_bytes()
            content = raw.decode("utf-8")
        except (OSError, UnicodeDecodeError):
            stats["skipped"] += 1
            continue
        digest = hashlib.sha256(raw).hexdigest()
        stats["plans"] += 1
        for line_number, line in enumerate(content.splitlines(), 1):
            match = CHECKED.match(line)
            if not match or not PLAN_ACTION.search(match.group(1)):
                continue
            task_match, run_match = TASK_ID.search(match.group(1)), RUN_ID.search(match.group(1))
            task_id = _valid_uuid(task_match.group(1)) if task_match else None
            run_id = _valid_uuid(run_match.group(1)) if run_match else None
            if not task_id or not run_id:
                stats["skipped"] += 1
                continue
            associations = scope_by_execution.get((task_id, run_id), set())
            if len(associations) != 1:
                stats["skipped"] += 1
                continue
            tenant, project, occurred_at = next(iter(associations))
            scope = {"tenant": tenant, "project": project}
            key = f"history:{digest}:plan-line:{line_number}:task:{task_id}:run:{run_id}"
            events.append(_base_event(scope, task_id, run_id, line_number, "OPTIMIZATION_ACTION", "RECORDED",
                "A completed plan checklist records an optimization action.",
                f"history:sha256:{digest}#plan-line:{line_number}", key,
                occurred_at, "MEDIUM", "historical_plan_action", None, None, None, "UNKNOWN"))

    return events, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=Path("eval/reports"))
    parser.add_argument("--plans", type=Path, default=Path("docs/superpowers/plans"))
    parser.add_argument("--output", type=Path, required=True, help="UTF-8 newline-delimited JSON output")
    args = parser.parse_args(argv)
    events, stats = build_history_events(args.reports, args.plans)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n"
                                     for item in events), encoding="utf-8")
    print(json.dumps({**stats, "events": len(events)}, separators=(",", ":")), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
