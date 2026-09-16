"""G12 skill 包重传工具契约测试（mock HTTP + 真实 tar 打包，不打网络）。"""
from __future__ import annotations

import io
import json
import sys
import tarfile
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "upload_skill_package", Path(__file__).resolve().parent / "upload-skill-package.py")
_usp = importlib.util.module_from_spec(_spec)
sys.modules["upload_skill_package"] = _usp
_spec.loader.exec_module(_usp)


class FakeClient:
    """记录请求并按脚本回放 prepare/complete；PUT 走 monkeypatch。"""

    def __init__(self, versions: list[dict] | None = None, fail_prepare: bool = False):
        self.calls: list[tuple] = []
        self._versions = versions or [{"id": "ver-1", "version": "1.0.0",
                                       "packageUploadStatus": "PENDING"}]
        self._fail_prepare = fail_prepare

    def request(self, method, path, body=None, idem=None):
        self.calls.append((method, path, body))
        if method == "GET" and path == "/api/v1/skills":
            return 200, [{"id": "skill-1", "name": "markdown-to-pdf"}]
        if method == "GET" and path == "/api/v1/skills/skill-1/versions":
            return 200, self._versions
        if method == "POST" and path.endswith("/package/upload"):
            if self._fail_prepare:
                return 400, {"error": "SKILL_PACKAGE_INVALID"}
            self._prepared = body
            return 200, {"skillId": "skill-1", "versionId": "ver-1",
                         "storageKey": "skills/skill-1/versions/ver-1/package.tar.gz",
                         "sizeBytes": body["sizeBytes"], "sha256": body["sha256"],
                         "uploadUrl": "http://fake-put/bucket/key",
                         "downloadUrl": "http://fake-get/bucket/key"}
        if method == "POST" and path.endswith("/package/complete"):
            return 200, {"id": "ver-1", "version": "1.0.0", "packageUploadStatus": "COMPLETED"}
        return 500, {"error": "unexpected"}

    def list_names(self, path, name_field="name"):  # noqa: D102
        return {"markdown-to-pdf": "skill-1"}


def _make_package(tmp: Path) -> Path:
    d = tmp / "pkg"
    (d / "assets").mkdir(parents=True)
    (d / "SKILL.md").write_text("# markdown-to-pdf\n转换指令正文\n", encoding="utf-8")
    (d / "assets" / "template.md").write_text("| 列 |", encoding="utf-8")
    return d


class TestPackDirectory(unittest.TestCase):
    def test_packs_sorted_files_and_returns_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = _make_package(Path(tmp))
            data, sha = _usp.pack_directory(src)
        self.assertEqual(len(sha), 64)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            names = sorted(tar.getnames())
        self.assertEqual(names, ["SKILL.md", "assets/template.md"])

    def test_missing_skill_md_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / "bad"
            d.mkdir()
            (d / "other.txt").write_text("x", encoding="utf-8")
            with self.assertRaises(SystemExit):
                _usp.pack_directory(d)


class TestUploadPackageFlow(unittest.TestCase):
    def test_dry_run_records_without_http_side_effect(self) -> None:
        client = FakeClient()
        rows = _usp.upload_package(client, "skill-1", "ver-1", b"data", "a" * 64, dry_run=True)
        self.assertEqual(rows["status"], "dry-run")
        self.assertEqual(client.calls, [])

    def test_full_flow_prepare_put_complete(self) -> None:
        client = FakeClient()
        put_calls = []
        orig = _usp.put_presigned
        _usp.put_presigned = lambda url, data: put_calls.append((url, data)) or 200
        try:
            rows = _usp.upload_package(client, "skill-1", "ver-1", b"tarball", "a" * 64,
                                       dry_run=False)
        finally:
            _usp.put_presigned = orig
        self.assertEqual(rows["status"], "completed")
        self.assertEqual(rows["size_bytes"], 7)
        prepare = [c for c in client.calls if c[1].endswith("/package/upload")][0]
        self.assertEqual(prepare[2]["sizeBytes"], 7)
        self.assertEqual(prepare[2]["sha256"], "a" * 64)
        self.assertEqual(prepare[2]["contentType"], "application/gzip")
        self.assertEqual(put_calls, [("http://fake-put/bucket/key", b"tarball")])
        self.assertTrue(any(c[1].endswith("/package/complete") for c in client.calls))

    def test_prepare_failure_stops_before_put(self) -> None:
        client = FakeClient(fail_prepare=True)
        orig = _usp.put_presigned
        _usp.put_presigned = lambda url, data: (_ for _ in ()).throw(AssertionError("must not PUT"))
        try:
            rows = _usp.upload_package(client, "skill-1", "ver-1", b"x", "a" * 64, dry_run=False)
        finally:
            _usp.put_presigned = orig
        self.assertEqual(rows["status"], "failed")
        self.assertEqual(rows["step"], "prepare")
        self.assertEqual(rows["http"], 400)


class TestResolveVersion(unittest.TestCase):
    def test_explicit_version_and_missing_error(self) -> None:
        client = FakeClient(versions=[{"id": "v1", "version": "1.0.0"},
                                      {"id": "v2", "version": "2.0.0"}])
        vid, row = _usp.resolve_version(client, "skill-1", "2.0.0")
        self.assertEqual(vid, "v2")
        with self.assertRaises(SystemExit):
            _usp.resolve_version(client, "skill-1", "9.9.9")

    def test_ambiguous_version_requires_explicit(self) -> None:
        client = FakeClient(versions=[{"id": "v1", "version": "1.0.0"},
                                      {"id": "v2", "version": "2.0.0"}])
        with self.assertRaises(SystemExit):
            _usp.resolve_version(client, "skill-1", None)

    def test_single_version_resolves_implicitly(self) -> None:
        client = FakeClient()
        vid, _ = _usp.resolve_version(client, "skill-1", None)
        self.assertEqual(vid, "ver-1")


if __name__ == "__main__":
    unittest.main()
