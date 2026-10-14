#!/usr/bin/env python3
"""Tests for extracting reader-facing PDF and source text for DeepEval."""

import unittest

import fitz

from eval.deepeval_adapter import _actual_output, _retrieval_context


def pdf_bytes(text):
    document = fitz.open()
    page = document.new_page()
    page.insert_text((48, 72), text)
    value = document.tobytes()
    document.close()
    return value


class DeepEvalPdfPayloadTest(unittest.TestCase):
    def test_actual_output_uses_extracted_final_pdf_text(self):
        observation = {"result": {"summary": "task succeeded", "artifacts": [{
            "name": "final.pdf", "contentType": "application/pdf",
            "contentBytes": pdf_bytes("PDF reader facing report"),
        }]}}

        value = _actual_output(observation)

        self.assertIn("PDF reader facing report", value)
        self.assertIn("task succeeded", value)

    def test_retrieval_context_includes_source_evidence_text_and_public_url(self):
        observation = {"sourceEvidence": {"calls": [{"sources": [{
            "title": "Official documentation", "url": "https://example.com/doc?token=secret",
            "snippet": "The documented feature is available in version 2.",
        }]}]}}

        values = _retrieval_context(observation)

        joined = "\n".join(values)
        self.assertIn("Official documentation", joined)
        self.assertIn("available in version 2", joined)
        self.assertNotIn("token=secret", joined)


if __name__ == "__main__":
    unittest.main()
