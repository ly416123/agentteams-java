import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "run_l5_subtask_delegation", ROOT / "scripts/run-l5-subtask-delegation.py")
L5 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules["run_l5_subtask_delegation"] = L5
SPEC.loader.exec_module(L5)


class Tokens:
    def get(self):
        return "test-token"


class L5SubtaskPdfArtifactTest(unittest.TestCase):
    def test_skill_mcp_child_verification_is_separate_and_opt_in(self):
        parser = L5.build_argument_parser()

        default_args = parser.parse_args(["--team-id", "team-1"])
        skill_args = parser.parse_args([
            "--team-id", "team-1", "--verify-child-skill-mcp"])

        self.assertFalse(default_args.verify_child_skill_mcp)
        self.assertTrue(skill_args.verify_child_skill_mcp)

    def test_requires_successful_pdf_mcp_event_and_downloadable_output(self):
        task_id, run_id, file_id = "task-1", "run-1", "file-1"
        responses = {
            "/process-events": (200, [{"eventType": "tool.finished",
                                        "payload": '{"tool":"create_task_pdf_artifact","ok":true}'}]),
            "/files?role=OUTPUT": (200, [{"fileId": file_id, "role": "OUTPUT",
                                          "name": "quarterly-report.pdf",
                                          "contentType": "application/pdf", "status": "AVAILABLE",
                                          "sizeBytes": 8}]),
        }

        def request_json(url, *args):
            return responses[next(key for key in responses if key in url)]

        with patch.object(L5, "request_json", side_effect=request_json), \
                patch.object(L5, "request_bytes", return_value=b"%PDF-1.7", create=True):
            result = L5.require_pdf_output("http://control-plane", Tokens(), task_id, run_id)

        self.assertEqual(result["fileId"], file_id)
        self.assertEqual(result["contentType"], "application/pdf")

    def test_rejects_missing_or_invalid_pdf_artifact(self):
        task_id, run_id = "task-1", "run-1"
        cases = [
            ([], b"%PDF-1.7"),
            ([{"fileId": "file-1", "role": "OUTPUT", "name": "report.txt",
              "contentType": "text/plain", "status": "AVAILABLE"}], b"%PDF-1.7"),
            ([{"fileId": "file-1", "role": "OUTPUT", "name": "report.pdf",
              "contentType": "application/pdf", "status": "AVAILABLE"},
             {"fileId": "file-2", "role": "OUTPUT", "name": "other.pdf",
              "contentType": "application/pdf", "status": "AVAILABLE"}], b"%PDF-1.7"),
            ([{"fileId": "file-1", "role": "OUTPUT", "name": "report.pdf",
              "contentType": "application/pdf", "status": "AVAILABLE"}], b"not a pdf"),
        ]
        for files, content in cases:
            with self.subTest(files=files, content=content):
                def request_json(url, *args):
                    if "/process-events" in url:
                        return 200, [{"eventType": "tool.finished",
                                      "payload": '{"tool":"create_task_pdf_artifact","ok":true}'}]
                    return 200, files

                with patch.object(L5, "request_json", side_effect=request_json), \
                        patch.object(L5, "request_bytes", return_value=content, create=True):
                    with self.assertRaises(RuntimeError):
                        L5.require_pdf_output("http://control-plane", Tokens(), task_id, run_id)

    def test_rejects_missing_pdf_mcp_tool_event(self):
        def request_json(url, *args):
            if "/process-events" in url:
                return 200, [{"eventType": "tool.finished", "payload": '{"tool":"web_search","ok":true}'}]
            return 200, [{"fileId": "file-1", "role": "OUTPUT", "name": "report.pdf",
                          "contentType": "application/pdf", "status": "AVAILABLE"}]

        with patch.object(L5, "request_json", side_effect=request_json), \
                patch.object(L5, "request_bytes", return_value=b"%PDF-1.7", create=True):
            with self.assertRaises(RuntimeError):
                L5.require_pdf_output("http://control-plane", Tokens(), "task-1", "run-1")


if __name__ == "__main__":
    unittest.main()
