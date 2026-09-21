import json
import tempfile
import unittest
from pathlib import Path

from eval.deepeval_calibration import load_calibration, recommend_thresholds, render_report
from scripts.agentteams_eval import EvalError


class DeepEvalCalibrationTest(unittest.TestCase):
    def calibration(self):
        return {
            "schemaVersion": "deepeval-calibration-v1",
            "suite": "core",
            "samples": [
                {
                    "caseId": "mcp-web-search-title",
                    "sampleId": "sample-pass",
                    "humanLabel": "pass",
                    "metricScores": {"answer_relevancy": 0.94, "tool_correctness": 0.96},
                },
                {
                    "caseId": "mcp-web-search-title",
                    "sampleId": "sample-fail",
                    "humanLabel": "fail",
                    "metricScores": {"answer_relevancy": 0.55, "tool_correctness": 0.60},
                },
            ],
        }

    def test_load_and_recommend_separable_thresholds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "calibration.json"
            path.write_text(json.dumps(self.calibration()), encoding="utf-8")
            calibration = load_calibration(path)

        recommendations = recommend_thresholds(calibration)
        self.assertEqual(2, calibration["sampleCount"])
        self.assertAlmostEqual(0.745, recommendations["answer_relevancy"]["candidateThreshold"])
        self.assertTrue(recommendations["answer_relevancy"]["separable"])
        self.assertEqual(1.0, recommendations["answer_relevancy"]["agreement"])

    def test_render_report_contains_only_scores_and_labels(self):
        report = render_report(self.calibration())
        serialized = json.dumps(report, ensure_ascii=False).lower()
        self.assertEqual("deepeval-calibration-report-v1", report["schemaVersion"])
        self.assertNotIn("actual_output", serialized)
        self.assertNotIn("downloadurl", serialized)
        self.assertNotIn("api_key", serialized)

    def test_rejects_unknown_labels_and_out_of_range_scores(self):
        calibration = self.calibration()
        calibration["samples"][0]["humanLabel"] = "maybe"
        with self.assertRaises(EvalError):
            load_calibration_from_value(calibration)

        calibration = self.calibration()
        calibration["samples"][0]["metricScores"]["answer_relevancy"] = 1.2
        with self.assertRaises(EvalError):
            load_calibration_from_value(calibration)


def load_calibration_from_value(value):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "calibration.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return load_calibration(path)


if __name__ == "__main__":
    unittest.main()
