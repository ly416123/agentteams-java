import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from eval.deepeval_adapter import (
    DeepEvalUnavailable,
    _make_tool_call,
    build_test_case_payload,
    build_deepeval_metrics,
    load_observation,
    load_rubric,
    render_export,
    summarize_metric_results,
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
                {"eventType": "tool.finished", "payload": {
                    "tool": "web_search", "output": "official MCP source context",
                }},
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
        self.assertEqual(["official MCP source context"], payload["retrieval_context"])
        self.assertEqual(7.0, payload["token_cost"])
        self.assertEqual(1.25, payload["completion_time"])
        serialized = json.dumps(payload, ensure_ascii=False).lower()
        for marker in ("downloadurl", "x-amz-signature", "bearer ", "api_key", "password"):
            self.assertNotIn(marker, serialized)

    def test_export_round_trip_is_json_and_uses_case_id(self):
        exported = render_export(self.case, self.observation)
        self.assertEqual("mcp-title", exported["caseId"])
        self.assertIn("actual_output", exported["testCase"])
        metadata = exported["judgeMetadata"]
        self.assertEqual("deepeval-rubric-v1", metadata["rubricVersion"])
        self.assertEqual("core-prompt-v1", metadata["promptVersion"])
        self.assertEqual("UNCALIBRATED", metadata["calibrationStatus"])
        self.assertRegex(metadata["rubricSha256"], r"^[0-9a-f]{64}$")
        self.assertIsNone(metadata["provider"])
        self.assertIsNone(metadata["model"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observation.json"
            path.write_text(json.dumps(self.observation), encoding="utf-8")
            self.assertEqual(self.observation, load_observation(path))

    def test_export_records_explicit_provider_model_and_rejects_secret_metadata(self):
        exported = render_export(
            self.case,
            self.observation,
            provider="openai",
            model="gpt-5.6-luna",
        )
        self.assertEqual("openai", exported["judgeMetadata"]["provider"])
        self.assertEqual("gpt-5.6-luna", exported["judgeMetadata"]["model"])
        with self.assertRaisesRegex(ValueError, "must not contain credentials"):
            render_export(self.case, self.observation, provider="api_key=secret")
        with self.assertRaisesRegex(ValueError, "must not contain credentials"):
            render_export(self.case, self.observation, model="sk-proj-secret-token")

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
        with patch("eval.deepeval_adapter.importlib.import_module", side_effect=ImportError):
            with self.assertRaises(DeepEvalUnavailable):
                build_deepeval_metrics(rubric, "mcp-web-search-title")

    def test_tool_call_type_is_compatible_with_deepeval_type_field(self):
        class FakeToolCall:
            def __init__(self, *, name, type=None):
                self.name = name
                self.type = type

        call = _make_tool_call(FakeToolCall, "web_search", "MCP")
        self.assertEqual("web_search", call.name)
        self.assertEqual("MCP", call.type)

    def test_semantic_result_preserves_sanitized_judge_reason(self):
        result = summarize_metric_results(
            [{
                "metric": "faithfulness",
                "score": 0.92,
                "threshold": 0.8,
                "reason": "Supported by sources; token=super-secret",
            }],
            ["source:call-1"],
        )
        self.assertEqual("PASSED", result["evaluationStatus"])
        self.assertEqual("Supported by sources; token=[REDACTED]", result["results"][0]["reason"])


if __name__ == "__main__":
    unittest.main()
