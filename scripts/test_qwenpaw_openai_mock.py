import importlib.util
import json
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("qwenpaw_openai_mock", ROOT / "scripts/qwenpaw-openai-mock.py")
MOCK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MOCK)


class QwenPawOpenAIMockTest(unittest.TestCase):
    def test_can_clear_delay_without_restarting_server(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), MOCK.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            MOCK.RESPONSE_DELAY_SECONDS = 60.0
            base_url = f"http://127.0.0.1:{server.server_address[1]}"
            request = urllib.request.Request(
                f"{base_url}/debug/delay",
                data=json.dumps({"seconds": 0}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 200)
            with urllib.request.urlopen(f"{base_url}/debug/delay", timeout=2) as response:
                self.assertEqual(json.loads(response.read())["seconds"], 0)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
