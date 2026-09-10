#!/usr/bin/env python3
"""L5 验收：任务评审闭环（G02）真模型一轮（PDF 交付物→评审→重交付）。

在 L5 主机内执行（对齐 run-l5-subtask-decomposition 的执行约定）：脚本自行解析
ClusterIP 直连 control-plane 与 Keycloak，经 k3s kubectl 完成数据库 fixture。
流程：alice token → 授权 project ADMIN（还原）→ 创建真模型任务（PDF 交付物）
→ 完成 → 结果 v1 SUBMITTED → 打回（缺 comment 400；带 comment REVISION_REQUIRED）
→ retry（SUCCEEDED→QUEUED 显式边）→ 新 run 真模型重交付 v2 → 评审通过
（幂等重放同记录）→ 归档 → 默认列表不含 / ARCHIVED 过滤可见 / stats 断言。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

POLL_TIMEOUT_SECONDS = 900.0   # 真模型（deepseek）执行与评审全链，放宽轮询上限
POLL_INTERVAL_SECONDS = 5.0
TOKEN_TTL_SECONDS = 240.0
KUBECTL = ("sudo", "/usr/local/bin/k3s", "kubectl")


def fail(message: str) -> None:
    raise RuntimeError(message)


def run_command(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"command failed: {' '.join(args)}: {detail}")
    return result.stdout.strip()


def kubectl(namespace: str, *args: str) -> str:
    return run_command(*KUBECTL, "-n", namespace, *args)


def cluster_service_url(namespace: str, service: str, port: int = 8080) -> str:
    ip = kubectl(namespace, "get", "svc", service,
                 "-o", "jsonpath={.spec.clusterIP}")
    if not ip:
        fail(f"service/{service} in namespace {namespace} has no cluster IP")
    return f"http://{ip}:{port}"


class TokenSource:
    """Bearer token provider：Keycloak password grant + 过期前刷新
    （access token 约 5 分钟，全链含两次真模型 run 需要刷新）。"""

    def __init__(self, keycloak_url: str):
        self.keycloak_url = keycloak_url.rstrip("/")
        if not self.keycloak_url:
            fail("TokenSource needs a Keycloak URL")
        self.cached = ""
        self.fetched_at = 0.0

    def get(self) -> str:
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
    """allow_error=True 时把非 2xx 返回给调用方（400 本身是断言目标）。"""
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
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


def ensure_project_membership(namespace: str, subject: str, tenant: str,
                              project: str) -> str | None:
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


def restore_project_membership(namespace: str, subject: str, tenant: str,
                               project: str, previous: str | None) -> None:
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


def create_and_queue_task(base_url: str, tokens: TokenSource, tenant: str,
                          project: str, team: str, title: str) -> str:
    """真模型任务：要求产出《季度项目报告》PDF 交付物内容稿（v1 交付物）。"""
    prompt = (
        "请撰写《季度项目报告》PDF 交付物的完整内容稿：包含报告标题、摘要，"
        "以及三个章节（项目进展、风险与依赖、下季度计划），每章至少列出两个要点；"
        "文末给出交付文件名 report.pdf 与预估页数。以 markdown 输出全文。")
    body = {
        "title": title,
        "description": "Task review lifecycle acceptance on L5 (G02, real model)",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": prompt},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"l5-review-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                 tokens.get(), f"l5-review-queue-{uuid.uuid4()}")
    return task_id


def wait_for_terminal_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    def check():
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                               "GET", None, tokens.get())
        phase = task.get("phase") if isinstance(task, dict) else None
        return phase if phase in ("SUCCEEDED", "FAILED", "CANCELLED") else None

    return poll_until(check, "the task to reach a terminal phase")


def ensure_succeeded(base_url: str, tokens: TokenSource, task_id: str,
                     max_attempts: int = 2) -> str:
    """等终态；FAILED 时重排队（FAILED→QUEUED 既有边）直至 SUCCEEDED
    （真模型偶发失败重试，上限 2 次避免成本失控）。"""
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
                     f"l5-review-recover-{attempt}-{uuid.uuid4()}")
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
    parser.add_argument("--base-url", default="")
    parser.add_argument("--keycloak-url", default="")
    parser.add_argument("--tenant", default="tenant-a")
    parser.add_argument("--project", default="project-a")
    parser.add_argument("--team", default="team-a")
    args = parser.parse_args()

    previous_role: str | None = None
    subject: str | None = None
    try:
        # 入口：外部未给地址时自行解析 ClusterIP（L5 主机内直连 svc）
        base_url = args.base_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-control-plane")
        keycloak_url = args.keycloak_url.rstrip("/") or cluster_service_url(
            args.namespace, "keycloak")
        print(f"control-plane={base_url} keycloak={keycloak_url}")

        token_source = TokenSource(keycloak_url)
        subject = token_subject(token_source.get())
        print(f"authenticated as subject={subject}")
        previous_role = ensure_project_membership(
            args.namespace, subject, args.tenant, args.project)
        print(f"project membership ensured (previous role: {previous_role!r})")

        title = f"l5-review-lifecycle-{uuid.uuid4()}"
        task_id = create_and_queue_task(base_url, token_source, args.tenant,
                                        args.project, args.team, title)
        print(f"real-model task created and queued: {task_id}")

        # ① 真模型完成 → manifest publish 联动提交结果 v1（D2）
        if ensure_succeeded(base_url, token_source, task_id) != "SUCCEEDED":
            fail("first real-model run did not reach SUCCEEDED even after re-queuing")
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
                                    {"status": "revision_required", "comment": "  "},
                                    f"l5-review-reject-blank-{uuid.uuid4()}", allow_error=True)
        if status != 400:
            fail(f"blank-comment rejection expected HTTP 400, got {status}: {error_body!r}")
        # ③ 带意见打回（评审对象：PDF 交付物 v1）
        status, rejected = review(base_url, token_source, task_id, result_v1,
                                  {"status": "revision_required",
                                   "comment": "请补充结论章节，并在文首增加版本号与日期元数据"},
                                  f"l5-review-reject-{uuid.uuid4()}")
        if status != 200 or rejected.get("status") != "REVISION_REQUIRED":
            fail(f"rejection expected 200/REVISION_REQUIRED, got {status}: {rejected!r}")
        print("result v1 returned for revision")

        # ④ retry：SUCCEEDED → QUEUED 显式重排队（D4 唯一新边）→ 新 run 真模型重交付 v2
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}", "GET",
                               None, token_source.get())
        status, retried = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/retry", "POST",
            {"expectedVersion": task.get("version")}, token_source.get(),
            f"l5-review-retry-{uuid.uuid4()}")
        if status != 200 or retried.get("phase") != "QUEUED":
            fail(f"retry expected 200/QUEUED, got {status}: {retried!r}")
        if ensure_succeeded(base_url, token_source, task_id) != "SUCCEEDED":
            fail("second real-model run did not reach SUCCEEDED even after re-queuing")
        versions = poll_until(lambda: results_of(base_url, token_source, task_id)
                              if len(results_of(base_url, token_source, task_id)) >= 2 else None,
                              "result version 2 to appear")
        latest, prior = versions[0], versions[-1]
        if latest.get("seq") != 2 or latest.get("status") != "SUBMITTED" \
                or latest.get("runId") == prior.get("runId") \
                or prior.get("status") != "REVISION_REQUIRED":
            fail(f"unexpected result versions after retry: {versions!r}")
        result_v2 = latest["id"]
        print(f"result v2 (redelivered by the real model): id={result_v2} run={latest['runId']}")

        # ⑤ 评审通过 + 幂等重放（D4/D5）
        accept_key = f"l5-review-accept-{uuid.uuid4()}"
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
            f"l5-review-archive-{uuid.uuid4()}")
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

        print("PASS l5-task-review-lifecycle:")
        print(f"  task={task_id} v1={result_v1} v2={result_v2} archiveStatus=ARCHIVED")
        return 0
    finally:
        # membership 还原用已解析的 subject，不依赖 token 可用性
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
        print(f"L5_TASK_REVIEW_LIFECYCLE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
