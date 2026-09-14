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
from inventory_legacy_platform import (collect_workers, collect_teams,
                                       collect_mcps, collect_endpoints,
                                       diff_names)
from inventory_legacy_platform import collect_history
from inventory_legacy_platform import SEED_MAPPINGS, evaluate_mappings


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

    def test_bytes_sensitive_value_fingerprinted(self) -> None:
        # DDL 可空性/二进制类型未核验：BLOB 类列 pymysql 返回 bytes，同样不得明文落盘
        masked = mask_row("de_worker", {"worker_id": "w-1", "api_key": b"ak-bytes-1"})
        self.assertEqual(masked["api_key"], fingerprint("ak-bytes-1"))


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
        return [{"table_name": t} for t in wanted if t in self._existing]

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


class FakeDb:
    """按 SQL 前缀路由的假 DB：queries = {sql 前缀: 行列表}。"""

    def __init__(self, queries: dict[str, list[dict]]) -> None:
        self._queries = queries

    def cursor(self):
        return FakeQueryCursor(self._queries)


class FakeQueryCursor:
    def __init__(self, queries: dict[str, list[dict]]) -> None:
        self._queries = queries

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        return None

    def execute(self, sql: str, params=None) -> None:
        # 先精确匹配，未命中再取最长前缀——防短键误命中长 SQL（聚合/抽样键交叉时静默串扰）
        if sql in self._queries:
            self._rows = list(self._queries[sql])
            return
        hits = [k for k in self._queries if sql.startswith(k)]
        self._rows = list(self._queries[max(hits, key=len)]) if hits else []

    def fetchall(self):
        return [dict(r) for r in self._rows]

    def fetchone(self):
        return dict(self._rows[0]) if self._rows else None


class TestCollectWorkers(unittest.TestCase):
    def test_workers_merged_with_diff(self) -> None:
        db = FakeDb({
            "SELECT name, agent_type": FIXTURE_AT_WORKER,
            "SELECT worker_id, worker_name": FIXTURE_DE_WORKER,
        })
        domain = collect_workers(db, present=True)
        self.assertEqual(domain["status"], "ok")
        self.assertEqual(domain["stats"]["at_worker_count"], 2)
        self.assertEqual(domain["stats"]["de_worker_count"], 1)
        # 差集：at_worker 有、de_worker 无
        self.assertEqual(domain["stats"]["only_in_at"], ["worker-beta"])
        self.assertEqual(domain["stats"]["only_in_de"], [])

    def test_missing_table_reports_missing(self) -> None:
        domain = collect_workers(FakeDb({}), present=False)
        self.assertEqual(domain["status"], "table_missing")
        self.assertIn("gateway.impl=remote", " ".join(domain["notes"]))

    def test_null_names_defended_in_diff(self) -> None:
        # 0827 DDL 不在仓库，name 可空性未核验：NULL 名不进差集（防 sorted None/str 混排 TypeError）
        db = FakeDb({
            "SELECT name, agent_type": [{"name": None, "agent_type": "x"},
                                        {"name": "w-a", "agent_type": "x"}],
            "SELECT worker_id, worker_name": [{"worker_id": "w-1", "worker_name": "w-b"}],
        })
        domain = collect_workers(db, present=True)
        self.assertEqual(domain["stats"]["at_worker_count"], 2)  # 计数不受过滤影响
        self.assertEqual(domain["stats"]["only_in_at"], ["w-a"])
        self.assertEqual(domain["stats"]["only_in_de"], ["w-b"])


class TestDiffNames(unittest.TestCase):
    def test_diff_names(self) -> None:
        self.assertEqual(diff_names(["a", "b"], ["a"]), ["b"])
        self.assertEqual(diff_names([], ["x"]), [])


class TestCollectTeamsMcpEndpoint(unittest.TestCase):
    def test_teams(self) -> None:
        rows = [{"team_id": "t-1", "team_name": "team-a", "leader_id": "w-1",
                 "status": "ACTIVE", "del_flag": 0}]
        db = FakeDb({
            "SELECT team_id, team_name": rows,
            "SELECT team_id, worker_id": [{"team_id": "t-1", "worker_id": "w-1", "role": "member"}],
            "SELECT team_id, crew_id": [{"team_id": "t-1", "crew_id": "c-1"}],
            "SELECT src_user_id": [{"src_user_id": "u-1", "tgt_user_id": "t-1", "del_flag": 0}],
            "SELECT name, description": [{"name": "at-t", "description": "d", "status": "ACTIVE"}],
        })
        domain = collect_teams(db, present=True)
        self.assertEqual(domain["stats"]["de_team_count"], 1)
        self.assertEqual(domain["stats"]["de_team_worker_rel_count"], 1)
        self.assertEqual(domain["stats"]["de_team_crew_rel_count"], 1)
        self.assertEqual(domain["stats"]["user_mapping_count"], 1)
        self.assertEqual(domain["stats"]["at_team_count"], 1)

    def test_mcps(self) -> None:
        rows = [{"mcp_id": "m-1", "name": "mcp-a", "protocol": "SSE",
                 "url": "http://m", "deploy_status": "DEPLOYED",
                 "mcp_server_config": '{"token":"t"}', "auth_config": None,
                 "auth_enabled": 1}]
        db = FakeDb({"SELECT mcp_id, name": rows})
        domain = collect_mcps(db, present=True)
        self.assertEqual(domain["stats"]["at_mcp_count"], 1)
        self.assertNotIn('{"token":"t"}', json.dumps(domain["rows"]))

    def test_endpoints(self) -> None:
        rows = [{"endpoint_id": "ep-1", "endpoint_name": "e", "component": "worker",
                 "resource_name": "worker-alpha", "domain": "d.example",
                 "api_key": "epk-1", "status": "AVAILABLE"}]
        db = FakeDb({"SELECT endpoint_id, endpoint_name": rows})
        domain = collect_endpoints(db, present=True)
        self.assertEqual(domain["stats"]["at_endpoint_count"], 1)
        self.assertNotIn("epk-1", json.dumps(domain["rows"]))


HISTORY_FIXTURES = {
    "SELECT status, COUNT(*) AS c FROM de_task":
        [{"status": "CP", "c": 7}, {"status": "F", "c": 3}],
    "SELECT task_type, COUNT(*) AS c FROM de_task":
        [{"task_type": "RT", "c": 8}, {"task_type": "SHD", "c": 2}],
    "SELECT COUNT(*) AS c, SUM(parent_task_id IS NOT NULL)":
        [{"c": 10, "child": 2}],
    "SELECT COUNT(*) AS c, SUM(ver_no > 1)":
        [{"c": 12, "multiver": 4, "ok": 9}],
    "SELECT COUNT(*) AS c FROM de_chat_msg":
        [{"c": 500}],
    "SELECT role, COUNT(*) AS c FROM de_chat_msg":
        [{"role": 1, "c": 120}],
    "SELECT COUNT(*) AS c FROM de_chat_convo":
        [{"c": 30}],
    # 真实 DictCursor 下 MIN/MAX 无别名列名为 "MIN(col)"，fixture 同构
    "SELECT MIN(stime), MAX(etime) FROM de_task":
        [{"MIN(stime)": "2025-01-01 00:00:00", "MAX(etime)": "2025-06-30 00:00:00"}],
    # 历史域抽样路由键：HISTORY_SAMPLE_TABLES 四条 SQL 的前缀
    "SELECT task_id, team_id, task_title":
        [{"task_id": "t-1", "task_title": "s"}] * 5,
    "SELECT task_id, ver_no":
        [{"task_id": "t-1", "ver_no": 1}] * 5,
    "SELECT id, user_id, team_id":
        [{"id": 1, "user_id": "u-1"}] * 5,
    "SELECT id, convo_id, role":
        [{"id": 1, "convo_id": 1, "role": 1}] * 5,
}


class TestCollectHistory(unittest.TestCase):
    def test_history_stats_and_samples(self) -> None:
        db = FakeDb({k: list(v) for k, v in HISTORY_FIXTURES.items()})
        domain = collect_history(db, present=True, sample_limit=5)
        self.assertEqual(domain["status"], "ok")
        self.assertEqual(domain["stats"]["de_task"]["total"], 10)
        self.assertEqual(domain["stats"]["de_task"]["by_status"], {"CP": 7, "F": 3})
        self.assertEqual(domain["stats"]["de_task"]["by_type"], {"RT": 8, "SHD": 2})
        self.assertEqual(domain["stats"]["de_task"]["child_task_count"], 2)
        self.assertEqual(domain["stats"]["de_task_rslt"]["total"], 12)
        self.assertEqual(domain["stats"]["de_task_rslt"]["multi_version_count"], 4)
        self.assertEqual(domain["stats"]["de_chat_msg"]["total"], 500)
        self.assertEqual(domain["stats"]["de_chat_msg"]["by_role"], {1: 120})
        self.assertEqual(domain["stats"]["de_chat_convo"]["total"], 30)
        # _span 正向分支：MIN/MAX 值 str() 化
        self.assertEqual(domain["stats"]["de_task"]["time_span"],
                         {"from": "2025-01-01 00:00:00", "to": "2025-06-30 00:00:00"})
        # _span 空回退分支：convo/msg 的 MIN/MAX 无 fixture → None/None
        self.assertEqual(domain["stats"]["de_chat_msg"]["time_span"],
                         {"from": None, "to": None})
        self.assertEqual(len(domain["rows"]["samples"]["de_task"]), 5)

    def test_history_empty_database(self) -> None:
        # 全空库：_row/_scalar/_pairs/_span 空回退 + 抽样空列表，不抛异常
        domain = collect_history(FakeDb({}), present=True)
        self.assertEqual(domain["status"], "ok")
        self.assertEqual(domain["stats"]["de_task"]["total"], 0)
        self.assertEqual(domain["stats"]["de_task"]["by_status"], {})
        self.assertEqual(domain["stats"]["de_task_rslt"]["success_count"], 0)
        self.assertEqual(domain["stats"]["de_chat_msg"]["time_span"],
                         {"from": None, "to": None})
        for table in ("de_task", "de_task_rslt", "de_chat_convo", "de_chat_msg"):
            self.assertEqual(domain["rows"]["samples"][table], [])

    def test_history_missing_table_reports_missing(self) -> None:
        domain = collect_history(FakeDb({}), present=False)
        self.assertEqual(domain["status"], "table_missing")
        self.assertIn("gateway.impl=remote", " ".join(domain["notes"]))


class TestMappings(unittest.TestCase):
    def test_seed_mappings_have_decision_and_reason(self) -> None:
        for m in SEED_MAPPINGS:
            self.assertIn(m["strategy"], {"adopt", "adapt", "drop"})
            self.assertTrue(m["reason"])

    def test_evaluate_counts_by_strategy(self) -> None:
        result = evaluate_mappings({})
        self.assertEqual(result["stats"],
                         {"adopt": 0, "adapt": 7, "drop": 1, "total": 8})

    def test_drop_rows_carry_legacy_concept(self) -> None:
        result = evaluate_mappings({})
        drops = [m for m in result["rows"] if m["strategy"] == "drop"]
        self.assertEqual(len(drops), 1)
        self.assertIn("前缀格式", drops[0]["legacy_concept"])


if __name__ == "__main__":
    unittest.main()
