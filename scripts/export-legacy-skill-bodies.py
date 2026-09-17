#!/usr/bin/env python3
"""导出旧平台（AgentCore workspace）全部 skill 正文包。

背景：旧平台 AgentTeams 实例的 skill 资源实际存放在 AgentCore 产品（控制台
agentcore.console.aliyun.com 前端走 product=AgentCore / AIRegistry 的 OpenAPI）。
AgentTeams 2026-06-05 的公开 API 无任何 skill 通道（38 个候选 action 实测确认），
而 AgentCore 2026-08-04 官方 SDK 提供 list_skills / get_skill_version_detail /
download_skill_version_via_oss 全套只读接口。

用法：
  source .local/aliyun-credentials.env && python3 scripts/export-legacy-skill-bodies.py
选项：
  --out DIR      产物目录（缺省 output/skill-bodies，gitignore）
  --ws ID        AgentCore workspace id（缺省 ws-a2164114355d46c194ddc）
  --skill NAME   只导出指定 skill（可重复）
  --list-only    只列清单不下载正文

产物：
  <out>/<skill>/<version>.zip   整包（SKILL.md + resource），首选
  <out>/<skill>/<version>.md    skillMd 正文，OSS 下载失败时的保底
  <out>/manifest.json           [{name, description, versions: [{version, status, source, path, sha256, size}]}]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ENDPOINT = "agentcore.cn-beijing.aliyuncs.com"
AGENTCORE_VERSION = "2026-08-04"
DEFAULT_WORKSPACE = "ws-a2164114355d46c194ddc"
PAGE_SIZE = 50


def paginate_skills(client, ws: str) -> list[dict]:
    """list_skills 全量翻页聚合。"""
    from alibabacloud_agentcore20260804 import models as M

    items: list[dict] = []
    page = 1
    while True:
        req = M.ListSkillsRequest(page_size=PAGE_SIZE, page_no=page)
        data = (client.list_skills(ws, req).body.to_map().get("data") or {})
        chunk = data.get("pageItems") or []
        items.extend(chunk)
        total = int(data.get("totalCount") or 0)
        if not chunk or (total and len(items) >= total) or len(chunk) < PAGE_SIZE:
            return items
        page += 1


def versions_of(detail_data: dict) -> list[tuple[str, str | None]]:
    """从 get_skill_detail 的 data 提取 (version, status) 列表；无 versions 时回退 labels.latest。"""
    versions = detail_data.get("versions") or []
    out = [(v.get("version"), v.get("status")) for v in versions if v.get("version")]
    if not out:
        latest = (detail_data.get("labels") or {}).get("latest")
        if latest:
            out = [(latest, None)]
    return out


def no_proxy_fetch(url: str) -> bytes:
    """直连下载 OSS 签名 URL。

    本机验证：urllib 即使 ProxyHandler({}) 仍受 macOS 系统代理影响破坏 OSS TLS 握手，
    curl --noproxy '*' 稳定可用（实测 200）；HTTP 错误时 --fail 非零退出由上层走 md 保底。
    """
    import subprocess

    result = subprocess.run(
        ["curl", "-sS", "--noproxy", "*", "--fail", "--max-time", "60", url],
        capture_output=True,
        timeout=90,
        check=True,
    )
    return result.stdout


def export_all(
    client,
    out_dir: Path,
    ws: str,
    fetch=no_proxy_fetch,
    only: list[str] | None = None,
    list_only: bool = False,
    version_details: dict | None = None,
) -> list[dict]:
    """全量导出。version_details 供测试注入 {(name, version): skillMd}。"""
    from alibabacloud_agentcore20260804 import models as M

    skills = paginate_skills(client, ws)
    manifest: list[dict] = []
    version_details = version_details or {}
    for item in skills:
        name = item["name"]
        if only and name not in only:
            continue
        detail = client.get_skill_detail(ws, name, M.GetSkillDetailRequest()).body.to_map().get("data") or {}
        entry = {"name": name, "description": item.get("description") or detail.get("description") or "", "versions": []}
        for version, status in versions_of(detail):
            record: dict = {"version": version, "status": status, "source": "pending", "path": None, "sha256": None, "size": None}
            skill_dir = out_dir / name
            if not list_only:
                blob: bytes | None = None
                source = "error"
                # 首选：OSS 签名整包下载
                try:
                    resp = client.download_skill_version_via_oss(
                        ws, name, version, M.DownloadSkillVersionViaOssRequest()
                    ).body.to_map()
                    url = (resp.get("data") or {}).get("url") if isinstance(resp.get("data"), dict) else resp.get("data")
                    if isinstance(url, str) and url.startswith("http"):
                        blob = fetch(url)
                        source = "zip"
                        path = skill_dir / f"{version}.zip"
                except Exception:
                    blob = None
                # 保底：get_skill_version_detail 的 skillMd 正文
                if blob is None:
                    try:
                        if (name, version) in version_details:
                            md = version_details[(name, version)]
                        else:
                            vd = client.get_skill_version_detail(
                                ws, name, version, M.GetSkillVersionDetailRequest()
                            ).body.to_map().get("data") or {}
                            md = vd.get("skillMd") or ""
                        if md:
                            blob = md.encode("utf-8")
                            source = "md"
                            path = skill_dir / f"{version}.md"
                    except Exception:
                        blob = None
                if blob is not None:
                    skill_dir.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(blob)
                    record.update(source=source, path=str(path), sha256=hashlib.sha256(blob).hexdigest(), size=len(blob))
                else:
                    record.update(source="error")
            entry["versions"].append(record)
        manifest.append(entry)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="output/skill-bodies", help="产物目录")
    parser.add_argument("--ws", default=DEFAULT_WORKSPACE, help="AgentCore workspace id")
    parser.add_argument("--skill", action="append", default=[], help="只导出指定 skill（可重复）")
    parser.add_argument("--list-only", action="store_true", help="只列清单不下载正文")
    args = parser.parse_args(argv)

    from alibabacloud_agentcore20260804.client import Client
    from alibabacloud_tea_openapi import models as open_api_models
    from alibabacloud_credentials.client import Client as CredClient

    config = open_api_models.Config(credential=CredClient())
    config.endpoint = ENDPOINT
    config.read_timeout = 30000
    config.connect_timeout = 15000
    client = Client(config)

    out_dir = Path(args.out)
    manifest = export_all(
        client,
        out_dir,
        args.ws,
        only=args.skill or None,
        list_only=args.list_only,
    )
    if not args.list_only:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    done = sum(1 for m in manifest for v in m["versions"] if v["source"] in ("zip", "md"))
    errors = sum(1 for m in manifest for v in m["versions"] if v["source"] == "error")
    print(f"[SUMMARY] skills={len(manifest)} versions_ok={done} errors={errors} out={out_dir}")
    for m in manifest:
        vers = ", ".join(f"{v['version']}({v['source']})" for v in m["versions"]) or "(no versions)"
        print(f"  - {m['name']}: {vers}")
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
