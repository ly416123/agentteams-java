"""G12 迁移执行器契约测试（mock HTTP，不打真实 API）。"""
from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

_spec = importlib.util.spec_from_file_location(
    "apply_migration_map", Path(__file__).resolve().parent / "apply-migration-map.py")
_app = importlib.util.module_from_spec(_spec)
sys.modules["apply_migration_map"] = _app
_spec.loader.exec_module(_app)


class FakeClient(_app.Client):
    """记录请求并按脚本回放，验证幂等/查重/失败路径。"""

    def __init__(self, existing_mcp: dict | None = None, existing_skill: dict | None = None,
                 fail_first_skill: bool = False, skill_versions: dict | None = None):
        super().__init__("http://fake", "tok")
        self.calls: list[tuple] = []
        self._existing_mcp = existing_mcp or {}
        self._existing_skill = existing_skill or {}
        self._fail_first_skill = fail_first_skill
        self._skill_versions = skill_versions or {}
        self._skill_posts = 0

    def request(self, method, path, body=None, idem=None):  # noqa: D102
        self.calls.append((method, path, body, idem))
        if method == "GET" and path == "/api/v1/mcp-servers":
            return 200, [{"id": v, "name": k} for k, v in self._existing_mcp.items()]
        if method == "GET" and path == "/api/v1/skills":
            return 200, [{"id": v, "name": k} for k, v in self._existing_skill.items()]
        if path == "/api/v1/mcp-servers" and method == "POST":
            return 201, {"id": "mcp-uuid-1", "name": body["name"]}
        if "/connection-test" in path:
            return 200, {"status": "HEALTHY"}
        if path == "/api/v1/skills" and method == "POST":
            self._skill_posts += 1
            if self._fail_first_skill and self._skill_posts == 1:
                return 409, {"error": "duplicate"}
            return 201, {"id": f"skill-uuid-{self._skill_posts}", "name": body["name"]}
        if method == "GET" and path.endswith("/versions"):
            sid = path.split("/")[4]
            return 200, self._skill_versions.get(sid, [])
        if path.endswith("/versions"):
            return 201, {"id": "version-uuid-1"}
        return 500, {"error": "unexpected"}


class TestApplyMcps(unittest.TestCase):
    DRAFT = {"name": "procurement-mcp", "transport": "SSE",
             "endpoint": "http://x/sse", "credential_ref": None}

    def test_dry_run_records_action_without_http(self) -> None:
        client = FakeClient()
        rows = _app.apply_mcps(client, [self.DRAFT], probe=True, dry_run=True)
        self.assertEqual(client.calls, [])
        self.assertEqual(rows[0]["status"], "dry-run")
        self.assertIn("procurement-mcp", rows[0]["action"])

    def test_create_with_deterministic_idempotency_and_probe(self) -> None:
        client = FakeClient()
        rows = _app.apply_mcps(client, [self.DRAFT], probe=True, dry_run=False)
        self.assertEqual(rows[0]["status"], "created")
        post = [c for c in client.calls if c[1] == "/api/v1/mcp-servers" and c[0] == "POST"][0]
        self.assertEqual(post[3], "legacy-mcp-procurement-mcp")
        self.assertTrue(any("/connection-test" in c[1] for c in client.calls))

    def test_existing_name_is_skipped(self) -> None:
        client = FakeClient(existing_mcp={"procurement-mcp": "old-uuid"})
        rows = _app.apply_mcps(client, [self.DRAFT], probe=False, dry_run=False)
        self.assertEqual(rows[0]["status"], "skipped")
        self.assertEqual(rows[0]["id"], "old-uuid")
        self.assertFalse([c for c in client.calls if c[0] == "POST"])


class TestApplySkills(unittest.TestCase):
    DRAFT = {"name": "markdown-to-pdf", "display_name": "md转pdf",
             "description": "转换", "version": "1.0.0", "in_use": True}

    def test_create_then_version_with_legacy_digest(self) -> None:
        client = FakeClient()
        rows = _app.apply_skills(client, [self.DRAFT], visibility="PUBLIC", dry_run=False)
        self.assertEqual(rows[0]["status"], "created")
        version_call = [c for c in client.calls if c[1].endswith("/versions")][0]
        digest = version_call[2]["digest"]
        self.assertTrue(digest.startswith("sha256:") and len(digest) == 71, digest)

    def test_create_failure_records_failed_without_version(self) -> None:
        client = FakeClient(fail_first_skill=True)
        rows = _app.apply_skills(client, [self.DRAFT], visibility="PUBLIC", dry_run=False)
        self.assertEqual(rows[0]["status"], "failed")
        self.assertEqual(rows[0]["http"], 409)
        self.assertFalse([c for c in client.calls if c[1].endswith("/versions") and c[0] == "POST"])

    def test_existing_skill_with_missing_version_is_backfilled(self) -> None:
        client = FakeClient(existing_skill={"markdown-to-pdf": "skill-uuid-9"},
                            skill_versions={"skill-uuid-9": []})
        rows = _app.apply_skills(client, [self.DRAFT], visibility="PUBLIC", dry_run=False)
        self.assertEqual(rows[0]["status"], "created")
        self.assertTrue(any(c[0] == "POST" and c[1].endswith("/versions") for c in client.calls))

    def test_existing_skill_with_same_version_is_skipped(self) -> None:
        client = FakeClient(existing_skill={"markdown-to-pdf": "skill-uuid-9"},
                            skill_versions={"skill-uuid-9": [{"version": "1.0.0"}]})
        rows = _app.apply_skills(client, [self.DRAFT], visibility="PUBLIC", dry_run=False)
        self.assertEqual(rows[0]["status"], "skipped")
        self.assertEqual(rows[0]["reason"], "version-exists")
        self.assertFalse([c for c in client.calls if c[0] == "POST"])


if __name__ == "__main__":
    unittest.main()
