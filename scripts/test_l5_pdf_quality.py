#!/usr/bin/env python3
"""Tests for PDF artifact quality checks used by L5 acceptance."""

import unittest
import tempfile
from pathlib import Path

import fitz

from scripts.l5_pdf_quality import inspect_pdf
from scripts.agentteams_eval import EvalCase, _persist_pdf_artifacts, evaluate_observation


def make_pdf(text="AI agents and MCP", *, fontname="helv", font_size=11, position=(48, 72)):
    document = fitz.open()
    page = document.new_page(width=595, height=842)
    page.insert_text(position, text, fontname=fontname, fontsize=font_size)
    value = document.tobytes()
    document.close()
    return value


class PdfQualityTest(unittest.TestCase):
    def test_reports_structure_font_and_layout_metrics_for_readable_pdf(self):
        report = inspect_pdf(make_pdf(), minimum_cjk_ratio=0)

        self.assertEqual(1, report["pageCount"])
        self.assertGreater(report["textCharacterCount"], 0)
        self.assertEqual(0, report["replacementCharacterCount"])
        self.assertEqual(0, report["outOfPageSpanCount"])
        self.assertEqual("PASSED", report["technicalStatus"])
        self.assertIn("fontUsage", report)
        self.assertIn("layout", report)

    def test_fails_when_pdf_has_no_extractable_text(self):
        document = fitz.open()
        document.new_page()
        data = document.tobytes()
        document.close()

        report = inspect_pdf(data)

        self.assertEqual("FAILED", report["technicalStatus"])
        self.assertIn("no_extractable_text", report["failureCodes"])

    def test_fails_when_pdf_contains_a_blank_page(self):
        document = fitz.open()
        page = document.new_page(width=595, height=842)
        page.insert_text((48, 72), "Readable content", fontname="helv", fontsize=11)
        document.new_page(width=595, height=842)
        data = document.tobytes()
        document.close()

        report = inspect_pdf(data, minimum_cjk_ratio=0)

        self.assertEqual("FAILED", report["technicalStatus"])
        self.assertEqual(1, report["blankPageCount"])
        self.assertIn("blank_page", report["failureCodes"])

    def test_reports_unparseable_pdf_as_a_quality_failure(self):
        report = inspect_pdf(b"not a PDF")

        self.assertEqual("FAILED", report["technicalStatus"])
        self.assertIn("unparseable_pdf", report["failureCodes"])

    def test_fails_when_text_is_clipped_outside_page_bounds(self):
        report = inspect_pdf(make_pdf(position=(580, 72)))

        self.assertEqual("FAILED", report["technicalStatus"])
        self.assertGreater(report["outOfPageSpanCount"], 0)
        self.assertIn("text_outside_page", report["failureCodes"])

    def test_flags_unacceptable_body_font_sizes(self):
        report = inspect_pdf(make_pdf(font_size=5))

        self.assertEqual("FAILED", report["technicalStatus"])
        self.assertIn("font_size_out_of_range", report["failureCodes"])

    def test_reports_chinese_text_ratio_for_language_gate(self):
        report = inspect_pdf(make_pdf("中文成果物质量验收", fontname="china-s"))

        self.assertGreater(report["cjkCharacterCount"], 0)
        self.assertGreater(report["cjkCharacterRatio"], 0.5)

    def test_pdf_quality_assertion_returns_metrics_without_pdf_bytes(self):
        case = EvalCase("pdf-quality", "PDF quality", {}, [{
            "type": "artifact_pdf_quality", "minimumCjkRatio": 0,
        }], {})
        artifact = {"name": "report.pdf", "contentType": "application/pdf",
                    "contentBytes": make_pdf()}

        result = evaluate_observation(case, {"result": {"artifacts": [artifact]}})[0]

        self.assertEqual("PASSED", result.status)
        self.assertEqual(1, result.actual["pageCount"])
        self.assertTrue(result.actual["visualReviewRequired"])
        self.assertNotIn("contentBytes", str(result.as_dict()))

    def test_pdf_quality_assertion_is_inconclusive_without_downloaded_bytes(self):
        case = EvalCase("pdf-quality", "PDF quality", {}, [
            {"type": "artifact_pdf_quality"}], {})

        result = evaluate_observation(case, {"result": {"artifacts": [{
            "name": "report.pdf", "contentType": "application/pdf",
        }]}})[0]

        self.assertEqual("INCONCLUSIVE", result.status)

    def test_persists_only_named_pdf_artifacts_for_visual_review(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = {"name": "final.pdf", "contentType": "application/pdf",
                        "contentBytes": make_pdf()}

            saved = _persist_pdf_artifacts("case-1", {"result": {"artifacts": [artifact]}},
                                           Path(directory))

            self.assertEqual(1, len(saved))
            target = Path(directory) / "case-1" / "final.pdf"
            self.assertEqual(artifact["contentBytes"], target.read_bytes())
            self.assertEqual("final.pdf", saved[0]["name"])


if __name__ == "__main__":
    unittest.main()
