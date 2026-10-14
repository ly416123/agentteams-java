import json
import unittest
from unittest.mock import patch

import l5_skill_mcp_policy_acceptance as policy


class SkillMcpPolicyAcceptanceTest(unittest.TestCase):
    def test_temporary_admin_update_changes_only_role(self):
        snapshot = {"role": "VIEWER", "status": "ACTIVE",
                    "updatedAt": "2026-09-30T10:20:30+00:00"}
        with patch.object(policy, "database_password", return_value="db-secret"), \
             patch.object(policy, "psql", side_effect=[
                 json.dumps(snapshot), ""]
             ) as execute_sql:
            result = policy.ensure_project_admin("agentteams", "subject-1", "tenant-a", "project-a")

        self.assertEqual(result, snapshot)
        update_sql = execute_sql.call_args_list[1].args[2]
        self.assertIn("SET role='ADMIN' WHERE", update_sql)
        self.assertNotIn("status=", update_sql)
        self.assertNotIn("updated_at=", update_sql)

    def test_role_restore_restores_status_and_original_updated_at(self):
        snapshot = {"role": "VIEWER", "status": "SUSPENDED",
                    "updatedAt": "2026-09-30T10:20:30+00:00"}
        with patch.object(policy, "database_password", return_value="db-secret"), \
             patch.object(policy, "psql", return_value="") as execute_sql:
            policy.restore_project_role("agentteams", "subject-1", "tenant-a", "project-a", snapshot)

        update_sql = execute_sql.call_args.args[2]
        self.assertIn("SET role='VIEWER'", update_sql)
        self.assertIn("status='SUSPENDED'", update_sql)
        self.assertIn("updated_at='2026-09-30T10:20:30+00:00'::timestamptz", update_sql)

    def test_records_skill_tool_call_with_required_mcp_tool_filtered(self):
        task = {"phase": "FAILED"}
        run = {"status": "FAILED", "summary": "REQUIRED_TOOL_NOT_CALLED: web_search"}
        events = [
            {"eventType": "tool.finished",
             "payload": '{"tool":"load_skill_through_path","ok":true}'},
            {"eventType": "tool.finished",
             "payload": '{"tool":"wait_async_results","ok":true}'},
        ]

        result = policy.verify_denial(task, run, events)

        self.assertTrue(result["denialVerified"])
        self.assertTrue(result["skillLoadToolCallObserved"])
        self.assertNotIn("skillLoaded", result)
        self.assertEqual(result["blockedTool"], "web_search")

    def test_rejects_any_web_search_tool_event(self):
        task = {"phase": "FAILED"}
        run = {"status": "FAILED", "summary": "REQUIRED_TOOL_NOT_CALLED: web_search"}
        events = [
            {"eventType": "tool.finished",
             "payload": {"tool": "load_skill_through_path", "ok": True}},
            {"eventType": "tool.called", "payload": {"tool": "web_search"}},
        ]

        with self.assertRaisesRegex(RuntimeError, "web_search.*invoked"):
            policy.verify_denial(task, run, events)

    def test_rejects_unrelated_run_failure(self):
        task = {"phase": "FAILED"}
        run = {"status": "FAILED", "summary": "MODEL_PROVIDER_UNAVAILABLE"}
        events = [{"eventType": "tool.finished",
                   "payload": {"tool": "load_skill_through_path", "ok": True}}]

        with self.assertRaisesRegex(RuntimeError, "REQUIRED_TOOL_NOT_CALLED"):
            policy.verify_denial(task, run, events)

    def test_records_skill_tool_call_that_reports_error(self):
        task = {"phase": "FAILED"}
        run = {"status": "FAILED", "summary": "REQUIRED_TOOL_NOT_CALLED: web_search"}
        events = [{"eventType": "tool.finished",
                   "payload": {"tool": "load_skill_through_path", "ok": False}}]

        result = policy.verify_denial(task, run, events)
        self.assertTrue(result["skillLoadToolCallObserved"])
        self.assertNotIn("skillLoaded", result)

    def test_rejects_probe_without_skill_tool_call(self):
        task = {"phase": "FAILED"}
        run = {"status": "FAILED", "summary": "REQUIRED_TOOL_NOT_CALLED: web_search"}
        events = [{"eventType": "tool.finished",
                   "payload": {"tool": "wait_async_results", "ok": True}}]

        with self.assertRaisesRegex(RuntimeError, "Skill load tool call"):
            policy.verify_denial(task, run, events)


if __name__ == "__main__":
    unittest.main()
