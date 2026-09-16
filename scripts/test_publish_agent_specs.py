"""publish-agent-specs.py 的契约测试。

覆盖：
- skill 引用解析（name@version / name）
- Catalog 侦察快照与 published_version 匹配
- 预检：引用齐全 -> publishable；缺 MCP / skill 无 PUBLISHED / model 缺失 -> blocked
- 发布流：DRAFT+包 COMPLETED 直接 publish skill；包 NONE 先传占位包；blocked 不触网；已 PUBLISHED 跳过
- dry-run 全程无写操作

运行：python3 -m unittest scripts.test_publish_agent_specs -v
"""

from __future__ import annotations

import importlib.util
import gzip
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "_pas", HERE / "publish-agent-specs.py")
_pas = importlib.util.module_from_spec(_spec)
sys.modules["_pas"] = _pas
_spec.loader.exec_module(_pas)
# 上传通道复用 upload-skill-package.py；其内部引用 _usp 模块全局的 put_presigned，
# 测试需 patch _pas._usp.put_presigned 才能拦截 presigned PUT。
_usp = _pas._usp


def _skill_row(name: str, sid: str) -> dict:
    return {"id": sid, "name": name}


def _version_row(sid: str, version: str, *, lifecycle: str = "DRAFT",
                 pkg: str = "NONE") -> dict:
    return {"id": f"vid-{sid}-{version}", "skillId": sid, "version": version,
            "lifecycle": lifecycle, "packageUploadStatus": pkg,
            "createdAt": f"2026-09-01T00:00:0{version}.00Z"}


class FakeClient:
    """最小 control-plane 假客户端：记录调用并返回构造的数据。"""

    def __init__(self):
        self.calls: list[tuple] = []
        self.mcps: list[dict] = []            # {"id","name","enabled"}
        self.skills: list[dict] = []          # {"id","name"}
        self.versions: dict[str, list] = {}   # skill_id -> [version rows]
        self.providers: list[dict] = []       # {"id","name","enabled"}
        self.models: dict[str, list] = {}     # provider_id -> [{"modelId","name"}]
        self.specs: list[dict] = []           # agent-spec rows
        self.published_versions: list[tuple] = []
        self.published_specs: list[str] = []

    # -- helpers ---------------------------------------------------------
    def add_spec(self, name: str, *, lifecycle: str = "DRAFT", provider: str = "default",
                 model: str = "m1", mcps: list[str] | None = None,
                 skills: list[str] | None = None) -> None:
        spec = {"modelRef": {"provider": provider, "model": model},
                "skillRefs": list(skills or []), "mcpRefs": list(mcps or [])}
        self.specs.append({"id": f"spec-{name}", "name": name,
                           "lifecycleStatus": lifecycle,
                           "spec": json.dumps(spec)})

    def add_skill(self, name: str, sid: str, versions: list[dict]) -> None:
        self.skills.append(_skill_row(name, sid))
        self.versions[sid] = versions

    # -- client API ------------------------------------------------------
    def request(self, method, path, body=None, idem=None):
        self.calls.append((method, path, body, idem))
        base, _, query = path.partition("?")  # 带 projectId 的请求与裸路径同路由
        if method == "GET" and base == "/api/v1/mcp-servers":
            return 200, self.mcps
        if method == "GET" and base == "/api/v1/skills":
            return 200, self.skills
        if method == "GET" and base.startswith("/api/v1/skills/") \
                and base.endswith("/versions"):
            sid = base[len("/api/v1/skills/"):-len("/versions")]
            return 200, self.versions.get(sid, [])
        if method == "POST" and "/versions/" in base and base.endswith("/review"):
            return 200, {"reviewStatus": "APPROVED"}
        if method == "POST" and "/versions/" in base and base.endswith("/publish"):
            sid = base[len("/api/v1/skills/"):-len("/publish")].split("/versions/")[0]
            self.published_versions.append((sid, body))
            return 200, {"id": "vid", "lifecycle": "PUBLISHED"}
        if method == "POST" and base.endswith("/package/upload"):
            return 200, {"uploadUrl": "http://presigned.invalid/put"}
        if method == "POST" and base.endswith("/package/complete"):
            return 200, {"packageUploadStatus": "COMPLETED"}
        if method == "GET" and base == "/api/v1/model-providers":
            return 200, self.providers
        if method == "GET" and base.startswith("/api/v1/model-providers/") \
                and base.endswith("/models"):
            pid = base[len("/api/v1/model-providers/"):-len("/models")]
            return 200, self.models.get(pid, [])
        if method == "GET" and base == "/api/v1/agent-specs":
            return 200, self.specs
        if method == "POST" and base.startswith("/api/v1/agent-specs/") \
                and base.endswith("/publish"):
            sid = base[len("/api/v1/agent-specs/"):-len("/publish")]
            self.published_specs.append(sid)
            return 200, {"id": sid, "lifecycleStatus": "PUBLISHED"}
        return 404, {"error": f"no route: {method} {path}"}


def _catalog(client: FakeClient) -> "_pas.Catalog":
    return _pas.Catalog.fetch(client)


class TestParseSkillRef(unittest.TestCase):

    def test_name_with_version(self):
        self.assertEqual(_pas.parse_skill_ref("corp_skill_a@1.0"), ("corp_skill_a", "1.0"))

    def test_bare_name(self):
        self.assertEqual(_pas.parse_skill_ref("corp_skill_a"), ("corp_skill_a", None))

    def test_blank(self):
        self.assertEqual(_pas.parse_skill_ref("  "), ("", None))


class TestCatalog(unittest.TestCase):

    def test_fetch_assembles_all_resources(self):
        c = FakeClient()
        c.mcps = [{"id": "mcp-1", "name": "procurement-mcp", "enabled": True}]
        c.add_skill("corp_skill_a", "sk-1", [_version_row("sk-1", "0.0.1")])
        c.providers = [{"id": "prov-1", "name": "default", "enabled": True}]
        c.models = {"prov-1": [{"modelId": "m1", "name": "m1"}]}
        cat = _catalog(c)
        self.assertEqual(cat.mcps["procurement-mcp"]["id"], "mcp-1")
        self.assertIn("corp_skill_a", cat.skills)
        self.assertEqual(cat.providers["default"]["id"], "prov-1")
        self.assertIn("m1", cat.provider_models["default"])

    def test_published_version_prefers_requested(self):
        c = FakeClient()
        c.add_skill("corp_skill_a", "sk-1", [
            _version_row("sk-1", "0.0.1", lifecycle="PUBLISHED"),
            _version_row("sk-1", "0.0.2", lifecycle="DRAFT"),
        ])
        cat = _catalog(c)
        row = cat.published_version("corp_skill_a")
        self.assertEqual(row["version"], "0.0.1")
        self.assertEqual(cat.published_version("corp_skill_a", "0.0.1")["id"],
                         "vid-sk-1-0.0.1")
        self.assertIsNone(cat.published_version("corp_skill_a", "9.9.9"))

    def test_published_version_latest_when_multiple(self):
        c = FakeClient()
        c.add_skill("corp_skill_a", "sk-1", [
            _version_row("sk-1", "0.0.1", lifecycle="PUBLISHED"),
            _version_row("sk-1", "0.0.2", lifecycle="PUBLISHED"),
        ])
        cat = _catalog(c)
        self.assertEqual(cat.published_version("corp_skill_a")["version"], "0.0.2")


class TestCheckSpec(unittest.TestCase):

    def _catalog_all_ok(self) -> tuple[FakeClient, "_pas.Catalog"]:
        c = FakeClient()
        c.mcps = [{"id": "mcp-1", "name": "procurement-mcp", "enabled": True}]
        c.add_skill("corp_skill_a", "sk-1",
                    [_version_row("sk-1", "0.0.1", lifecycle="PUBLISHED")])
        c.providers = [{"id": "prov-1", "name": "default", "enabled": True}]
        c.models = {"prov-1": [{"modelId": "m1", "name": "m1"}]}
        return c, _catalog(c)

    def test_all_references_present(self):
        c, cat = self._catalog_all_ok()
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": ["corp_skill_a"], "mcpRefs": ["procurement-mcp"]})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "publishable")

    def test_missing_mcp(self):
        c, cat = self._catalog_all_ok()
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": [], "mcpRefs": ["dev-mcp-x"]})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "blocked")
        self.assertIn("dev-mcp-x", check["missing_mcp"])

    def test_disabled_mcp_blocks(self):
        c, cat = self._catalog_all_ok()
        c.mcps[0]["enabled"] = False
        cat = _catalog(c)
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": [], "mcpRefs": ["procurement-mcp"]})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "blocked")
        self.assertIn("procurement-mcp", check["missing_mcp"])

    def test_skill_without_published_version_is_publishable(self):
        """skill 存在但版本均 DRAFT：不阻塞，发布链先补 publish 版本（deps 指向 DRAFT）。"""
        c, cat = self._catalog_all_ok()
        c.versions["sk-1"][0]["lifecycle"] = "DRAFT"
        cat = _catalog(c)
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": ["corp_skill_a"], "mcpRefs": []})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "publishable")
        self.assertEqual(check["skill_versions"][0]["lifecycle"], "DRAFT")

    def test_missing_skill_record_blocks(self):
        """skill 记录不存在才阻塞。"""
        c, cat = self._catalog_all_ok()
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": ["ghost_skill"], "mcpRefs": []})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "blocked")
        self.assertIn("ghost_skill", check["missing_skill"])

    def test_skill_ref_with_unmatched_version(self):
        c, cat = self._catalog_all_ok()
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "default", "model": "m1"},
             "skillRefs": ["corp_skill_a@9.9.9"], "mcpRefs": []})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "blocked")

    def test_missing_model(self):
        c, cat = self._catalog_all_ok()
        row = {"name": "bidding-worker", "spec": json.dumps(
            {"modelRef": {"provider": "ghost", "model": "m1"},
             "skillRefs": [], "mcpRefs": []})}
        check = _pas.check_spec(row, cat)
        self.assertEqual(check["status"], "blocked")
        self.assertTrue(check["model_issue"])

    def test_spec_as_json_string_and_dict_both_supported(self):
        c, cat = self._catalog_all_ok()
        as_dict = {"name": "w", "spec": {"modelRef": {"provider": "default", "model": "m1"},
                                         "skillRefs": [], "mcpRefs": []}}
        as_str = {"name": "w", "spec": json.dumps(as_dict["spec"])}
        self.assertEqual(_pas.check_spec(as_dict, cat)["status"], "publishable")
        self.assertEqual(_pas.check_spec(as_str, cat)["status"], "publishable")

    def test_preflight_reports_summary(self):
        c, _ = self._catalog_all_ok()
        c.add_spec("worker-ok", mcps=["procurement-mcp"], skills=["corp_skill_a"])
        c.add_spec("worker-bad", mcps=["dev-mcp-x"])
        checks = _pas.preflight(c)
        summary = _pas.summarize(checks)
        self.assertEqual(summary["publishable"], 1)
        self.assertEqual(summary["blocked"], 1)


class TestPublishFlow(unittest.TestCase):

    def _env(self) -> FakeClient:
        c = FakeClient()
        c.mcps = [{"id": "mcp-1", "name": "procurement-mcp", "enabled": True}]
        c.providers = [{"id": "prov-1", "name": "default", "enabled": True},
                       {"id": "prov-2", "name": "ghost", "enabled": False}]
        c.models = {"prov-1": [{"modelId": "m1", "name": "m1"}]}
        return c

    def test_draft_with_completed_package_publishes_skill_then_spec(self):
        c = self._env()
        c.add_skill("corp_skill_a", "sk-1",
                    [_version_row("sk-1", "0.0.1", lifecycle="DRAFT", pkg="COMPLETED")])
        c.add_spec("worker-ok", mcps=["procurement-mcp"], skills=["corp_skill_a"])
        cat = _catalog(c)
        logs: list[str] = []
        results = _pas.publish_specs(c, _pas.preflight(c), cat, dry_run=False, logs=logs)
        self.assertEqual(results[0]["status"], "published")
        self.assertIn(("sk-1", None), [(r[0], r[1]) for r in c.published_versions])
        self.assertIn("spec-worker-ok", c.published_specs)
        # 顺序：review -> skill publish -> spec publish；spec publish 带 projectId 参数
        posts = [(m, p) for m, p, *_ in c.calls if m == "POST"]
        self.assertTrue(any(p.endswith("/review") for _, p in posts))
        spec_post = next(p for _, p in posts if "agent-specs" in p)
        self.assertIn("?projectId=project-a", spec_post)
        kinds = ["skill-publish" if p.partition("?")[0].endswith("/publish") and "/skills/" in p
                 else "spec-publish" for m, p, *_ in c.calls
                 if m == "POST" and p.partition("?")[0].endswith("/publish")]
        self.assertEqual(kinds.index("skill-publish") < kinds.index("spec-publish"), True)

    def test_draft_without_package_uploads_placeholder_first(self):
        c = self._env()
        c.add_skill("corp_skill_a", "sk-1",
                    [_version_row("sk-1", "0.0.1", lifecycle="DRAFT", pkg="NONE")])
        c.add_spec("worker-ok", mcps=["procurement-mcp"], skills=["corp_skill_a"])
        cat = _catalog(c)

        def fake_put(url, data):
            return 200

        with patch.object(_usp, "put_presigned", side_effect=fake_put), \
                patch.object(_pas, "placeholder_bytes", return_value=b"pkg"):
            results = _pas.publish_specs(c, _pas.preflight(c), cat, dry_run=False,
                                         logs=[])
        self.assertEqual(results[0]["status"], "published")
        steps = [p for m, p, *_ in c.calls if "package/" in p]
        self.assertEqual(len(steps), 2)  # upload + complete

    def test_blocked_spec_never_touches_network(self):
        c = self._env()
        c.add_spec("worker-bad", mcps=["dev-mcp-x"])
        cat = _catalog(c)
        results = _pas.publish_specs(c, _pas.preflight(c), cat, dry_run=False, logs=[])
        self.assertEqual(results[0]["status"], "blocked")
        self.assertFalse(any(m == "POST" for m, *_ in c.calls))

    def test_already_published_spec_skipped(self):
        c = self._env()
        c.add_spec("worker-ok", lifecycle="PUBLISHED")
        cat = _catalog(c)
        results = _pas.publish_specs(c, _pas.preflight(c), cat, dry_run=False, logs=[])
        self.assertEqual(results[0]["status"], "already-published")
        self.assertFalse(any(m == "POST" for m, *_ in c.calls))

    def test_dry_run_has_no_side_effect(self):
        c = self._env()
        c.add_skill("corp_skill_a", "sk-1",
                    [_version_row("sk-1", "0.0.1", lifecycle="DRAFT", pkg="COMPLETED")])
        c.add_spec("worker-ok", mcps=["procurement-mcp"], skills=["corp_skill_a"])
        cat = _catalog(c)
        results = _pas.publish_specs(c, _pas.preflight(c), cat, dry_run=True, logs=[])
        self.assertEqual(results[0]["status"], "planned")
        self.assertFalse(any(m == "POST" for m, *_ in c.calls))

    def test_placeholder_bytes_stable_and_contains_skill_md(self):
        data = _pas.placeholder_bytes("corp_skill_a")
        self.assertIsInstance(data, bytes)
        self.assertEqual(data, _pas.placeholder_bytes("corp_skill_a"))
        import tarfile as _tarfile
        with _tarfile.open(fileobj=io.BytesIO(gzip.decompress(data))) as tar:
            names = tar.getnames()
            self.assertIn("SKILL.md", names)
            body = tar.extractfile("SKILL.md").read().decode("utf-8")
            self.assertIn("corp_skill_a", body)


if __name__ == "__main__":
    unittest.main()
