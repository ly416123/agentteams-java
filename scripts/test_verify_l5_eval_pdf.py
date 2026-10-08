import hashlib
import io
import unittest

from pypdf import PdfWriter

from scripts.verify_l5_eval_pdf import EvalError, verify_pdf


class VerifyL5EvalPdfTest(unittest.TestCase):
    def setUp(self):
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        stream = io.BytesIO()
        writer.write(stream)
        self.pdf = stream.getvalue()
        self.artifact = {
            "name": "report.pdf", "contentType": "application/pdf",
            "sizeBytes": len(self.pdf), "sha256": hashlib.sha256(self.pdf).hexdigest(),
            "storageRef": "tasks/11111111-1111-1111-1111-111111111111/attempts/"
                          "22222222-2222-2222-2222-222222222222/artifacts/report.pdf",
            "downloadUrl": "http://192.168.122.55:30090/bucket/report.pdf?X-Amz-Signature=secret",
        }

    def test_verifies_downloaded_pdf_without_returning_signed_url(self):
        result = verify_pdf(self.artifact, "192.168.122.55", lambda url, limit: self.pdf)

        self.assertEqual(1, result["pageCount"])
        self.assertEqual(self.artifact["sha256"], result["sha256"])
        self.assertNotIn("downloadUrl", result)
        self.assertNotIn("secret", str(result))

    def test_rejects_wrong_host_and_corrupt_download_without_leaking_url(self):
        bad_host = {**self.artifact, "downloadUrl":
                    "http://outside.example:30090/report.pdf?X-Amz-Signature=secret"}
        with self.assertRaises(EvalError) as raised:
            verify_pdf(bad_host, "192.168.122.55", lambda url, limit: self.pdf)
        self.assertNotIn("secret", str(raised.exception))

        with self.assertRaises(EvalError) as raised:
            verify_pdf(self.artifact, "192.168.122.55", lambda url, limit: b"not-a-pdf")
        self.assertNotIn("secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
