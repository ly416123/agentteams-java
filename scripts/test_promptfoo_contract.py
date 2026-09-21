import json
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).parents[1]


class PromptfooContractTest(unittest.TestCase):
    def test_promptfoo_config_uses_canonical_cases_and_python_hooks(self):
        config = yaml.safe_load((ROOT / "eval/promptfooconfig.yaml").read_text(encoding="utf-8"))
        self.assertEqual("file://promptfoo_provider.py", config["providers"][0]["id"])
        self.assertEqual("file://promptfoo_tests.py:generate_tests", config["tests"][0])
        self.assertEqual("file://promptfoo_assert.py", config["defaultTest"]["assert"][0]["value"])

        cases = yaml.safe_load((ROOT / "eval/cases/core.yaml").read_text(encoding="utf-8"))["cases"]
        self.assertEqual(3, len(cases))
        for case in cases:
            serialized = json.dumps(case, ensure_ascii=False).lower()
            for marker in ("bearer ", "api_key", "password", "cookie"):
                self.assertNotIn(marker, serialized)

    def test_promptfoo_hooks_compile_without_live_credentials(self):
        compile((ROOT / "eval/promptfoo_tests.py").read_text(encoding="utf-8"), "promptfoo_tests.py", "exec")
        compile((ROOT / "eval/promptfoo_provider.py").read_text(encoding="utf-8"), "promptfoo_provider.py", "exec")
        compile((ROOT / "eval/promptfoo_assert.py").read_text(encoding="utf-8"), "promptfoo_assert.py", "exec")


if __name__ == "__main__":
    unittest.main()
