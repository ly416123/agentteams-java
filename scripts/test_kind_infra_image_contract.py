#!/usr/bin/env python3
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class KindInfraImageContractTest(unittest.TestCase):
    def test_preload_declares_the_canonical_qwenpaw_digest_reference(self):
        preload = (ROOT / "deploy/preload-kind-images.sh").read_text(encoding="utf-8")
        self.assertIn(
            '"docker.io/agentscope/qwenpaw@sha256:1132da56170f49c63aa583dd1ea3b09c19ce1ab76a1983813b8ad2f220771bcd|',
            preload,
        )


if __name__ == "__main__":
    unittest.main()
