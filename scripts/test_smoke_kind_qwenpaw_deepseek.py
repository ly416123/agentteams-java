#!/usr/bin/env python3
"""Contract tests for the authenticated QwenPaw DeepSeek smoke script."""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "smoke-kind-qwenpaw-deepseek.sh"


class DeepSeekSmokeScriptContractTest(unittest.TestCase):
    def test_optional_auth_arguments_are_safe_when_token_is_unset(self):
        source = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("curl_api()", source)
        self.assertIn("if (( ${#API_AUTH_ARGS[@]} > 0 )); then", source)
        self.assertIn('curl_api --fail --silent "${BASE_URL}/actuator/health"', source)
        self.assertNotIn('curl --fail --silent "${API_AUTH_ARGS[@]}"', source)


if __name__ == "__main__":
    unittest.main()
