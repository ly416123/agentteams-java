import json
import tempfile
import unittest
from pathlib import Path

from eval.deepeval_adapter import (
    DeepEvalUnavailable,
    build_test_case_payload,
    build_deepeval_metrics,
    load_observation,
    load_rubric,
    render_export,
)
from scripts.agentteams_eval import EvalCase


class DeepEvalContractTest(unittest.TestCase):
    def setUp(self):
        self.case = EvalCase(
            case_id="mcp-title",
            name="MCP title",
            task={"inputJson": {"prompt": "Find the MCP title"}},
            assertions=[
                {"type": "required_tool", "name": "web_search"},
                {"type": "output_contains", "value": "Model Context Protocol"},
            ],
            limits={},
        )
        self.observation = {
            "task": {"id": "task-1", "phase": "SUCCEEDED"},
            "run": {"id": "run-1", "completionTime": 1.25, "tokenCost": 7},
            "events": [
                {"eventType": "tool.called", "payload": {"tool": "web_search"}},
                {"eventType": "tool.finished", "payload": {"tool": "web_search"}},
            ],
            "result": {
                "summary": "Model Context Protocol is a standard.",
                "artifacts": [{
                    "name": "answer.md",
                    "downloadUrl": "https://minio.invalid/result?X-Amz-Signature=fixture",
                    "content": "Model Context Protocol is a standard.",
                }],
            },
        }

    def test_payload_maps_agentteams_evidence_without_urls(self):
        payload = build_test_case_payload(self.case, self.observation)

        self.assertEqual("Find the MCP title", payload["input"])
        self.assertIn("Model Context Protocol", payload["actual_output"])
        self.assertEqual([{"name": "web_search"}], payload["tools_called"])
        self.assertEqual([{"name": "web_search"}], payload["expected_tools"])
        self.assertEqual(7.0, payload["token_cost"])
        self.assertEqual(1.25, payload["completion_time"])
        serialized = json.dumps(payload, ensure_ascii=False).lower()
        for marker in ("downloadurl", "x-amz-signature", "bearer ", "api_key", "password"):
            self.assertNotIn(marker, serialized)

    def test_export_round_trip_is_json_and_uses_case_id(self):
        exported = render_export(self.case, self.observation)
        self.assertEqual("mcp-title", exported["caseId"])
        self.assertIn("actual_output", exported["testCase"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observation.json"
            path.write_text(json.dumps(self.observation), encoding="utf-8")
            self.assertEqual(self.observation, load_observation(path))

    def test_module_has_no_deepeval_import_requirement(self):
        # The contract suite intentionally runs without the optional package.
        payload = build_test_case_payload(self.case, self.observation)
        self.assertTrue(payload["actual_output"])

    def test_core_rubric_is_versioned_and_keeps_deterministic_hard_gate(self):
        rubric = load_rubric(Path(__file__).parents[1] / "eval/rubrics/core.yaml")

        self.assertEqual("deepeval-rubric-v1", rubric["schemaVersion"])
        self.assertEqual("deterministic", rubric["hardGate"])
        case_ids = {case["id"] for case in rubric["cases"]}
        self.assertEqual(
            {"mcp-web-search-title", "no-tool-simple-answer", "artifact-integrity"},
            case_ids,
        )
        for case in rubric["cases"]:
            metric_ids = [metric["id"] for metric in case["metrics"]]
            self.assertEqual(len(metric_ids), len(set(metric_ids)))
            for metric in case["metrics"]:
                self.assertGreaterEqual(metric["threshold"], 0.0)
                self.assertLessEqual(metric["threshold"], 1.0)

    def test_metric_construction_is_optional_when_deepeval_is_absent(self):
        rubric = load_rubric(Path(__file__).parents[1] / "eval/rubrics/core.yaml")
        with self.assertRaises(DeepEvalUnavailable):
            build_deepeval_metrics(rubric, "mcp-web-search-title")


if __name__ == "__main__":
    unittest.main()
