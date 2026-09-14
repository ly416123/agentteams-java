#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
连接契约：conn 须以 pymysql.connect(..., cursorclass=pymysql.cursors.DictCursor) 创建，
所有采集统一按字典行处理（批次 D CLI 建连时落实）。
产出：output/legacy-inventory-<ts>/detail/*.json（明细，gitignore）
      docs/inventory/<date>-legacy-inventory.md（汇总，入库，脱敏）。
"""

from __future__ import annotations

import hashlib

import yaml

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
    return {k: (fingerprint(v) if k in sensitive and isinstance(v, str) and v else v)
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
