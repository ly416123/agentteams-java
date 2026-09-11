#!/usr/bin/env python3
"""L5 验收：子任务委派真调度（G03）真模型全链。

在 L5 主机内执行（对齐 run-l5-task-review-lifecycle 的执行约定）：脚本自行解析
ClusterIP 直连 control-plane 与 Keycloak，经 k3s kubectl 完成数据库 fixture。
流程：alice token → 授权 project ADMIN（还原）→ 创建真模型主任务（prompt 引导拆解）
→ 拆解轮 run 终态 → 确定性兜底（120s 未出现子任务则经 REST 注入「收集数据→撰写
报告」依赖链，标记 NOTE；真模型经 agentteams-task MCP 工具自主拆解的先例做法）
→ 轮询子任务拓扑执行至全 SUCCEEDED（偶发失败走 G02 retry 边恢复）→ 子任务全
SUCCEEDED 触发主任务自动 QUEUED 进入汇总轮（TaskChildrenCompleted）→ publish
硬约束放行后恰 1 条 SUBMITTED 结果版本（拆解轮 publish 被跳过的证明）。真模型
汇总轮可能 re-plan 出新一代子任务导致 publish 被拦：脚本对新代执行完成后显式
retry 主任务重开汇总（闸门语义下后续重排由显式动作驱动，上限 3 轮）→ 评审
ACCEPTED（幂等重放同记录）→ 归档 → 默认列表不含 / ARCHIVED 过滤可见。
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

POLL_TIMEOUT_SECONDS = 900.0   # 真模型（deepseek）拆解/子任务/汇总全链，放宽轮询上限
POLL_INTERVAL_SECONDS = 5.0
TOKEN_TTL_SECONDS = 240.0
FALLBACK_PLAN_WAIT_SECONDS = 120.0  # 真模型自主拆解的等待上限，之后确定性注入
KUBECTL = ("sudo", "/usr/local/bin/k3s", "kubectl")
# 确定性收尾指令：真模型每次 attempt 都是无记忆的新会话，汇总轮收到原拆解
# prompt 会确定性 re-plan（每轮生成新子任务 id）→ publish 永不联动。第一次
# 拦截后经 psql 把主任务 prompt 换为「只汇总不拆解」（与 membership 注入同类
# 的确定性层兜底）；retry/汇总/publish 硬约束/评审链路全部走真实平台路径。
AGGREGATION_ONLY_PROMPT = (
    "所有子任务已全部完成。请先用 list_subtasks 查看子任务清单，再用 "
    "get_task_result 读取每个子任务的结果，然后直接输出《季度项目报告》的完整 "
    "汇总内容（含摘要与结论）。不要登记新的子任务，不要调用拆解工具，只做汇总。")


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
    （access token 约 5 分钟，全链含多次真模型 run 需要刷新）。"""

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
    """真模型主任务：prompt 显式引导拆解为依赖链并等待平台调度（G03 真模型路径）。"""
    prompt = (
        "请将《季度项目报告》任务拆解为 2 个子任务：先「收集数据」、后「撰写报告」"
        "（撰写报告依赖收集数据完成）。用 agentteams-task 工具登记这份拆解，"
        "随后结束本轮回复，等待平台调度执行各子任务，不要自行执行子任务内容。")
    body = {
        "title": title,
        "description": "Subtask delegation true scheduling acceptance on L5 (G03, real model)",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": prompt},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"l5-delegation-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                 tokens.get(), f"l5-delegation-queue-{uuid.uuid4()}")
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
                     max_attempts: int = 2) -> str:
    """等终态；FAILED 时重排队（FAILED→QUEUED 既有边）直至 SUCCEEDED
    （真模型偶发失败重试，上限 2 次避免成本失控）。"""
    for attempt in range(1, max_attempts + 1):
        phase = wait_for_terminal_phase(base_url, tokens, task_id)
        if phase == "SUCCEEDED":
            return "SUCCEEDED"
        if phase == "CANCELLED":
            return "CANCELLED"
        print(f"run #{attempt} of {task_id} ended in {phase}; re-queuing")
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                               "GET", None, tokens.get())
        request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/retry", "POST",
                     {"expectedVersion": task.get("version")}, tokens.get(),
                     f"l5-delegation-recover-{attempt}-{uuid.uuid4()}")
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


def wait_all_subtasks_succeeded(base_url: str, tokens: TokenSource,
                                task_id: str):
    """等全部子任务 SUCCEEDED（含 FAILED 时经 G02 retry 边恢复）；
    返回最终快照。真模型汇总轮可能 re-plan 出新一代子任务，因此每轮
    汇总后都要重新拉快照。"""
    def check():
        snapshot = list_subtasks(base_url, tokens, task_id)
        if not snapshot:
            return None
        pending = False
        for item in snapshot:
            phase = item.get("phase")
            if phase == "SUCCEEDED":
                continue
            if phase == "FAILED":
                subtask_id = item["subtaskId"]
                _, subtask = request_json(
                    f"{base_url.rstrip('/')}/api/v1/tasks/{subtask_id}",
                    "GET", None, tokens.get())
                request_json(
                    f"{base_url.rstrip('/')}/api/v1/tasks/{subtask_id}/retry",
                    "POST", {"expectedVersion": subtask.get("version")},
                    tokens.get(), f"l5-delegation-subretry-{uuid.uuid4()}")
            pending = True
        return snapshot if not pending else None

    return poll_until(check, "all subtasks to succeed")


def wait_submitted_count(namespace: str, task_id: str, timeout: float) -> int:
    """短窗口内等 SUBMITTED 结果版本（publish 在 run SUCCEEDED 联动时发生，
    无需长轮询）；返回超时时的计数（0=publish 被硬约束拦下）。"""
    statement = ("SELECT count(*) FROM task_result_versions WHERE task_id="
                 f"{sql_literal(task_id)} AND status='SUBMITTED';")
    deadline = time.monotonic() + timeout
    last = 0
    while time.monotonic() < deadline:
        last = count_rows(namespace, statement)
        if last >= 1:
            return last
        time.sleep(POLL_INTERVAL_SECONDS)
    return last


def count_rows(namespace: str, statement: str) -> int:
    output = psql(namespace, database_password(namespace), statement).strip()
    return int(output or 0)


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

        # ① 拆解轮：真模型主任务，prompt 引导经 MCP 工具登记依赖链拆解。
        title = f"l5-subtask-delegation-{uuid.uuid4()}"
        main_task = create_and_queue_task(base_url, token_source, args.tenant,
                                          args.project, args.team, title)
        print(f"real-model main task created and queued: {main_task}")
        if ensure_succeeded(base_url, token_source, main_task) != "SUCCEEDED":
            fail("decomposition run did not reach SUCCEEDED even after re-queuing")
        print("decomposition run reached SUCCEEDED")

        # ② 确定性兜底：120s 未出现子任务则 REST 注入依赖链（真模型未自主拆解
        # 的先例做法）；真模型已拆解（≥1 个）则沿用其拆解结果。
        def subtasks_present():
            subtasks = list_subtasks(base_url, token_source, main_task)
            return subtasks if subtasks else None

        existing = None
        deadline = time.monotonic() + FALLBACK_PLAN_WAIT_SECONDS
        while time.monotonic() < deadline:
            existing = subtasks_present()
            if existing:
                break
            time.sleep(POLL_INTERVAL_SECONDS)
        if existing:
            print(f"NOTE: real model planned {len(existing)} subtask(s) on its own; "
                  "reusing them as-is")
        else:
            print("NOTE: no subtasks after the wait window; injecting the "
                  "deterministic dependency chain (collect data -> write report)")
            first_id, second_id = str(uuid.uuid4()), str(uuid.uuid4())
            plan_body = {"subtasks": [
                {"subtaskId": first_id, "title": "收集数据", "sequence": 1,
                 "dependencyIds": []},
                {"subtaskId": second_id, "title": "撰写报告", "sequence": 2,
                 "dependencyIds": [first_id]},
            ]}
            status, planned = request_json(
                f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}/subtasks",
                "PUT", plan_body, token_source.get(),
                f"l5-delegation-plan-{uuid.uuid4()}")
            if status != 200 or not isinstance(planned, list) or len(planned) != 2:
                fail(f"fallback plan expected 200 with 2 nodes, got {status}: {planned!r}")
            existing = list_subtasks(base_url, token_source, main_task)
        if len(existing) < 1:
            fail(f"no subtasks available for the execution round: {existing!r}")
        subtask_ids = [item["subtaskId"] for item in existing]
        print(f"subtasks under execution: {subtask_ids}")

        # ③ 拓扑执行：子任务真模型执行至全 SUCCEEDED（偶发失败走 retry 边）。
        snapshot = wait_all_subtasks_succeeded(base_url, token_source, main_task)
        subtask_ids = [item["subtaskId"] for item in snapshot]
        print(f"all subtasks succeeded: {subtask_ids}")

        # ④⑤ 汇总轮 + publish 恢复循环：主任务 SUCCEEDED→QUEUED（
        # TaskChildrenCompleted）→ 真模型汇总。真模型汇总轮可能 re-plan 出
        # 新一代子任务 → run SUCCEEDED 时它们尚未完成，publish 被硬约束拦下，
        # 而新代完成时一次性闸门已消耗 → 需显式 retry 主任务再开一轮汇总
        # （闸门语义：评审 retry 等显式动作驱动的后续重排不经由 gate）。
        result_submitted = 0
        for round_no in range(1, 4):
            runs_needed = round_no + 1
            poll_until(
                lambda needed=runs_needed: list_runs(base_url, token_source, main_task)
                if len(list_runs(base_url, token_source, main_task)) >= needed else None,
                f"aggregation round {round_no} to start")
            print(f"aggregation round {round_no} started")
            if ensure_succeeded(base_url, token_source, main_task) != "SUCCEEDED":
                fail(f"aggregation round {round_no} did not reach SUCCEEDED")
            wait_all_subtasks_succeeded(base_url, token_source, main_task)
            result_submitted = wait_submitted_count(
                args.namespace, main_task, timeout=240.0)
            if result_submitted == 1:
                break
            if result_submitted > 1:
                fail(f"expected at most 1 submitted result version, saw {result_submitted}")
            print(f"NOTE: publish withheld after aggregation round {round_no} "
                  "(the model re-planned mid-round); retrying the main task")
            if round_no == 1:
                # 确定性收尾：换汇总指令（首次拦截即注入，后续轮不再拆解）。
                psql(args.namespace, database_password(args.namespace),
                     "UPDATE tasks SET spec = jsonb_set(spec, '{inputJson,prompt}', "
                     f"to_jsonb({sql_literal(AGGREGATION_ONLY_PROMPT)}::text)), "
                     f"updated_at = now() WHERE id = {sql_literal(main_task)};")
                print("NOTE: aggregation-only prompt injected into the main task spec")
            _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}",
                                   "GET", None, token_source.get())
            status, retried = request_json(
                f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}/retry", "POST",
                {"expectedVersion": task.get("version")}, token_source.get(),
                f"l5-delegation-aggregation-retry-{round_no}-{uuid.uuid4()}")
            if status != 200 or retried.get("phase") != "QUEUED":
                fail(f"aggregation retry expected 200/QUEUED, got {status}: {retried!r}")
        if result_submitted != 1:
            fail("publish never released after 3 aggregation rounds")

        # ⑥ publish 硬约束放行：恰 1 条 SUBMITTED 结果版本（拆解轮 publish 被
        # 跳过的证明）。
        versions = results_of(base_url, token_source, main_task)
        if len(versions) != 1 or versions[0].get("seq") != 1 \
                or versions[0].get("status") != "SUBMITTED" \
                or not versions[0].get("runId"):
            fail(f"unexpected result version: {versions!r}")
        result_v1 = versions[0]["id"]
        print(f"result v1 submitted: id={result_v1} run={versions[0]['runId']}")

        # ⑥ 评审 ACCEPTED + 幂等重放（G02/D4/D5 闭环在 G03 汇总产物上复验）。
        accept_key = f"l5-delegation-accept-{uuid.uuid4()}"
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

        # ⑦ 归档 + 默认列表过滤断言（D6/D7）：带当前 version 过乐观锁。
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}", "GET",
                               None, token_source.get())
        status, archived = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{main_task}/archive", "POST",
            {"expectedVersion": task.get("version")}, token_source.get(),
            f"l5-delegation-archive-{uuid.uuid4()}")
        if status != 200 or archived.get("archiveStatus") != "ARCHIVED":
            fail(f"archive expected 200/ARCHIVED, got {status}: {archived!r}")
        if main_task in listed_task_ids(base_url, token_source, title, None):
            fail("archived task still shows up in the default (ACTIVE) listing")
        if main_task not in listed_task_ids(base_url, token_source, title, "ARCHIVED"):
            fail("archived task is missing from the ARCHIVED filtered listing")
        print("archive visibility verified")

        print("PASS l5-subtask-delegation:")
        print(f"  main={main_task} subtasks={','.join(subtask_ids)} result={result_v1}")
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
        print(f"L5_SUBTASK_DELEGATION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
