# scripts/test_inventory_legacy_platform.py
"""G12 盘点工具契约测试（合成 fixture，不打真库）。"""

from __future__ import annotations

import importlib.util
import json
import sys
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 连字符文件名 → 下划线模块别名（仓库惯例，参见 test_agentteams_task_mcp.py）：
# 注册后，下方 from ... import 的模块名与脚本功能保持一致。
_SPEC = importlib.util.spec_from_file_location(
    "inventory_legacy_platform",
    Path(__file__).resolve().parent / "inventory-legacy-platform.py",
)
_MODULE = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
sys.modules["inventory_legacy_platform"] = _MODULE
_SPEC.loader.exec_module(_MODULE)

from inventory_legacy_platform import fingerprint, mask_row
from inventory_legacy_platform import collect_config
from inventory_legacy_platform import EXIT_DEGRADED, EXIT_OK, probe_tables, degrade_status


class TestGitignore(unittest.TestCase):
    def test_output_dir_is_ignored(self) -> None:
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("output/", gitignore)


# 合成 fixture（脱敏风格，模拟 DDL 字段；后续批次任务复用）
FIXTURE_AT_WORKER = [
    {"name": "worker-alpha", "agent_type": "Qwenpaw", "deploy_type": "Managed",
     "model_provider": "deepseek", "model_name": "deepseek-chat", "status": "RUNNING",
     "soul": "SOUL MARKER alpha", "agents": "AGENTS MARKER alpha",
     "mcp_servers_json": '[{"name":"mcp-a","transport":"SSE","url":"http://x"}]',
     "skills_json": '[{"name":"skill-a","label":"A","version":"1"}]',
     "groups_json": '[{"name":"team-x","role":"leader","type":"worker"}]'},
    {"name": "worker-beta", "agent_type": "Qwenpaw", "deploy_type": "SelfHosted",
     "model_provider": "deepseek", "model_name": "deepseek-chat", "status": "RUNNING",
     "soul": "SOUL MARKER beta", "agents": "AGENTS MARKER beta",
     "mcp_servers_json": None, "skills_json": None, "groups_json": None},
]
FIXTURE_DE_WORKER = [
    {"worker_id": "w-1", "worker_name": "worker-alpha", "status": "RUN",
     "model_name": "deepseek-chat", "model_mfr_name": "deepseek",
     "endpoint": "http://e-1", "api_key": "ak-live-999"},
]


class TestFingerprint(unittest.TestCase):
    def test_fingerprint_is_stable_8_hex(self) -> None:
        self.assertEqual(fingerprint("secret-value"), fingerprint("secret-value"))
        self.assertEqual(len(fingerprint("secret-value")), 8)
        self.assertNotIn("secret-value", fingerprint("secret-value"))


class TestMaskRow(unittest.TestCase):
    def test_sensitive_fields_become_fingerprints(self) -> None:
        masked = mask_row("de_worker", dict(FIXTURE_DE_WORKER[0]))
        self.assertEqual(masked["worker_name"], "worker-alpha")  # 非敏感保留
        self.assertEqual(masked["api_key"], fingerprint("ak-live-999"))  # 敏感指纹化

    def test_endpoint_api_key_masked(self) -> None:
        row = {"endpoint_id": "ep-1", "api_key": "epk-1", "domain": "d.example"}
        masked = mask_row("at_service_endpoint", row)
        self.assertEqual(masked["api_key"], fingerprint("epk-1"))
        self.assertEqual(masked["domain"], "d.example")

    def test_mcp_config_json_masked(self) -> None:
        row = {"mcp_id": "m-1", "mcp_server_config": '{"token":"t"}', "auth_config": '{"u":"a"}'}
        masked = mask_row("at_mcp_server", row)
        self.assertEqual(masked["mcp_server_config"], fingerprint('{"token":"t"}'))
        self.assertEqual(masked["auth_config"], fingerprint('{"u":"a"}'))


CORP_YAML = textwrap.dedent("""\
    agentteams:
      endpoint: agentteams.cn-beijing.aliyuncs.com
      instance-id: at-cn-test0001
      worker-url: http://gw.example.internal
      api-key: "atk-secret"
      gateway:
        impl: remote
      task:
        enabled: true
        homeserver-url: http://ws-test.agentteams.aliyuncs.com
        signin-base-url: https://signin.example
        default-leader-user-id: "@leader:at-cn-test0001"
        sync-timeout-minutes: 120
    agentcore:
      enabled: true
      workspace-id: ws-test0001
      leader-agent-id: agent-test0001
      api-key: "FwcSECRET"
      endpoint-template: http://{agentId}.{workspaceId}.agentteams.aliyuncs.com
      runtime:
        compute-class: "STANDARD"
        session-policy-type: "DISABLED"
""")


class TestCollectConfig(unittest.TestCase):
    def test_config_domain_extraction(self) -> None:
        cfg = collect_config(CORP_YAML)
        self.assertEqual(cfg["domain"], "platform-config")
        self.assertEqual(cfg["status"], "ok")
        self.assertTrue(cfg["rows"]["agentcore"]["enabled"])
        self.assertEqual(cfg["rows"]["agentcore"]["workspace_id"], "ws-test0001")
        self.assertEqual(cfg["rows"]["agentcore"]["runtime"]["compute_class"], "STANDARD")
        self.assertEqual(cfg["rows"]["agentteams_legacy"]["instance_id"], "at-cn-test0001")
        self.assertEqual(cfg["rows"]["agentteams_legacy"]["gateway_impl"], "remote")
        self.assertEqual(cfg["rows"]["agentteams_legacy"]["task"]["homeserver_url"],
                         "http://ws-test.agentteams.aliyuncs.com")

    def test_credentials_fingerprinted_not_plaintext(self) -> None:
        cfg = collect_config(CORP_YAML)
        self.assertEqual(cfg["rows"]["agentcore"]["api_key_fingerprint"],
                         fingerprint("FwcSECRET"))
        self.assertNotIn("FwcSECRET", json.dumps(cfg))
        self.assertNotIn("atk-secret", json.dumps(cfg))
        self.assertIn("agentcore_api_key", cfg["rows"]["credential_sources"])


class FakeCursor:
    """INFORMATION_SCHEMA 探测假游标：table_rows = [存在表名]。"""

    def __init__(self, existing: list[str]) -> None:
        self._existing = set(existing)

    def execute(self, sql: str, params=None) -> None:
        self._last = (sql, params)

    def fetchall(self):
        sql, params = self._last
        wanted = list(params)
        return [(t,) for t in wanted if t in self._existing]

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        return None


class FakeConn:
    def __init__(self, existing: list[str]) -> None:
        self._cursor = FakeCursor(existing)

    def cursor(self):
        return self._cursor


ALL_TABLES = ["at_team", "at_worker", "at_mcp_server", "at_service_endpoint",
              "de_worker", "de_team", "de_team_worker_rel", "de_team_crew_rel",
              "de_user_mapp", "de_task", "de_task_rslt", "de_chat_convo", "de_chat_msg"]


class TestProbeTables(unittest.TestCase):
    def test_all_present_ok(self) -> None:
        presence = probe_tables(FakeConn(ALL_TABLES))
        self.assertTrue(all(presence.values()))
        self.assertEqual(degrade_status(presence), EXIT_OK)

    def test_at_tables_missing_degrades(self) -> None:
        presence = probe_tables(FakeConn([t for t in ALL_TABLES if not t.startswith("at_")]))
        self.assertFalse(presence["at_worker"])
        self.assertTrue(presence["de_worker"])
        self.assertEqual(degrade_status(presence), EXIT_DEGRADED)


if __name__ == "__main__":
    unittest.main()
