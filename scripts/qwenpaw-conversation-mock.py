#!/usr/bin/env python3
"""Deterministic QwenPaw HTTP/SSE mock for Conversation runtime tests."""

from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlencode, urlsplit
import urllib.error
import urllib.request


DEFAULT_DELAY_SECONDS = max(0.0, float(os.environ.get("QWENPAW_CONVERSATION_MOCK_DELAY_SECONDS", "0")))
DEFAULT_DISCONNECT_AFTER = None
CONFIG_LOCK = threading.Lock()
DELAY_SECONDS = DEFAULT_DELAY_SECONDS
DISCONNECT_AFTER = DEFAULT_DISCONNECT_AFTER
SESSIONS: dict[str, dict[str, Any]] = {}
# G03：taskId → 已分配委派剧本的会话数（1=拆解轮，2+=汇总轮）。
DECOMPOSITION_ROUNDS: dict[str, int] = {}
# G03 确定性失败注入：失败子任务 id 集合 + 会话计数（首会话 failed 终态，
# retry 后的第 2+ 会话走标准剧本成功）。
FAIL_FIRST: set[str] = set()
FAIL_FIRST_ROUNDS: dict[str, int] = {}
AUDIT: list[str] = []

# 拆解剧本（二期任务 8，G03 改造为两段）：runtime 在 prompt 前注入平台上下文块，
# 其中的 taskId 行加拆解标记（SUBTASK_DECOMPOSITION_PROMPT）是委派剧本的激活信号。
# 子任务 spec 的 inputJson 由控制面重写为 {"prompt": title}（不继承父任务 prompt），
# 天然无拆解标记——走标准剧本产出普通文本产物，保证子任务 run SUCCEEDED
# 自动触发 manifest publish。携带 FAIL_FIRST_MARKER 的子任务例外：首个会话
# 发 failed 终态事件（retry 恢复链与硬约束断言的确定性 fixture）。
TASK_ID_PATTERN = re.compile(
    r"taskId=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
DECOMPOSITION_MARKER = "SUBTASK_DECOMPOSITION_PROMPT"
FAIL_FIRST_MARKER = "CONVERSATION_MOCK_FAIL_FIRST"
REST_TIMEOUT_SECONDS = 5.0
_TOKEN_CACHE: dict[str, tuple[float, str]] = {}

STANDARD_DEFINITIONS: tuple[tuple[str, dict[str, Any]], ...] = (
    ("conversation.started", {"status": "created", "object": "response"}),
    ("message.delta", {
        "status": "in_progress", "type": "message", "delta": True,
        "role": "assistant", "content": [{"text": "CONVERSATION_MOCK_DELTA"}],
    }),
    ("message.completed", {
        "status": "completed", "object": "response",
        "output": [{"type": "message", "role": "assistant",
                     "content": [{"text": "CONVERSATION_MOCK_OK"}]}],
    }),
)


def delegation_context(request: dict[str, Any]) -> tuple[str | None, bool]:
    """提取 runtime 平台上下文注入的 taskId 与拆解标记。

    主任务与子任务会话都携带 taskId；拆解标记仅主任务 prompt 有
    （子任务 inputJson 由控制面重写为 {"prompt": title}），
    失败注入子任务则靠 plan 时注册的 FAIL_FIRST 集合路由。
    """
    input_items = request.get("input")
    if not isinstance(input_items, list):
        return None, False
    task_id = None
    marked = False
    for item in input_items:
        if not isinstance(item, dict):
            continue
        content_items = item.get("content")
        if not isinstance(content_items, list):
            continue
        for content in content_items:
            text = content.get("text") if isinstance(content, dict) else None
            if isinstance(text, str):
                match = TASK_ID_PATTERN.search(text)
                if match:
                    task_id = match.group(1)
                if DECOMPOSITION_MARKER in text:
                    marked = True
    return task_id, marked


def control_plane_config() -> dict[str, str] | None:
    """配置缺失返回 None（软失败）：动作降级为 failed 输出而非拒绝服务。"""
    base = os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", "").rstrip("/")
    if not base:
        return None
    return {
        "base": base,
        "token_url": os.environ.get("AGENTTEAMS_OIDC_TOKEN_URL", ""),
        "client_id": os.environ.get("AGENTTEAMS_OIDC_CLIENT_ID", "agentteams-api"),
        "username": os.environ.get("AGENTTEAMS_MCP_USERNAME", ""),
        "password": os.environ.get("AGENTTEAMS_MCP_PASSWORD", ""),
    }


def _bearer_token(config: dict[str, str]) -> str:
    cached = _TOKEN_CACHE.get(config["token_url"])
    if cached and cached[0] > time.time():
        return cached[1]
    body = urlencode({
        "grant_type": "password",
        "client_id": config["client_id"],
        "username": config["username"],
        "password": config["password"],
    }).encode()
    request = urllib.request.Request(
        config["token_url"], data=body, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=REST_TIMEOUT_SECONDS) as response:
        payload = json.loads(response.read() or b"{}")
    token = str(payload["access_token"])
    expires_in = payload.get("expires_in")
    try:
        ttl = max(30.0, float(expires_in) - 30.0) if expires_in is not None else 60.0
    except (TypeError, ValueError):
        ttl = 60.0
    _TOKEN_CACHE[config["token_url"]] = (time.time() + ttl, token)
    return token


def _rest_ok(config: dict[str, str], method: str, path: str,
             body: dict[str, Any]) -> bool:
    request = urllib.request.Request(
        config["base"] + path,
        data=json.dumps(body).encode(),
        method=method,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {_bearer_token(config)}",
            "Idempotency-Key": str(uuid.uuid4()),
        })
    with urllib.request.urlopen(request, timeout=REST_TIMEOUT_SECONDS) as response:
        return 200 <= response.status < 300


def _perform_rest(tool: str, method: str, path: str, body: dict[str, Any]) -> bool:
    """拆解动作执行：任何失败只降级 output 状态，绝不中断 SSE 流（公理一）。"""
    config = control_plane_config()
    if config is None:
        return False
    try:
        return _rest_ok(config, method, path, body)
    except Exception as error:  # noqa: BLE001 - best-effort：所有异常降级为 failed 输出
        print(f"conversation mock decomposition {tool} failed: {error}",
              file=sys.stderr, flush=True)
        return False


def _perform_get(tool: str, path: str) -> Any:
    """汇总轮只读动作（GET 无幂等键）：失败降级为 None，不中断 SSE 流。"""
    config = control_plane_config()
    if config is None:
        return None
    try:
        request = urllib.request.Request(
            config["base"] + path, method="GET",
            headers={"Accept": "application/json",
                     "Authorization": f"Bearer {_bearer_token(config)}"})
        with urllib.request.urlopen(request, timeout=REST_TIMEOUT_SECONDS) as response:
            return json.loads(response.read() or b"[]")
    except Exception as error:  # noqa: BLE001 - best-effort：所有异常降级为 failed 输出
        print(f"conversation mock aggregation {tool} failed: {error}",
              file=sys.stderr, flush=True)
        return None


def decomposition_definitions(task_id: str) -> list[tuple[str, dict[str, Any]]]:
    """G03 拆解剧本（第 1 会话）：plan 3 子任务后即完成，不再代跑状态推进。

    A/B 无依赖、C 依赖 A+B；requiredCapabilities 引导 worker 配额准入。
    子任务由平台真调度（gate→QUEUED→worker→SUCCEEDED），每个子任务自身
    的会话 prompt 为其 title，走标准剧本产出普通文本产物。C 的 title 携带
    失败标记并注册进 FAIL_FIRST：首个会话确定性失败，retry 后成功。
    """
    definitions: list[tuple[str, dict[str, Any]]] = [
        ("conversation.started", {"status": "created", "object": "response"}),
        ("message.delta", {
            "status": "in_progress", "type": "message", "delta": True,
            "role": "assistant", "content": [{"text": "SUBTASK_DECOMPOSITION_DELTA"}],
        }),
    ]

    first_id = str(uuid.uuid4())
    second_id = str(uuid.uuid4())
    third_id = str(uuid.uuid4())
    subtasks: list[dict[str, Any]] = [
        {
            "subtaskId": first_id,
            "title": "抓取邮件",
            "sequence": 1,
            # 服务端 SubtaskSpec 契约要求 dependencyIds 键必须存在（null 被拒）。
            "dependencyIds": [],
            "requiredCapabilities": ["qwenpaw"],
        },
        {
            "subtaskId": second_id,
            "title": "生成摘要",
            "sequence": 2,
            "dependencyIds": [],
            "requiredCapabilities": ["qwenpaw"],
        },
        {
            "subtaskId": third_id,
            # title 随 inputJson.prompt 进入 C 的会话 prompt：首会话命中失败标记。
            "title": f"汇总产物 {FAIL_FIRST_MARKER}",
            "sequence": 3,
            "dependencyIds": [first_id, second_id],
            "requiredCapabilities": ["qwenpaw"],
        },
    ]
    with CONFIG_LOCK:
        FAIL_FIRST.add(third_id)

    definitions.append(("tool.started", {"type": "tool.started", "tool": "plan_subtasks"}))
    ok = _perform_rest("plan_subtasks", "PUT", f"/api/v1/tasks/{task_id}/subtasks",
                       {"subtasks": subtasks})
    definitions.append(("plugin_call_output", {
        "type": "plugin_call_output", "tool": "plan_subtasks",
        "status": "success" if ok else "failed",
    }))

    definitions.append(("message.completed", {
        "status": "completed", "object": "response",
        "output": [{"type": "message", "role": "assistant",
                     "content": [{"text": "SUBTASK_DECOMPOSITION_SUMMARY"}]}],
    }))
    return definitions


def failure_definitions() -> list[tuple[str, dict[str, Any]]]:
    """G03 失败注入剧本：以 failed 终态事件收尾，worker 走 publishFailure
    （任务 FAILED）；相比截断流无解析歧义，retry 后的第 2+ 会话走标准剧本。"""
    return [
        ("conversation.started", {"status": "created", "object": "response"}),
        ("message.delta", {
            "status": "in_progress", "type": "message", "delta": True,
            "role": "assistant", "content": [{"text": "SUBTASK_DECOMPOSITION_DELTA"}],
        }),
        ("message.failed", {"status": "failed", "object": "response",
                             "error": FAIL_FIRST_MARKER}),
    ]


def aggregation_definitions(task_id: str) -> list[tuple[str, dict[str, Any]]]:
    """G03 汇总剧本（第 2+ 会话，主任务 retry 边后）：list_subtasks →
    对每个 SUCCEEDED 子任务取最新 run result → 总结。

    只读 GET 无幂等键；子任务产物与摘要由 get_task_result 事件对透传。
    """
    definitions: list[tuple[str, dict[str, Any]]] = [
        ("conversation.started", {"status": "created", "object": "response"}),
        ("message.delta", {
            "status": "in_progress", "type": "message", "delta": True,
            "role": "assistant", "content": [{"text": "SUBTASK_AGGREGATION_DELTA"}],
        }),
    ]

    def read_pair(tool: str, path: str) -> Any:
        definitions.append(("tool.started", {"type": "tool.started", "tool": tool}))
        payload = _perform_get(tool, path)
        definitions.append(("plugin_call_output", {
            "type": "plugin_call_output", "tool": tool,
            "status": "success" if payload is not None else "failed",
        }))
        return payload

    subtasks = read_pair("list_subtasks", f"/api/v1/tasks/{task_id}/subtasks")
    for item in subtasks if isinstance(subtasks, list) else []:
        if not isinstance(item, dict) or item.get("phase") != "SUCCEEDED":
            continue
        subtask_id = item.get("subtaskId")
        if not subtask_id:
            continue
        runs = read_pair("get_task_result", f"/api/v1/tasks/{subtask_id}/runs")
        latest = (max(runs, key=lambda run: str(run.get("createdAt", "")))
                  if isinstance(runs, list) and runs else None)
        if isinstance(latest, dict) and latest.get("id"):
            read_pair("get_task_result",
                      f"/api/v1/tasks/{subtask_id}/runs/{latest['id']}/result")

    definitions.append(("message.completed", {
        "status": "completed", "object": "response",
        "output": [{"type": "message", "role": "assistant",
                     "content": [{"text": "SUBTASK_AGGREGATION_SUMMARY"}]}],
    }))
    return definitions


def reset_state() -> None:
    global DELAY_SECONDS, DISCONNECT_AFTER
    with CONFIG_LOCK:
        DELAY_SECONDS = DEFAULT_DELAY_SECONDS
        DISCONNECT_AFTER = DEFAULT_DISCONNECT_AFTER
        SESSIONS.clear()
        DECOMPOSITION_ROUNDS.clear()
        FAIL_FIRST.clear()
        FAIL_FIRST_ROUNDS.clear()
        AUDIT.clear()


def audit_log() -> list[str]:
    with CONFIG_LOCK:
        return list(AUDIT)


def configuration() -> dict[str, Any]:
    with CONFIG_LOCK:
        return {"delay_seconds": DELAY_SECONDS, "disconnect_after": DISCONNECT_AFTER}


def set_configuration(delay_seconds: float | None = None, disconnect_after: int | None = None) -> None:
    global DELAY_SECONDS, DISCONNECT_AFTER
    with CONFIG_LOCK:
        if delay_seconds is not None:
            if delay_seconds < 0:
                raise ValueError("delay_seconds must not be negative")
            DELAY_SECONDS = delay_seconds
        if disconnect_after is not None and disconnect_after < 1:
            raise ValueError("disconnect_after must be positive")
        DISCONNECT_AFTER = disconnect_after


def mark_cancelled(session_id: str) -> None:
    with CONFIG_LOCK:
        SESSIONS.setdefault(session_id, {"cursor": 0, "cancelled": False, "events": [], "requests": {}})["cancelled"] = True


def next_cursor(session_id: str) -> int:
    with CONFIG_LOCK:
        state = SESSIONS.setdefault(session_id, {"cursor": 0, "cancelled": False, "events": [], "requests": {}})
        state["cursor"] += 1
        return state["cursor"]


def is_cancelled(session_id: str) -> bool:
    with CONFIG_LOCK:
        return bool(SESSIONS.get(session_id, {}).get("cancelled", False))


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentTeamsQwenPawConversationMock/1.0"

    def log_message(self, format: str, *args: object) -> None:
        # Request bodies, authorization headers and message content are never logged.
        with CONFIG_LOCK:
            AUDIT.append(format % args)

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        path = urlsplit(self.path).path.rstrip("/")
        if path == "/health":
            self.send_json(200, {"status": "ok"})
        elif path == "/debug/config":
            self.send_json(200, configuration())
        elif path == "/api/models/active":
            # Worker 启动 bootstrap 读取 active provider（QwenPawHttpRuntimePort.activeProviderId）。
            self.send_json(200, {"active_llm": {"provider_id": "agentteams-mock",
                                                 "model": "conversation-mock"}})
        else:
            self.send_json(404, {"error": "not found"})

    def do_PUT(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        # Worker native model config bootstrap 的两个 PUT（selection 与 model config）
        # 只需成功返回；配置内容在 mock 中无行为语义。
        path = urlsplit(self.path).path.rstrip("/")
        if path == "/api/models/active" or "/api/models/" in path:
            self.read_json()
            self.send_json(200, {"status": "ok"})
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        path = urlsplit(self.path).path.rstrip("/")
        if path == "/debug/config":
            self.update_configuration()
            return
        if path == "/api/console/cancel":
            self.cancel_session()
            return
        if path == "/api/console/chat":
            self.chat()
            return
        self.send_json(404, {"error": "not found"})

    def update_configuration(self) -> None:
        try:
            body = self.read_json()
            delay = body.get("delay_seconds")
            disconnect = body.get("disconnect_after", DISCONNECT_AFTER)
            if delay is not None:
                delay = float(delay)
            if disconnect is not None:
                disconnect = int(disconnect)
            set_configuration(delay, disconnect)
            self.send_json(200, configuration())
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid mock configuration", "detail": str(error)})

    def cancel_session(self) -> None:
        try:
            session_id = str(self.read_json()["session_id"])
            if not session_id:
                raise ValueError("session_id must not be empty")
            mark_cancelled(session_id)
            self.send_json(200, {"session_id": session_id, "status": "cancelled"})
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid cancel request", "detail": str(error)})

    def chat(self) -> None:
        try:
            request = self.read_json()
            session_id = str(request["session_id"])
            if not session_id:
                raise ValueError("session_id must not be empty")
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": "invalid chat request", "detail": str(error)})
            return

        idempotency_key = self.headers.get("Idempotency-Key", "")
        request_fingerprint = json.dumps(request, sort_keys=True, separators=(",", ":"))
        conflict = False
        new_request = False
        with CONFIG_LOCK:
            state = SESSIONS.setdefault(
                session_id, {"cursor": 0, "cancelled": False, "events": [], "requests": {}}
            )
            previous = state["requests"].get(idempotency_key) if idempotency_key else None
            if previous is not None and previous["fingerprint"] != request_fingerprint:
                conflict = True
            elif idempotency_key:
                # A brand-new key is a new conversation turn, not a retry of
                # an earlier request; retries reuse their original key.
                new_request = previous is None
                state["requests"][idempotency_key] = {
                    "fingerprint": request_fingerprint,
                }

        with CONFIG_LOCK:
            AUDIT.append(
                f"chat session={session_id} agent={bool(self.headers.get('X-Agent-Id'))}"
                f" idempotency-key={idempotency_key}"
            )
        if conflict:
            self.send_json(409, {"error": "idempotency key conflicts with the original request"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        # A deliberately incomplete length makes disconnect tests observable to HTTP clients.
        if configuration()["disconnect_after"] is not None:
            self.send_header("Content-Length", "999999")
        self.end_headers()

        task_id, marked = delegation_context(request)
        events = self.session_events(session_id, new_request, task_id, marked)
        query = parse_qs(urlsplit(self.path).query)
        after_values = [query.get("after", ["0"])[0], self.headers.get("Last-Event-ID", "0")]
        try:
            after = max(int(value or "0") for value in after_values)
        except ValueError:
            self.send_json(400, {"error": "after must be a non-negative cursor"})
            return
        if after < 0:
            self.send_json(400, {"error": "after must be a non-negative cursor"})
            return
        events = [event for event in events if event[0] > after]
        emitted = 0
        try:
            for cursor, event_name, payload in events:
                delay = configuration()["delay_seconds"]
                if delay:
                    time.sleep(delay)
                if is_cancelled(session_id):
                    self.write_event(session_id, "conversation.cancelled",
                                     {"status": "cancelled", "object": "response"})
                    return
                self.write_event_payload(cursor, event_name, payload)
                emitted += 1
                disconnect_after = configuration()["disconnect_after"]
                if disconnect_after is not None and emitted >= disconnect_after:
                    return
        except (BrokenPipeError, ConnectionResetError):
            return
        finally:
            self.flush_stream()

    def session_events(self, session_id: str, is_new_request: bool,
                       delegation_task_id: str | None = None,
                       decomposition_marked: bool = False
                       ) -> list[tuple[int, str, dict[str, Any]]]:
        # 拆解动作的 REST 调用在 CONFIG_LOCK 之外执行，避免阻塞其他会话。
        # 并发首请求可能重复执行动作——验收场景单 worker 单请求，可容忍。
        definitions = None
        if delegation_task_id and not self._session_has_events(session_id):
            if delegation_task_id in FAIL_FIRST:
                # 失败注入子任务：第 1 个会话确定性失败，retry 后的第 2+
                # 个会话走标准剧本成功（G03 retry 恢复链断言）。
                with CONFIG_LOCK:
                    fail_round = FAIL_FIRST_ROUNDS.get(delegation_task_id, 0) + 1
                    FAIL_FIRST_ROUNDS[delegation_task_id] = fail_round
                if fail_round == 1:
                    definitions = failure_definitions()
            elif decomposition_marked:
                # 同一 taskId 第 1 个会话走拆解剧本，第 2+ 个（主任务 retry 边后
                # 的汇总轮）走汇总剧本；计数只对主任务会话递增。
                with CONFIG_LOCK:
                    round_no = DECOMPOSITION_ROUNDS.get(delegation_task_id, 0) + 1
                    DECOMPOSITION_ROUNDS[delegation_task_id] = round_no
                if round_no == 1:
                    definitions = decomposition_definitions(delegation_task_id)
                else:
                    definitions = aggregation_definitions(delegation_task_id)
        with CONFIG_LOCK:
            state = SESSIONS.setdefault(
                session_id, {"cursor": 0, "cancelled": False, "events": [], "requests": {}}
            )
            state.setdefault("events", [])
            state.setdefault("requests", {})
            if not state["events"]:
                if definitions is None:
                    definitions = list(STANDARD_DEFINITIONS)
                for event_name, payload in definitions:
                    state["cursor"] += 1
                    event_payload = dict(payload)
                    event_payload["cursor"] = state["cursor"]
                    state["events"].append((state["cursor"], event_name, event_payload))
                return list(state["events"])
            if is_new_request:
                # Real QwenPaw answers every turn with fresh event ids and
                # content. Replaying the first turn for a new idempotency key
                # would make Manager's source-event dedup discard the whole
                # response, so a new key must emit a fresh cursored round.
                state["round"] = int(state.get("round", 1)) + 1
                round_no = state["round"]
                appended: list[tuple[int, str, dict[str, Any]]] = []
                for event_name, payload in (
                    ("message.delta", {
                        "status": "in_progress", "type": "message", "delta": True,
                        "role": "assistant",
                        "content": [{"text": f"CONVERSATION_MOCK_DELTA_{round_no}"}],
                    }),
                    ("message.completed", {
                        "status": "completed", "object": "response",
                        "output": [{"type": "message", "role": "assistant",
                                     "content": [{"text": f"CONVERSATION_MOCK_OK_{round_no}"}]}],
                    }),
                ):
                    state["cursor"] += 1
                    event_payload = dict(payload)
                    event_payload["cursor"] = state["cursor"]
                    appended.append((state["cursor"], event_name, event_payload))
                state["events"].extend(appended)
                return appended
            return list(state["events"])

    def _session_has_events(self, session_id: str) -> bool:
        with CONFIG_LOCK:
            return bool(SESSIONS.get(session_id, {}).get("events"))

    def write_event(self, session_id: str, event_name: str, payload: dict[str, Any]) -> None:
        payload = dict(payload)
        payload["cursor"] = next_cursor(session_id)
        self.write_event_payload(payload["cursor"], event_name, payload)

    def write_event_payload(self, cursor: int, event_name: str, payload: dict[str, Any]) -> None:
        encoded = (f"id: {cursor}\n"
                   f"event: {event_name}\n"
                   f"data: {json.dumps(payload, separators=(',', ':'))}\n\n").encode("utf-8")
        self.wfile.write(encoded)
        self.wfile.flush()

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 1024 * 1024:
            raise ValueError("request body is too large")
        return json.loads(self.rfile.read(length) or b"{}")

    def flush_stream(self) -> None:
        try:
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
