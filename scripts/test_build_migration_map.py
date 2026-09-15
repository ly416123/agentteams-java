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


if __name__ == "__main__":
    unittest.main()
