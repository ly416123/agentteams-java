"""G12 迁移映射包生成器契约测试（合成数据，不打 API）。"""
from __future__ import annotations

import base64
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# 连字符文件名不能直接 import，同盘点工具用 importlib 桥接
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "build_migration_map", Path(__file__).resolve().parent / "build-migration-map.py")
_bmm = importlib.util.module_from_spec(_spec)
sys.modules["build_migration_map"] = _bmm
_spec.loader.exec_module(_bmm)

env_tag = _bmm.env_tag
parse_config = _bmm.parse_config
transport_of = _bmm.transport_of


class TestEnvTag(unittest.TestCase):
    def test_uat_variant_test_and_default(self) -> None:
        self.assertEqual(env_tag("memory-mcp10010-uat"), "uat")
        self.assertEqual(env_tag("order-mcp-10002-v2"), "variant")
        self.assertEqual(env_tag("memory-mcp10010-v1"), "variant")
        self.assertEqual(env_tag("query-user-feedback-for-test-01"), "test")
        self.assertEqual(env_tag("procurement-mcp"), "prod-candidate")


class TestTransportOf(unittest.TestCase):
    def test_known_protocols_direct_map(self) -> None:
        self.assertEqual(transport_of("SSE"), ("SSE", False))
        self.assertEqual(transport_of("StreamableHTTP"), ("STREAMABLE_HTTP", False))
        self.assertEqual(transport_of("streamable_http"), ("STREAMABLE_HTTP", False))

    def test_unknown_protocol_flags_review(self) -> None:
        transport, review = transport_of(None)
        self.assertEqual(transport, "STREAMABLE_HTTP")
        self.assertTrue(review)


class TestParseConfig(unittest.TestCase):
    def test_json_direct(self) -> None:
        self.assertEqual(parse_config('{"url":"http://x"}'), {"url": "http://x"})

    def test_base64_yaml(self) -> None:
        raw = "Server:\n  name: m\n  mcpServerURL: http://x/sse\n"
        encoded = base64.b64encode(raw.encode("utf-8")).decode("ascii")
        self.assertEqual(parse_config(encoded),
                         {"Server": {"name": "m", "mcpServerURL": "http://x/sse"}})

    def test_garbage_falls_back_to_truncated_raw(self) -> None:
        out = parse_config("not-json not-base64!!!")
        self.assertIn("_raw", out)


def _write(tmp: Path, name: str, rows) -> Path:
    d = tmp / "detail"
    d.mkdir(parents=True, exist_ok=True)
    (d / name).write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    return d


WORKER_FULL = {
    "name": "bidding-worker", "status": "Running", "agent_type": "qwenpaw",
    "deploy_type": "SelfHosted", "version_code": "worker-1.1.18",
    "template": {"name": "template-bidding-worker", "version": "0.0.2"},
    "groups": [{"name": "corp-map-algo-test", "role": "worker", "type": "team"}],
    "mcp_servers": [{"name": "procurement-mcp"}],
    "skills": [{"name": "corp_skill_bidding_search", "version": ""}],
    "model": {"model_name": "qwen3.8-max", "model_provider": "default"},
    "soul": "# 人格：招投标情报分析员\n", "agents": "## 拥有技能列表\n",
}


class TestModelCatalogMapping(unittest.TestCase):
    def test_providers_map_to_openai_compatible_with_credential_ref(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            detail = _write(Path(tmp), "model_providers.json", [
                {"id": "api-1", "name": "default", "provider": "qwen",
                 "address": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                 "protocols": ["OpenAI/v1"], "api_keys": ["***"], "deploy_status": "Deployed"}])
            rows = _bmm.build_model_providers(detail)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["provider_type"], "openai-compatible")
        self.assertEqual(rows[0]["endpoint"], "https://dashscope.aliyuncs.com/compatible-mode/v1")
        self.assertEqual(rows[0]["credential_ref"], "legacy-modelprov-default")

    def test_models_union_catalog_and_worker_references(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            detail = _write(Path(tmp), "models.json", [
                {"name": "qwen3.8-max", "provider_name": "default", "provider": "qwen"}])
            _write(Path(tmp), "workers.json", [
                {"full": {**WORKER_FULL}},
                {"full": {**WORKER_FULL, "model": {"model_provider": "", "model_name": "qwen3.7-max"}}},
            ])
            rows = _bmm.build_models(detail)
        by_key = {(m["provider_name"], m["name"]): m for m in rows}
        self.assertEqual(set(by_key), {("default", "qwen3.8-max"), ("default", "qwen3.7-max")})
        self.assertEqual(by_key[("default", "qwen3.8-max")]["source"], "model-catalog")
        self.assertEqual(by_key[("default", "qwen3.7-max")]["source"], "worker-reference")
        self.assertEqual(by_key[("default", "qwen3.7-max")]["in_use_by_workers"], 1)


class TestAgentSpecRegistration(unittest.TestCase):
    def test_full_mapping_and_spec_payload(self) -> None:
        reg = _bmm.agent_spec_registration(WORKER_FULL)
        self.assertEqual(reg["name"], "bidding-worker")
        self.assertEqual(reg["runtime"], "qwenpaw")
        self.assertEqual(reg["workerType"], "EXECUTOR")
        self.assertEqual(reg["teamRef"], "corp-map-algo-test")
        self.assertEqual(reg["desiredState"], "RUNNING")
        self.assertEqual(reg["modelProvider"], "default")
        self.assertEqual(reg["modelName"], "qwen3.8-max")
        spec = reg["spec"]
        self.assertEqual(spec["modelRef"], {"provider": "default", "model": "qwen3.8-max"})
        self.assertEqual(spec["skillRefs"], ["corp_skill_bidding_search"])
        self.assertEqual(spec["mcpRefs"], ["procurement-mcp"])
        self.assertEqual(spec["legacy"]["soul"], WORKER_FULL["soul"])
        self.assertEqual(spec["legacy"]["template"], WORKER_FULL["template"])

    def test_leader_role_and_stopped_state(self) -> None:
        worker = {**WORKER_FULL, "status": "Stopped",
                  "groups": [{"name": "wxlteam", "role": "leader", "type": "team"}]}
        reg = _bmm.agent_spec_registration(worker)
        self.assertEqual(reg["workerType"], "LEADER")
        self.assertEqual(reg["teamRef"], "wxlteam")
        self.assertEqual(reg["desiredState"], "STOPPED")

    def test_empty_provider_falls_back_to_default(self) -> None:
        worker = {**WORKER_FULL, "model": {"model_provider": "", "model_name": "qwen3.7-max"}}
        reg = _bmm.agent_spec_registration(worker)
        self.assertEqual(reg["modelProvider"], "default")

    def test_build_workers_embeds_registration_and_size(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            detail = _write(Path(tmp), "workers.json", [{"summary": {}, "full": WORKER_FULL}])
            rows = _bmm.build_workers(detail)
        self.assertEqual(len(rows), 1)
        d = rows[0]
        self.assertNotIn("_soul_raw", d)  # 正文已内嵌 registration，不再重复顶层导出
        self.assertEqual(d["registration"]["name"], "bidding-worker")
        self.assertGreater(d["spec_bytes"], 0)
        self.assertLess(d["spec_bytes"], 64 * 1024)


if __name__ == "__main__":
    unittest.main()
