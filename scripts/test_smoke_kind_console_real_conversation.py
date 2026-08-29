import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/smoke-kind-console-real-conversation.sh"


class KindConsoleRealConversationSmokeContractTest(unittest.TestCase):
    def test_real_console_smoke_entrypoint_is_present_and_delegates_to_authenticated_acceptance(self):
        source = SCRIPT.read_text(encoding="utf-8")
        for required in (
                "kubectl", "keycloak", "port-forward", "AGENTTEAMS_API_BEARER_TOKEN",
                "run-kind-qwenpaw-conversation-acceptance.py", "CONSOLE_REAL_CONVERSATION_OK"):
            self.assertIn(required, source)

    def test_real_console_smoke_does_not_print_credentials(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn('echo "${token}"', source)
        self.assertNotIn('printf "%s\\n" "${token}"', source)
        self.assertNotIn("--token \"${token}\"", source)


if __name__ == "__main__":
    unittest.main()
