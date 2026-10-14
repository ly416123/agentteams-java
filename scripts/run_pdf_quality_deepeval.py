#!/usr/bin/env python3
"""Run ChatGPT plan OAuth-backed DeepEval quality checks for selected L5 PDFs."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from eval.deepeval_adapter import build_test_case_payload
from scripts.agentteams_eval import EvalError, _sanitize_value, load_cases


DEFAULT_REPORT = ROOT / "eval/reports/2026-10-10-l5-dual-mcp-10-cases-pdf-metadata-gate.json"
DEFAULT_EVIDENCE = ROOT / "eval/reports/2026-10-10-l5-dual-mcp-10-cases-pdf-metadata-gate-evidence"
DEFAULT_DEEPEVAL_INPUTS = ROOT / "eval/reports/2026-10-10-l5-dual-mcp-10-cases-pdf-metadata-gate-deepeval-inputs"
DEFAULT_OUTPUT = ROOT / "eval/reports/2026-10-10-l5-dual-mcp-10-cases-pdf-metadata-gate-deepeval.json"
def _status(metrics: list[dict[str, Any]]) -> str:
    if not metrics or any(item.get("score") is None for item in metrics):
        return "INCONCLUSIVE"
    return "FAILED" if any(item["score"] < item["threshold"] for item in metrics) else "PASSED"


def _safe_error_message(error: Exception) -> str:
    """Expose bounded, credential-scrubbed evaluator diagnostics."""
    return str(_sanitize_value(str(error)) or type(error).__name__)[:500]


def _deterministic_pdf_selected(item: Mapping[str, Any]) -> bool:
    """Only judge the PDF selected by this run's deterministic result manifest."""
    for assertion in item.get("assertions", []) or []:
        if isinstance(assertion, Mapping) and assertion.get("type") == "artifact_pdf":
            return assertion.get("passed") is True
    return False


def _observation(bundle: Mapping[str, Any], pdf_path: Path) -> dict[str, Any]:
    events = []
    for key in ("taskEvents", "processEvents", "subtaskEvents"):
        values = bundle.get(key, [])
        if isinstance(values, list):
            events.extend(item for item in values if isinstance(item, Mapping))
    pdf_bytes = pdf_path.read_bytes()
    task_snapshot = bundle.get("taskSnapshot") or {}
    manifest = bundle.get("resultManifest") or {}
    return {
        "task": task_snapshot,
        "run": {"id": bundle.get("executionRunId")},
        "events": events,
        "taskTree": bundle.get("taskTree"),
        "sourceEvidence": bundle.get("sourceEvidence"),
        "result": {
            "summary": manifest.get("summary", ""),
            "artifacts": [{"name": pdf_path.name, "contentType": "application/pdf",
                           "contentBytes": pdf_bytes}],
        },
    }


def _chatgpt_metrics():
    from deepeval.metrics import AnswerRelevancyMetric, FaithfulnessMetric, GEval
    from deepeval.test_case import SingleTurnParams
    from eval.chatgpt_plan_deepeval import ChatGptPlanDeepEvalModel

    model = ChatGptPlanDeepEvalModel(model=os.environ.get("CHATGPT_OAUTH_MODEL", "gpt-5.6-luna"))
    return [
        ("question_relevance", 0.80, AnswerRelevancyMetric(threshold=0.80, model=model)),
        ("factual_accuracy", 0.80, FaithfulnessMetric(threshold=0.80, model=model)),
        ("semantic_completeness", 0.80, GEval(
            name="Semantic Completeness",
            criteria="Assess structural and semantic completeness: does this Simplified Chinese research PDF answer the requested research question and include recent news findings, official verification, impact analysis, conclusion, and a source list? For each news item, check for a title, publication date, publisher, and original URL. If the report explicitly reports that fewer than five eligible news items were supported by the retrieved evidence and describes the search limitation, do not penalize the count alone; penalize unsupported padding or invented details. Judge presence, coverage, and logical organization from the extracted PDF text. The deterministic gate already checks that the artifact is a valid PDF, so do not speculate about file type. Do not judge factual support because source evidence is evaluated separately. Score from 0 to 1.",
            evaluation_params=[SingleTurnParams.INPUT, SingleTurnParams.ACTUAL_OUTPUT], threshold=0.80, model=model)),
        ("writing_quality", 0.80, GEval(
            name="Chinese Writing Quality",
            criteria="Assess whether the actual PDF text is clear, coherent, professional Simplified Chinese, with readable structure and concise wording. Product names and URLs may remain in English. Penalize garbled or awkward language. Score from 0 to 1.",
            evaluation_params=[SingleTurnParams.ACTUAL_OUTPUT], threshold=0.80, model=model)),
    ]


def run(report_path: Path, evidence_dir: Path, output_path: Path,
        deepeval_input_dir: Path | None = None) -> dict[str, Any]:
    os.environ.setdefault("DEEPEVAL_TELEMETRY_OPT_OUT", "1")
    os.environ.setdefault("DEEPEVAL_DISABLE_DOTENV", "1")
    os.environ.setdefault("DEEPEVAL_HOME", str(Path.home() / ".cache/agentteams/pdf-quality-deepeval-state"))
    if not os.environ.get("CHATGPT_OAUTH_ACCESS_TOKEN"):
        raise EvalError("CHATGPT_OAUTH_ACCESS_TOKEN is required; obtain it from the authorized L5 OAuth session")

    report = json.loads(report_path.read_text(encoding="utf-8"))
    cases = {case.case_id: case for case in load_cases(ROOT / "eval/cases/ai-news-web-search-10.yaml")}
    metric_specs = _chatgpt_metrics()
    output: dict[str, Any] = {
        "schemaVersion": "pdf-quality-deepeval-report-v1",
        "provider": "chatgpt-plan-oauth",
        "model": os.environ.get("CHATGPT_OAUTH_MODEL", "gpt-5.6-luna"),
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "inputReport": report_path.name,
        "cases": [],
    }
    for item in report.get("cases", []):
        case_id = item.get("caseId")
        case_output: dict[str, Any] = {"caseId": case_id, "deterministicStatus": item.get("evaluationStatus"),
                                       "metrics": []}
        if not _deterministic_pdf_selected(item):
            case_output.update({"status": "FAILED",
                                "reason": "current run did not select a valid final PDF artifact"})
            output["cases"].append(case_output)
            print(json.dumps({"caseId": case_id, "status": "FAILED",
                              "reason": "deterministic PDF artifact gate failed"}), flush=True)
            continue
        pdfs = sorted((evidence_dir / "pdfs" / str(case_id)).glob("*.pdf"))
        if len(pdfs) != 1:
            case_output.update({"status": "FAILED", "reason": "expected exactly one selected final PDF",
                                "pdfCount": len(pdfs)})
            output["cases"].append(case_output)
            print(json.dumps({"caseId": case_id, "status": "FAILED", "reason": "missing or ambiguous PDF"}), flush=True)
            continue
        if case_id not in cases:
            raise EvalError(f"case definition not found for {case_id}")
        evidence_path = evidence_dir / f"{case_id}.json"
        bundle = json.loads(evidence_path.read_text(encoding="utf-8"))
        payload = build_test_case_payload(cases[case_id], _observation(bundle, pdfs[0]))
        if deepeval_input_dir is not None:
            raw_input_path = deepeval_input_dir / f"{case_id}.json"
            try:
                raw_input = json.loads(raw_input_path.read_text(encoding="utf-8"))
                exported_case = raw_input.get("testCase", {})
                exported_context = exported_case.get("retrieval_context", [])
                if isinstance(exported_context, list):
                    payload["retrieval_context"] = [str(value)[:1_800] for value in exported_context[:12]
                                                     if isinstance(value, str) and value.strip()]
            except (OSError, json.JSONDecodeError, AttributeError):
                # A missing or malformed raw input leaves factual scoring inconclusive.
                pass
        # Bound judge context to keep requests focused and avoid oversized prompts.
        payload["actual_output"] = payload["actual_output"][:16000]
        payload["retrieval_context"] = [str(value)[:1800] for value in payload.get("retrieval_context", [])[:12]]
        from deepeval.test_case import LLMTestCase
        test_case = LLMTestCase(input=payload["input"], actual_output=payload["actual_output"],
                                retrieval_context=payload["retrieval_context"])
        for dimension, threshold, metric in metric_specs:
            if dimension == "factual_accuracy" and not payload.get("retrieval_context"):
                case_output["metrics"].append({"dimension": dimension, "score": None,
                                               "threshold": threshold, "status": "INCONCLUSIVE",
                                               "reason": "Evidence bundle has source references but no source text for claim verification."})
                continue
            try:
                metric.measure(test_case)
                score = float(metric.score) if metric.score is not None else None
                reason = str(metric.reason or "")[:1200]
                case_output["metrics"].append({"dimension": dimension, "score": score,
                                               "threshold": threshold, "reason": reason,
                                               "status": "PASSED" if score is not None and score >= threshold else
                                                         "FAILED" if score is not None else "INCONCLUSIVE"})
            except Exception as exc:
                case_output["metrics"].append({"dimension": dimension, "score": None,
                                               "threshold": threshold, "status": "INCONCLUSIVE",
                                               "errorType": type(exc).__name__,
                                               "errorMessage": _safe_error_message(exc)})
            output_path.parent.mkdir(parents=True, exist_ok=True)
            case_output["status"] = _status(case_output["metrics"])
            output["cases"].append(case_output) if not output["cases"] or output["cases"][-1] is not case_output else None
            # Persist each metric so interrupted DeepSeek requests do not lose completed evidence.
            output["finishedAt"] = datetime.now(timezone.utc).isoformat()
            output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({"caseId": case_id, "dimension": dimension,
                              "status": case_output["metrics"][-1]["status"],
                              "score": case_output["metrics"][-1]["score"]}, ensure_ascii=False), flush=True)
        # Refresh after all four metrics, including status changes.
        case_output["status"] = _status(case_output["metrics"])
        output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output["finishedAt"] = datetime.now(timezone.utc).isoformat()
    output["expectedCaseCount"] = len(report.get("cases", []))
    output["passed"] = (len(output["cases"]) == output["expectedCaseCount"]
                         and all(c.get("status") == "PASSED" for c in output["cases"]))
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--evidence-dir", type=Path, default=DEFAULT_EVIDENCE)
    parser.add_argument("--deepeval-input-dir", type=Path, default=DEFAULT_DEEPEVAL_INPUTS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        result = run(args.report, args.evidence_dir, args.output, args.deepeval_input_dir)
    except (EvalError, OSError, ValueError, KeyError) as exc:
        print(f"DeepEval PDF quality error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]),
                      "output": str(args.output)}, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
