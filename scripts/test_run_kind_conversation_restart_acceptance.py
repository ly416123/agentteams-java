import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/run-kind-conversation-restart-acceptance.py"


class KindConversationRestartAcceptanceContractTest(unittest.TestCase):
    def test_script_restarts_manager_and_compares_persisted_history(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("/history", source)
        self.assertIn("rollout", source)
        self.assertIn("message.completed", source)
        self.assertIn("history changed or disappeared after Manager restart", source)
        self.assertIn("message idempotency replay changed after Manager restart", source)

    def test_script_does_not_print_token_or_full_message_payload(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("print(token", source)
        self.assertNotIn("print(before", source)
        self.assertNotIn("print(after", source)


if __name__ == "__main__":
    unittest.main()
