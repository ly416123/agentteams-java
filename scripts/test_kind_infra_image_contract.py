#!/usr/bin/env python3
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class KindInfraImageContractTest(unittest.TestCase):
    def test_preload_does_not_pull_a_second_native_runtime_image(self):
        preload = (ROOT / "deploy/preload-kind-images.sh").read_text(encoding="utf-8")
        self.assertNotIn("agentscope/agentscope", preload)
        self.assertNotIn("agentscope/qwenpaw", preload)


if __name__ == "__main__":
    unittest.main()
