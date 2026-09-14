#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
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
