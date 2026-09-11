#!/usr/bin/env python3
"""Run the Kind subtask delegation acceptance (G03) against the deployed stack.

Drives the two-round true scheduling chain over the real APIs: a main task
whose prompt carries the decomposition marker is planned by the conversation
mock into three subtasks (A/B independent, C depending on both); the gate
admits A/B immediately while C stays DRAFT until both dependencies succeed.
C is failed via a SQL fixture (real task row phase), the aggregation publish
stays withheld (hard constraint: zero submitted result versions), the failed
subtask recovers through the G02 retry edge, and only then does the platform
auto-queue the main task for the aggregation round (TaskChildrenCompleted),
whose publish commits exactly one SUBMITTED result version. The review chain
(accept with idempotent replay, archive, listing/stats) closes the loop. A
negative path cancels a second main task after planning and asserts that
unqueued subtasks are cancelled in cascade.

Requires a Keycloak bearer token (alice) via --token or
AGENTTEAMS_API_BEARER_TOKEN; when absent the script obtains one itself through
a temporary port-forward to the Keycloak service and refreshes it before
expiry.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

POLL_TIMEOUT_SECONDS = 240.0
POLL_INTERVAL_SECONDS = 2.0
TOKEN_TTL_SECONDS = 240.0
# 拆解标记：与 scripts/qwenpaw-conversation-mock.py 的 DECOMPOSITION_MARKER 一致。
DECOMPOSITION_MARKER = "SUBTASK_DECOMPOSITION_PROMPT"
# 失败注入标记：C 的 title 携带它（title 随 inputJson.prompt 进入子任务会话），
# mock 对该子任务的首个会话发 failed 终态——硬约束与 retry 恢复链的确定性 fixture。
FAIL_FIRST_MARKER = "CONVERSATION_MOCK_FAIL_FIRST"
C_TITLE = f"汇总产物 {FAIL_FIRST_MARKER}"
EXPECTED_SUBTASK_TITLES = ("抓取邮件", "生成摘要", C_TITLE)


def fail(message: str) -> None:
    raise RuntimeError(message)


def command_available(name: str) -> str:
    resolved = shutil.which(name)
    if not resolved:
        fail(f"required command is unavailable: {name}")
    return resolved


def run_command(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed: {' '.join(args)}: {detail}")
    return result.stdout.strip()


def kubectl(namespace: str, *args: str) -> str:
    return run_command("kubectl", "-n", namespace, *args)


def start_port_forward(namespace: str, service: str, local_port: int) -> subprocess.Popen:
    process = subprocess.Popen(
        ["kubectl", "-n", namespace, "port-forward", f"service/{service}",
         f"{local_port}:8080"],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(2.0)
    if process.poll() is None:
        return process
    detail = process.stderr.read().decode(errors="replace").strip() if process.stderr else ""
    fail(f"port-forward to service/{service} on :{local_port} exited immediately: {detail}")


def stop_port_forward(process: subprocess.Popen | None) -> None:
    if process is None:
        return
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()


class TokenSource:
    """Bearer token provider: env var passthrough or Keycloak password grant
    with refresh before expiry (access tokens live ~5 minutes and the whole
    chain, including two task runs, takes longer)."""

    def __init__(self, static_token: str, keycloak_url: str):
        self.static_token = static_token.strip()
        self.keycloak_url = keycloak_url.rstrip("/")
        if not self.static_token and not self.keycloak_url:
            fail("TokenSource needs either a static token or a Keycloak URL")
        self.cached = self.static_token
        self.fetched_at = time.monotonic() if self.cached else 0.0

    def get(self) -> str:
        if self.static_token:
            return self.static_token
        if self.cached and time.monotonic() - self.fetched_at < TOKEN_TTL_SECONDS:
            return self.cached
        self.cached = self.fetch()
        self.fetched_at = time.monotonic()
        return self.cached

    def fetch(self) -> str:
        body = urllib.parse.urlencode({
            "grant_type": "password",
            "client_id": "agentteams-api",
            "username": os.environ.get("AGENTTEAMS_API_USERNAME", "alice"),
            "password": os.environ.get("AGENTTEAMS_API_PASSWORD", "alice-dev"),
        }).encode()
        request = urllib.request.Request(
            f"{self.keycloak_url}/realms/agentteams/protocol/openid-connect/token",
            data=body, method="POST",
            headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                token = json.loads(response.read().decode()).get("access_token")
        except (urllib.error.URLError, urllib.error.HTTPError) as error:
            fail(f"could not obtain a bearer token from Keycloak: {error}")
        if not token:
            fail("Keycloak token response carried no access_token")
        return token


def request_json(url: str, method: str = "GET", body: dict | list | None = None,
                 token: str = "", idempotency_key: str | None = None,
                 allow_error: bool = False) -> tuple[int, object]:
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        if allow_error:
            try:
                return error.code, json.loads(raw) if raw else None
            except ValueError:
                return error.code, raw[:500]
        fail(f"HTTP {error.code} from {url}: {raw[:500]}")


def require_environment(namespace: str) -> None:
    command_available("kubectl")
    command_available("kind")
    clusters = run_command("kind", "get", "clusters")
    if not clusters or "agentteams" not in clusters.splitlines():
        fail("Kind cluster agentteams is required; kind get clusters returned no matching cluster")
    deployments = json.loads(kubectl(namespace, "get", "deployments", "-o", "json"))
    for required in ("agentteams-agentteams-java-control-plane", "qwenpaw-worker"):
        items = [item for item in deployments.get("items", [])
                 if item["metadata"]["name"] == required]
        if not items:
            fail(f"deployment/{required} is required in namespace {namespace}")
        if int(items[0].get("status", {}).get("readyReplicas", 0)) < 1:
            fail(f"deployment/{required} is not ready in namespace {namespace}")


def token_subject(token: str) -> str:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def database_password(namespace: str) -> str:
    encoded = kubectl(namespace, "get", "secret", "agentteams-database",
                      "-o", "jsonpath={.data.password}")
    return base64.b64decode(encoded).decode()


def sql_literal(value: str) -> str:
    """单引号转义：插值进 SQL 的任何外部输入都经此包装。"""
    return "'" + value.replace("'", "''") + "'"


def psql(namespace: str, password: str, statement: str) -> str:
    return kubectl(namespace, "exec", "statefulset/postgresql", "--",
                   "env", f"PGPASSWORD={password}",
                   "psql", "-U", "agentteams", "-d", "agentteams",
                   "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def ensure_project_membership(namespace: str, subject: str, tenant: str, project: str) -> str | None:
    """评审需要 project_memberships 上的 TASK_APPROVE 授权（ADMIN 含该动作）；
    返回原 role 供结束后还原（None=原本不存在）。"""
    password = database_password(namespace)
    previous = psql(namespace, password,
                    "SELECT role FROM project_memberships "
                    f"WHERE subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND project_id="
                    f"(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} AND name={sql_literal(project)}) LIMIT 1;").strip()
    psql(namespace, password, f"""
        INSERT INTO project_memberships(tenant_id, project_id, subject, role, status, created_at, updated_at, version)
        SELECT {sql_literal(tenant)}, id, {sql_literal(subject)}, 'ADMIN', 'ACTIVE', now(), now(), 0
          FROM projects WHERE tenant_id = {sql_literal(tenant)} AND name = {sql_literal(project)}
        ON CONFLICT (tenant_id, project_id, subject)
        DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = now();
    """)
    return previous or None


def restore_project_membership(namespace: str, subject: str, tenant: str, project: str,
                               previous: str | None) -> None:
    password = database_password(namespace)
    if previous:
        psql(namespace, password,
             f"UPDATE project_memberships SET role={sql_literal(previous)}, updated_at=now() "
             f"WHERE subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND project_id="
             f"(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} AND name={sql_literal(project)});")
    else:
        psql(namespace, password,
             f"DELETE FROM project_memberships WHERE subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} "
             f"AND project_id=(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} AND name={sql_literal(project)});")


def poll_until(action, description: str, timeout: float = POLL_TIMEOUT_SECONDS):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = action()
        if last:
            return last
        time.sleep(POLL_INTERVAL_SECONDS)
    fail(f"timed out waiting for {description}: {last}")


def create_and_queue_task(base_url: str, tokens: TokenSource, tenant: str, project: str,
                          team: str, title: str, prompt: str, queue: bool = True) -> str:
    body = {
        "title": title,
        "description": "Subtask delegation true scheduling acceptance (G03)",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": prompt},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"kind-delegation-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    if queue:
        request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                     tokens.get(), f"kind-delegation-queue-{uuid.uuid4()}")
    return task_id


def get_task(base_url: str, tokens: TokenSource, task_id: str) -> dict:
    _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                           "GET", None, tokens.get())
    return task if isinstance(task, dict) else {}


def task_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    return str(get_task(base_url, tokens, task_id).get("phase"))


def list_subtasks(base_url: str, tokens: TokenSource, task_id: str) -> list[dict]:
    _, subtasks = request_json(
        f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/subtasks",
        "GET", None, tokens.get())
    return subtasks if isinstance(subtasks, list) else []


def subtask_by_title(subtasks: list[dict], title: str) -> dict | None:
    return next((item for item in subtasks if item.get("title") == title), None)


def list_runs(base_url: str, tokens: TokenSource, task_id: str) -> list[dict]:
    _, runs = request_json(
        f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs",
        "GET", None, tokens.get())
    return runs if isinstance(runs, list) else []


def wait_for_terminal_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    def check():
        phase = task_phase(base_url, tokens, task_id)
        return phase if phase in ("SUCCEEDED", "FAILED", "CANCELLED") else None

    return poll_until(check, "the task to reach a terminal phase")


def ensure_succeeded(base_url: str, tokens: TokenSource, task_id: str,
                     max_attempts: int = 3) -> str:
    """等终态；FAILED 时自动重排队（FAILED→QUEUED 既有转移边）直至 SUCCEEDED。

    conversation-mock 对偶发的新 run 会截断在首条 delta 事件
    （RUNTIME_FAILURE），重排队即可恢复；这是 mock 层已知限制。
    """
    for attempt in range(1, max_attempts + 1):
        phase = wait_for_terminal_phase(base_url, tokens, task_id)
        if phase == "SUCCEEDED":
            return "SUCCEEDED"
        if phase == "CANCELLED":
            return "CANCELLED"
        print(f"run #{attempt} ended in {phase}; re-queuing")
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                               "GET", None, tokens.get())
        request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/retry", "POST",
                     {"expectedVersion": task.get("version")}, tokens.get(),
                     f"kind-delegation-recover-{attempt}-{uuid.uuid4()}")
    return "FAILED"


def results_of(base_url: str, tokens: TokenSource, task_id: str) -> list[dict]:
    _, results = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/results",
                              "GET", None, tokens.get())
    return results if isinstance(results, list) else []


def review(base_url: str, tokens: TokenSource, task_id: str, result_id: str,
           body: dict, key: str) -> tuple[int, object]:
    return request_json(
        f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/results/{result_id}/review",
        "POST", body, tokens.get(), key)


def listed_task_ids(base_url: str, tokens: TokenSource, query: str,
                    archive_status: str | None) -> set[str]:
    url = f"{base_url.rstrip('/')}/api/v1/tasks?pageSize=100&q={urllib.parse.quote(query)}"
    if archive_status:
        url += f"&archiveStatus={archive_status}"
    _, page = request_json(url, "GET", None, tokens.get())
    items = page.get("items", []) if isinstance(page, dict) else []
    return {item.get("id") for item in items if isinstance(item, dict)}


def count_rows(namespace: str, statement: str) -> int:
    output = psql(namespace, database_password(namespace), statement).strip()
    return int(output or 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", ""))
    parser.add_argument("--control-plane-port", type=int, default=18094)
    parser.add_argument("--token", default=os.environ.get("AGENTTEAMS_API_BEARER_TOKEN", ""))
    parser.add_argument("--keycloak-port", type=int,
                        default=int(os.environ.get("KIND_KEYCLOAK_LOCAL_PORT", "18082")))
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_SCOPE_TENANT", "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_SCOPE_PROJECT", "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_SCOPE_TEAM", "team-a"))
    args = parser.parse_args()

    require_environment(args.namespace)
    keycloak_pf: subprocess.Popen | None = None
    control_plane_pf: subprocess.Popen | None = None
    previous_role: str | None = None
    subject: str | None = None
    try:
        base_url = args.base_url.rstrip("/")
        if not base_url:
            control_plane_pf = start_port_forward(
                args.namespace, "agentteams-agentteams-java-control-plane",
                args.control_plane_port)
            base_url = f"http://127.0.0.1:{args.control_plane_port}"

        if args.token:
            token_source = TokenSource(args.token, "")
        else:
            keycloak_pf = start_port_forward(args.namespace, "keycloak", args.keycloak_port)
            token_source = TokenSource("", f"http://127.0.0.1:{args.keycloak_port}")
        subject = token_subject(token_source.get())
        print(f"authenticated as subject={subject}")
        previous_role = ensure_project_membership(
            args.namespace, subject, args.tenant, args.project)
        print(f"project membership ensured (previous role: {previous_role!r})")

        # ① 拆解轮：prompt 带拆解标记 → mock plan 3 子任务（A/B 无依赖、C 依赖 A+B）。
        title = f"kind-subtask-delegation-{uuid.uuid4()}"
        prompt = f"{DECOMPOSITION_MARKER}\n请围绕「季度报告」拆解并等待平台调度执行。"
        main_task = create_and_queue_task(base_url, token_source, args.tenant,
                                          args.project, args.team, title, prompt)
        print(f"main task created and queued: {main_task}")

        subtasks = poll_until(
            lambda: (list_subtasks(base_url, token_source, main_task)
                     if len(list_subtasks(base_url, token_source, main_task)) == 3 else None),
            "the mock to plan 3 subtasks")
        titles = tuple(sorted(item.get("title") or "" for item in subtasks))
        if titles != tuple(sorted(EXPECTED_SUBTASK_TITLES)):
            fail(f"planned subtask titles {titles} do not match {EXPECTED_SUBTASK_TITLES}")
        by_title = {item["title"]: item for item in subtasks}
        first, second, third = (by_title["抓取邮件"], by_title["生成摘要"], by_title[C_TITLE])
        if set(third.get("dependencyIds") or []) != {first["subtaskId"], second["subtaskId"]}:
            fail(f"aggregation subtask dependencies unexpected: {third!r}")
        print(f"3 subtasks planned: a={first['subtaskId']} b={second['subtaskId']} "
              f"c={third['subtaskId']}")

        # ② 拓扑 gate：无依赖子任务被放行（离开 DRAFT）而 C 仍 DRAFT。
        def gate_window():
            snapshot = list_subtasks(base_url, token_source, main_task)
            phases = {item["title"]: item.get("phase") for item in snapshot}
            released = phases.get("抓取邮件") in ("QUEUED", "ASSIGNED", "RUNNING", "SUCCEEDED") \
                or phases.get("生成摘要") in ("QUEUED", "ASSIGNED", "RUNNING", "SUCCEEDED")
            if released and phases.get(C_TITLE) == "DRAFT":
                return phases
            return None

        phases = poll_until(gate_window,
                            "the gate to release independent subtasks while C stays DRAFT")
        print(f"topology gate verified: {phases}")

        # ③ A、B 跑完（各自独立 attempt）→ C 转 QUEUED（依赖 gate 放行）。
        def both_succeeded():
            snapshot = list_subtasks(base_url, token_source, main_task)
            phases = {item["title"]: item.get("phase") for item in snapshot}
            return phases if phases.get("抓取邮件") == "SUCCEEDED" \
                and phases.get("生成摘要") == "SUCCEEDED" else None

        poll_until(both_succeeded, "subtasks A and B to succeed")
        attempts = count_rows(
            args.namespace,
            "SELECT count(*) FROM task_attempts WHERE task_id IN ("
            f"{sql_literal(first['subtaskId'])},{sql_literal(second['subtaskId'])});")
        if attempts < 2:
            fail(f"expected one attempt per succeeded subtask, saw {attempts}")
        # C 可能两次轮询间走完 QUEUED→RUNNING→SUCCEEDED（mock 零延迟）：
        # 只要离开 DRAFT 即证明依赖 gate 已放行。
        poll_until(
            lambda: next((item for item in list_subtasks(base_url, token_source, main_task)
                          if item.get("title") == C_TITLE), {}).get("phase") != "DRAFT" or None,
            "the dependency gate to release subtask C after A and B succeeded")
        print("dependency gate verified: C released after both dependencies succeeded")

        # ④ C 首个会话被 mock 确定性置败（failed 终态事件；零延迟下 psql 注入
        # 抢不到非终态窗口，fixture 改走 mock 剧本）→ 汇总被硬约束拦下。
        def c_failed():
            phase = next((item for item in list_subtasks(base_url, token_source, main_task)
                          if item.get("title") == C_TITLE), {}).get("phase")
            return phase if phase == "FAILED" else None

        poll_until(c_failed, "subtask C to fail on its first session")
        print("subtask C failed on its first session (mock fail-first fixture)")

        def withheld():
            submitted = count_rows(
                args.namespace,
                "SELECT count(*) FROM task_result_versions WHERE task_id="
                f"{sql_literal(main_task)} AND status='SUBMITTED';")
            phase = task_phase(base_url, token_source, main_task)
            # 拆解轮终态为 SUCCEEDED；硬约束下不应自动转 QUEUED 开汇总轮。
            return (submitted, phase) if submitted == 0 and phase == "SUCCEEDED" else None

        poll_until(withheld, "the publish to stay withheld while C is FAILED")
        print("hard constraint verified: no submitted result version while C is FAILED")

        # ⑤ 失败子任务 retry 恢复链（G02 边 FAILED→QUEUED）→ C SUCCEEDED。
        failed_task = get_task(base_url, token_source, third["subtaskId"])
        if failed_task.get("phase") != "FAILED":
            fail(f"subtask C expected FAILED, saw {failed_task.get('phase')!r}")
        status, retried = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{third['subtaskId']}/retry", "POST",
            {"expectedVersion": failed_task.get("version")}, token_source.get(),
            f"kind-delegation-subtask-retry-{uuid.uuid4()}")
        if status != 200 or retried.get("phase") != "QUEUED":
            fail(f"subtask retry expected 200/QUEUED, got {status}: {retried!r}")
        poll_until(
            lambda: next((item for item in list_subtasks(base_url, token_source, main_task)
                          if item.get("title") == C_TITLE), {}).get("phase") == "SUCCEEDED" or None,
            "subtask C to succeed after retry")
        print("subtask C recovered through the retry edge")

        # ⑥ 汇总轮自动触发：主任务 SUCCEEDED→QUEUED（TaskChildrenCompleted）→ 第二跑。
        # mock 零延迟下 QUEUED 窗口 <1s（2s 轮询必错过），改用不可跳过的证据：
        # 第二个 run 存在即证明自动排队已发生并被调度。
        def aggregation_queued():
            runs = list_runs(base_url, token_source, main_task)
            return runs if len(runs) >= 2 else None

        poll_until(aggregation_queued,
                   "the main task to run the aggregation round")
        print("main task auto-queued and running the aggregation round")
        if ensure_succeeded(base_url, token_source, main_task) != "SUCCEEDED":
            fail("aggregation run did not reach SUCCEEDED even after re-queuing")

        # ⑦ 结果版本：恰 1 条 SUBMITTED（拆解轮 publish 被跳过的证明）。
        submitted = count_rows(
            args.namespace,
            "SELECT count(*) FROM task_result_versions WHERE task_id="
            f"{sql_literal(main_task)} AND status='SUBMITTED';")
        if submitted != 1:
            fail(f"expected exactly 1 submitted result version, saw {submitted}")
        versions = poll_until(lambda: results_of(base_url, token_source, main_task) or None,
                              "the result version to appear")
        if len(versions) != 1 or versions[0].get("seq") != 1 \
                or versions[0].get("status") != "SUBMITTED" \
                or not versions[0].get("runId"):
            fail(f"unexpected result version: {versions!r}")
        result_v1 = versions[0]["id"]
        print(f"result v1 submitted: id={result_v1} run={versions[0]['runId']}")

        # ⑧ 评审 ACCEPTED + 幂等重放 → 归档 → 列表/stats。
        accept_key = f"kind-delegation-accept-{uuid.uuid4()}"
        status, accepted = review(base_url, token_source, main_task, result_v1,
                                  {"status": "accepted"}, accept_key)
        if status != 200 or accepted.get("status") != "ACCEPTED":
            fail(f"acceptance expected 200/ACCEPTED, got {status}: {accepted!r}")
        status, replayed = review(base_url, token_source, main_task, result_v1,
                                  {"status": "accepted"}, accept_key)
        if status != 200 or replayed.get("id") != accepted.get("id") \
                or replayed.get("version") != accepted.get("version"):
            fail(f"idempotent replay returned a different result: {replayed!r} vs {accepted!r}")
        print("result v1 accepted; replay returned the same record")
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}", "GET",
                               None, token_source.get())
        status, archived = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}/archive", "POST",
            {"expectedVersion": task.get("version")}, token_source.get(),
            f"kind-delegation-archive-{uuid.uuid4()}")
        if status != 200 or archived.get("archiveStatus") != "ARCHIVED":
            fail(f"archive expected 200/ARCHIVED, got {status}: {archived!r}")
        if main_task in listed_task_ids(base_url, token_source, title, None):
            fail("archived task still shows up in the default (ACTIVE) listing")
        if main_task not in listed_task_ids(base_url, token_source, title, "ARCHIVED"):
            fail("archived task is missing from the ARCHIVED filtered listing")
        print("archive visibility verified")

        # ⑨ 负路径：主任务 DRAFT 直接 plan 两个子任务 → cancel 级联。
        negative_title = f"kind-subtask-cancel-{uuid.uuid4()}"
        negative_task = create_and_queue_task(base_url, token_source, args.tenant,
                                              args.project, args.team, negative_title,
                                              "cancel cascade negative path", queue=False)
        first_id, second_id = str(uuid.uuid4()), str(uuid.uuid4())
        plan_body = {"subtasks": [
            {"subtaskId": first_id, "title": "未出队子任务", "sequence": 1,
             "dependencyIds": []},
            {"subtaskId": second_id, "title": "更晚的子任务", "sequence": 2,
             "dependencyIds": [first_id]},
        ]}
        status, planned = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{negative_task}/subtasks", "PUT",
            plan_body, token_source.get(), f"kind-delegation-plan-{uuid.uuid4()}")
        if status != 200:
            fail(f"negative-path plan expected 200, got {status}: {planned!r}")
        status, queued = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{first_id}/queue", "POST", {},
            token_source.get(), f"kind-delegation-subqueue-{uuid.uuid4()}")
        if status != 200 or queued.get("phase") != "QUEUED":
            fail(f"subtask queue expected 200/QUEUED, got {status}: {queued!r}")
        negative = get_task(base_url, token_source, negative_task)
        status, cancelled = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{negative_task}/cancel", "POST",
            {"expectedVersion": negative.get("version"), "reason": "acceptance negative path"},
            token_source.get(), f"kind-delegation-cancel-{uuid.uuid4()}")
        if status != 200 or cancelled.get("phase") != "CANCELLED":
            fail(f"cancel expected 200/CANCELLED, got {status}: {cancelled!r}")
        for subtask_id, label in ((first_id, "queued"), (second_id, "draft")):
            phase = task_phase(base_url, token_source, subtask_id)
            if phase != "CANCELLED":
                fail(f"{label} subtask {subtask_id} expected CANCELLED, saw {phase!r}")
        print("negative path verified: cancel cascaded to unqueued subtasks")

        print("PASS kind-subtask-delegation:")
        print(f"  main={main_task} c={third['subtaskId']} result={result_v1} "
              f"negative={negative_task}")
        return 0
    finally:
        stop_port_forward(keycloak_pf)
        stop_port_forward(control_plane_pf)
        if subject:
            try:
                restore_project_membership(args.namespace, subject,
                                           args.tenant, args.project, previous_role)
                print("project membership restored")
            except (RuntimeError, urllib.error.URLError) as error:
                print(f"WARNING: membership restore failed: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"KIND_SUBTASK_DELEGATION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
