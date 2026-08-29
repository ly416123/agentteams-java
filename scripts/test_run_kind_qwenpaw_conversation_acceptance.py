#!/usr/bin/env python3
"""Contract tests for the Kind Conversation acceptance script."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/run-kind-qwenpaw-conversation-acceptance.py"


class KindQwenPawConversationAcceptanceContractTest(unittest.TestCase):
    def test_script_uses_client_session_id_and_conversation_api(self):
        self.assertTrue(SCRIPT.exists())
        source = SCRIPT.read_text(encoding="utf-8")
        for required in (
                "/api/v1/conversations", "sessionId", "Idempotency-Key",
                "Last-Event-ID", "after", "/api/console/chat", "/api/console/chat/stop",
                "/api/console/cancel"):
            self.assertIn(required, source)

    def test_script_checks_all_conversation_acceptance_behaviors(self):
        source = SCRIPT.read_text(encoding="utf-8")
        for required in (
                "message.delta", "message.completed", "disconnect_after",
                "duplicate", "cancel", "token", "SKIPPED", "KIND_CONVERSATION_OK"):
            self.assertIn(required, source)

    def test_script_does_not_claim_success_when_kind_or_image_is_unavailable(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("kind get clusters", source)
        self.assertIn("kubectl", source)
        self.assertIn("image", source)
        self.assertIn("return 0", source)
        self.assertIn("KIND_CONVERSATION_OK", source)

    def test_script_is_independent_from_existing_deepseek_worker_smoke(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("QWENPAW_DEEPSEEK_SMOKE_OK", source)
        self.assertNotIn("qwenpaw-worker", source)


if __name__ == "__main__":
    unittest.main()
