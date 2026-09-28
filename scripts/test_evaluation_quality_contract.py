#!/usr/bin/env python3
"""Offline contract checks for the Evidence Bundle evaluation runner."""

from pathlib import Path
import json
import unittest

import yaml

from scripts.agentteams_eval import build_evidence_bundle
from eval.deepeval_adapter import summarize_metric_results


ROOT = Path(__file__).resolve().parents[1]


class EvaluationQualityContractTest(unittest.TestCase):
    def test_runner_exports_independent_task_and_evaluation_statuses(self):
        source = (ROOT / "scripts/agentteams_eval.py").read_text(encoding="utf-8")
        self.assertIn("build_evidence_bundle", source)
        self.assertIn('"taskSuccess"', source)
        self.assertIn('"evaluationStatus"', source)
        self.assertIn("--evaluation-run-id", source)
        self.assertIn("--evidence-output", source)

    def test_bundle_never_contains_sensitive_or_presigned_material(self):
        bundle = build_evidence_bundle(
            {"id": "task-1", "spec": {"password": "secret"}},
            {"id": "run-1"},
            [{"eventId": "event-1", "payload": {"api_key": "secret"}}],
            [], [],
            {"artifacts": [{"name": "out", "storageRef": "https://minio.invalid/out?X-Amz-Signature=secret", "sha256": "a" * 64}]},
        )
        serialized = json.dumps(bundle, ensure_ascii=False).lower()
        self.assertNotIn("secret", serialized)
        self.assertNotIn("x-amz-signature", serialized)
        self.assertNotIn("downloadurl", serialized)

    def test_rubric_is_deterministic_gate_with_bounded_thresholds(self):
        rubric = yaml.safe_load((ROOT / "eval/rubrics/core.yaml").read_text(encoding="utf-8"))
        self.assertEqual("deepeval-rubric-v1", rubric["schemaVersion"])
        self.assertEqual("deterministic", rubric["hardGate"])
        for case in rubric["cases"]:
            for metric in case.get("metrics", []):
                self.assertGreaterEqual(metric["threshold"], 0)
                self.assertLessEqual(metric["threshold"], 1)

    def test_semantic_scores_keep_threshold_and_evidence_refs(self):
        report = summarize_metric_results(
            [{"metric": "answer_relevancy", "score": 0.7, "threshold": 0.8}],
            ["task-events:run-1"],
        )

        self.assertEqual("FAILED", report["evaluationStatus"])
        self.assertEqual(["task-events:run-1"], report["results"][0]["evidenceRefs"])


if __name__ == "__main__":
    unittest.main()
