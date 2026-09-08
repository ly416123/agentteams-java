import importlib.util
import json
import os
import subprocess
import sys
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "agentteams_task_mcp", ROOT / "scripts/agentteams-task-mcp.py"
)
MCP = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
# Register before exec_module: dataclass annotation evaluation looks the module
# up in sys.modules (Python 3.14 dataclasses._is_type).
sys.modules["agentteams_task_mcp"] = MCP
SPEC.loader.exec_module(MCP)


def rpc(request: dict) -> dict:
    response = MCP.handle_request(request)
    assert response is not None
    return response


class ControlPlaneStub(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _reply(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        if self.path.endswith("/token"):
            # Token endpoint receives form-encoded credentials; do not json-decode.
            self.server.requests.append(("POST", self.path, dict(self.headers), None))
            self._reply({"access_token": "granted-token", "expires_in": 300})
            return
        payload = json.loads(raw or b"{}")
        self.server.requests.append(("POST", self.path, dict(self.headers), payload))
        if self.path == "/api/v1/tasks":
            self.server.create_body = payload
            self._reply({"id": self.server.task_id, "phase": "DRAFT", "version": 0})
        elif "/queue" in self.path:
            self.server.queue_body = payload
            self._reply({"id": self.server.task_id, "phase": "QUEUED", "version": 1})
        else:
            self._reply({"error": "not found"}, 404)

    def do_GET(self):
        self.server.requests.append(("GET", self.path, dict(self.headers), None))
        if self.path == f"/api/v1/tasks/{self.server.task_id}":
            self._reply({"id": self.server.task_id, "phase": "SUCCEEDED", "title": "t"})
        elif self.path == f"/api/v1/tasks/{self.server.task_id}/runs":
            self._reply([{"id": "run-1", "createdAt": "2026-09-08T00:00:00Z", "status": "SUCCEEDED"}])
        elif "/runs/run-1/result" in self.path:
            self._reply({"status": "SUCCEEDED", "summary": "done", "artifacts": [
                {"name": "output.md", "storageRef": "tasks/x/artifacts/output.md",
                 "contentType": "text/markdown", "sizeBytes": 12,
                 "downloadUrl": "http://minio/presigned"}]})
        else:
            self._reply({"error": "not found"}, 404)


class AgentTeamsTaskMcpTest(unittest.TestCase):
    def setUp(self):
        MCP.reset_state()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ControlPlaneStub)
        self.server.task_id = str(uuid.uuid4())
        self.server.requests = []
        self.server.create_body = None
        self.server.queue_body = None
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.env = {
            "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
            "AGENTTEAMS_MCP_SCOPE": '{"tenant":"tenant-a","project":"project-a","team":"team-a"}',
            "AGENTTEAMS_MCP_TOKEN": "static-test-token",
        }

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_protocol_initialize_tools_list_and_unknown_method(self):
        init = rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertEqual(init["result"]["protocolVersion"], "2024-11-05")
        self.assertEqual(init["result"]["serverInfo"]["name"], "agentteams-tasks")

        tools = rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual([t["name"] for t in tools["result"]["tools"]],
                         ["create_task", "get_task", "get_task_result"])

        self.assertIsNone(MCP.handle_request({"jsonrpc": "2.0", "method": "notifications/initialized"}))

        unknown = rpc({"jsonrpc": "2.0", "id": 3, "method": "resources/list"})
        self.assertTrue(unknown["result"]["content"][0]["text"].find("unknown_method") > 0)

    def _use_env(self, env):
        # load_config is a pure function; assign the parsed config to the
        # module-level cache exactly like get_config() would.
        MCP._CONFIG = MCP.load_config(env)

    def test_create_task_posts_spec_and_queues_with_idempotency_keys(self):
        self._use_env(self.env)
        payload = MCP.call_tool("create_task", {
            "title": "500强名单", "description": "d",
            "prompt": "生成中国企业500强名单，PDF 格式",
        })
        self.assertTrue(payload["ok"], payload)
        self.assertEqual(payload["phase"], "QUEUED")

        method, path, headers, body = next(r for r in self.server.requests
                                           if r[0] == "POST" and r[1] == "/api/v1/tasks")
        self.assertEqual(headers.get("Authorization"), "Bearer static-test-token")
        self.assertTrue(headers.get("Idempotency-Key"))
        self.assertEqual(body["title"], "500强名单")
        self.assertEqual(body["spec"]["taskType"], "qwenpaw")
        self.assertEqual(body["spec"]["scope"],
                         {"tenant": "tenant-a", "project": "project-a", "team": "team-a"})
        self.assertEqual(body["spec"]["inputJson"]["prompt"], "生成中国企业500强名单，PDF 格式")
        self.assertEqual(self.server.queue_body, {"expectedVersion": 0})

    def test_create_task_writes_conversation_source_into_input_json(self):
        self._use_env(self.env)
        payload = MCP.call_tool("create_task", {
            "title": "来源回链", "prompt": "p",
            "source": {"conversation_id": "conv-1", "message_id": "msg-9"},
        })
        self.assertTrue(payload["ok"], payload)
        method, path, headers, body = next(r for r in self.server.requests
                                           if r[0] == "POST" and r[1] == "/api/v1/tasks")
        self.assertEqual(body["spec"]["inputJson"]["source"],
                         {"conversationId": "conv-1", "messageId": "msg-9"})

    def test_create_task_without_source_keeps_input_json_unchanged(self):
        self._use_env(self.env)
        payload = MCP.call_tool("create_task", {"title": "无来源", "prompt": "p"})
        self.assertTrue(payload["ok"], payload)
        method, path, headers, body = next(r for r in self.server.requests
                                           if r[0] == "POST" and r[1] == "/api/v1/tasks")
        self.assertNotIn("source", body["spec"]["inputJson"])

    def test_password_grant_token_is_fetched_once_and_cached(self):
        env = dict(self.env)
        env.pop("AGENTTEAMS_MCP_TOKEN")
        env.update({
            "AGENTTEAMS_OIDC_TOKEN_URL": self.base + "/token",
            "AGENTTEAMS_MCP_USERNAME": "svc-agent",
            "AGENTTEAMS_MCP_PASSWORD": "svc-secret",
        })
        MCP._CONFIG = MCP.load_config(env)
        self.assertTrue(MCP.call_tool("create_task", {"title": "a", "prompt": "p"})["ok"])
        self.assertTrue(MCP.call_tool("create_task", {"title": "b", "prompt": "p"})["ok"])
        token_calls = [r for r in self.server.requests if r[1] == "/token"]
        self.assertEqual(len(token_calls), 1)
        granted = [h for _, _, h, _ in self.server.requests
                   if h.get("Authorization") == "Bearer granted-token"]
        self.assertGreaterEqual(len(granted), 2)

    def test_get_task_result_returns_latest_run_artifacts(self):
        self._use_env(self.env)
        payload = MCP.call_tool("get_task_result", {"task_id": self.server.task_id})
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["status"], "SUCCEEDED")
        self.assertEqual(payload["summary"], "done")
        self.assertEqual(payload["artifacts"][0]["downloadUrl"], "http://minio/presigned")

    def test_input_validation_and_error_mapping(self):
        self._use_env(self.env)
        with self.assertRaises(ValueError):
            MCP.call_tool("create_task", {"title": "  ", "prompt": "p"})
        with self.assertRaises(ValueError):
            MCP.call_tool("create_task", {"title": "t", "prompt": "x" * (MCP.MAX_PROMPT + 1)})
        with self.assertRaises(ValueError):
            MCP.call_tool("get_task", {"task_id": "not-a-uuid"})

        broken = rpc({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                      "params": {"name": "get_task", "arguments": {"task_id": "bad"}}})
        self.assertTrue(broken["result"]["isError"])
        self.assertIn("must be a UUID", broken["result"]["content"][0]["text"])

    def test_config_file_settings_are_overlaid_by_explicit_env(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump({
                "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
                "AGENTTEAMS_MCP_TOKEN": "file-token",
                "AGENTTEAMS_MCP_SCOPE": self.env["AGENTTEAMS_MCP_SCOPE"],
            }, handle)
            config_path = handle.name
        try:
            from_file = MCP.load_config({"AGENTTEAMS_MCP_CONFIG_FILE": config_path})
            self.assertEqual(from_file.static_token, "file-token")
            from_env = MCP.load_config({
                "AGENTTEAMS_MCP_CONFIG_FILE": config_path,
                "AGENTTEAMS_MCP_TOKEN": "env-token",
            })
            self.assertEqual(from_env.static_token, "env-token")
        finally:
            os.unlink(config_path)

    def test_deploy_configmap_embeds_current_script(self):
        import yaml
        manifest = yaml.safe_load(
            (ROOT / "deploy/kind-qwenpaw-task-mcp.yaml").read_text())
        embedded = manifest["data"]["agentteams-task-mcp.py"]
        self.assertEqual(embedded,
                         (ROOT / "scripts/agentteams-task-mcp.py").read_text())

    def test_stdio_subprocess_roundtrip(self):
        env = dict(os.environ)
        env.update(self.env)
        proc = subprocess.Popen(
            [sys.executable, str(ROOT / "scripts/agentteams-task-mcp.py")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            env=env, text=True,
        )
        try:
            def ask(request):
                assert proc.stdin is not None and proc.stdout is not None
                proc.stdin.write(json.dumps(request) + "\n")
                proc.stdin.flush()
                return json.loads(proc.stdout.readline())

            init = ask({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
            self.assertEqual(init["result"]["protocolVersion"], "2024-11-05")
            tools = ask({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
            self.assertEqual(len(tools["result"]["tools"]), 3)
            created = ask({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
                "name": "create_task",
                "arguments": {"title": "500强", "prompt": "生成中国企业500强名单，PDF 格式"}}})
            self.assertIn('"ok": true', created["result"]["content"][0]["text"])
            result = ask({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
                "name": "get_task_result", "arguments": {"task_id": self.server.task_id}}})
            self.assertIn("presigned", result["result"]["content"][0]["text"])
        finally:
            proc.stdin.close()
            proc.stdout.close()
            proc.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()