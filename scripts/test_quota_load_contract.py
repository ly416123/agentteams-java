#!/usr/bin/env python3
"""Contract tests for the deterministic quota load harness."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "run-quota-load.py"


def load_module():
    spec = importlib.util.spec_from_file_location("quota_load", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load {MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class QuotaLoadContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()

    def test_options_require_positive_bounded_values(self):
        options = self.module.validate_options(
            concurrency=4, requests=200, projects=4, timeout=2.0,
            seed=7, tenant="tenant-a", max_concurrent=1, estimated_tokens=10)
        self.assertEqual(options.concurrency, 4)
        self.assertEqual(options.requests, 200)
        self.assertEqual(self.module.validate_options(
            4, 200, 4, 2.0, 7, "tenant-a", 1, 0).estimated_tokens, 0)
        with self.assertRaises(ValueError):
            self.module.validate_options(2, 200, 4, 2.0, 7, "tenant-a", 1, 10)
        with self.assertRaises(ValueError):
            self.module.validate_options(0, 200, 4, 2.0, 7, "tenant-a", 1, 10)
        with self.assertRaises(ValueError):
            self.module.validate_options(4, 0, 4, 2.0, 7, "tenant-a", 1, 10)
        with self.assertRaises(ValueError):
            self.module.validate_options(4, 200, 0, 2.0, 7, "tenant-a", 1, 10)
        with self.assertRaises(ValueError):
            self.module.validate_options(4, 200, 4, 0.0, 7, "tenant-a", 1, 10)

    def test_summarize_results_has_only_stable_fields(self):
        results = [
            {"outcome": "success", "released": True, "duplicate": 1, "observed_concurrency": 1,
             "response": {"secret": "do-not-print"}},
            {"outcome": "rejected", "released": True, "observed_concurrency": 1,
             "response": {"token": "do-not-print"}},
            {"outcome": "timeout", "released": False, "observed_concurrency": 2,
             "response": {"prompt": "do-not-print"}},
            {"outcome": "duplicate", "released": True, "observed_concurrency": 1,
             "response": {"scope": "do-not-print"}},
        ]
        summary = self.module.summarize_results(results, requests=4, projects=2, seed=9)
        self.assertEqual(summary, {
            "success": 1,
            "rejected": 1,
            "timeout": 1,
            "duplicate": 1,
            "max_observed_concurrency": 2,
            "unreleased_reservations": 1,
            "requests": 4,
            "projects": 2,
            "seed": 9,
        })
        encoded = json.dumps(summary, sort_keys=True)
        self.assertNotIn("do-not-print", encoded)
        self.assertEqual(set(summary), {
            "success", "rejected", "timeout", "duplicate",
            "max_observed_concurrency", "unreleased_reservations",
            "requests", "projects", "seed",
        })

    def test_run_load_releases_every_accepted_reservation(self):
        calls = []

        def fake_call(method, request, timeout):
            calls.append((method, request, timeout))
            if method == "Acquire":
                return {"accepted": True, "reservationId": f"reservation-{len(calls)}"}
            return {"accepted": True, "reservationId": request["reservationId"]}

        results = self.module.run_load(
            fake_call, concurrency=2, requests=5, projects=2, timeout=1.0,
            seed=3, tenant="tenant-a", max_concurrent=1, estimated_tokens=10)
        self.assertEqual(len(results), 5)
        self.assertEqual(sum(item["outcome"] == "success" for item in results), 5)
        self.assertEqual(sum(item["released"] for item in results), 5)
        self.assertEqual(sum(method == "Acquire" for method, _, _ in calls), 10)
        self.assertEqual(sum(method == "Release" for method, _, _ in calls), 5)

    def test_run_load_marks_duplicate_mismatch_and_release_failure(self):
        calls = {"acquire": 0}

        def duplicate_mismatch(method, request, _timeout):
            if method == "Acquire":
                calls["acquire"] += 1
                return {"accepted": True, "reservationId": "first" if calls["acquire"] == 1 else "other"}
            return {"accepted": False}

        results = self.module.run_load(
            duplicate_mismatch, concurrency=1, requests=1, projects=1, timeout=1.0,
            seed=3, tenant="tenant-a", max_concurrent=1, estimated_tokens=10,
            project_names=["project-a"])
        self.assertTrue(results[0]["invariant_failure"])
        self.assertFalse(results[0]["released"])
        self.assertEqual(self.module.summarize_results(results, 1, 1, 3)["unreleased_reservations"], 1)

    def test_missing_reservation_is_a_protocol_violation(self):
        def missing_reservation(_method, _request, _timeout):
            return {"accepted": True}

        results = self.module.run_load(
            missing_reservation, concurrency=1, requests=1, projects=1, timeout=1.0,
            seed=3, tenant="tenant-a", max_concurrent=1, estimated_tokens=10)
        self.assertTrue(results[0]["protocol_violation"])
        self.assertFalse(results[0]["released"])


if __name__ == "__main__":
    unittest.main()
