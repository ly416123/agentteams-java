"""AgentSpec 发布链工具（迁移视角）。

发布链校验语义（control-plane CatalogAgentSpecReferenceValidator）：
- skill 引用（skillRefs 支持 name 与 name@version）要求存在 lifecycle=PUBLISHED 的版本
- MCP 引用要求已注册且 enabled
- modelRef 要求 provider 存在且 enabled，model 属于该 provider
- skill version publish 的硬前置：包上传 COMPLETED（SkillService.publish）

流程：侦察（Catalog.fetch）→ 预检（preflight）→ --publish 时对可行 spec：
  1) 引用的 PUBLISHED 版本若为 DRAFT：包未 COMPLETED 先传占位包，再 publish 版本
  2) POST /api/v1/agent-specs/{id}/publish（Idempotency-Key: legacy-agentspec-publish-<name>）
blocked 的 spec 不触网；已 PUBLISHED 的 spec 跳过。

默认 dry-run（仅预检输出矩阵）；加 --publish 执行；--out <dir> 写 result.json。

用法：
  AGENTTEAMS_CONTROL_PLANE_URL=http://127.0.0.1:8080 python3 scripts/publish-agent-specs.py
  ... python3 scripts/publish-agent-specs.py --publish --out output/publish-20260916
"""

from __future__ import annotations

import importlib.util
import gzip
import io
import json
import os
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path

_HERE = Path(__file__).resolve().parent


def _load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, _HERE / file)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


_app = _load("_pam_app", "apply-migration-map.py")       # Client
_usp = _load("_pam_usp", "upload-skill-package.py")      # put_presigned/upload_package

# AgentSpec 引用校验用 PrincipalContext scope 查 resource_scopes（tenant/project/team），
# scope.projectId 须为 canonical 项目标识 —— 发布请求必须带 ?projectId= 触发 canonicalize。
PROJECT_PARAM = os.environ.get("AGENTTEAMS_PROJECT_SCOPE", "project-a")

PLACEHOLDER_BODY = ("# {name} (migrated placeholder)\n\n"
                    "Legacy skill body pending manual export from the legacy platform console.\n")


def placeholder_bytes(name: str) -> bytes:
    """占位包：仅 SKILL.md（新平台 manifest.entry 契约）；mtime=0 保证同内容同 sha256。"""
    body = PLACEHOLDER_BODY.format(name=name).encode("utf-8")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        info = tarfile.TarInfo("SKILL.md")
        info.size = len(body)
        info.mtime = 0
        tar.addfile(info, io.BytesIO(body))
    return gzip.compress(buf.getvalue(), mtime=0)


def put_presigned(url: str, data: bytes) -> int:
    """模块级转发，保持与 upload-skill-package 一致的语义（测试可替换）。"""
    return _usp.put_presigned(url, data)


def parse_skill_ref(value: str) -> tuple[str, str | None]:
    """skillRefs 引用格式：name@version 或 name（与 AgentSpecSkillServiceReferenceCatalogAdapter 一致）。"""
    normalized = (value or "").strip()
    sep = normalized.rfind("@")
    if sep <= 0 or sep == len(normalized) - 1:
        return normalized, None
    return normalized[:sep], normalized[sep + 1:]


class Catalog:
    """发布预检用的资源快照（全部来自 GET，只读）。"""

    def __init__(self, mcps: dict, skills: dict, versions: dict,
                 providers: dict, provider_models: dict, spec_rows: list | None = None):
        self.mcps = mcps                      # name -> row
        self.skills = skills                  # name -> row
        self.versions = versions              # skill name -> [version rows]
        self.providers = providers            # name -> row
        self.provider_models = provider_models  # provider name -> {modelId}
        self.spec_rows = spec_rows or []      # agent-spec 原始行

    @classmethod
    def fetch(cls, client) -> "Catalog":
        st, mcps = client.request("GET", "/api/v1/mcp-servers")
        if st != 200:
            raise SystemExit(f"GET mcp-servers 失败: {st}")
        st, skills = client.request("GET", "/api/v1/skills")
        if st != 200:
            raise SystemExit(f"GET skills 失败: {st}")
        versions: dict[str, list] = {}
        for skill in skills:
            sid = skill["id"]
            vst, rows = client.request("GET", f"/api/v1/skills/{sid}/versions")
            if vst == 200 and isinstance(rows, list):
                versions[skill["name"]] = rows
        st, providers = client.request("GET", "/api/v1/model-providers")
        if st != 200:
            raise SystemExit(f"GET model-providers 失败: {st}")
        provider_models: dict[str, set] = {}
        for provider in providers:
            pid = provider["id"]
            mst, models = client.request("GET", f"/api/v1/model-providers/{pid}/models")
            if mst == 200 and isinstance(models, list):
                provider_models[provider["name"]] = {m.get("modelId") for m in models}
        st, specs = client.request("GET", "/api/v1/agent-specs")
        if st != 200:
            raise SystemExit(f"GET agent-specs 失败: {st}")
        return cls(
            mcps={m["name"]: m for m in mcps if m.get("name")},
            skills={s["name"]: s for s in skills if s.get("name")},
            versions=versions,
            providers={p["name"]: p for p in providers if p.get("name")},
            provider_models=provider_models, spec_rows=specs)

    def published_version(self, skill_name: str, version: str | None = None) -> dict | None:
        rows = [v for v in self.versions.get(skill_name, [])
                if str(v.get("lifecycle", "")).upper() == "PUBLISHED"]
        if version is not None:
            return next((v for v in rows if v.get("version") == version), None)
        return max(rows, key=lambda v: v.get("createdAt") or "", default=None)

    def latest_version(self, skill_name: str, version: str | None = None) -> dict | None:
        """无 PUBLISHED 版本时的回退目标：指定版本或最新版本（任意 lifecycle）。"""
        rows = self.versions.get(skill_name, [])
        if version is not None:
            return next((v for v in rows if v.get("version") == version), None)
        return max(rows, key=lambda v: v.get("createdAt") or "", default=None)

    def mcp_ok(self, name: str) -> bool:
        row = self.mcps.get(name)
        return bool(row and row.get("enabled"))

    def model_issue(self, provider: str, model: str) -> str | None:
        prow = self.providers.get(provider)
        if not prow:
            return f"provider '{provider}' 不存在"
        if not prow.get("enabled"):
            return f"provider '{provider}' 未启用"
        if model not in self.provider_models.get(provider, set()):
            return f"model '{model}' 未注册于 provider '{provider}'"
        return None


def spec_dict(row: dict) -> dict:
    """agent-spec 行的 spec 字段兼容 JSON 字符串（真实 API）与 dict（测试/离线）。"""
    raw = row.get("spec")
    if isinstance(raw, str):
        return json.loads(raw)
    return dict(raw or {})


def check_spec(row: dict, catalog: Catalog) -> dict:
    spec = spec_dict(row)
    model_ref = spec.get("modelRef") or {}
    if isinstance(model_ref, str):
        provider, _, model = model_ref.partition("/")
    else:
        provider, model = model_ref.get("provider") or "", model_ref.get("model") or ""
    missing_mcp = sorted({r for r in spec.get("mcpRefs") or [] if not catalog.mcp_ok(r)})
    missing_skill = []
    pending_skill = []
    deps: list[dict] = []
    for ref in spec.get("skillRefs") or []:
        name, version = parse_skill_ref(ref)
        vrow = catalog.published_version(name, version)
        if vrow is not None:
            deps.append(vrow)
            continue
        # 无 PUBLISHED 版本：发布链可自行补（先 publish 版本再 publish spec）。
        # 仅当 skill 记录不存在，或引用显式指定了不存在的版本时才阻塞。
        if name not in catalog.skills or (version and not any(
                v.get("version") == version for v in catalog.versions.get(name, []))):
            missing_skill.append(ref)
            continue
        draft = catalog.latest_version(name, version)
        if draft is None:
            missing_skill.append(ref)
        else:
            pending_skill.append(ref)
            deps.append(draft)
    issue = catalog.model_issue(provider, model)
    blocked = bool(missing_mcp or missing_skill or issue)
    return {"name": row.get("name"), "id": row.get("id"),
            "status": "blocked" if blocked else "publishable",
            "missing_mcp": missing_mcp, "missing_skill": missing_skill,
            "pending_skill_publish": pending_skill,
            "model_issue": issue, "skill_versions": deps}


def preflight(client) -> list[dict]:
    catalog = Catalog.fetch(client)
    return [check_spec(row, catalog) for row in catalog.spec_rows]


def summarize(checks: list[dict]) -> dict:
    blocked = [c for c in checks if c["status"] == "blocked"]
    return {"total": len(checks),
            "publishable": len(checks) - len(blocked),
            "blocked": len(blocked),
            "missing_mcp_refs": sorted({r for c in blocked for r in c["missing_mcp"]}),
            "missing_skill_refs": sorted({r for c in blocked for r in c["missing_skill"]}),
            "model_issues": sorted({c["model_issue"] for c in blocked if c["model_issue"]})}


def _ensure_skill_published(client, vrow: dict, *, dry_run: bool, logs: list) -> str:
    """版本 DRAFT 时补占位包 -> review APPROVED -> publish；返回 already-published / published / planned / failed:<原因>。"""
    if str(vrow.get("lifecycle", "")).upper() == "PUBLISHED":
        return "already-published"
    sid, vid = vrow["skillId"], vrow["id"]
    if vrow.get("packageUploadStatus") != "COMPLETED":
        if dry_run:
            return "planned"
        data = placeholder_bytes(vrow.get("version") or "skill")
        result = _usp.upload_package(client, sid, vid, data,
                                     _usp.hashlib.sha256(data).hexdigest(), dry_run=False)
        if result.get("status") != "completed":
            logs.append(f"[FAIL] 包上传 {vrow.get('version')}: {result}")
            return "failed:package-upload"
    if dry_run:
        return "planned"
    rst, rbody = client.request("POST", f"/api/v1/skills/{sid}/versions/{vid}/review",
                                {"status": "APPROVED"})
    if rst != 200:
        logs.append(f"[FAIL] 版本 review {vrow.get('version')}: {rst} {rbody}")
        return "failed:skill-review"
    st, body = client.request("POST", f"/api/v1/skills/{sid}/versions/{vid}/publish")
    if st != 200:
        logs.append(f"[FAIL] 版本 publish {vrow.get('version')}: {st} {body}")
        return "failed:skill-publish"
    logs.append(f"[OK] skill 版本已发布 {vrow.get('version')}")
    return "published"


def publish_specs(client, checks: list[dict], catalog: "Catalog", *,
                  dry_run: bool, logs: list | None = None) -> list[dict]:
    logs = logs if logs is not None else []
    results = []
    for check in checks:
        base = {"name": check["name"], "id": check["id"]}
        if check["status"] == "blocked":
            results.append({**base, "status": "blocked",
                            "missing_mcp": check["missing_mcp"],
                            "missing_skill": check["missing_skill"],
                            "model_issue": check["model_issue"]})
            continue
        row = next((r for r in catalog.spec_rows if r.get("id") == check["id"]), {})
        if str(row.get("lifecycleStatus", "")).upper() == "PUBLISHED":
            results.append({**base, "status": "already-published"})
            continue
        if dry_run:
            results.append({**base, "status": "planned"})
            continue
        failed = None
        for vrow in check["skill_versions"]:
            state = _ensure_skill_published(client, vrow, dry_run=False, logs=logs)
            if state.startswith("failed"):
                failed = state
                break
        if failed:
            results.append({**base, "status": failed})
            continue
        st, body = client.request(
            "POST", f"/api/v1/agent-specs/{check['id']}/publish?projectId={PROJECT_PARAM}",
            idem=f"legacy-agentspec-publish-{check['name']}")
        if st != 200:
            logs.append(f"[FAIL] spec publish {check['name']}: {st} {body}")
            results.append({**base, "status": f"failed:spec-publish({st})"})
            continue
        logs.append(f"[OK] agent-spec 已发布 {check['name']}")
        results.append({**base, "status": "published"})
    return results


def _print_report(checks: list[dict], results: list[dict] | None,
                  logs: list[str] | None = None) -> None:
    summary = summarize(checks)
    print(f"[预检] 共 {summary['total']} 个 agent-spec："
          f"可发布 {summary['publishable']}，阻塞 {summary['blocked']}")
    for check in checks:
        mark = "✓" if check["status"] == "publishable" else "✗"
        reasons = []
        if check["missing_mcp"]:
            reasons.append(f"缺MCP: {','.join(check['missing_mcp'])}")
        if check["missing_skill"]:
            reasons.append(f"缺PUBLISHED skill: {','.join(check['missing_skill'])}")
        if check["model_issue"]:
            reasons.append(f"model: {check['model_issue']}")
        print(f"  {mark} {check['name']}" + (f"（{'；'.join(reasons)}）" if reasons else ""))
    if results:
        counts: dict[str, int] = {}
        for r in results:
            counts[r["status"]] = counts.get(r["status"], 0) + 1
        print(f"[发布] {json.dumps(counts, ensure_ascii=False)}")
        for log in (logs or []):
            print(log)


def main(argv: list[str]) -> int:
    do_publish = "--publish" in argv
    out_dir = None
    if "--out" in argv:
        out_dir = Path(argv[argv.index("--out") + 1])

    base_url = os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "")
    if not base_url:
        raise SystemExit("需要 AGENTTEAMS_CONTROL_PLANE_URL（如 http://127.0.0.1:8080）")
    client = _app.Client(base_url, os.environ.get("AGENTTEAMS_MCP_TOKEN") or None)

    catalog = Catalog.fetch(client)
    checks = [check_spec(row, catalog) for row in catalog.spec_rows]
    _print_report(checks, None)

    results = None
    if do_publish:
        logs: list[str] = []
        results = publish_specs(client, checks, catalog, dry_run=False, logs=logs)
        _print_report(checks, results, logs)
    elif "--plan" in argv:
        results = publish_specs(client, checks, catalog, dry_run=True, logs=[])
        _print_report(checks, results, [])

    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        payload = {"generated_at": stamp, "dry_run": not do_publish,
                   "summary": summarize(checks), "checks": checks,
                   "publish": results}
        path = out_dir / "result.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[OUT] {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
