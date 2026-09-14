#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
产出：output/legacy-inventory-<ts>/detail/*.json（明细，gitignore）
      docs/inventory/<date>-legacy-inventory.md（汇总，入库，脱敏）。
"""

from __future__ import annotations

import hashlib

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
