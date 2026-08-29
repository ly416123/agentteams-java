import importlib.util
import http.client
import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "qwenpaw_conversation_mock", ROOT / "scripts/qwenpaw-conversation-mock.py"
)
MOCK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MOCK)


class QwenPawConversationMockTest(unittest.TestCase):
    def setUp(self):
        MOCK.reset_state()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), MOCK.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_health_and_chat_return_cursored_delta_and_terminal_sse(self):
        with urllib.request.urlopen(f"{self.base_url}/health", timeout=2) as response:
            self.assertEqual(json.loads(response.read())["status"], "ok")

        request = urllib.request.Request(
            f"{self.base_url}/api/console/chat",
            data=json.dumps({
                "session_id": "session-a",
                "input": [{"role": "user", "content": [{"type": "text", "text": "private prompt"}]}],
            }).encode(),
            headers={
                "Accept": "text/event-stream",
                "Content-Type": "application/json",
                "X-Agent-Id": "agent-a",
                "Authorization": "Bearer secret-token",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            body = response.read().decode()

        self.assertEqual(body.count("event: message.delta"), 1)
        self.assertIn('"cursor":1', body)
        self.assertIn('"cursor":2', body)
        self.assertIn('"status":"completed"', body)
        self.assertNotIn("private prompt", body)
        self.assertNotIn("secret-token", body)
        self.assertNotIn("private prompt", "\n".join(MOCK.audit_log()))
        self.assertNotIn("secret-token", "\n".join(MOCK.audit_log()))

    def test_delay_and_disconnect_can_be_changed_without_restarting_server(self):
        self.post_json("/debug/config", {"delay_seconds": 0.02, "disconnect_after": 1})
        self.assertEqual(self.get_json("/debug/config")["disconnect_after"], 1)

        with self.assertRaises((http.client.IncompleteRead, ConnectionResetError, TimeoutError)):
            self.read_chat("session-disconnect", "not returned in full")

        self.post_json("/debug/config", {"delay_seconds": 0, "disconnect_after": None})
        body = self.read_chat("session-reconnected", "another private prompt")
        self.assertIn('"status":"completed"', body)

    def test_cancel_returns_cancelled_event_and_suppresses_terminal_completion(self):
        self.post_json("/debug/config", {"delay_seconds": 0.05, "disconnect_after": None})
        result = {}

        def send():
            try:
                result["body"] = self.read_chat("session-cancel", "secret message")
            except Exception as error:  # pragma: no cover - assertion below checks the outcome
                result["error"] = error

        thread = threading.Thread(target=send)
        thread.start()
        time.sleep(0.02)
        self.post_json("/api/console/cancel", {"session_id": "session-cancel"})
        thread.join(timeout=2)

        self.assertFalse(thread.is_alive())
        self.assertNotIn('"status":"completed"', result.get("body", ""))
        self.assertIn('"status":"cancelled"', result.get("body", ""))

    def test_kind_manifest_declares_the_conversation_mock_without_changing_openai_mock(self):
        manifest_path = ROOT / "deploy/kind-qwenpaw-openai-mock.yaml"
        manifest = manifest_path.read_text(encoding="utf-8")
        self.assertIn("qwenpaw-conversation-mock", manifest)
        self.assertIn("conversation-server.py", manifest)
        self.assertIn("/health", manifest)
        self.assertIn("QWENPAW_CONVERSATION_MOCK_DELAY_SECONDS", manifest)
        self.assertIn("qwenpaw-openai-mock", manifest)
        documents = list(yaml.safe_load_all(manifest))
        configmaps = [document for document in documents if document.get("kind") == "ConfigMap"]
        conversation_configmaps = [configmap for configmap in configmaps
                                   if configmap.get("metadata", {}).get("name")
                                   == "qwenpaw-conversation-mock"]
        self.assertEqual(len(conversation_configmaps), 1)
        self.assertEqual(
            conversation_configmaps[0]["data"]["conversation-server.py"],
            (ROOT / "scripts/qwenpaw-conversation-mock.py").read_text(encoding="utf-8"),
        )

    def read_chat(self, session_id, prompt):
        request = urllib.request.Request(
            f"{self.base_url}/api/console/chat",
            data=json.dumps({
                "session_id": session_id,
                "input": [{"role": "user", "content": [{"type": "text", "text": prompt}]}],
            }).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.read().decode()

    def post_json(self, path, payload):
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual(response.status, 200)
            return json.loads(response.read())

    def get_json(self, path):
        with urllib.request.urlopen(self.base_url + path, timeout=2) as response:
            return json.loads(response.read())


if __name__ == "__main__":
    unittest.main()
