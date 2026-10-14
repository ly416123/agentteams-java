import json
import tempfile
import unittest
import uuid
from pathlib import Path

from scripts.import_artifact_quality_history import build_history_events


class ArtifactQualityHistoryImportTest(unittest.TestCase):
    def test_extracts_only_supported_quality_facts_and_never_copies_raw_content(self):
        task_id = str(uuid.uuid4())
        run_id = str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = root / "reports"
            plans = root / "plans"
            reports.mkdir()
            plans.mkdir()
            report = reports / "evaluation.json"
            report.write_text(json.dumps({
                "schemaVersion": "evaluation-report-v1",
                "startedAt": "2026-10-13T01:00:00Z",
                "finishedAt": "2026-10-13T01:02:00Z",
                "targetScope": {"tenant": "tenant-a", "project": "project-a"},
                "cases": [{
                    "caseId": "pdf-case",
                    "taskId": task_id,
                    "runId": run_id,
                    "passed": False,
                    "assertions": [
                        {"type": "artifact_pdf", "status": "FAILED", "message": "PDF artifact is missing",
                         "actual": {"prompt": "private source body", "api_key": "hidden"}},
                        {"type": "phase", "status": "PASSED", "message": "phase succeeded"},
                    ],
                    "pdfArtifacts": [{"name": "report.pdf", "sha256": "a" * 64,
                                      "sourceTaskId": task_id, "sourceFileId": str(uuid.uuid4())}],
                }],
            }), encoding="utf-8")

            events, stats = build_history_events(reports, plans)

        self.assertEqual(2, len(events))  # quality check and its derived final status
        check = events[0]
        self.assertEqual(task_id, check["taskId"])
        self.assertEqual(run_id, check["runId"])
        self.assertEqual("HISTORICAL", check["sourceType"])
        self.assertEqual("HIGH", check["confidence"])
        self.assertEqual("report.pdf", check["artifactName"])
        self.assertNotIn("sourceFileId", check)  # source artifact is not asserted to be this run's file
        encoded = json.dumps(events)
        self.assertNotIn("private source body", encoded)
        self.assertNotIn("hidden", encoded)
        self.assertEqual(1, stats["reports"])

    def test_keeps_missing_task_and_run_ids_empty_instead_of_fabricating_them(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "reports").mkdir()
            (root / "plans").mkdir()
            (root / "reports" / "weak.json").write_text(json.dumps({
                "finishedAt": "2026-10-13T01:02:00Z",
                "targetScope": {"tenant": "tenant-a", "project": "project-a"},
                "cases": [{"caseId": "weak-case", "assertions": [
                    {"type": "artifact_pdf", "passed": False}]}],
            }), encoding="utf-8")

            events, _ = build_history_events(root / "reports", root / "plans")

        self.assertIsNone(events[0]["taskId"])
        self.assertIsNone(events[0]["runId"])
        self.assertEqual("LOW", events[0]["confidence"])

    def test_skips_broken_reports_and_records_only_explicit_completed_plan_actions(self):
        task_id = str(uuid.uuid4())
        run_id = str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = root / "reports"
            plans = root / "plans"
            reports.mkdir()
            plans.mkdir()
            (reports / "bad.json").write_text("{", encoding="utf-8")
            (reports / "good.json").write_text(json.dumps({
                "finishedAt": "2026-10-13T01:02:00Z",
                "targetScope": {"tenant": "tenant-a", "project": "project-a"},
                "cases": [{"caseId": "pdf", "taskId": task_id, "runId": run_id,
                    "assertions": [{"type": "artifact_pdf", "status": "INCONCLUSIVE"}]}],
            }), encoding="utf-8")
            (plans / "repair.md").write_text(
                f"- [x] 修复 PDF 门禁 taskId: {task_id} runId: {run_id}\n"
                "- [ ] 修复未执行的候选动作\n",
                encoding="utf-8")

            events, stats = build_history_events(reports, plans)

        actions = [event for event in events if event["eventType"] == "OPTIMIZATION_ACTION"]
        self.assertEqual(1, len(actions))
        self.assertEqual(task_id, actions[0]["taskId"])
        self.assertEqual(run_id, actions[0]["runId"])
        self.assertEqual("UNKNOWN", actions[0]["actionSource"])
        self.assertEqual("MEDIUM", actions[0]["confidence"])
        self.assertEqual(1, stats["skipped"])


if __name__ == "__main__":
    unittest.main()
