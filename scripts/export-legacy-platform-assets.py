#!/usr/bin/env python3
"""G12 配置迁移素材导出器：阿里云平台侧资产 OpenAPI 全量拉取（只读）。

数据流：ALIBABA_CLOUD_* 环境变量（STS 凭据链）→ ListMcps/ListWorkers+GetWorker/
ListTeams/ListServiceEndpoints/ListModels/ListModelProviders → output/platform-export-<ts>/detail/*.json。

明细层全量落 output/（gitignore）；stdout 摘要只含 id/name/数量，敏感值
（mcp_server_config/api_key/soul/agents 正文）不回显。
用法：source .local/aliyun-credentials.env && python3 scripts/export-legacy-platform-assets.py
"""
from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from alibabacloud_agentteams20260605 import models as m
from alibabacloud_agentteams20260605.client import Client as AgentTeamsClient
from alibabacloud_credentials.client import Client as CredClient
from alibabacloud_tea_openapi import models as open_api_models

INSTANCE_ID = "at-cn-4jg4w7zdk01"
ENDPOINT = "agentteams.cn-beijing.aliyuncs.com"
PAGE = 50


def _client() -> AgentTeamsClient:
    config = open_api_models.Config(credential=CredClient())
    config.endpoint = ENDPOINT
    config.read_timeout = 60000
    config.connect_timeout = 15000
    return AgentTeamsClient(config)


def _paged(call, key: str) -> list[dict]:
    """通用分页：call(next_token)->response，取 response.body.<key> 迭代拼接。"""
    rows, token = [], None
    while True:
        resp = call(token)
        body = resp.body
        rows.extend(_todict(r) for r in (getattr(body, key) or []))
        token = getattr(body, "next_token", None)
        if not token:
            return rows


def _snake(key: str) -> str:
    """PascalCase/camelCase -> snake_case（to_map 保留 API 原始字段名）。"""
    out = []
    for i, ch in enumerate(key):
        if ch.isupper() and i > 0 and (not key[i - 1].isupper() or (i + 1 < len(key) and key[i + 1].islower())):
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def _norm(value):
    if isinstance(value, dict):
        return {_snake(k): _norm(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_norm(v) for v in value]
    return value


def _todict(obj):
    """darabonba 模型 → 蛇形键纯 dict（键名规范化，值递归）。"""
    if hasattr(obj, "to_map"):
        return _norm(obj.to_map())
    if isinstance(obj, dict):
        return _norm(obj)
    return {k: _norm(v) for k, v in vars(obj).items() if not k.startswith("_")}


def _get_worker_full(client, name: str) -> dict:
    resp = client.get_worker(m.GetWorkerRequest(instance_id=INSTANCE_ID, name=name))
    return _todict(resp.body.data)


def main() -> int:
    out_root = Path("output") / f"platform-export-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    detail = out_root / "detail"
    detail.mkdir(parents=True, exist_ok=True)
    client = _client()

    # 1) MCP 全量（含 mcp_server_config 敏感字段，仅落 detail）
    mcps = _paged(lambda t: client.list_mcps(
        m.ListMcpsRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items")
    (detail / "mcps.json").write_text(
        json.dumps(mcps, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"[mcps] {len(mcps)} 条 -> detail/mcps.json")

    # 2) Workers：分页摘要 + 逐个 GetWorker 全量（soul/agents/skills/mcp 绑定）
    summaries = _paged(lambda t: client.list_workers(
        m.ListWorkersRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items")
    workers = []
    for i, s in enumerate(summaries):
        try:
            full = _get_worker_full(client, s["name"])
        except Exception as exc:  # 单个失败不阻断，留痕
            full = {"name": s["name"], "error": f"{type(exc).__name__}: {exc}"}
        workers.append({"summary": s, "full": full})
        time.sleep(0.1)
        if (i + 1) % 20 == 0:
            print(f"[workers] {i + 1}/{len(summaries)} ...")
    (detail / "workers.json").write_text(
        json.dumps(workers, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"[workers] {len(workers)} 条 -> detail/workers.json")

    # 3) Teams / Endpoints / Models / ModelProviders（端点与模型是迁移映射素材）
    for label, fn, key in (
        ("teams", lambda t: client.list_teams(
            m.ListTeamsRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items"),
        ("endpoints", lambda t: client.list_service_endpoints(
            m.ListServiceEndpointsRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items"),
        ("models", lambda t: client.list_models(
            m.ListModelsRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items"),
        ("model_providers", lambda t: client.list_model_providers(
            m.ListModelProvidersRequest(instance_id=INSTANCE_ID, max_results=PAGE, next_token=t)), "items"),
    ):
        try:
            rows = _paged(fn, key)
            (detail / f"{label}.json").write_text(
                json.dumps(rows, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
            print(f"[{label}] {len(rows)} 条 -> detail/{label}.json")
        except Exception as exc:
            print(f"[{label}] 拉取失败（不阻断）：{type(exc).__name__}: {exc}")

    # 4) Skills 聚合对账：worker 全量配置里的 skills 去重 → 与 yaml 已知 9 个对账
    yaml_known = {"customer-discovery", "render-company-list", "render-customer-insight-report",
                  "resolve-company-identity", "industry-research", "risk-mcp-operations",
                  "corp_skill_supply_recommend", "strategy-policy-match", "markdown-to-pdf"}
    api_skills: dict[str, dict] = {}
    for w in workers:
        full = w.get("full") or {}
        for s in (full.get("skills") or []):
            d = _todict(s) if not isinstance(s, dict) else s
            if d.get("name"):
                api_skills.setdefault(d["name"], d)
    aggregate = {
        "api_skill_count": len(api_skills),
        "api_skills": sorted(api_skills.values(), key=lambda x: x["name"]),
        "yaml_only": sorted(yaml_known - set(api_skills)),
        "api_only": sorted(set(api_skills) - yaml_known),
    }
    (detail / "skills-aggregate.json").write_text(
        json.dumps(aggregate, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"[skills] API 侧去重 {len(api_skills)} 个（yaml 9 个对账：仅yaml={len(aggregate['yaml_only'])} 仅API={len(aggregate['api_only'])}）")

    print(f"[DONE] 导出根目录：{out_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
