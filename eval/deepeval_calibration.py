#!/usr/bin/env python3
"""Offline calibration helpers for DeepEval scores and human labels.

Calibration records contain metric scores and reviewer labels only.  They do
not contain prompts, outputs, artifacts, credentials, or model responses.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Mapping

import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import EvalError  # noqa: E402


def _read_value(path: str | Path) -> Any:
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise EvalError(f"cannot read calibration file {path}: {exc}") from exc
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        try:
            return yaml.safe_load(text)
        except yaml.YAMLError as exc:
            raise EvalError(f"invalid calibration JSON/YAML {path}: {exc}") from exc


def load_calibration(path: str | Path) -> dict[str, Any]:
    """Load and validate a human-reviewed score calibration record."""
    value = _read_value(path)
    if not isinstance(value, Mapping):
        raise EvalError("calibration must contain an object")
    calibration = dict(value)
    if calibration.get("schemaVersion") != "deepeval-calibration-v1":
        raise EvalError("calibration schemaVersion must be deepeval-calibration-v1")
    suite = str(calibration.get("suite", "")).strip()
    if not suite:
        raise EvalError("calibration suite is required")
    raw_samples = calibration.get("samples")
    if not isinstance(raw_samples, list) or not raw_samples:
        raise EvalError("calibration samples must be a non-empty list")

    samples: list[dict[str, Any]] = []
    seen_sample_ids: set[str] = set()
    for index, raw_sample in enumerate(raw_samples):
        if not isinstance(raw_sample, Mapping):
            raise EvalError(f"calibration sample[{index}] must be an object")
        sample = dict(raw_sample)
        case_id = str(sample.get("caseId", "")).strip()
        sample_id = str(sample.get("sampleId", "")).strip()
        label = str(sample.get("humanLabel", "")).strip().lower()
        if not case_id or not sample_id:
            raise EvalError(f"calibration sample[{index}] requires caseId and sampleId")
        if sample_id in seen_sample_ids:
            raise EvalError(f"duplicate calibration sampleId: {sample_id}")
        if label not in {"pass", "fail"}:
            raise EvalError(f"calibration sample {sample_id} humanLabel must be pass or fail")
        scores = sample.get("metricScores")
        if not isinstance(scores, Mapping) or not scores:
            raise EvalError(f"calibration sample {sample_id}.metricScores must be non-empty")
        normalized_scores: dict[str, float] = {}
        for metric_id, raw_score in scores.items():
            metric_name = str(metric_id).strip()
            if not metric_name:
                raise EvalError(f"calibration sample {sample_id} has an empty metric id")
            if isinstance(raw_score, bool) or not isinstance(raw_score, (int, float)):
                raise EvalError(f"calibration sample {sample_id} metric {metric_name} must be numeric")
            score = float(raw_score)
            if not 0.0 <= score <= 1.0:
                raise EvalError(f"calibration sample {sample_id} metric {metric_name} must be between 0 and 1")
            normalized_scores[metric_name] = score
        seen_sample_ids.add(sample_id)
        samples.append({"caseId": case_id, "sampleId": sample_id,
                        "humanLabel": label, "metricScores": normalized_scores})
    return {"schemaVersion": "deepeval-calibration-v1", "suite": suite,
            "samples": samples, "sampleCount": len(samples)}


def recommend_thresholds(calibration: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    """Recommend separating thresholds when pass/fail scores do not overlap."""
    samples = calibration.get("samples", [])
    by_metric: dict[str, dict[str, list[float]]] = {}
    for sample in samples:
        label = str(sample["humanLabel"])
        for metric_id, score in sample["metricScores"].items():
            entry = by_metric.setdefault(metric_id, {"pass": [], "fail": []})
            entry[label].append(float(score))

    recommendations: dict[str, dict[str, Any]] = {}
    for metric_id, groups in sorted(by_metric.items()):
        passed = groups["pass"]
        failed = groups["fail"]
        result: dict[str, Any] = {
            "passSamples": len(passed), "failSamples": len(failed),
            "candidateThreshold": None, "separable": False, "agreement": None,
        }
        if passed and failed and min(passed) > max(failed):
            threshold = (min(passed) + max(failed)) / 2.0
            correct = sum((score >= threshold) == (label == "pass")
                          for sample in samples
                          if metric_id in sample["metricScores"]
                          for score, label in [(float(sample["metricScores"][metric_id]),
                                                str(sample["humanLabel"]))])
            count = len(passed) + len(failed)
            result["candidateThreshold"] = round(threshold, 6)
            result["separable"] = True
            result["agreement"] = round(correct / count, 6)
        recommendations[metric_id] = result
    return recommendations


def render_report(calibration: Mapping[str, Any]) -> dict[str, Any]:
    sample_count = calibration.get("sampleCount")
    if sample_count is None:
        sample_count = len(calibration.get("samples", []))
    return {
        "schemaVersion": "deepeval-calibration-report-v1",
        "suite": calibration["suite"],
        "sampleCount": sample_count,
        "metrics": recommend_thresholds(calibration),
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="calibration JSON/YAML")
    parser.add_argument("--output", required=True, help="threshold recommendation JSON")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        report = render_report(load_calibration(args.input))
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
        return 0
    except (EvalError, OSError, ValueError) as exc:
        print(f"DeepEval calibration error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
