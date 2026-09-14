# scripts/test_inventory_legacy_platform.py
"""G12 盘点工具契约测试（合成 fixture，不打真库）。"""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

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


if __name__ == "__main__":
    unittest.main()
