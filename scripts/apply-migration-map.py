#!/usr/bin/env python3
"""G12 基础配置迁移执行器：映射包草案 → 新平台注册（MCP + Skills）。

输入：output/migration-map-<ts>/detail/*.json（build-migration-map.py 产物）
目标：agentteams-java control-plane（/api/v1/mcp-servers、/api/v1/skills）

用法：
  export AGENTTEAMS_CONTROL_PLANE_URL=http://agentteams-control-plane:8080
  export AGENTTEAMS_MCP_TOKEN=<bearer>            # 鉴权开启时必填
  python3 scripts/apply-migration-map.py --dry-run            # 预览动作
  python3 scripts/apply-migration-map.py [--include-tags prod-candidate] [--probe]

幂等：MCP/Skill 均按 name 查重跳过；Idempotency-Key 确定性（legacy-mcp-<name> 等），重跑安全。
产出：output/apply-migration-<ts>/result.json（created/skipped/failed 明细）。
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_TAGS = ["prod-candidate"]


def _latest_map_dir(explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit) / "detail"
        if not p.is_dir():
            raise SystemExit(f"映射目录不存在: {p}")
        return p
    roots = sorted(Path("output").glob("migration-map-*/detail"))
    if not roots:
        raise SystemExit("未找到 output/migration-map-*/detail，先跑 build-migration-map.py")
    return roots[-1]


class Client:
    """control-plane HTTP 客户端（stdlib，Bearer 可选）。"""

    def __init__(self, base_url: str, token: str | None):
        self.base = base_url.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, body: dict | None = None,
                idem: str | None = None) -> tuple[int, dict | list | None]:
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if idem:
            headers["Idempotency-Key"] = idem
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(f"{self.base}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return resp.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return e.code, {"_raw": raw.decode("utf-8", "replace")[:300]}

    def list_names(self, path: str, name_field: str = "name") -> dict:
        """GET 列表 -> {name: id}（分页接口无、直接全量）。"""
        status, body = self.request("GET", path)
        if status != 200 or not isinstance(body, list):
            raise SystemExit(f"GET {path} 失败: {status} {body}")
        return {row.get(name_field): row.get("id") for row in body if row.get(name_field)}


def mcp_payload(draft: dict) -> dict:
    return {"name": draft["name"], "transport": draft["transport"], "endpoint": draft["endpoint"],
            "credentialRef": draft.get("credential_ref"), "enabled": True}


def skill_payload(draft: dict, visibility: str) -> dict:
    return {"name": draft["name"], "displayName": draft["display_name"] or draft["name"],
            "description": draft.get("description") or "", "visibility": visibility}


def version_payload(draft: dict) -> dict:
    """版本草案：digest 用占位内容的真实 sha256（包本体待从旧平台导出后重传覆盖）。"""
    version = draft.get("version") or "0.0.1"
    placeholder = f"legacy:{draft['name']}@{version}"
    digest = hashlib.sha256(placeholder.encode("utf-8")).hexdigest()
    label = draft.get("display_name") or draft["name"]
    description = draft.get("description") or label
    return {"version": version, "digest": f"sha256:{digest}",
            "manifest": {"name": draft["name"], "description": description, "entry": "SKILL.md",
                         "sizeBytes": 0, "source": "legacy-agentcore"},
            "visibility": "PRIVATE"}


def apply_mcps(client: Client, drafts: list[dict], *, probe: bool, dry_run: bool) -> list[dict]:
    existing = {} if dry_run else client.list_names("/api/v1/mcp-servers")
    results = []
    for d in drafts:
        name = d["name"]
        if name in existing:
            results.append({"kind": "mcp", "name": name, "status": "skipped",
                            "reason": "exists", "id": existing[name]})
            continue
        if dry_run:
            results.append({"kind": "mcp", "name": name, "status": "dry-run",
                            "action": f"POST /api/v1/mcp-servers {mcp_payload(d)}"})
            continue
        status, body = client.request("POST", "/api/v1/mcp-servers", mcp_payload(d), idem=f"legacy-mcp-{name}")
        row = {"kind": "mcp", "name": name, "status": "created" if status in (200, 201) else "failed",
               "http": status, "id": body.get("id") if isinstance(body, dict) else None, "resp": body}
        if row["status"] == "created" and probe and row["id"]:
            pstatus, pbody = client.request("POST", f"/api/v1/mcp-servers/{row['id']}/connection-test")
            row["probe"] = {"http": pstatus, "result": pbody}
        results.append(row)
    return results


def apply_skills(client: Client, drafts: list[dict], *, visibility: str, dry_run: bool) -> list[dict]:
    existing = {} if dry_run else client.list_names("/api/v1/skills")
    results = []
    for d in drafts:
        name = d["name"]
        vp = version_payload(d)
        if name in existing:
            # skill 已存在：版本级补齐（02:06 首轮版本注册曾因可见性 403，不可只按 name 跳过）
            sid = existing[name]
            if dry_run:
                results.append({"kind": "skill", "name": name, "status": "dry-run",
                                "action": f"PATCH 版本 {vp}"})
                continue
            vst, vlist = client.request("GET", f"/api/v1/skills/{sid}/versions")
            have = {v.get("version") for v in (vlist or [])} if vst == 200 and isinstance(vlist, list) else set()
            if vp["version"] in have:
                results.append({"kind": "skill", "name": name, "status": "skipped",
                                "reason": "version-exists", "id": sid})
                continue
            vst2, vbody = client.request("POST", f"/api/v1/skills/{sid}/versions", vp,
                                          idem=f"legacy-skill-{name}-version")
            results.append({"kind": "skill", "name": name,
                            "status": "created" if vst2 in (200, 201) else "failed",
                            "id": sid, "version_http": vst2, "version_resp": vbody})
            continue
        if dry_run:
            results.append({"kind": "skill", "name": name, "status": "dry-run",
                            "action": f"POST /api/v1/skills {skill_payload(d, visibility)} + version {version_payload(d)}"})
            continue
        status, body = client.request("POST", "/api/v1/skills", skill_payload(d, visibility),
                                      idem=f"legacy-skill-{name}")
        if status not in (200, 201):
            results.append({"kind": "skill", "name": name, "status": "failed", "http": status, "resp": body})
            continue
        sid = body.get("id")
        vstatus, vbody = client.request("POST", f"/api/v1/skills/{sid}/versions", vp,
                                        idem=f"legacy-skill-{name}-version")
        results.append({"kind": "skill", "name": name, "status": "created" if vstatus in (200, 201) else "failed",
                        "id": sid, "version_http": vstatus, "version_resp": vbody})
    return results


def main(argv: list[str]) -> int:
    args = list(argv)
    dry_run = "--apply" not in args
    probe = "--probe" in args
    map_dir = None
    visibility = "PUBLIC"
    if "--map-dir" in args:
        map_dir = args[args.index("--map-dir") + 1]
    if "--skill-visibility" in args:
        visibility = args[args.index("--skill-visibility") + 1]
    tags = DEFAULT_TAGS
    if "--include-tags" in args:
        tags = args[args.index("--include-tags") + 1].split(",")

    detail = _latest_map_dir(map_dir)
    base_url = os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "")
    if not dry_run and not base_url:
        raise SystemExit("--apply 需要 AGENTTEAMS_CONTROL_PLANE_URL")
    token = os.environ.get("AGENTTEAMS_MCP_TOKEN") or None
    client = Client(base_url or "http://dry-run.invalid", token)

    def tag_filter(rows: list[dict], key: str = "env_tag") -> list[dict]:
        return [r for r in rows if r.get(key) in tags]

    mcps = tag_filter(json.loads((detail / "mcp-register-drafts.json").read_text(encoding="utf-8")))
    skills = json.loads((detail / "skill-register-drafts.json").read_text(encoding="utf-8"))

    print(f"[PLAN] tags={tags} dry_run={dry_run} target={base_url or '(dry-run)'}")
    print(f"[PLAN] MCP {len(mcps)} 个（{[m['name'] for m in mcps]}）")
    print(f"[PLAN] Skills {len(skills)} 个（活跃 {sum(1 for s in skills if s['in_use'])}）")

    results = apply_mcps(client, mcps, probe=probe, dry_run=dry_run)
    results += apply_skills(client, skills, visibility=visibility, dry_run=dry_run)

    out_root = Path("output") / f"apply-migration-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    if not dry_run:
        out_root.mkdir(parents=True, exist_ok=True)
        (out_root / "result.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    stat: dict[str, int] = {}
    for r in results:
        stat[r["status"]] = stat.get(r["status"], 0) + 1
    print(f"[RESULT] {stat}")
    for r in results:
        if r["status"] == "failed":
            print(f"  FAIL {r['kind']} {r['name']}: {r.get('http')} {json.dumps(r.get('resp'))[:200]}")
    if dry_run:
        print("[HINT] 预览模式；确认后加 --apply 执行（需 AGENTTEAMS_CONTROL_PLANE_URL[/AGENTTEAMS_MCP_TOKEN]）")
    else:
        print(f"[DONE] 结果: {out_root}/result.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
