#!/usr/bin/env python3
"""Contract tests for the Java 21 I/O concurrency benchmark harness."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "benchmark-io-concurrency.py"


def load_module():
    spec = importlib.util.spec_from_file_location("benchmark_io_concurrency", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load {MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BenchmarkIoConcurrencyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()

    def test_default_concurrency_levels_are_fixed(self):
        self.assertEqual(self.module.DEFAULT_CONCURRENCIES, (16, 64, 128, 256))
        options = self.module.validate_options(
            base_url="http://127.0.0.1:8088", path="/api/console/chat",
            duration_seconds=1, warmup_seconds=0, runs=1,
            mode="both", concurrencies=None, output=None)
        self.assertEqual(options.concurrencies, (16, 64, 128, 256))

    def test_percentile_uses_sorted_linear_interpolation(self):
        self.assertEqual(self.module.percentile([], 0.95), None)
        self.assertEqual(self.module.percentile([3, 1, 2], 0.50), 2.0)
        self.assertEqual(self.module.percentile([10, 20, 30, 40], 0.95), 38.5)

    def test_options_reject_invalid_values(self):
        with self.assertRaises(ValueError):
            self.module.validate_options(
                "", "/chat", 1, 0, 0, 1, "platform", None, None)
        with self.assertRaises(ValueError):
            self.module.validate_options(
                "http://127.0.0.1:8088", "/chat", 0, 0, 0, 1,
                "platform", None, None)
        with self.assertRaises(ValueError):
            self.module.validate_options(
                "http://127.0.0.1:8088", "/chat", 1, 0, 0, 1,
                "invalid", None, None)

    def test_summary_never_invents_optional_metrics(self):
        summary = self.module.summarize(
            mode="virtual", concurrency=16, run=1,
            outcomes=[{"outcome": "success", "latency_seconds": 0.1},
                      {"outcome": "error", "latency_seconds": 0.3}],
            metrics={})
        self.assertEqual(summary["success"], 1)
        self.assertEqual(summary["error"], 1)
        self.assertIsNone(summary["metrics"]["rss_bytes"])
        self.assertEqual(summary["latency_seconds"]["p50"], 0.2)


if __name__ == "__main__":
    unittest.main()
