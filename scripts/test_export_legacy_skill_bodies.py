"""export-legacy-skill-bodies.py 单元测试（FakeClient 不触网）。"""
from __future__ import annotations

import gzip
import hashlib
import importlib.util
import io
import json
import types
import zipfile
from pathlib import Path

import pytest

_HERE = Path(__file__).resolve().parent


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), _HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    import sys

    sys.modules[name.replace("-", "_")] = mod
    spec.loader.exec_module(mod)
    return mod


_elb = _load("export-legacy-skill-bodies")


def _resp(data: dict):
    class _Body:
        def to_map(self):
            return {"data": data, "requestId": "req-1"}

    return types.SimpleNamespace(body=_Body())


def _skill(name: str, latest: str = "0.0.1"):
    return {"name": name, "description": f"desc-{name}", "labels": {"latest": latest}, "scope": "PUBLIC"}


class FakeClient:
    """对齐 AgentCore SDK 签名：位置参数 (ws[, name][, version], request)。"""

    def __init__(self, pages: list[list[dict]], details: dict, downloads: dict, version_details: dict):
        self._pages = pages
        self._details = details
        self._downloads = downloads
        self._version_details = version_details
        self.calls: list[tuple] = []

    def list_skills(self, ws, request):
        self.calls.append(("list_skills", ws, request.page_no, request.page_size))
        page = self._pages[request.page_no - 1]
        return _resp({"pageItems": page, "totalCount": sum(len(p) for p in self._pages)})

    def get_skill_detail(self, ws, name, request):
        self.calls.append(("get_skill_detail", name))
        return _resp(self._details[name])

    def download_skill_version_via_oss(self, ws, name, version, request):
        self.calls.append(("download", name, version))
        url = self._downloads.get((name, version))
        if url is None:
            raise RuntimeError(f"download unavailable for {name}@{version}")
        return _resp({"url": url})

    def get_skill_version_detail(self, ws, name, version, request):
        self.calls.append(("version_detail", name, version))
        md = self._version_details.get((name, version))
        if md is None:
            raise RuntimeError(f"no version detail for {name}@{version}")
        return _resp({"skillMd": md, "resource": {}})


class FakeFetch:
    def __init__(self, payloads: dict):
        self._payloads = payloads
        self.urls: list[str] = []

    def __call__(self, url: str) -> bytes:
        self.urls.append(url)
        payload = self._payloads.get(url)
        if payload is None:
            raise RuntimeError(f"no payload for {url}")
        return payload


def _zip_bytes(files: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for path, content in files.items():
            zf.writestr(path, content)
    return buf.getvalue()


WS = "ws-test"


class TestPaginateSkills:
    def test_single_page(self):
        client = FakeClient(pages=[[_skill("a"), _skill("b")]], details={}, downloads={}, version_details={})
        items = _elb.paginate_skills(client, WS)
        assert [i["name"] for i in items] == ["a", "b"]
        assert client.calls == [("list_skills", WS, 1, 50)]

    def test_multi_page_until_total(self):
        page1 = [_skill(f"s{i}") for i in range(50)]
        page2 = [_skill("tail")]
        client = FakeClient(pages=[page1, page2], details={}, downloads={}, version_details={})
        items = _elb.paginate_skills(client, WS)
        assert len(items) == 51
        assert [c[2] for c in client.calls] == [1, 2]

    def test_empty(self):
        client = FakeClient(pages=[[]], details={}, downloads={}, version_details={})
        assert _elb.paginate_skills(client, WS) == []


class TestVersionsOf:
    def test_extract_version_status(self):
        data = {"versions": [{"version": "0.0.1", "status": "online"}, {"version": "0.0.2", "status": "draft"}]}
        assert _elb.versions_of(data) == [("0.0.1", "online"), ("0.0.2", "draft")]

    def test_missing_versions_falls_back_to_labels(self):
        data = {"labels": {"latest": "2.2.0"}}
        assert _elb.versions_of(data) == [("2.2.0", None)]

    def test_nothing(self):
        assert _elb.versions_of({}) == []


class TestExportAll:
    def _client_and_fetch(self):
        zip_bytes = _zip_bytes({"customer-discovery/SKILL.md": "---\nversion: 0.0.1\n---\nbody"})
        details = {
            "customer-discovery": {"versions": [{"version": "0.0.1", "status": "online"}]},
        }
        downloads = {("customer-discovery", "0.0.1"): "https://oss/signed/customer-discovery.zip"}
        client = FakeClient(
            pages=[[_skill("customer-discovery")]], details=details, downloads=downloads, version_details={}
        )
        fetch = FakeFetch({("https://oss/signed/customer-discovery.zip"): zip_bytes})
        return client, fetch, zip_bytes

    def test_zip_downloaded_with_manifest(self, tmp_path: Path):
        client, fetch, zip_bytes = self._client_and_fetch()
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch)
        out = tmp_path / "customer-discovery" / "0.0.1.zip"
        assert out.read_bytes() == zip_bytes
        entry = manifest[0]
        assert entry["name"] == "customer-discovery"
        assert entry["versions"][0]["source"] == "zip"
        assert entry["versions"][0]["size"] == len(zip_bytes)
        assert entry["versions"][0]["sha256"] == hashlib.sha256(zip_bytes).hexdigest()

    def test_md_fallback_when_download_fails(self, tmp_path: Path):
        details = {"solo": {"versions": [{"version": "0.0.9", "status": "online"}]}}
        client = FakeClient(pages=[[_skill("solo")]], details=details, downloads={}, version_details={})
        version_details = {("solo", "0.0.9"): "---\nversion: 0.0.9\n---\nmd-body"}
        fetch = FakeFetch({})
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch, version_details=version_details)
        md_path = tmp_path / "solo" / "0.0.9.md"
        assert "md-body" in md_path.read_text()
        assert manifest[0]["versions"][0]["source"] == "md"
        assert manifest[0]["versions"][0]["sha256"] == hashlib.sha256(
            version_details[("solo", "0.0.9")].encode()
        ).hexdigest()

    def test_download_and_detail_both_fail_records_error(self, tmp_path: Path):
        details = {"broken": {"versions": [{"version": "1.0", "status": "online"}]}}
        client = FakeClient(pages=[[_skill("broken")]], details=details, downloads={}, version_details={})
        fetch = FakeFetch({})
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch, version_details={})
        assert manifest[0]["versions"][0]["source"] == "error"

    def test_only_filter_skips_others(self, tmp_path: Path):
        client, fetch, _ = self._client_and_fetch()
        client._details["other"] = {"versions": []}
        client._pages = [[_skill("customer-discovery"), _skill("other")]]
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch, only=["customer-discovery"])
        assert [m["name"] for m in manifest] == ["customer-discovery"]
        assert not ("get_skill_detail", "other") in client.calls

    def test_list_only_touches_no_downloads(self, tmp_path: Path):
        client, fetch, _ = self._client_and_fetch()
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch, list_only=True)
        assert manifest[0]["versions"][0]["source"] == "pending"
        assert fetch.urls == []
        assert not ("download", "customer-discovery", "0.0.1") in client.calls

    def test_multi_versions_all_exported(self, tmp_path: Path):
        zip1 = _zip_bytes({"s/SKILL.md": "v1"})
        zip2 = _zip_bytes({"s/SKILL.md": "v2"})
        details = {"s": {"versions": [{"version": "1.0", "status": "online"}, {"version": "2.0", "status": "draft"}]}}
        client = FakeClient(
            pages=[[_skill("s")]],
            details=details,
            downloads={("s", "1.0"): "https://oss/signed/s-1.0.zip", ("s", "2.0"): "https://oss/signed/s-2.0.zip"},
            version_details={},
        )
        fetch = FakeFetch({"https://oss/signed/s-1.0.zip": zip1, "https://oss/signed/s-2.0.zip": zip2})
        manifest = _elb.export_all(client, tmp_path, WS, fetch=fetch)
        assert [v["version"] for v in manifest[0]["versions"]] == ["1.0", "2.0"]
        assert (tmp_path / "s" / "1.0.zip").read_bytes() == zip1
        assert (tmp_path / "s" / "2.0.zip").read_bytes() == zip2
