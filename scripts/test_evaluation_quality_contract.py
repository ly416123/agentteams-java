#!/usr/bin/env python3
"""Offline contract checks for the Evidence Bundle evaluation runner."""

import base64
import json
import unittest
from pathlib import Path

import yaml

from scripts.agentteams_eval import EvalCase, build_evidence_bundle, evaluate_observation, load_cases
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

    def test_bundle_preserves_only_allowlisted_tool_lifecycle_summary(self):
        bundle = build_evidence_bundle(
            {"id": "task-1", "phase": "SUCCEEDED"}, {"id": "run-1", "status": "SUCCEEDED"},
            [{"eventId": "e1", "eventType": "tool.finished", "payload": {
                "tool": "search", "sequence": 2, "ok": True, "arguments": "private input", "result": "private output"}}],
            [], [], {"status": "SUCCEEDED", "artifacts": []},
            {"status": "TRUNCATED", "observedEventCount": 1001, "eventLimit": 1000},
        )

        self.assertEqual("SUCCEEDED", bundle["taskPhase"])
        self.assertEqual("SUCCEEDED", bundle["runStatus"])
        self.assertEqual("SUCCEEDED", bundle["manifestStatus"])
        self.assertEqual({"status": "TRUNCATED", "observedEventCount": 1001, "eventLimit": 1000},
                         bundle["evidenceCompleteness"])
        self.assertEqual("TRUNCATED", bundle["eventCompleteness"])
        self.assertEqual({"tool": "search", "sequence": 2, "ok": True}, bundle["taskEvents"][0]["payload"])
        self.assertNotIn("private", json.dumps(bundle))
        self.assertEqual("INCONCLUSIVE", bundle["inputStatus"])

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

    def test_pdf_offline_fixtures_are_not_executable_suite_cases(self):
        suite = yaml.safe_load((ROOT / "eval/cases/core.yaml").read_text(encoding="utf-8"))
        fixture_ids = {fixture["id"] for fixture in suite["offlineFixtures"]}
        self.assertEqual({"multi-agent-pdf-failed-tool", "multi-agent-pdf-missing-dependency",
                          "multi-agent-pdf-corrupt-and-truncated", "multi-agent-pdf-invalid-handoff",
                          "multi-agent-pdf-multiple-pdfs", "multi-agent-pdf-partial-tool"}, fixture_ids)
        executable_ids = {case.case_id for case in load_cases(ROOT / "eval/cases/core.yaml")}
        self.assertTrue(fixture_ids.isdisjoint(executable_ids))
        for fixture in suite["offlineFixtures"]:
            observation = fixture["observation"]
            for artifact in (observation.get("result", {}).get("artifacts", []) or []):
                if "contentBase64" in artifact:
                    artifact["contentBytes"] = base64.b64decode(artifact.pop("contentBase64"))
            case = EvalCase(fixture["id"], fixture["id"], {}, fixture["assertions"], {})
            self.assertEqual(fixture["expectedStatuses"],
                             [result.status for result in evaluate_observation(case, observation)])


if __name__ == "__main__":
    unittest.main()
