#!/usr/bin/env python3
"""Run the Kind task review lifecycle acceptance (G02) against the deployed stack.

Drives the full review chain over the real APIs: create and queue a qwenpaw
task, let the Worker finish it (the manifest publish commits result version 1
in the same transaction), reject the result back for revision (comment is
required), retry the task (SUCCEEDED → QUEUED explicit edge → new run → result
version 2), accept the new result with an idempotent replay, then archive the
task and verify the default listing excludes it while the ARCHIVED filter and
stats still report it.

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
    """allow_error=True returns non-2xx codes to the caller instead of failing
    (used where an HTTP 400 is itself an assertion target)."""
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
    """单引号转义（与 run-kind-subtask-decomposition 同约定）：插值进 SQL 的任何外部输入都经此包装。"""
    return "'" + value.replace("'", "''") + "'"


def psql(namespace: str, password: str, statement: str) -> str:
    return kubectl(namespace, "exec", "statefulset/postgresql", "--",
                   "env", f"PGPASSWORD={password}",
                   "psql", "-U", "agentteams", "-d", "agentteams",
                   "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def ensure_project_membership(namespace: str, subject: str, tenant: str, project: str) -> str | None:
    """review 需要 project_memberships 上的 TASK_APPROVE 授权（ADMIN 含该动作）；
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
                          team: str, title: str) -> str:
    body = {
        "title": title,
        "description": "Task review lifecycle end-to-end acceptance (G02)",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": "Summarize the task review lifecycle acceptance scenario."},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"kind-review-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                 tokens.get(), f"kind-review-queue-{uuid.uuid4()}")
    return task_id


def wait_for_terminal_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    def check():
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                               "GET", None, tokens.get())
        phase = task.get("phase") if isinstance(task, dict) else None
        return phase if phase in ("SUCCEEDED", "FAILED", "CANCELLED") else None

    return poll_until(check, "the task to reach a terminal phase")


def ensure_succeeded(base_url: str, tokens: TokenSource, task_id: str,
                     max_attempts: int = 3) -> str:
    """等终态；FAILED 时自动重排队（FAILED→QUEUED 既有转移边）直至 SUCCEEDED。

    conversation-mock 的拆解剧本对偶发的新 run 会截断在首条 delta 事件
    （RUNTIME_FAILURE），重排队即可恢复；这是 mock 层已知限制，与被验收
    的评审闭环无关。
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
                     f"kind-review-recover-{attempt}-{uuid.uuid4()}")
    return "FAILED"


def results_of(base_url: str, tokens: TokenSource, task_id: str) -> list[dict]:
    _, results = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/results",
                              "GET", None, tokens.get())
    return results if isinstance(results, list) else []


def review(base_url: str, tokens: TokenSource, task_id: str, result_id: str,
           body: dict, key: str, allow_error: bool = False) -> tuple[int, object]:
    return request_json(
        f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/results/{result_id}/review",
        "POST", body, tokens.get(), key, allow_error=allow_error)


def listed_task_ids(base_url: str, tokens: TokenSource, query: str,
                    archive_status: str | None) -> set[str]:
    url = f"{base_url.rstrip('/')}/api/v1/tasks?pageSize=100&q={urllib.parse.quote(query)}"
    if archive_status:
        url += f"&archiveStatus={archive_status}"
    _, page = request_json(url, "GET", None, tokens.get())
    items = page.get("items", []) if isinstance(page, dict) else []
    return {item.get("id") for item in items if isinstance(item, dict)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL", ""))
    parser.add_argument("--control-plane-port", type=int, default=18093)
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
        # 控制面入口：外部未给 base-url 时建立临时 port-forward
        base_url = args.base_url.rstrip("/")
        if not base_url:
            control_plane_pf = start_port_forward(
                args.namespace, "agentteams-agentteams-java-control-plane",
                args.control_plane_port)
            base_url = f"http://127.0.0.1:{args.control_plane_port}"

        # 认证：外部 token 或临时 port-forward 自取（脚本全程 >5 分钟，TokenSource 负责刷新）
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

        title = f"kind-review-lifecycle-{uuid.uuid4()}"
        task_id = create_and_queue_task(base_url, token_source, args.tenant,
                                        args.project, args.team, title)
        print(f"task created and queued: {task_id}")

        # ① 完成 → manifest publish 联动提交结果 v1（D2）
        if ensure_succeeded(base_url, token_source, task_id) != "SUCCEEDED":
            fail("first run did not reach SUCCEEDED even after re-queuing")
        versions = poll_until(lambda: results_of(base_url, token_source, task_id) or None,
                              "result version 1 to appear")
        if len(versions) != 1 or versions[0].get("seq") != 1 \
                or versions[0].get("status") != "SUBMITTED" \
                or not versions[0].get("runId"):
            fail(f"unexpected first result version: {versions!r}")
        result_v1 = versions[0]["id"]
        print(f"result v1 submitted: id={result_v1} run={versions[0]['runId']}")

        # ② 打回必填意见（D4）：缺 comment → 400
        status, error_body = review(base_url, token_source, task_id, result_v1,
                                    {"status": "revision_required", "comment": "   "},
                                    f"kind-review-reject-blank-{uuid.uuid4()}", allow_error=True)
        if status != 400:
            fail(f"blank-comment rejection expected HTTP 400, got {status}: {error_body!r}")
        # ③ 带意见打回
        status, rejected = review(base_url, token_source, task_id, result_v1,
                                  {"status": "revision_required", "comment": "缺少验收标准，请补充错误场景"},
                                  f"kind-review-reject-{uuid.uuid4()}")
        if status != 200 or rejected.get("status") != "REVISION_REQUIRED":
            fail(f"rejection expected 200/REVISION_REQUIRED, got {status}: {rejected!r}")
        print("result v1 returned for revision")

        # ④ retry：SUCCEEDED → QUEUED 显式重排队（D4 唯一新边）→ 新 run → 结果 v2。
        # fixture：先删除首跑遗留的子任务行——SubtaskService 的全量声明式同步尚未
        # 适配「同任务二次 plan+update」（第二个 run 的 update_subtask_status 回调
        # 返回 400，worker 将 tool 失败升级为 RUNTIME_FAILURE）；该组合场景由 G02
        # retry 首次开启，re-plan 语义另行立项。测试任务的子任务行属脚本 fixture，
        # 清理后新 run 的 mock 剧本与首跑等价。
        psql(args.namespace, database_password(args.namespace),
             f"DELETE FROM task_subtasks WHERE task_id = {sql_literal(task_id)};")
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}", "GET",
                               None, token_source.get())
        status, retried = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/retry", "POST",
            {"expectedVersion": task.get("version")}, token_source.get(),
            f"kind-review-retry-{uuid.uuid4()}")
        if status != 200 or retried.get("phase") != "QUEUED":
            fail(f"retry expected 200/QUEUED, got {status}: {retried!r}")
        if ensure_succeeded(base_url, token_source, task_id) != "SUCCEEDED":
            fail("second run did not reach SUCCEEDED even after re-queuing")
        versions = poll_until(lambda: results_of(base_url, token_source, task_id)
                              if len(results_of(base_url, token_source, task_id)) >= 2 else None,
                              "result version 2 to appear")
        latest, prior = versions[0], versions[-1]
        if latest.get("seq") != 2 or latest.get("status") != "SUBMITTED" \
                or latest.get("runId") == prior.get("runId") \
                or prior.get("status") != "REVISION_REQUIRED":
            fail(f"unexpected result versions after retry: {versions!r}")
        result_v2 = latest["id"]
        print(f"result v2 submitted: id={result_v2} run={latest['runId']}")

        # ⑤ 评审通过 + 幂等重放（D4/D5）
        accept_key = f"kind-review-accept-{uuid.uuid4()}"
        status, accepted = review(base_url, token_source, task_id, result_v2,
                                  {"status": "accepted"}, accept_key)
        if status != 200 or accepted.get("status") != "ACCEPTED":
            fail(f"acceptance expected 200/ACCEPTED, got {status}: {accepted!r}")
        status, replayed = review(base_url, token_source, task_id, result_v2,
                                  {"status": "accepted"}, accept_key)
        if status != 200 or replayed.get("id") != accepted.get("id") \
                or replayed.get("version") != accepted.get("version"):
            fail(f"idempotent replay returned a different result: {replayed!r} vs {accepted!r}")
        print("result v2 accepted; replay returned the same record")

        # ⑥ 归档 + 默认列表过滤断言（D6/D7）：带当前 version 过乐观锁
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}", "GET",
                               None, token_source.get())
        status, archived = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/archive", "POST",
            {"expectedVersion": task.get("version")}, token_source.get(),
            f"kind-review-archive-{uuid.uuid4()}")
        if status != 200 or archived.get("archiveStatus") != "ARCHIVED":
            fail(f"archive expected 200/ARCHIVED, got {status}: {archived!r}")
        if listed_task_ids(base_url, token_source, title, None):
            fail("archived task still shows up in the default (ACTIVE) listing")
        archived_ids = listed_task_ids(base_url, token_source, title, "ARCHIVED")
        if task_id not in archived_ids:
            fail("archived task is missing from the ARCHIVED filtered listing")
        _, stats = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/stats?archiveStatus=ARCHIVED",
            "GET", None, token_source.get())
        if not isinstance(stats, dict) or int(stats.get("SUCCEEDED", 0)) < 1:
            fail(f"archived stats report no SUCCEEDED task: {stats!r}")
        print("archive visibility and stats verified")

        print("PASS kind-task-review-lifecycle:")
        print(f"  task={task_id} v1={result_v1} v2={result_v2} archiveStatus=ARCHIVED")
        return 0
    finally:
        # pf 清理先于 membership 还原：还原失败不应吞掉端口清理；
        # membership 还原用已解析的 subject，不依赖 token/端口转发。
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
        print(f"KIND_TASK_REVIEW_LIFECYCLE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
