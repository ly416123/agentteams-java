import unittest
from pathlib import Path

from scripts.run_pdf_quality_deepeval import _deterministic_pdf_selected, _safe_error_message, _status


class DeepEvalRunnerTest(unittest.TestCase):
    def test_selected_judge_is_chatgpt_plan_oauth_not_deepseek(self):
        source = Path(__file__).with_name("run_pdf_quality_deepeval.py").read_text(encoding="utf-8")
        self.assertIn("ChatGptPlanDeepEvalModel", source)
        self.assertIn('"gpt-5.6-luna"', source)
        self.assertNotIn("DeepSeekModel", source)
        self.assertNotIn("DEEPSEEK_API_KEY", source)

    def test_semantic_rubric_accepts_explicitly_reported_evidence_shortage(self):
        source = Path(__file__).parents[1].joinpath("scripts/run_pdf_quality_deepeval.py").read_text(encoding="utf-8")
        self.assertIn("explicitly reports that fewer than five", source)
        self.assertIn("do not penalize the count alone", source)

    def test_error_diagnostics_are_bounded_and_credential_redacted(self):
        error = RuntimeError("ChatGPT Responses API returned HTTP 429; token=hidden-value " + "x" * 500)
        message = _safe_error_message(error)
        self.assertIn("HTTP 429", message)
        self.assertNotIn("hidden-value", message)
        self.assertLessEqual(len(message), 500)

    def test_scores_below_threshold_fail(self):
        self.assertEqual(_status([{'score': 0.79, 'threshold': 0.8}]), 'FAILED')

    def test_all_scores_at_threshold_pass(self):
        self.assertEqual(_status([{'score': 0.8, 'threshold': 0.8}]), 'PASSED')

    def test_missing_scores_are_inconclusive(self):
        self.assertEqual(_status([{'score': None, 'threshold': 0.8}]), 'INCONCLUSIVE')

    def test_deepeval_requires_current_run_to_select_pdf(self):
        self.assertTrue(_deterministic_pdf_selected({"assertions": [
            {"type": "artifact_pdf", "passed": True}]}))
        self.assertFalse(_deterministic_pdf_selected({"assertions": [
            {"type": "artifact_pdf", "passed": False}]}))
        self.assertFalse(_deterministic_pdf_selected({"assertions": []}))


if __name__ == '__main__':
    unittest.main()
