#!/usr/bin/env python3
"""G12 配置迁移映射包：平台侧导出明细 → 新平台注册草案（脱敏汇总入库）。

输入：output/platform-export-<ts>/detail/*.json（export-legacy-platform-assets.py 产物）
输出：output/migration-map-<ts>/detail/*.json（注册草案，gitignore）
      docs/inventory/<date>-legacy-asset-migration-map.md（入库，无敏感值）

映射口径（对齐新平台 V17/V18 迁移）：
- mcp_server_config.url -> mcp_servers.endpoint；headers/auth -> credential_ref 建议（值不出 detail）
- protocol -> transport（SSE 直映；其余 STREAMABLE_HTTP 并标记待核）
- skill(name,label,version) -> skills(name,display_name=label,description=label) + skill_versions
- model_providers(address,protocols) -> model-providers(providerType=openai-compatible)；api_keys 导出已脱敏 -> credential_ref 占位
- models + worker 引用并集 -> /model-providers/{id}/models（modelId=name，AgentSpec 引用键）
- worker(full) -> agent-specs（runtime=qwenpaw；soul/agents 内嵌 spec.legacy 保真，spec ≤64KB 已核）
- 模板从引用 worker 的 soul/agents 实例反推（引用关系保留）
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

YAML_ONLY_SKILLS = [
    {"name": "render-company-list", "version": "2.2.0",
     "label": "基于 sales-find-potential-customers 返回的潜客数据生成 Markdown 企业清单报告"},
    {"name": "render-customer-insight-report", "version": "2.0.0",
     "label": "将 sales-get-customer-insights 返回的企业画像映射为 Markdown 客户洞察报告"},
    {"name": "resolve-company-identity", "version": "2.0.0",
     "label": "企业名称解析为标准身份（yaml 配置驱动 skill）"},
]


def fp(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:8]


def env_tag(name: str) -> str:
    low = name.lower()
    if "-uat" in low or "10010-uat" in low or "10004-uat" in low or "10002-uat" in low:
        return "uat"
    if re.search(r"-(v\d+)$", low) or re.search(r"\d+-v\d+$", low):
        return "variant"
    if "test" in low:
        return "test"
    if low.endswith("-dev") or "-dev-" in low:
        return "dev"
    return "prod-candidate"


def transport_of(protocol: str | None) -> tuple[str, bool]:
    """旧 protocol -> (新 transport, 是否需人工复核)。"""
    p = (protocol or "").upper().replace("_", "")
    if "STREAMABLE" in p:
        return "STREAMABLE_HTTP", False
    if "SSE" in p:
        return "SSE", False
    return "STREAMABLE_HTTP", True


def parse_config(raw) -> dict:
    """mcp_server_config 三态解析：JSON 直解 / base64(YAML) / 兜底截断。"""
    import base64
    import yaml
    if not raw or not isinstance(raw, str):
        return {}
    try:
        return json.loads(raw) or {}
    except json.JSONDecodeError:
        pass
    try:
        return yaml.safe_load(base64.b64decode(raw, validate=True).decode("utf-8")) or {}
    except Exception:
        return {"_raw": raw[:200]}


def latest_export() -> Path:
    roots = sorted(Path("output").glob("platform-export-*/detail"))
    if not roots:
        raise SystemExit("未找到 output/platform-export-*/detail，先跑 export-legacy-platform-assets.py")
    return roots[-1]


SENSITIVE_KEYS = {"headers", "authorization", "api_key", "token", "auth",
                  "securitySchemes", "defaultUpstreamSecurity"}


def _flatten_keys(d, depth: int = 0) -> set:
    if depth > 4 or not isinstance(d, dict):
        return set()
    keys = set(d.keys())
    for v in d.values():
        keys |= _flatten_keys(v, depth + 1)
    return keys


def build_mcps(detail: Path) -> list[dict]:
    rows = json.loads((detail / "mcps.json").read_text(encoding="utf-8"))
    drafts = []
    for m in rows:
        name = m.get("name") or ""
        config = parse_config(m.get("mcp_server_config"))
        transport, needs_review = transport_of(m.get("protocol"))
        addresses = m.get("addresses") or []
        sensitive = bool(_flatten_keys(config) & SENSITIVE_KEYS)
        drafts.append({
            "legacy_id": m.get("id"), "name": name, "env_tag": env_tag(name),
            "transport": transport, "endpoint": addresses[0] if addresses else config.get("mcpServerURL") or config.get("url"),
            "credential_ref": f"legacy-{name}" if sensitive else None,
            "transport_needs_review": needs_review,
            "description": m.get("description"), "protocol_legacy": m.get("protocol"),
            "create_type": m.get("create_type"), "deploy_status": m.get("deploy_status"),
            "legacy_config": config,
        })
    return drafts


def build_skills(detail: Path) -> list[dict]:
    agg = json.loads((detail / "skills-aggregate.json").read_text(encoding="utf-8"))
    drafts, seen = [], set()
    for s in agg["api_skills"]:
        seen.add(s["name"])
        drafts.append({"name": s["name"], "display_name": s.get("label") or s["name"],
                       "description": s.get("label") or "", "version": s.get("version"),
                       "source": "worker-binding", "in_use": True})
    for s in YAML_ONLY_SKILLS:
        if s["name"] not in seen:
            drafts.append({"name": s["name"], "display_name": s["name"],
                           "description": s["label"], "version": s["version"],
                           "source": "onboard-yaml", "in_use": False})
    return drafts


def build_templates(detail: Path) -> list[dict]:
    workers = json.loads((detail / "workers.json").read_text(encoding="utf-8"))
    by_tpl: dict[tuple, dict] = {}
    for w in workers:
        full = w.get("full") or {}
        tpl = full.get("template") or {}
        if not tpl.get("name"):
            continue
        key = (tpl["name"], tpl.get("version"))
        e = by_tpl.setdefault(key, {"template_name": tpl["name"], "template_version": tpl.get("version"),
                                    "referenced_by": [], "instance_example": None})
        e["referenced_by"].append(full.get("name"))
        if e["instance_example"] is None:
            e["instance_example"] = {
                "soul_fingerprint": fp(full.get("soul") or ""), "soul_chars": len(full.get("soul") or ""),
                "agents_fingerprint": fp(full.get("agents") or ""), "agents_chars": len(full.get("agents") or ""),
                "model": full.get("model"), "skills": [s.get("name") for s in (full.get("skills") or [])],
                "mcp_servers": [x.get("name") for x in (full.get("mcp_servers") or [])],
                "_soul_raw": full.get("soul"), "_agents_raw": full.get("agents"),
            }
    return list(by_tpl.values())


def build_model_providers(detail: Path) -> list[dict]:
    rows = json.loads((detail / "model_providers.json").read_text(encoding="utf-8"))
    drafts = []
    for p in rows:
        name = p.get("name") or ""
        drafts.append({
            "name": name, "provider_type": "openai-compatible", "endpoint": p.get("address"),
            # 旧平台 api_keys 导出即脱敏（***），凭据值属迁移决策项：credential_ref 占位
            "credential_ref": f"legacy-modelprov-{name}",
            "legacy_provider": p.get("provider"), "protocols": p.get("protocols") or [],
            "deploy_status": p.get("deploy_status"), "legacy_id": p.get("id"),
        })
    return drafts


def build_models(detail: Path) -> list[dict]:
    """模型目录（models.json）与 worker 实际引用取并集；worker 引用未注册模型时
    按 provider 兜底归档（空 provider 归 default）——AgentSpec 校验按 (provider, modelId) 查。"""
    rows = json.loads((detail / "models.json").read_text(encoding="utf-8"))
    by_key: dict[tuple, dict] = {}
    for m in rows:
        pname = m.get("provider_name") or "default"
        mname = m.get("name")
        if not mname:
            continue
        by_key[(pname, mname)] = {"name": mname, "model_id": mname, "provider_name": pname,
                                  "source": "model-catalog", "in_use_by_workers": 0}
    for w in json.loads((detail / "workers.json").read_text(encoding="utf-8")):
        model = (w.get("full") or {}).get("model") or {}
        mname = model.get("model_name")
        if not mname:
            continue
        key = (model.get("model_provider") or "default", mname)
        if key not in by_key:
            by_key[key] = {"name": mname, "model_id": mname, "provider_name": key[0],
                           "source": "worker-reference", "in_use_by_workers": 0}
        by_key[key]["in_use_by_workers"] += 1
    return list(by_key.values())


def agent_spec_registration(full: dict) -> dict:
    """worker full -> POST /api/v1/agent-specs 请求体（runtime 同值直映 qwenpaw）。"""
    model = full.get("model") or {}
    provider = model.get("model_provider") or "default"
    model_name = model.get("model_name") or ""
    role = "EXECUTOR"
    team = None
    for g in (full.get("groups") or []):
        if not team:
            team = g.get("name")
        if (g.get("role") or "").lower() == "leader":
            role = "LEADER"
    spec = {
        "modelRef": {"provider": provider, "model": model_name},
        "skillRefs": [s.get("name") for s in (full.get("skills") or []) if s.get("name")],
        "mcpRefs": [m.get("name") for m in (full.get("mcp_servers") or []) if m.get("name")],
        # soul/agents 为人格与技能路由 prompt 正文，64KB 限内（导出最大 ~24KB）
        "legacy": {"soul": full.get("soul") or "", "agents": full.get("agents") or "",
                   "template": full.get("template"), "agentType": full.get("agent_type"),
                   "versionCode": full.get("version_code"), "subagents": full.get("subagents"),
                   "channels": full.get("channels"), "credentials": full.get("credentials"),
                   "deployType": full.get("deploy_type"), "legacyStatus": full.get("status")},
    }
    return {"name": full.get("name"), "runtime": "qwenpaw", "workerType": role,
            "modelProvider": provider, "modelName": model_name, "teamRef": team,
            "desiredState": "RUNNING" if (full.get("status") or "").lower() == "running" else "STOPPED",
            "spec": spec}


def build_workers(detail: Path) -> list[dict]:
    workers = json.loads((detail / "workers.json").read_text(encoding="utf-8"))
    drafts = []
    for w in workers:
        full = w.get("full") or {}
        reg = agent_spec_registration(full)
        drafts.append({
            "name": full.get("name") or (w.get("summary") or {}).get("name"),
            "status": full.get("status"), "agent_type": full.get("agent_type"),
            "deploy_type": full.get("deploy_type"), "model": full.get("model"),
            "template": full.get("template"),
            "groups": [g.get("name") for g in (full.get("groups") or [])],
            "mcp_servers": [x.get("name") for x in (full.get("mcp_servers") or [])],
            "skills": [s.get("name") for s in (full.get("skills") or [])],
            "soul_fingerprint": fp(full.get("soul") or ""), "agents_fingerprint": fp(full.get("agents") or ""),
            "soul_chars": len(full.get("soul") or ""), "agents_chars": len(full.get("agents") or ""),
            "worker_type": reg["workerType"], "team_ref": reg["teamRef"],
            "desired_state": reg["desiredState"],
            "registration": reg,
            "spec_bytes": len(json.dumps(reg["spec"], ensure_ascii=False).encode("utf-8")),
        })
    return drafts


def write_summary(path: Path, mcps: list, skills: list, templates: list, workers: list,
                  providers: list, models: list) -> None:
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    tags: dict[str, int] = {}
    for m in mcps:
        tags[m["env_tag"]] = tags.get(m["env_tag"], 0) + 1
    lines = [
        f"# 旧平台基础配置迁移映射（{date}）", "",
        "- 来源：OpenAPI 全量导出（output/platform-export-*/detail，gitignore）",
        "- 敏感性：本报告不含 mcp_server_config / soul / agents 正文；草案明细在 output/migration-map-*/detail",
        "", "## MCP 注册草案（mcp_servers 映射）", "",
        "| 名称 | 环境标记 | transport | endpoint | credential_ref | 待复核 |",
        "|---|---|---|---|---|---|",
    ]
    for m in mcps:
        lines.append(f"| {m['name']} | {m['env_tag']} | {m['transport']} | {m['endpoint'] or '（config 无 url，需人工）'} "
                     f"| {m['credential_ref'] or '-'} | {'是' if m['transport_needs_review'] else '否'} |")
    lines += ["", f"环境标记统计：{tags}", "", "## Skill 注册草案（skills/skill_versions 映射）", "",
              "| 名称 | 版本 | 来源 | 活跃绑定 |", "|---|---|---|---|"]
    for s in skills:
        lines.append(f"| {s['name']} | {s.get('version') or '-'} | {s['source']} | {'是' if s['in_use'] else '否'} |")
    lines += ["", "## Agent 模板反推（4 个在用）", ""]
    for t in templates:
        e = t["instance_example"]
        lines.append(f"- `{t['template_name']}@{t['template_version']}`（引用 {len(t['referenced_by'])} 个 worker："
                     f"{', '.join(t['referenced_by'])}）—— soul {e['soul_chars']} 字/agents {e['agents_chars']} 字"
                     f"（指纹 {e['soul_fingerprint']}/{e['agents_fingerprint']}），模型 {e['model']}，"
                     f"绑定 MCP {len(e['mcp_servers'])}、skill {len(e['skills'])}")
    lines += ["", f"## Worker → AgentSpec 草案：{len(workers)} 个（明细见 output/migration-map-*/detail）", "",
              f"- workerType：LEADER {sum(1 for w in workers if w['worker_type'] == 'LEADER')} / "
              f"EXECUTOR {sum(1 for w in workers if w['worker_type'] == 'EXECUTOR')}；"
              f"desiredState RUNNING {sum(1 for w in workers if w['desired_state'] == 'RUNNING')} / "
              f"STOPPED {sum(1 for w in workers if w['desired_state'] == 'STOPPED')}",
              f"- spec 体积：max {max((w['spec_bytes'] for w in workers), default=0)} bytes"
              f"（限 64KB，超限 {sum(1 for w in workers if w['spec_bytes'] > 64 * 1024)} 个）",
              "", "## Model Provider / Model 注册草案", "",
              "| Provider | 类型 | endpoint | credential_ref |", "|---|---|---|---|"]
    for p in providers:
        lines.append(f"| {p['name']} | {p['provider_type']} | {p['endpoint']} | {p['credential_ref']} |")
    lines += ["", "| Model | Provider | 来源 | worker 引用 |", "|---|---|---|---|"]
    for m in sorted(models, key=lambda x: (x["provider_name"], -x["in_use_by_workers"])):
        lines.append(f"| {m['name']} | {m['provider_name']} | {m['source']} | {m['in_use_by_workers']} |")
    lines += ["", "## 待办决策", "",
              "- [ ] MCP 22 个中哪些生产迁移（环境标记已打：uat/variant/test 默认不迁）",
              "- [ ] transport 兼容性复核（非 SSE protocol 标记 STREAMABLE_HTTP 待核）",
              "- [ ] 模板实例反推 vs 控制台模板中心定义（若有出入以控制台为准）",
              "- [ ] model provider 凭据值（api_keys 导出已脱敏，需控制台重取后入 credential store）",
              "- [ ] AgentSpec 发布（publish）时机：需 MCP/skill 引用在新平台可见后执行，当前保持 DRAFT"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    detail = latest_export()
    out_root = Path("output") / f"migration-map-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    out_detail = out_root / "detail"
    out_detail.mkdir(parents=True, exist_ok=True)

    mcps = build_mcps(detail)
    skills = build_skills(detail)
    templates = build_templates(detail)
    workers = build_workers(detail)
    providers = build_model_providers(detail)
    models = build_models(detail)

    for name, rows in (("mcp-register-drafts.json", mcps), ("skill-register-drafts.json", skills),
                       ("model-provider-drafts.json", providers), ("model-register-drafts.json", models),
                       ("template-extracts.json", templates), ("worker-agent-spec-drafts.json", workers)):
        (out_detail / name).write_text(json.dumps(rows, ensure_ascii=False, indent=1, default=str),
                                       encoding="utf-8")

    docs = Path("docs/inventory")
    docs.mkdir(parents=True, exist_ok=True)
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    write_summary(docs / f"{date}-legacy-asset-migration-map.md", mcps, skills, templates, workers,
                  providers, models)

    print(f"[mcps] {len(mcps)}（标记 {({t: sum(1 for m in mcps if m['env_tag']==t) for t in set(m['env_tag'] for m in mcps)})}）")
    print(f"[skills] {len(skills)}（活跃 {sum(1 for s in skills if s['in_use'])}）")
    print(f"[providers] {len(providers)}；[models] {len(models)}（worker 引用补齐 "
          f"{sum(1 for m in models if m['source'] == 'worker-reference')}）")
    print(f"[templates] {len(templates)}；[workers] {len(workers)}（"
          f"LEADER {sum(1 for w in workers if w['worker_type'] == 'LEADER')}）")
    print(f"[DONE] 草案：{out_root}/detail；汇总：docs/inventory/{date}-legacy-asset-migration-map.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
