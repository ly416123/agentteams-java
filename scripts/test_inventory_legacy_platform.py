# scripts/test_inventory_legacy_platform.py
"""G12 盘点工具契约测试（合成 fixture，不打真库）。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parents[1]


class TestGitignore(unittest.TestCase):
    def test_output_dir_is_ignored(self) -> None:
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("output/", gitignore)


if __name__ == "__main__":
    unittest.main()
