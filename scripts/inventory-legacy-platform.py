#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
连接契约：conn 须以 pymysql.connect(..., cursorclass=pymysql.cursors.DictCursor) 创建，
所有采集统一按字典行处理（批次 D CLI 建连时落实）。
序列化契约：rows/samples 可能含 datetime，批次 D CLI 落盘须 json.dumps(..., default=str)。
产出：output/legacy-inventory-<ts>/detail/*.json（明细，gitignore）
      docs/inventory/<date>-legacy-inventory.md（汇总，入库，脱敏）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.parse

import yaml
from datetime import datetime, timezone
from pathlib import Path

# 退出码：0=正常完成，1=降级完成（存在表缺失），2=连接失败
EXIT_OK = 0
EXIT_DEGRADED = 1
EXIT_CONN_FAIL = 2

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

    def _fp(v):
        if isinstance(v, bytes):  # BLOB/VARBINARY 类列 pymysql 返回 bytes
            v = v.decode("utf-8", "replace")
        return fingerprint(v)

    return {k: (_fp(v) if k in sensitive and isinstance(v, (str, bytes)) and v else v)
            for k, v in row.items()}


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


INVENTORY_TABLES = [
    "at_team", "at_worker", "at_mcp_server", "at_service_endpoint",
    "de_worker", "de_team", "de_team_worker_rel", "de_team_crew_rel",
    "de_user_mapp", "de_task", "de_task_rslt", "de_chat_convo", "de_chat_msg",
]

PROBE_SQL = ("SELECT table_name FROM information_schema.tables "
             "WHERE table_schema = DATABASE() AND table_name IN (%s"
             + ", %s" * (len(INVENTORY_TABLES) - 1) + ")")


def probe_tables(conn) -> dict[str, bool]:
    """逐表探测 information_schema；返回 {表名: 是否存在}。"""
    with conn.cursor() as cur:
        cur.execute(PROBE_SQL, tuple(INVENTORY_TABLES))
        existing = {row["table_name"] for row in cur.fetchall()}
    return {t: t in existing for t in INVENTORY_TABLES}


def degrade_status(presence: dict[str, bool]) -> int:
    return EXIT_OK if all(presence.values()) else EXIT_DEGRADED


MISSING_NOTE = ("at_* 平台镜像表缺失：corp-agent 当前 agentteams.gateway.impl=remote"
                "（纯远程透传，从未落库）。资源清单以 de_* 业务台账为准，"
                "平台侧真实状态需 OpenAPI 对账（后续阶段）。")


def _fetch(conn, sql: str, params=None) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(sql, params)  # pymysql args=None 等价无参数化
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
    # 0827 DDL 不在仓库，name 可空性未核验：过滤空名后再差集，防 None/str 混排 TypeError
    at_names = [r["name"] for r in at_rows if r["name"]]
    de_names = [r["worker_name"] for r in de_rows if r["worker_name"]]
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
    # tgt_user_pwd 有意不 SELECT（最小列原则）；mask_row 按 SENSITIVE_FIELDS 表级兜底
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
        samples[table] = _fetch(conn, sql, (sample_limit,))
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
    """三态映射评估：种子表 + 运行期发现（当前仅种子，OpenAPI 对账阶段扩展）。

    domains 为运行期对账结果预留；strategy 合法性由 TestMappings 守护，
    接入运行期发现后未知策略需重新决策（fail-fast 或计入 other）。"""
    rows = [dict(m) for m in SEED_MAPPINGS]
    stats = {"adopt": 0, "adapt": 0, "drop": 0, "total": len(rows)}
    for m in rows:
        stats[m["strategy"]] += 1
    return {"domain": "legacy-to-new-mapping", "status": "ok",
            "rows": rows, "stats": stats, "notes": []}


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
