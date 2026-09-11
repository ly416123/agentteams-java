import importlib.util
import http.client
import json
import os
import re
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "qwenpaw_conversation_mock", ROOT / "scripts/qwenpaw-conversation-mock.py"
)
MOCK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MOCK)


class StubControlPlane(BaseHTTPRequestHandler):
    """Records the REST calls the decomposition script issues (token/plan/status)."""

    calls: list[dict] = []
    status_code = 200

    def log_message(self, format, *args):  # noqa: A002 - BaseHTTPRequestHandler API
        pass

    def _json(self, status, payload):
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def do_POST(self):  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path.endswith("/token"):
            length = int(self.headers.get("Content-Length", "0"))
            form = dict(urllib.parse.parse_qsl(self.rfile.read(length).decode()))
            StubControlPlane.calls.append({"kind": "token", "body": form})
            self._json(200, {"access_token": f"stub-token-{len(StubControlPlane.calls)}"})
            return
        self._json(404, {"error": "not found"})

    def do_PUT(self):  # noqa: N802 - required by BaseHTTPRequestHandler
        StubControlPlane.calls.append({
            "kind": "put",
            "path": self.path,
            "authorization": self.headers.get("Authorization"),
            "idempotency_key": self.headers.get("Idempotency-Key"),
            "body": self._body(),
        })
        if StubControlPlane.status_code != 200:
            self._json(StubControlPlane.status_code, {"error": "boom"})
            return
        if self.path.endswith("/status"):
            self._json(200, {"node": {"subtaskId": "n/a", "status": "RUNNING"}})
        else:
            self._json(200, {"nodes": []})

    def do_GET(self):  # noqa: N802 - required by BaseHTTPRequestHandler
        StubControlPlane.calls.append({
            "kind": "get",
            "path": self.path,
            "authorization": self.headers.get("Authorization"),
        })
        if StubControlPlane.status_code != 200:
            self._json(StubControlPlane.status_code, {"error": "boom"})
            return
        path = self.path
        if path.endswith("/subtasks"):
            self._json(200, [
                {"subtaskId": "st-1", "title": "抓取邮件", "sequence": 1,
                 "status": "SUCCEEDED", "phase": "SUCCEEDED", "dependencyIds": []},
                {"subtaskId": "st-2", "title": "生成摘要", "sequence": 2,
                 "status": "SUCCEEDED", "phase": "SUCCEEDED", "dependencyIds": []},
            ])
        elif path.endswith("/result"):
            self._json(200, {"status": "SUCCEEDED", "summary": "子任务产物",
                             "artifacts": []})
        elif "/runs" in path:
            self._json(200, [{"id": "run-1", "createdAt": "2026-09-11T00:00:00Z",
                              "status": "SUCCEEDED"}])
        else:
            self._json(404, {"error": "not found"})


class QwenPawConversationMockTest(unittest.TestCase):
    def setUp(self):
        MOCK.reset_state()
        StubControlPlane.calls = []
        StubControlPlane.status_code = 200
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), MOCK.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.control_plane = ThreadingHTTPServer(("127.0.0.1", 0), StubControlPlane)
        self.control_plane_thread = threading.Thread(
            target=self.control_plane.serve_forever, daemon=True)
        self.control_plane_thread.start()
        self.cp_base_url = f"http://127.0.0.1:{self.control_plane.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.control_plane.shutdown()
        self.control_plane.server_close()

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
                "Idempotency-Key": "message-1",
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
        self.assertIn("idempotency-key=message-1", "\n".join(MOCK.audit_log()))

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

    def test_chat_reconnects_after_query_and_last_event_id(self):
        body = self.read_chat("session-reconnect", "reconnect prompt", {"Idempotency-Key": "reconnect-1"})
        self.assertIn("id: 1\n", body)
        self.assertIn("id: 2\n", body)
        self.assertIn("id: 3\n", body)

        replay = self.read_chat(
            "session-reconnect",
            "reconnect prompt",
            {"Idempotency-Key": "reconnect-1", "Last-Event-ID": "1"},
            query="after=1",
        )
        self.assertNotIn("id: 1\n", replay)
        self.assertIn("id: 2\n", replay)
        self.assertIn("id: 3\n", replay)

    def test_duplicate_idempotency_key_replays_and_conflicting_input_is_rejected(self):
        headers = {"Idempotency-Key": "duplicate-1"}
        first = self.read_chat("session-duplicate", "first prompt", headers)
        duplicate = self.read_chat("session-duplicate", "first prompt", headers)
        self.assertEqual(duplicate, first)

        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.read_chat("session-duplicate", "different prompt", headers)
        self.assertEqual(raised.exception.code, 409)
        self.assertNotIn("different prompt", raised.exception.read().decode())

    def test_new_idempotency_key_emits_a_fresh_round_for_multi_turn_sessions(self):
        first = self.read_chat("session-multi", "first prompt", {"Idempotency-Key": "round-1"})
        self.assertIn("CONVERSATION_MOCK_OK", first)

        retry = self.read_chat("session-multi", "first prompt", {"Idempotency-Key": "round-1"})
        self.assertEqual(retry, first)

        second = self.read_chat("session-multi", "second prompt", {"Idempotency-Key": "round-2"})
        self.assertIn("id: 4\n", second)
        self.assertIn("id: 5\n", second)
        self.assertNotIn("id: 1\n", second)
        self.assertIn("CONVERSATION_MOCK_DELTA_2", second)
        self.assertIn("CONVERSATION_MOCK_OK_2", second)
        self.assertNotIn('"text":"CONVERSATION_MOCK_OK"}', second)

        third = self.read_chat("session-multi", "third prompt", {"Idempotency-Key": "round-3"})
        self.assertIn("id: 6\n", third)
        self.assertIn("CONVERSATION_MOCK_OK_3", third)

    decomposition_env = {
        "AGENTTEAMS_CONTROL_PLANE_URL": "",
        "AGENTTEAMS_OIDC_TOKEN_URL": "",
        "AGENTTEAMS_OIDC_CLIENT_ID": "agentteams-api",
        "AGENTTEAMS_MCP_USERNAME": "alice",
        "AGENTTEAMS_MCP_PASSWORD": "alice-dev",
    }

    def control_plane_env(self):
        return dict(self.decomposition_env,
                    AGENTTEAMS_CONTROL_PLANE_URL=self.cp_base_url,
                    AGENTTEAMS_OIDC_TOKEN_URL=f"{self.cp_base_url}/realms/agentteams/protocol/openid-connect/token")

    def test_decomposition_script_plans_subtasks_via_rest_then_aggregation_round(self):
        task_id = "123e4567-e89b-42d3-a456-426614174000"
        prompt = (f"[平台上下文]\ntaskId={task_id}\n（可用 agentteams-task MCP 工具引用此 taskId）\n\n"
                  + MOCK.DECOMPOSITION_MARKER + "\n整理邮件摘要报告")
        with mock.patch.dict(os.environ, self.control_plane_env()):
            body = self.read_chat("session-decomp", prompt, {"Idempotency-Key": "decomp-1"})

        calls = StubControlPlane.calls
        token_calls = [call for call in calls if call["kind"] == "token"]
        puts = [call for call in calls if call["kind"] == "put"]
        self.assertEqual(token_calls[0]["body"]["grant_type"], "password")
        self.assertEqual(token_calls[0]["body"]["username"], "alice")

        # 拆解轮：仅 plan（3 子任务，C 依赖 A+B），状态推进交给平台真调度。
        plan = puts[0]
        self.assertEqual(plan["path"], f"/api/v1/tasks/{task_id}/subtasks")
        specs = plan["body"]["subtasks"]
        self.assertEqual([spec["title"] for spec in specs],
                         ["抓取邮件", "生成摘要", f"汇总产物 {MOCK.FAIL_FIRST_MARKER}"])
        self.assertEqual([spec["sequence"] for spec in specs], [1, 2, 3])
        self.assertEqual(specs[0]["dependencyIds"], [])
        self.assertEqual(specs[1]["dependencyIds"], [])
        self.assertEqual(specs[2]["dependencyIds"],
                         [specs[0]["subtaskId"], specs[1]["subtaskId"]])
        self.assertTrue(all(spec["requiredCapabilities"] == ["qwenpaw"] for spec in specs))
        self.assertEqual(len(puts), 1)
        self.assertTrue(all(call["authorization"].startswith("Bearer ") for call in puts))
        self.assertTrue(all(call["idempotency_key"] for call in puts))

        self.assertEqual(body.count('"type":"tool.started"'), 1)
        self.assertEqual(body.count('"type":"plugin_call_output"'), 1)
        self.assertIn('"tool":"plan_subtasks","status":"success"', body.replace(" ", "")
                      if " " in body else body)
        self.assertIn("SUBTASK_DECOMPOSITION_SUMMARY", body)
        self.assertIn('"status":"completed"', body)
        cursors = [int(value) for value in re.findall(r'"cursor":(\d+)', body)]
        self.assertEqual(cursors, sorted(set(cursors)))

        # 汇总轮（第 2 个会话）：list_subtasks → 每个 SUCCEEDED 子任务取 result。
        with mock.patch.dict(os.environ, self.control_plane_env()):
            aggregate = self.read_chat("session-aggregate", prompt,
                                       {"Idempotency-Key": "decomp-2"})
        self.assertIn("SUBTASK_AGGREGATION_SUMMARY", aggregate)
        self.assertNotIn("SUBTASK_DECOMPOSITION_SUMMARY", aggregate)
        gets = [call for call in StubControlPlane.calls if call["kind"] == "get"]
        self.assertIn(f"/api/v1/tasks/{task_id}/subtasks", [call["path"] for call in gets])
        self.assertEqual(
            [call["path"] for call in gets if call["path"].endswith("/result")],
            ["/api/v1/tasks/st-1/runs/run-1/result", "/api/v1/tasks/st-2/runs/run-1/result"])
        # GET 无写语义：不带幂等键。
        self.assertTrue(all("idempotency" not in call for call in gets))

    def test_fail_first_subtask_fails_then_recovers_via_standard_script(self):
        """G03 失败注入：注册进 FAIL_FIRST 的子任务首个会话发 failed 终态，
        retry 后的第 2 个会话走标准剧本成功（无拆解动作、无聚合动作）。"""
        task_id = "323e4567-e89b-42d3-a456-426614174000"
        prompt = (f"[平台上下文]\ntaskId={task_id}\n（可用 agentteams-task MCP 工具引用此 taskId）\n\n"
                  + MOCK.DECOMPOSITION_MARKER + "\n拆解")
        with mock.patch.dict(os.environ, self.control_plane_env()):
            self.read_chat("session-fail-plan", prompt, {"Idempotency-Key": "fail-plan-1"})
        subtask_id = StubControlPlane.calls[1]["body"]["subtasks"][2]["subtaskId"]
        subtask_prompt = (f"[平台上下文]\ntaskId={subtask_id}\n（可用 agentteams-task MCP 工具引用此 taskId）\n\n"
                          f"汇总产物 {MOCK.FAIL_FIRST_MARKER}")

        failed = self.read_chat("session-c-first", subtask_prompt,
                                {"Idempotency-Key": "c-first"})
        self.assertIn('"status":"failed"', failed)
        self.assertIn(MOCK.FAIL_FIRST_MARKER, failed)
        self.assertNotIn('"status":"completed"', failed)

        recovered = self.read_chat("session-c-retry", subtask_prompt,
                                   {"Idempotency-Key": "c-retry"})
        self.assertIn('"status":"completed"', recovered)
        self.assertIn("CONVERSATION_MOCK_OK", recovered)
        self.assertNotIn('"status":"failed"', recovered)
        # 失败/标准剧本不产生任何 control-plane REST 动作。
        self.assertEqual(len(StubControlPlane.calls), 2)

    def test_decomposition_rest_failure_degrades_without_threatening_terminal(self):
        StubControlPlane.status_code = 500
        task_id = "223e4567-e89b-42d3-a456-426614174000"
        with mock.patch.dict(os.environ, self.control_plane_env()):
            body = self.read_chat(
                "session-decomp-fail",
                f"[平台上下文]\ntaskId={task_id}\n\n" + MOCK.DECOMPOSITION_MARKER + "\nprompt",
                {"Idempotency-Key": "decomp-fail-1"})
        self.assertEqual(body.count('"status":"failed"'), 1)
        self.assertIn("SUBTASK_DECOMPOSITION_SUMMARY", body)
        self.assertIn('"status":"completed"', body)

    def test_decomposition_without_control_plane_config_still_completes(self):
        task_id = "323e4567-e89b-42d3-a456-426614174000"
        # 与其余拆解用例一样隔离宿主环境：开发者本机若导出了
        # AGENTTEAMS_CONTROL_PLANE_URL，不加护栏会真实外呼。
        with mock.patch.dict(os.environ, self.decomposition_env):
            body = self.read_chat(
                "session-decomp-noenv",
                f"[平台上下文]\ntaskId={task_id}\n\n" + MOCK.DECOMPOSITION_MARKER + "\nprompt",
                {"Idempotency-Key": "decomp-noenv-1"})
        self.assertEqual(body.count('"status":"failed"'), 1)
        self.assertIn('"status":"completed"', body)

    def test_subtask_session_without_marker_keeps_standard_script(self):
        # 子任务会话同样携带 taskId 注入但无拆解标记——走标准剧本，
        # 不触发委派动作（避免子任务递归拆解）。
        task_id = "423e4567-e89b-42d3-a456-426614174000"
        body = self.read_chat(
            "session-subtask",
            f"[平台上下文]\ntaskId={task_id}\n\n子任务执行提示")
        self.assertNotIn('"type":"tool.started"', body)
        self.assertIn("CONVERSATION_MOCK_OK", body)

    def test_plain_prompt_keeps_existing_script_without_tool_events(self):
        body = self.read_chat("session-plain", "hello without platform context")
        self.assertNotIn('"type":"tool.started"', body)
        self.assertIn("CONVERSATION_MOCK_OK", body)

    def test_native_model_endpoints_accept_worker_bootstrap(self):
        # Worker 启动时用 native model config 做 bootstrap：active 提供者必须可读，
        # 两个配置 PUT 必须成功，否则 WorkerRuntimeRouter.start 直接 crash。
        with urllib.request.urlopen(f"{self.base_url}/api/models/active", timeout=2) as response:
            payload = json.loads(response.read())
        self.assertIn("provider_id", payload["active_llm"])

        for path, body in (
            ("/api/models/active", {"provider_id": "deepseek", "model": "deepseek-chat",
                                     "scope": "agent", "agent_id": "default"}),
            ("/api/models/deepseek/models/deepseek-chat/config", {"max_tokens": "1024"}),
        ):
            request = urllib.request.Request(
                self.base_url + path, data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json"}, method="PUT")
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 200)

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
        conversation_deployments = [
            document for document in documents
            if document.get("kind") == "Deployment"
            and document.get("metadata", {}).get("name") == "qwenpaw-conversation-mock"
        ]
        self.assertEqual(len(conversation_deployments), 1)
        env_names = {entry["name"] for entry
                     in conversation_deployments[0]["spec"]["template"]["spec"]["containers"][0]["env"]}
        self.assertLessEqual({"AGENTTEAMS_CONTROL_PLANE_URL", "AGENTTEAMS_OIDC_TOKEN_URL",
                              "AGENTTEAMS_MCP_USERNAME"}, env_names)

    def test_kind_manifest_allows_the_conversation_mock_through_the_control_plane_networkpolicy(self):
        """拆解剧本直调 control-plane REST，与真实 QwenPaw 平台（经 ingress-nginx）同路。

        helm chart 的 control-plane NetworkPolicy 白名单不含测试用 mock pod，
        因此 kind manifest 叠加一条附加 NetworkPolicy（NP ingress 取并集）
        放行 mock pod 访问 control-plane 8080。
        """
        manifest = (ROOT / "deploy/kind-qwenpaw-openai-mock.yaml").read_text(encoding="utf-8")
        allow_policies = [
            document for document in yaml.safe_load_all(manifest)
            if document.get("kind") == "NetworkPolicy"
            and document.get("metadata", {}).get("name")
            == "qwenpaw-conversation-mock-allow-control-plane"
        ]
        self.assertEqual(len(allow_policies), 1)
        spec = allow_policies[0]["spec"]
        self.assertEqual(spec["podSelector"]["matchLabels"],
                         {"app.kubernetes.io/name": "agentteams-control-plane"})
        self.assertEqual(spec["policyTypes"], ["Ingress"])
        self.assertIn(
            {"podSelector": {"matchLabels":
                             {"app.kubernetes.io/name": "qwenpaw-conversation-mock"}}},
            spec["ingress"][0]["from"],
        )
        self.assertIn({"protocol": "TCP", "port": 8080}, spec["ingress"][0]["ports"])

    def read_chat(self, session_id, prompt, headers=None, query=""):
        request = urllib.request.Request(
            f"{self.base_url}/api/console/chat?{query}" if query else f"{self.base_url}/api/console/chat",
            data=json.dumps({
                "session_id": session_id,
                "input": [{"role": "user", "content": [{"type": "text", "text": prompt}]}],
            }).encode(),
            headers={"Content-Type": "application/json", **(headers or {})},
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
