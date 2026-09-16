#!/usr/bin/env python3
"""G12 skill 包本体重传工具：本地包目录 → 新平台 presign PUT → complete。

背景：迁移注册的 skill_versions 为占位 manifest（entry=SKILL.md/sizeBytes=0）。
旧平台 OpenAPI 无 skill 正文端点（skill 仅以引用形式存在于 worker 配置），
包本体需从旧平台控制台导出后用本工具重传（prepareUpload 会重置已 COMPLETED
的版本状态，可反复覆盖直到内容定稿）。

用法：
  export AGENTTEAMS_CONTROL_PLANE_URL=http://agentteams-control-plane:8080
  export AGENTTEAMS_MCP_TOKEN=<bearer>
  python3 scripts/upload-skill-package.py --skill markdown-to-pdf --version 1.0.0 \
      --dir path/to/skill-package            # 预览（默认 dry-run）
  ... 加 --apply 执行上传

包目录要求：根目录必须含 SKILL.md；其余文件按相对路径一并打入 tar.gz。
产出：stdout 明细（每步 http 状态），失败即停（非零退出）。
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path

# 复用执行器的 HTTP Client（连字符模块 importlib 桥接，先例见迁移契约测试）
_spec = importlib.util.spec_from_file_location(
    "apply_migration_map", Path(__file__).resolve().parent / "apply-migration-map.py")
_app = importlib.util.module_from_spec(_spec)
sys.modules["apply_migration_map"] = _app
_spec.loader.exec_module(_app)

CONTENT_TYPE = "application/gzip"


def pack_directory(src: Path) -> tuple[bytes, str]:
    """目录 → (tar.gz bytes, sha256 hex)。文件按路径排序打包；根目录必须含 SKILL.md。"""
    src = src.resolve()
    if not src.is_dir():
        raise SystemExit(f"包目录不存在: {src}")
    entries = sorted(p for p in src.rglob("*") if p.is_file())
    if "SKILL.md" not in {p.relative_to(src).as_posix() for p in entries}:
        raise SystemExit(f"{src} 缺少 SKILL.md（新平台 manifest.entry 契约）")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for p in entries:
            tar.add(p, arcname=p.relative_to(src).as_posix())
    data = buf.getvalue()
    return data, hashlib.sha256(data).hexdigest()


def put_presigned(url: str, data: bytes) -> int:
    """presigned PUT：不得携带 Authorization（会破坏 S3 签名）；Content-Type 须与 prepare 一致。"""
    req = urllib.request.Request(url, data=data, method="PUT",
                                 headers={"Content-Type": CONTENT_TYPE})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.status


def upload_package(client, skill_id: str, version_id: str, data: bytes, sha256: str,
                   *, dry_run: bool) -> dict:
    size = len(data)
    if dry_run:
        return {"status": "dry-run", "size_bytes": size, "sha256": f"sha256:{sha256}"}
    st, body = client.request(
        "POST", f"/api/v1/skills/{skill_id}/versions/{version_id}/package/upload",
        {"sizeBytes": size, "sha256": sha256, "contentType": CONTENT_TYPE})
    if st != 200:
        return {"status": "failed", "step": "prepare", "http": st, "resp": body}
    upload_url = (body or {}).get("uploadUrl")
    if not upload_url:
        return {"status": "failed", "step": "prepare", "reason": "missing uploadUrl", "resp": body}
    try:
        put_status = put_presigned(upload_url, data)
    except urllib.error.HTTPError as e:  # urllib.error 顶层引用保持与执行器一致
        return {"status": "failed", "step": "put", "http": e.code, "resp": e.read().decode("utf-8", "replace")[:300]}
    if put_status != 200:
        return {"status": "failed", "step": "put", "http": put_status}
    cst, cbody = client.request(
        "POST", f"/api/v1/skills/{skill_id}/versions/{version_id}/package/complete")
    if cst != 200:
        return {"status": "failed", "step": "complete", "http": cst, "resp": cbody}
    return {"status": "completed", "size_bytes": size, "sha256": f"sha256:{sha256}",
            "version": cbody}


def resolve_version(client, skill_id: str, version: str | None) -> tuple[str, dict]:
    """返回 (version_id, 版本记录)；version 为空时要求唯一版本。"""
    st, rows = client.request("GET", f"/api/v1/skills/{skill_id}/versions")
    if st != 200 or not isinstance(rows, list):
        raise SystemExit(f"GET versions 失败: {st} {rows}")
    if version:
        matches = [r for r in rows if r.get("version") == version]
        if not matches:
            raise SystemExit(f"版本不存在: {version}（可用: {[r.get('version') for r in rows]}）")
        return matches[0]["id"], matches[0]
    if len(rows) == 1:
        return rows[0]["id"], rows[0]
    raise SystemExit(f"skill 有 {len(rows)} 个版本，需显式 --version（可用: {[r.get('version') for r in rows]}）")


def main(argv: list[str]) -> int:
    def opt(name: str, required: bool = True) -> str | None:
        if name in argv:
            return argv[argv.index(name) + 1]
        if required:
            raise SystemExit(f"缺少参数 {name}")
        return None

    skill = opt("--skill")
    src = Path(opt("--dir"))
    version = opt("--version", required=False)
    dry_run = "--apply" not in argv

    base_url = os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "")
    if not dry_run and not base_url:
        raise SystemExit("--apply 需要 AGENTTEAMS_CONTROL_PLANE_URL")
    client = _app.Client(base_url or "http://dry-run.invalid",
                         os.environ.get("AGENTTEAMS_MCP_TOKEN") or None)

    data, sha256 = pack_directory(src)
    print(f"[PACK] {src} -> {len(data)} bytes sha256:{sha256[:16]}...")

    if dry_run:
        # dry-run 也校验 skill/版本可达性（只读 GET），确保 --apply 时参数有效
        names = client.list_names("/api/v1/skills") if base_url else {}
        if base_url and skill not in names:
            raise SystemExit(f"skill 不存在: {skill}（已有: {sorted(names)[:10]}...）")
        vid, vrow = (None, {}) if not base_url else resolve_version(client, names[skill], version)
        print(f"[PLAN] skill={skill} id={names.get(skill)} version={version or vrow.get('version')} "
              f"version_id={vid} dry_run=True")
        print(f"[HINT] 预览模式；确认后加 --apply 执行 presign PUT 上传")
        print("[DONE] dry-run")
        return 0

    names = client.list_names("/api/v1/skills")
    if skill not in names:
        raise SystemExit(f"skill 不存在: {skill}")
    vid, vrow = resolve_version(client, names[skill], version)
    print(f"[PLAN] skill={skill} id={names[skill]} version={vrow.get('version')} version_id={vid}")

    result = upload_package(client, names[skill], vid, data, sha256, dry_run=False)
    print(f"[RESULT] {json.dumps(result, ensure_ascii=False, default=str)[:500]}")
    if result["status"] != "completed":
        return 1
    print("[DONE] 包已上传并完成校验（packageUploadStatus=COMPLETED）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
