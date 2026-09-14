# G12 前置：旧平台盘点工具实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建 `scripts/inventory-legacy-platform.py`，对 corp-agent MySQL 台账与 yaml 配置做六大域盘点，产出两层报告（明细 JSON gitignore / 汇总 Markdown 入库）与三态映射评估表。

**架构：** 单文件 Python CLI（scripts/ 惯例），Collector（Db+Yaml）→ 统一域 dict → MappingEngine（种子映射+三态决策）→ ReportWriter（两层）。全部查询只读；敏感字段指纹化；`at_*` 表缺失降级不失败。

**技术栈：** Python 3 标准库（argparse/json/unittest/hashlib/dataclasses）+ PyYAML（已装）+ pymysql（仅真实运行需要，测试不依赖）。

**规格：** `docs/superpowers/specs/2026-09-14-g12-legacy-inventory-design.md`

**测试惯例修正：** 规格 §8 的 pytest 更正为 **unittest**（CI 实证 `python3 -m unittest scripts/test_*.py`，见 `.github/workflows/ci.yml:50-88`），收尾任务统一修正规格。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `scripts/inventory-legacy-platform.py`（创建） | 工具主体：域模型/指纹化/Yaml 配置采集/表探测/六域采集/映射引擎/两层报告/CLI |
| `scripts/test_inventory_legacy_platform.py`（创建） | unittest 契约测试（合成 fixture，不打真库，不 import pymysql） |
| `.gitignore`（修改） | 追加 `output/`（盘点明细不入库） |
| `docs/superpowers/specs/2026-09-14-g12-legacy-inventory-design.md`（修改） | §3/§8 pytest→unittest 口径修正 |
| `output/legacy-inventory-<ts>/detail/*.json`（运行时产物，不提交） | 明细层 |
| `docs/inventory/<date>-legacy-inventory.md`（运行时产物，试跑后提交） | 汇总层 |

**域模型约定（全工具统一）：** 每域 = `dict`，含 `domain`、`status`（`ok`/`table_missing`）、`rows`（脱敏后行列表）、`stats`（统计 dict）、`notes`（缺失说明等）。测试通过注入假行数据驱动全部纯函数；DB 连接仅存在于 `run()` 组装层。

---

### 任务 1：`.gitignore` 与测试骨架

**文件：**
- 修改：`.gitignore`（第 26-27 行 `.local/` 附近）
- 创建：`scripts/test_inventory_legacy_platform.py`
- 创建：`scripts/inventory-legacy-platform.py`（空壳：docstring + 退出码常量）

- [ ] **步骤 1：编写失败的测试**

```python
# scripts/test_inventory_legacy_platform.py
"""G12 盘点工具契约测试（合成 fixture，不打真库）。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parents[1]


class TestGitignore(unittest.TestCase):
    def test_output_dir_is_ignored(self) -> None:
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("output/", gitignore)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（`.gitignore` 无 `output/`）

- [ ] **步骤 3：修改 `.gitignore`**

在 `# Common local tool output` 块（第 26 行附近）追加：

```gitignore
output/
```

同时创建工具空壳：

```python
#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
产出：output/legacy-inventory-<ts>/detail/*.json（明细，gitignore）
      docs/inventory/<date>-legacy-inventory.md（汇总，入库，脱敏）。
"""

from __future__ import annotations

# 退出码：0=正常完成，1=降级完成（存在表缺失），2=连接失败
EXIT_OK = 0
EXIT_DEGRADED = 1
EXIT_CONN_FAIL = 2
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（1 test）

- [ ] **步骤 5：Commit**

```bash
git add .gitignore scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): G12 盘点工具骨架与 output 目录 gitignore"
```

---

### 任务 2：敏感字段指纹化与合成 fixture

**文件：**
- 修改：`scripts/inventory-legacy-platform.py`
- 修改：`scripts/test_inventory_legacy_platform.py`

- [ ] **步骤 1：编写失败的测试**

```python
from inventory_legacy_platform import fingerprint, mask_row


class TestFingerprint(unittest.TestCase):
    def test_fingerprint_is_stable_8_hex(self) -> None:
        self.assertEqual(fingerprint("secret-value"), fingerprint("secret-value"))
        self.assertEqual(len(fingerprint("secret-value")), 8)
        self.assertNotIn("secret-value", fingerprint("secret-value"))


# 合成 fixture（脱敏风格，模拟 DDL 字段；后续任务复用）
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: fingerprint）

- [ ] **步骤 3：实现指纹化**

在 `scripts/inventory-legacy-platform.py` 追加（`import hashlib`）：

```python
# 表内敏感字段（DDL 核验 2026-09-14）：值一律指纹化，不落任何产出物
SENSITIVE_FIELDS: dict[str, set[str]] = {
    "de_worker": {"api_key"},
    "de_user_mapp": {"tgt_user_pwd"},
    "at_service_endpoint": {"api_key"},
    "at_mcp_server": {"mcp_server_config", "auth_config"},
}


def fingerprint(value: str) -> str:
    """SHA-256 前 8 位十六进制，稳定且不可逆。"""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:8]


def mask_row(table: str, row: dict) -> dict:
    """返回脱敏副本；敏感字段替换为指纹，其余原样保留。"""
    sensitive = SENSITIVE_FIELDS.get(table, set())
    return {k: (fingerprint(v) if k in sensitive and isinstance(v, str) and v else v)
            for k, v in row.items()}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（4 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具敏感字段指纹化与合成 fixture"
```

---

### 任务 3：YamlConfigCollector（平台配置域）

**文件：** 同前两文件。

- [ ] **步骤 1：编写失败的测试**

```python
import textwrap
from inventory_legacy_platform import collect_config

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
        self.assertIn("agentcore_api_key", cfg["rows"]["agentcore"]["credential_sources"])
```

（测试文件头部需 `import json`。）

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: collect_config）

- [ ] **步骤 3：实现配置域采集**

工具追加（`import yaml`）：

```python
def collect_config(yaml_text: str) -> dict:
    """平台配置域：从 corp-agent application yaml 提取双平台配置。

    标识符（workspace/instance/leader-agent id）全文记录；
    凭据（api-key 类）只记指纹并登记 credential_sources。
    """
    raw = yaml.safe_load(yaml_text) or {}
    at = raw.get("agentteams") or {}
    ac = raw.get("agentcore") or {}
    at_task = at.get("task") or {}
    at_gateway = at.get("gateway") or {}
    ac_runtime = ac.get("runtime") or {}
    credential_sources: list[str] = []

    def fp(key: str, value) -> str | None:
        if not value:
            return None
        credential_sources.append(key)
        return fingerprint(str(value))

    rows = {
        "agentcore": {
            "enabled": bool(ac.get("enabled", False)),
            "workspace_id": ac.get("workspace-id"),
            "leader_agent_id": ac.get("leader-agent-id"),
            "endpoint_template": ac.get("endpoint-template"),
            "api_key_fingerprint": fp("agentcore_api_key", ac.get("api-key")),
            "runtime": {
                "compute_class": ac_runtime.get("compute-class"),
                "session_policy_type": ac_runtime.get("session-policy-type"),
            },
        },
        "agentteams_legacy": {
            "endpoint": at.get("endpoint"),
            "instance_id": at.get("instance-id"),
            "worker_url": at.get("worker-url"),
            "gateway_impl": at_gateway.get("impl", "remote"),
            "api_key_fingerprint": fp("agentteams_api_key", at.get("api-key")) or "unset",
            "task": {
                "enabled": bool(at_task.get("enabled", False)),
                "homeserver_url": at_task.get("homeserver-url"),
                "signin_base_url": at_task.get("signin-base-url"),
                "default_leader_user_id": at_task.get("default-leader-user-id"),
                "sync_timeout_minutes": at_task.get("sync-timeout-minutes"),
            },
        },
        "credential_sources": credential_sources,
    }
    return {"domain": "platform-config", "status": "ok", "rows": rows,
            "stats": {}, "notes": []}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（7 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具平台配置域采集（凭据指纹化）"
```

---

### 任务 4：表探测与降级

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
from inventory_legacy_platform import EXIT_DEGRADED, EXIT_OK, probe_tables, degrade_status


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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: probe_tables）

- [ ] **步骤 3：实现探测与降级**

```python
INVENTORY_TABLES = [
    "at_team", "at_worker", "at_mcp_server", "at_service_endpoint",
    "de_worker", "de_team", "de_team_worker_rel", "de_team_crew_rel",
    "de_user_mapp", "de_task", "de_task_rslt", "de_chat_convo", "de_chat_msg",
]

PROBE_SQL = ("SELECT table_name FROM information_schema.tables "
             "WHERE table_schema = DATABASE() AND table_name IN (%s" + ", %s" * 12 + ")")


def probe_tables(conn) -> dict[str, bool]:
    """逐表探测 information_schema；返回 {表名: 是否存在}。"""
    with conn.cursor() as cur:
        cur.execute(PROBE_SQL, tuple(INVENTORY_TABLES))
        existing = {row[0] for row in cur.fetchall()}
    return {t: t in existing for t in INVENTORY_TABLES}


def degrade_status(presence: dict[str, bool]) -> int:
    return EXIT_OK if all(presence.values()) else EXIT_DEGRADED
```

`FakeCursor` 需支持上下文管理器（`with conn.cursor()`）：给 `FakeCursor` 追加：

```python
    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        return None
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（9 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具表探测与 at_* 缺失降级"
```

---

### 任务 5：资源域采集（Worker/Team/MCP/Endpoint + 双侧差集）

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
from inventory_legacy_platform import (collect_workers, collect_teams,
                                       collect_mcps, collect_endpoints,
                                       diff_names)


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
        self._rows = next((v for k, v in self._queries.items() if sql.startswith(k)), [])

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
        self.assertIn("gateway.impl=db", " ".join(domain["notes"]))


class TestDiffNames(unittest.TestCase):
    def test_diff_names(self) -> None:
        self.assertEqual(diff_names(["a", "b"], ["a"]), ["b"])
        self.assertEqual(diff_names([], ["x"]), [])


class TestCollectTeamsMcpEndpoint(unittest.TestCase):
    def test_teams(self) -> None:
        rows = [{"team_id": "t-1", "team_name": "team-a", "leader_id": "w-1",
                 "status": "ACTIVE", "del_flag": 0}]
        db = FakeDb({"SELECT team_id, team_name": rows})
        domain = collect_teams(db, present=True)
        self.assertEqual(domain["stats"]["de_team_count"], 1)

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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: collect_workers）

- [ ] **步骤 3：实现资源域采集**

```python
MISSING_NOTE = ("at_* 平台镜像表缺失：corp-agent 当前 agentteams.gateway.impl=remote"
                "（纯远程透传，从未落库）。资源清单以 de_* 业务台账为准，"
                "平台侧真实状态需 OpenAPI 对账（后续阶段）。")


def _fetch(conn, sql: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(sql)
        return [dict(r) for r in cur.fetchall()]


def diff_names(at_names: list[str], de_names: list[str]) -> list[str]:
    """at_* 有、de_* 无 的名单（漂移线索）。"""
    return sorted(set(at_names) - set(de_names))


def _domain(name: str, present: bool, rows: dict, stats: dict,
            notes: list[str] | None = None) -> dict:
    if not present:
        return {"domain": name, "status": "table_missing", "rows": {},
                "stats": {}, "notes": [MISSING_NOTE]}
    return {"domain": name, "status": "ok", "rows": rows,
            "stats": stats, "notes": notes or []}


def collect_workers(conn, present: bool) -> dict:
    if not present:
        return _domain("workers", False, {}, {})
    at_rows = [mask_row("at_worker", r) for r in _fetch(
        conn, "SELECT name, agent_type, deploy_type, model_provider, model_name,"
              " status, soul, agents, mcp_servers_json, skills_json, groups_json"
              " FROM at_worker WHERE deleted = 0")]
    de_rows = [mask_row("de_worker", r) for r in _fetch(
        conn, "SELECT worker_id, worker_name, status, model_name, model_mfr_name,"
              " endpoint, api_key FROM de_worker WHERE del_flag = 0")]
    at_names = [r["name"] for r in at_rows]
    de_names = [r["worker_name"] for r in de_rows]
    stats = {"at_worker_count": len(at_rows), "de_worker_count": len(de_rows),
             "only_in_at": diff_names(at_names, de_names),
             "only_in_de": sorted(set(de_names) - set(at_names))}
    return _domain("workers", True, {"at_worker": at_rows, "de_worker": de_rows}, stats)


def collect_teams(conn, present: bool) -> dict:
    if not present:
        return _domain("teams", False, {}, {})
    de_teams = _fetch(conn, "SELECT team_id, team_name, dscr, leader_id, status,"
                            " create_time FROM de_team WHERE del_flag = 0")
    rels = _fetch(conn, "SELECT team_id, worker_id, role FROM de_team_worker_rel"
                        " WHERE del_flag = 0")
    crews = _fetch(conn, "SELECT team_id, crew_id FROM de_team_crew_rel"
                         " WHERE del_flag = 0")
    user_map = [mask_row("de_user_mapp", r) for r in _fetch(
        conn, "SELECT src_user_id, tgt_user_id, del_flag FROM de_user_mapp")]
    at_teams = _fetch(conn, "SELECT name, description, admin_name, leader_name,"
                            " status, member_names, worker_names FROM at_team"
                            " WHERE deleted = 0")
    stats = {"de_team_count": len(de_teams), "de_team_worker_rel_count": len(rels),
             "de_team_crew_rel_count": len(crews), "user_mapping_count": len(user_map),
             "at_team_count": len(at_teams)}
    return _domain("teams", True,
                   {"de_team": de_teams, "de_team_worker_rel": rels,
                    "de_team_crew_rel": crews, "de_user_mapp": user_map,
                    "at_team": at_teams}, stats)


def collect_mcps(conn, present: bool) -> dict:
    if not present:
        return _domain("mcps", False, {}, {})
    rows = [mask_row("at_mcp_server", r) for r in _fetch(
        conn, "SELECT mcp_id, name, description, protocol, addresses_json, url,"
              " deploy_status, create_type, mcp_server_config, auth_enabled,"
              " auth_config FROM at_mcp_server WHERE deleted = 0")]
    return _domain("mcps", True, {"at_mcp_server": rows},
                   {"at_mcp_count": len(rows)})


def collect_endpoints(conn, present: bool) -> dict:
    if not present:
        return _domain("endpoints", False, {}, {})
    rows = [mask_row("at_service_endpoint", r) for r in _fetch(
        conn, "SELECT endpoint_id, endpoint_name, component, resource_name, domain,"
              " domain_type, network_type, status, cert_identifier, api_key_name,"
              " api_key FROM at_service_endpoint WHERE deleted = 0")]
    return _domain("endpoints", True, {"at_service_endpoint": rows},
                   {"at_endpoint_count": len(rows)})
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（15 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具资源域采集（Worker/Team/MCP/Endpoint+双侧差集）"
```

---

### 任务 6：历史域聚合统计

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
from inventory_legacy_platform import collect_history


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
        self.assertEqual(len(domain["rows"]["samples"]["de_task"]), 5)
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: collect_history）

- [ ] **步骤 3：实现历史域**

```python
HISTORY_SAMPLE_TABLES = {
    "de_task": ("SELECT task_id, team_id, task_title, task_type, status, prior_lv,"
                " parent_task_id, stime, etime, create_time FROM de_task"
                " WHERE del_flag = 0 ORDER BY create_time DESC LIMIT %s",),
    "de_task_rslt": ("SELECT task_id, ver_no, summ, succ_flag, error_info, etime"
                     " FROM de_task_rslt WHERE del_flag = 0 ORDER BY create_time DESC LIMIT %s",),
    "de_chat_convo": ("SELECT id, user_id, team_id, title, status, last_chat_time"
                      " FROM de_chat_convo WHERE del_flag = 0 ORDER BY last_chat_time"
                      " DESC LIMIT %s",),
    "de_chat_msg": ("SELECT id, convo_id, role, sender_id, gena_status, task_id,"
                    " end_reason, create_time FROM de_chat_msg WHERE del_flag = 0"
                    " ORDER BY create_time DESC LIMIT %s",),
}


def collect_history(conn, present: bool, sample_limit: int = 20) -> dict:
    """历史域：全量聚合统计 + 每表抽样 ≤ sample_limit 条画像。

    消息正文（de_chat_msg.cont）与任务详情（de_task_rslt.detl）不采集。
    """
    if not present:
        return _domain("history", False, {}, {})
    task = _row(conn, "SELECT COUNT(*) AS c, SUM(parent_task_id IS NOT NULL) AS child"
                      " FROM de_task WHERE del_flag = 0")
    rslt = _row(conn, "SELECT COUNT(*) AS c, SUM(ver_no > 1) AS multiver,"
                      " SUM(succ_flag = 1) AS ok FROM de_task_rslt WHERE del_flag = 0")
    stats: dict[str, dict] = {
        "de_task": {
            "total": task.get("c", 0),
            "child_task_count": int(task.get("child") or 0),
            "by_status": _pairs(conn, "SELECT status, COUNT(*) AS c FROM de_task"
                                      " WHERE del_flag = 0 GROUP BY status"),
            "by_type": _pairs(conn, "SELECT task_type, COUNT(*) AS c FROM de_task"
                                    " WHERE del_flag = 0 GROUP BY task_type"),
            "time_span": _span(conn, "SELECT MIN(stime), MAX(etime) FROM de_task"
                                     " WHERE del_flag = 0"),
        },
        "de_task_rslt": {
            "total": rslt.get("c", 0),
            "multi_version_count": int(rslt.get("multiver") or 0),
            "success_count": int(rslt.get("ok") or 0),
        },
        "de_chat_convo": {
            "total": _scalar(conn, "SELECT COUNT(*) AS c FROM de_chat_convo"
                                   " WHERE del_flag = 0"),
            "time_span": _span(conn, "SELECT MIN(last_chat_time), MAX(last_chat_time)"
                                     " FROM de_chat_convo WHERE del_flag = 0"),
        },
        "de_chat_msg": {
            "total": _scalar(conn, "SELECT COUNT(*) AS c FROM de_chat_msg"
                                   " WHERE del_flag = 0"),
            "by_role": _pairs(conn, "SELECT role, COUNT(*) AS c FROM de_chat_msg"
                                    " WHERE del_flag = 0 GROUP BY role"),
            "time_span": _span(conn, "SELECT MIN(create_time), MAX(create_time)"
                                     " FROM de_chat_msg WHERE del_flag = 0"),
        },
    }
    samples: dict[str, list[dict]] = {}
    for table, (sql,) in HISTORY_SAMPLE_TABLES.items():
        with conn.cursor() as cur:
            cur.execute(sql, (sample_limit,))
            samples[table] = [dict(r) for r in cur.fetchall()]
    return _domain("history", True, {"samples": samples}, stats)


def _row(conn, sql: str) -> dict:
    """单行多列聚合：真库 DictCursor 返回 dict；fixture 同构。"""
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchone() or {}


def _scalar(conn, sql: str):
    """单值聚合：取行第一列。"""
    row = _row(conn, sql)
    return next(iter(row.values())) if row else 0


def _pairs(conn, sql: str) -> dict:
    """两列分组计数 → dict（首列=键，次列=计数）。"""
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    if not rows:
        return {}
    first = next(iter(rows[0]))
    second = next(k for k in rows[0] if k != first)
    return {r[first]: r[second] for r in rows}


def _span(conn, sql: str) -> dict:
    row = _row(conn, sql)
    values = list(row.values()) if row else [None, None]
    lo, hi = (values + [None, None])[:2]
    return {"from": str(lo) if lo else None, "to": str(hi) if hi else None}
```

注意：`_row/_pairs/_span` 依赖 pymysql DictCursor 的列名字典序（首列=键）；fixture 按同构 dict 提供行，FakeDb 按 SQL 前缀路由。

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（16 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具历史域聚合统计与抽样画像"
```

---

### 任务 7：MappingEngine（种子映射 + 三态决策）

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
from inventory_legacy_platform import SEED_MAPPINGS, evaluate_mappings


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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: SEED_MAPPINGS）

- [ ] **步骤 3：实现映射引擎**

```python
SEED_MAPPINGS: list[dict] = [
    {"legacy_concept": "at_worker.soul + agents（固定分段标记合成 instruction）",
     "new_concept": "Worker/AgentSpec prompt 直接建模", "strategy": "adapt",
     "reason": "分段标记协议是阿里实现细节，corp-agent 适配器自述需『厚翻译』"},
    {"legacy_concept": "legacy Team 无 Leader 参数（groups[0].role=leader 隐式认定）",
     "new_concept": "Team 显式 Leader 一等概念", "strategy": "adapt",
     "reason": "隐式格式语义易错"},
    {"legacy_concept": "AgentCore Team.agents 恰一 Leader 硬约束",
     "new_concept": "自研 Team 状态机自行定义成员约束", "strategy": "adapt",
     "reason": "平台硬约束不进入领域模型"},
    {"legacy_concept": "Agent 属于 Team 时禁止删除（AgentCore 409）",
     "new_concept": "解绑+删除显式编排（detachAndDeleteWorker 既有）", "strategy": "adapt",
     "reason": "约束属平台实现细节"},
    {"legacy_concept": "ServiceEndpoint {workerName}.worker.{host} 前缀格式匹配",
     "new_concept": "Endpoint 一等实体显式字段", "strategy": "drop",
     "reason": "格式即协议的反模式"},
    {"legacy_concept": "modelProvider ↔ modelConnectionId 平台侧连接映射",
     "new_concept": "AgentSpec manifest + credentialRef 引用", "strategy": "adapt",
     "reason": "模型配置不绑死平台侧连接"},
    {"legacy_concept": "mcpServers ↔ tools[{name,type=MCP}]",
     "new_concept": "MCP 注册中心 + AgentSpec manifest 下发", "strategy": "adapt",
     "reason": "自研 MCP 发现与运行时绑定已交付"},
    {"legacy_concept": "Worker（ManagedAgent）资源模型",
     "new_concept": "Worker + AgentSpec + Team Revision", "strategy": "adapt",
     "reason": "自研控制平面已按自身架构建模"},
]


def evaluate_mappings(domains: dict) -> dict:
    """三态映射评估：种子表 + 运行期发现（当前仅种子，OpenAPI 对账阶段扩展）。"""
    rows = [dict(m) for m in SEED_MAPPINGS]
    stats = {"adopt": 0, "adapt": 0, "drop": 0, "total": len(rows)}
    for m in rows:
        stats[m["strategy"]] += 1
    return {"domain": "legacy-to-new-mapping", "status": "ok",
            "rows": rows, "stats": stats, "notes": []}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（19 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具三态映射评估引擎与种子映射表"
```

---

### 任务 8：ReportWriter（两层产出 + 分离断言）

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
import tempfile
from inventory_legacy_platform import write_detail, write_summary, build_domains

FIXTURE_DOMAINS = [
    collect_config(CORP_YAML),
    collect_workers(FakeDb({
        "SELECT name, agent_type": FIXTURE_AT_WORKER,
        "SELECT worker_id, worker_name": FIXTURE_DE_WORKER}), present=True),
    collect_history(FakeDb({
        "SELECT status, COUNT(*) AS c FROM de_task":
            [{"status": "CP", "c": 7}, {"status": "F", "c": 3}],
        "SELECT COUNT(*) AS c, SUM(parent_task_id IS NOT NULL)":
            [{"c": 10, "child": 2}],
        "SELECT COUNT(*) AS c, SUM(ver_no > 1)": [{"c": 12, "multiver": 4}],
        "SELECT COUNT(*) AS c FROM de_chat_msg": [{"c": 500}],
    }), present=True, sample_limit=5),
    evaluate_mappings({}),
]


class TestReportWriter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())

    def test_detail_contains_bodies_and_meta(self) -> None:
        path = write_detail(self.tmp, FIXTURE_DOMAINS)
        payload = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(payload["_meta"]["caliber"], "ledger-mirror")
        self.assertEqual(payload["_meta"]["domains"], 4)
        workers_text = (path.parent / "workers.json").read_text(encoding="utf-8")
        self.assertIn("SOUL MARKER alpha", workers_text)  # 正文仅入明细层

    def test_summary_excludes_bodies_and_credentials(self) -> None:
        path = write_summary(self.tmp, FIXTURE_DOMAINS)
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("SOUL MARKER", text)          # Prompt 正文不入库
        self.assertNotIn("AGENTS MARKER", text)
        self.assertNotIn("FwcSECRET", text)             # 凭据不入库
        self.assertIn("worker-beta", text)              # 清单名可入库
        self.assertIn("adopt", text)                    # 映射统计在汇总
        self.assertIn("台账镜像口径", text)               # 口径声明

    def test_build_domains_orders_six(self) -> None:
        domains = build_domains(
            config_domain=FIXTURE_DOMAINS[0],
            workers=FIXTURE_DOMAINS[1],
            teams=_domain("teams", False, {}, {}),
            mcps=_domain("mcps", False, {}, {}),
            endpoints=_domain("endpoints", False, {}, {}),
            history=FIXTURE_DOMAINS[2],
            mapping=FIXTURE_DOMAINS[3],
        )
        self.assertEqual([d["domain"] for d in domains],
                         ["platform-config", "workers", "teams", "mcps",
                          "endpoints", "history", "legacy-to-new-mapping"])
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: write_detail）

- [ ] **步骤 3：实现两层报告**

```python
def _meta(source: str) -> dict:
    return {
        "tool": "inventory-legacy-platform",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": source,
        "caliber": "ledger-mirror",
        "caliber_note": "台账镜像口径：数据来自 corp-agent 本地台账（at_*/de_*），"
                        "可能与阿里云平台侧真实状态存在漂移；核对需 OpenAPI 对账（后续阶段）",
        "sensitivity": "detail 层含 prompt 正文与抽样消息元数据；禁止提交到 git",
    }


def write_detail(out_root: Path, domains: list[dict]) -> Path:
    """明细层：output/legacy-inventory-<ts>/detail/*.json（每域一文件）。"""
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    detail_dir = out_root / f"legacy-inventory-{ts}" / "detail"
    detail_dir.mkdir(parents=True, exist_ok=True)
    meta = _meta("corp-agent MySQL ledger + application yaml")
    for d in domains:
        payload = {"_meta": {**meta, "domains": len(domains)}, **d}
        path = detail_dir / f"{d['domain']}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2,
                                   default=str), encoding="utf-8")
    return detail_dir / f"{domains[0]['domain']}.json"


SUMMARY_TEMPLATE = """# 旧平台资产盘点汇总（{date}）

- 数据源：corp-agent MySQL 台账 + corp-agent application yaml
- 口径：**台账镜像口径** —— 台账为「本地落库+远程同步」镜像，可能与平台侧真实状态漂移；核对需后续 OpenAPI 对账
- 敏感性：本报告不含 Prompt 正文、消息内容与凭据；明细（含正文）在 `output/`（gitignore）

## 资产总览

| 域 | 状态 | 数量统计 |
|---|---|---|
{domain_rows}

## 漂移线索（at_* vs de_* 名单差集）

{drift_rows}

## 历史数据画像

{history_rows}

## 旧→新映射评估（三态决策）

策略口径：adopt=概念对等直接迁移；adapt=语义等价按自研架构重建；drop=阿里缺陷或无迁移价值（须记录理由）。

| 旧概念 | 新平台对应 | 策略 | 理由 |
|---|---|---|---|
{mapping_rows}

## 映射统计

| 策略 | 数量 |
|---|---|
| adopt | {n_adopt} |
| adapt | {n_adapt} |
| drop | {n_drop} |
"""


def write_summary(out_root: Path, domains: list[dict]) -> Path:
    """汇总层：docs/inventory/<date>-legacy-inventory.md（入库，脱敏）。"""
    domain_rows, drift_rows, history_rows = [], [], []
    for d in domains:
        stats_inline = "; ".join(f"{k}={v}" for k, v in d.get("stats", {}).items()
                                 if isinstance(v, (int, str)))
        domain_rows.append(f"| {d['domain']} | {d['status']} | {stats_inline} |")
        for key in ("only_in_at", "only_in_de"):
            values = d.get("stats", {}).get(key) or []
            if values:
                drift_rows.append(f"- `{d['domain']}.{key}`: {', '.join(map(str, values))}")
        if d["domain"] == "history":
            for table, s in d.get("stats", {}).items():
                head = ", ".join(f"{k}={v}" for k, v in list(s.items())[:3])
                history_rows.append(f"- `{table}`: {head}")
    mapping = next(d for d in domains if d["domain"] == "legacy-to-new-mapping")
    mapping_rows = ["| {} | {} | {} | {} |".format(
        m["legacy_concept"], m["new_concept"], m["strategy"], m["reason"])
        for m in mapping["rows"]]
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    docs_dir = out_root / "docs" / "inventory"
    docs_dir.mkdir(parents=True, exist_ok=True)
    path = docs_dir / f"{date}-legacy-inventory.md"
    path.write_text(SUMMARY_TEMPLATE.format(
        date=date, domain_rows="\n".join(domain_rows),
        drift_rows="\n".join(drift_rows) or "-（无漂移线索）",
        history_rows="\n".join(history_rows) or "-（无历史数据）",
        mapping_rows="\n".join(mapping_rows),
        n_adopt=mapping["stats"]["adopt"], n_adapt=mapping["stats"]["adapt"],
        n_drop=mapping["stats"]["drop"]), encoding="utf-8")
    return path


def build_domains(**kwargs) -> list[dict]:
    """固定域序组装：配置→资源四域→历史→映射。"""
    order = ["config_domain", "workers", "teams", "mcps", "endpoints",
             "history", "mapping"]
    return [kwargs[k] for k in order]
```

（工具需 `from datetime import datetime, timezone`。）

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（22 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具两层报告产出与脱敏断言"
```

---

### 任务 9：CLI 主入口（--check 预检 / 退出码 / run 组装）

**文件：** 同前。

- [ ] **步骤 1：编写失败的测试**

```python
import subprocess
from inventory_legacy_platform import parser, run, EXIT_CONN_FAIL


class TestCli(unittest.TestCase):
    def test_parser_accepts_check_mode(self) -> None:
        args = parser().parse_args(["--check", "--dsn", "mysql://x"])
        self.assertTrue(args.check)
        self.assertEqual(args.dsn, "mysql://x")

    def test_run_unreachable_dsn_returns_conn_fail(self) -> None:
        rc = run(["--check", "--dsn", "mysql://127.0.0.1:1/none",
                  "--user", "u", "--password", "p"])
        self.assertEqual(rc, EXIT_CONN_FAIL)

    def test_module_help_smoke(self) -> None:
        result = subprocess.run(
            [sys.executable, "scripts/inventory-legacy-platform.py", "--help"],
            capture_output=True, text=True, cwd=str(ROOT))
        self.assertEqual(result.returncode, 0)
        self.assertIn("--check", result.stdout)
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：FAIL（ImportError: parser）

- [ ] **步骤 3：实现 CLI**

```python
def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--check", action="store_true",
                         help="仅预检：DNS/连接/账号权限/表存在性，不产出报告")
    command.add_argument("--dsn", default=None,
                         help="MySQL DSN，如 mysql://host:3306/inner_imp_de；"
                              "缺省读环境变量 INV_DSN")
    command.add_argument("--user", default=None, help="缺省读 INV_USER")
    command.add_argument("--password", default=None, help="缺省读 INV_PASSWORD；"
                                                         "凭据仅内存使用，不落盘")
    command.add_argument("--corp-config", type=Path,
                         default=Path("/Users/gecko/IDEAPlace/corp-agent/"
                                      "corp-agent-app/src/main/resources/"
                                      "application-local.yaml"),
                         help="corp-agent application yaml 路径")
    command.add_argument("--repo-root", type=Path,
                         default=Path(__file__).resolve().parents[1],
                         help="agentteams-java 仓库根（产出 output/ 与 docs/inventory/）")
    command.add_argument("--sample-limit", type=int, default=20,
                         help="历史域每表抽样上限（默认 20）")
    return command


def _connect(args) -> tuple[object | None, str | None]:
    """pymysql 延迟导入；返回 (conn, error)。DSN 解析失败/连接失败均返回 error。"""
    try:
        import pymysql  # 仅真实运行需要；测试不打真库，不依赖
    except ImportError:
        return None, "pymysql 未安装：pip3 install pymysql"
    dsn = args.dsn or os.environ.get("INV_DSN")
    user = args.user or os.environ.get("INV_USER")
    password = args.password or os.environ.get("INV_PASSWORD")
    if not (dsn and user and password):
        return None, "缺少连接参数：--dsn/--user/--password 或 INV_DSN/INV_USER/INV_PASSWORD"
    parsed = urllib.parse.urlparse(dsn if "://" in dsn else f"mysql://{dsn}")
    try:
        return (pymysql.connect(host=parsed.hostname, port=parsed.port or 3306,
                                database=parsed.path.lstrip("/"), user=user,
                                password=password, cursorclass=pymysql.cursors.DictCursor,
                                connect_timeout=10, read_timeout=60), None)
    except Exception as exc:  # 连接失败：不含凭据的错误摘要
        return None, f"连接失败：{type(exc).__name__}: {exc}".replace(password, "***")


def run(argv: list[str]) -> int:
    args = parser().parse_args(argv)
    repo = args.repo_root
    conn, error = _connect(args)
    if conn is None:
        print(f"[PRECHECK] FAIL: {error}", file=sys.stderr)
        return EXIT_CONN_FAIL
    presence = probe_tables(conn)
    missing = [t for t, ok in presence.items() if not ok]
    if missing:
        print(f"[PRECHECK] 缺失表（降级继续）: {', '.join(missing)}", file=sys.stderr)
    if args.check:
        print("[PRECHECK] OK" if not missing else "[PRECHECK] DEGRADED")
        return degrade_status(presence)

    import yaml  # 配置域采集依赖 PyYAML
    config_domain = collect_config(args.corp_config.read_text(encoding="utf-8"))
    domains = build_domains(
        config_domain=config_domain,
        workers=collect_workers(conn, presence["at_worker"] and presence["de_worker"]),
        teams=collect_teams(conn, presence["de_team"]),
        mcps=collect_mcps(conn, presence["at_mcp_server"]),
        endpoints=collect_endpoints(conn, presence["at_service_endpoint"]),
        history=collect_history(conn, presence["de_task"], sample_limit=args.sample_limit),
        mapping=evaluate_mappings({}),
    )
    detail_hint = write_detail(repo / "output", domains)
    summary_path = write_summary(repo, domains)
    print(f"[DONE] 明细: {detail_hint.parent}")
    print(f"[DONE] 汇总: {summary_path}")
    return degrade_status(presence)


def main() -> int:
    return run(sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
```

（工具头部需 `import argparse, os, urllib.parse`。）

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（25 tests）

- [ ] **步骤 5：Commit**

```bash
git add scripts/inventory-legacy-platform.py scripts/test_inventory_legacy_platform.py
git commit -m "feat(迁移): 盘点工具 CLI 入口（--check 预检与三分退出码）"
```

---

### 任务 10：规格口径修正 + 全量回归 + 真库试跑指引

**文件：**
- 修改：`docs/superpowers/specs/2026-09-14-g12-legacy-inventory-design.md`（§3、§8）
- 修改：`docs/superpowers/specs/2026-09-14-g12-legacy-inventory-design.md` §4 Team 域补 `de_user_mapp` 归属

- [ ] **步骤 1：修正规格口径**

§3 中 `配套 scripts/test_inventory_legacy_platform.py（pytest 契约测试）` 改为
`配套 scripts/test_inventory_legacy_platform.py（unittest 契约测试，CI 惯例 python3 -m unittest）`。

§8 标题与正文中的 `pytest` 全部改为 `unittest`，运行命令统一为
`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`。

§4 Team 域数据源追加 `de_user_mapp`（用户映射，凭据字段指纹化），归入组织关系统计。

- [ ] **步骤 2：全量回归**

运行：`python3 -m unittest scripts/test_inventory_legacy_platform.py -v`
预期：OK（25 tests），且 `git diff --check` 干净。

运行既有相邻契约测试确认无破坏：
`python3 -m unittest scripts/test_migration_history_contract.py`
预期：`MIGRATION_HISTORY_CONTRACT_OK`

- [ ] **步骤 3：真库试跑（可选，网络可达时）**

```bash
pip3 install pymysql
export INV_DSN="mysql://pc-2ze5721848c0dbb90.rwlb.rds.aliyuncs.com:3306/inner_imp_de"
export INV_USER="..." INV_PASSWORD="..."   # 从 corp-agent application-local.yaml 取，勿写入任何文件
python3 scripts/inventory-legacy-platform.py --check   # 先预检（RDS 白名单常见失败点）
python3 scripts/inventory-legacy-platform.py           # 全量盘点
```

预期：`output/legacy-inventory-<ts>/detail/*.json` 六域齐全；`docs/inventory/<date>-legacy-inventory.md` 生成；缺失 `at_*` 表时报告显示 `table_missing` 与降级说明。凭据不写入任何文件（含 shell 历史）。

- [ ] **步骤 4：提交**

```bash
git add docs/superpowers/specs/2026-09-14-g12-legacy-inventory-design.md
git commit -m "docs(迁移): G12 盘点规格测试口径修正为 unittest 并补 de_user_mapp 归属"
```

若真库试跑成功且汇总报告内容完备，追加：

```bash
git add docs/inventory/<date>-legacy-inventory.md
git commit -m "docs(迁移): G12 旧平台资产盘点首轮汇总报告（台账镜像口径）"
```
