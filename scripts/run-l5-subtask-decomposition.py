#!/usr/bin/env python3
"""L5 二期端到端验收：子任务拆解、协作事件与阻塞恢复（真模型环境）。

在 L5 主机上执行（kubectl/psql 在本机可用，control-plane 经 NodePort 30080 访问）。
与 kind 版（run-kind-subtask-decomposition.py）的差异：
- 无 conversation mock 切换：qwenpaw 是真实模型服务，Worker CR 的 QWENPAW_ENDPOINT
  保持不变；
- 确定性层由脚本直调 REST 完成（plan → status 推进），模拟 agent 通过 task-mcp
  工具发出的协作调用；
- 真模型自主拆解作为 best-effort 观察项：第二任务在 prompt 中明确要求 agent 调用
  plan_subtasks/update_subtask_status；若事件流出现 agent 发起的 subtask.planned
  即为自主拆解证据，未出现则按设计公理记录 NOTE（不判失败）。

断言目标（对应二期设计验收节）：
- 树投影：主任务 + 2 子任务（seq1 SUCCEEDED、seq2 FAILED，dependencyIds 正确）；
- 协作事件：subtask.planned×2 / started×2 / succeeded / failed，sequence 严格单调；
- 终态隔离：子任务 FAILED 不影响主任务 SUCCEEDED（最佳努力公理）；
- 主任务过程事件（task.*）与子任务事件共存于同一 run 事件流。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

POLL_TIMEOUT_SECONDS = 180.0
POLL_INTERVAL_SECONDS = 1.0
MAX_SUBTASKS = 20
SUBTASK_STATUSES = ("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")


def fail(message: str) -> None:
    raise RuntimeError(message)


def run_command(*args: str) -> str:
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"command {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def kubectl(namespace: str, *args: str) -> str:
    return run_command("kubectl", "-n", namespace, *args)


def request_json(url: str, method: str, body, token: str, idempotency: str):
    request = urllib.request.Request(url, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Idempotency-Key", idempotency)
    request.add_header("Accept", "application/json")
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        request.add_header("Content-Type", "application/json")
        request.data = payload
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")[:300]
        fail(f"{method} {url} returned HTTP {error.code}: {detail}")


def fetch_token(keycloak_url: str, username: str, password: str) -> str:
    body = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "agentteams-api",
        "username": username,
        "password": password,
    }).encode()
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/agentteams/protocol/openid-connect/token",
        data=body, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode())["access_token"]
    except urllib.error.HTTPError as error:
        fail(f"keycloak token request failed: HTTP {error.code}")


def token_subject(token: str) -> str:
    payload = token.split(".")[1]
    padded = payload + "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(padded)).get("preferred_username", "")


def poll_until(action, description: str, timeout: float = POLL_TIMEOUT_SECONDS):
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            outcome = action()
            if outcome is not None:
                return outcome
        except (RuntimeError, urllib.error.URLError) as error:
            last_error = error
        time.sleep(POLL_INTERVAL_SECONDS)
    suffix = f" (last error: {last_error})" if last_error else ""
    fail(f"timed out waiting for {description}{suffix}")


def database_password(namespace: str, secret: str = "agentteams-database") -> str:
    return kubectl(namespace, "get", "secret", secret,
                   "-o", "jsonpath={.data.password}")


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def psql(namespace: str, password: str, statement: str) -> str:
    return kubectl(namespace, "exec", "statefulset/postgresql", "--", "env",
                   f"PGPASSWORD={password}", "psql", "-U", "agentteams",
                   "-d", "agentteams", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)


def ensure_memberships(namespace: str, subject: str, tenant: str, project: str) -> str | None:
    """process-events/tree 需三重 membership（org/tenant/project，与 run-kind-memory-scope
    同约定）；返回 project membership 原 role 供结束后还原（None=原本不存在）。

    注意：org/tenant membership 与参照脚本一致被统一覆写为 MEMBER 且不还原——
    参照脚本的供数是自建 fixture，而这里作用于真实账号，这是验收脚本对
    开发集群的已知副作用。
    """
    password = database_password(namespace)
    previous = psql(namespace, password,
                    "SELECT role FROM project_memberships "
                    f"WHERE subject={sql_literal(subject)} AND tenant_id={sql_literal(tenant)} AND project_id="
                    f"(SELECT id FROM projects WHERE tenant_id={sql_literal(tenant)} AND name={sql_literal(project)}) LIMIT 1;").strip()
    psql(namespace, password, f"""
        INSERT INTO organization_memberships(organization_id, subject, role, created_at, updated_at)
        SELECT organization_id, {sql_literal(subject)}, 'MEMBER', now(), now()
          FROM legacy_tenant_mappings WHERE legacy_tenant_key = {sql_literal(tenant)}
        ON CONFLICT (organization_id, subject) DO UPDATE SET role = 'MEMBER', updated_at = now();
        INSERT INTO tenant_memberships(organization_id, tenant_id, subject, role, created_at, updated_at)
        SELECT organization_id, tenant_id, {sql_literal(subject)}, 'MEMBER', now(), now()
          FROM legacy_tenant_mappings WHERE legacy_tenant_key = {sql_literal(tenant)}
        ON CONFLICT (tenant_id, subject) DO UPDATE SET role = 'MEMBER', updated_at = now();
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


def create_and_queue_task(base_url: str, token: str, tenant: str, project: str,
                          team: str, title: str, prompt: str) -> str:
    body = {
        "title": title,
        "description": "L5 phase-2 subtask decomposition acceptance",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": prompt},
            "requiredCapabilities": [],
        },
    }
    _, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                              token, f"l5-subtask-decomp-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail("task creation returned no id")
    task_id = created["id"]
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                 token, f"l5-subtask-decomp-queue-{uuid.uuid4()}")
    return task_id


def wait_for_run_id(base_url: str, token: str, task_id: str) -> str:
    def check():
        _, runs = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs",
                               "GET", None, token, "l5-run-list")
        rows = runs if isinstance(runs, list) else runs.get("runs", [])
        if rows:
            return rows[0]["runId"] if isinstance(rows[0], dict) and rows[0].get("runId") \
                else rows[0]["id"]
        return None
    return poll_until(check, "first task run")


def plan_subtasks(base_url: str, token: str, task_id: str, specs: list[dict]) -> list[dict]:
    _, planned = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/subtasks",
                              "PUT", {"subtasks": specs}, token,
                              f"l5-subtask-plan-{uuid.uuid4()}")
    return planned.get("nodes", []) if isinstance(planned, dict) else []


def update_status(base_url: str, token: str, task_id: str, subtask_id: str,
                  status: str, note: str = "") -> None:
    if status not in SUBTASK_STATUSES:
        fail(f"invalid subtask status {status}")
    request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/subtasks/"
                 f"{subtask_id}/status", "PUT", {"status": status, "note": note},
                 token, f"l5-subtask-status-{uuid.uuid4()}")


def wait_for_tree(base_url: str, token: str, task_id: str, run_id: str) -> list[dict]:
    """树投影：根 + 2 子任务，子任务终态 SUCCEEDED 与 FAILED，sequence/依赖正确。"""
    def check():
        _, tree = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs/{run_id}/tree",
            "GET", None, token, "l5-tree")
        nodes = tree.get("nodes", tree) if isinstance(tree, dict) else tree
        if not isinstance(nodes, list):
            return None
        children = [node for node in nodes if node.get("parentTaskId") == task_id]
        statuses = sorted(node.get("status") for node in children)
        if statuses == ["FAILED", "SUCCEEDED"]:
            return children
        return None
    return poll_until(check, "tree projection with subtasks SUCCEEDED + FAILED")


def wait_for_events(base_url: str, token: str, task_id: str, run_id: str,
                    min_planned: int = 2) -> list[dict]:
    def check():
        _, page = request_json(
            f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/runs/{run_id}/process-events",
            "GET", None, token, "l5-events")
        events = page.get("events", []) if isinstance(page, dict) else page
        planned = sum(1 for event in events if event.get("eventType") == "subtask.planned")
        if planned >= min_planned:
            return events
        return None
    return poll_until(check, f">= {min_planned} subtask.planned events")


def assert_event_invariants(events: list[dict], run_id: str) -> None:
    counts: dict[str, int] = {}
    sequences: list[int] = []
    for event in events:
        if event.get("runId") != run_id:
            continue
        counts[event["eventType"]] = counts.get(event["eventType"], 0) + 1
        sequences.append(event["sequence"])
    if sequences != sorted(sequences) or len(set(sequences)) != len(sequences):
        fail(f"event sequences not strictly monotonic: {sequences}")
    expected = {"subtask.planned": 2, "subtask.started": 2,
                "subtask.succeeded": 1, "subtask.failed": 1}
    for event_type, minimum in expected.items():
        if counts.get(event_type, 0) < minimum:
            fail(f"missing {event_type}: observed {counts}")
    if counts.get("task.completed", 0) + counts.get("task.succeeded", 0) == 0 and \
            counts.get("task.failed", 0) == 0:
        print("  note: no terminal task.* event observed (may follow later)")
    print(f"  event counts: {json.dumps(counts, sort_keys=True)}")


def wait_for_terminal_phase(base_url: str, token: str, task_id: str,
                            expected: str | None = None) -> str:
    """等待任意终态；expected 给定时则必须匹配，否则 fail。"""
    def check():
        _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                               "GET", None, token, "l5-task")
        phase = task.get("phase") if isinstance(task, dict) else None
        if phase in ("SUCCEEDED", "FAILED", "CANCELLED"):
            return phase
        return None
    phase = poll_until(check, "terminal task phase")
    if expected is not None and phase != expected:
        fail(f"task terminal phase is {phase}, expected {expected}")
    return phase


def assert_mcp_tooling(namespace: str) -> None:
    """注入链证据：qwenpaw 挂载的 task-mcp 脚本必须包含二期新方法 plan_subtasks。"""
    pods = kubectl(namespace, "get", "pods", "-o", "name").splitlines()
    pod = next((name.split("/")[1] for name in pods
                if re.match(r"^pod/qwenpaw-[0-9a-f]", name)), None)
    if not pod:
        fail("qwenpaw pod not found")
    script = kubectl(namespace, "exec", pod, "--", "cat",
                     "/opt/agentteams-mcp/agentteams-task-mcp.py")
    if "plan_subtasks" not in script:
        fail("mounted task-mcp script lacks plan_subtasks (phase-2 tooling absent)")
    print(f"  task-mcp script on {pod} contains plan_subtasks (phase-2 mounted)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url",
                        default=os.environ.get("AGENTTEAMS_CONTROL_PLANE_URL",
                                               "http://127.0.0.1:30080"))
    parser.add_argument("--keycloak-url",
                        default=os.environ.get("AGENTTEAMS_KEYCLOAK_URL",
                                               "http://127.0.0.1:30082"))
    parser.add_argument("--username", default="alice")
    parser.add_argument("--password", default="alice-dev")
    parser.add_argument("--tenant", default=os.environ.get("AGENTTEAMS_SCOPE_TENANT",
                                                           "tenant-a"))
    parser.add_argument("--project", default=os.environ.get("AGENTTEAMS_SCOPE_PROJECT",
                                                            "project-a"))
    parser.add_argument("--team", default=os.environ.get("AGENTTEAMS_SCOPE_TEAM",
                                                         "team-a"))
    parser.add_argument("--db-secret", default="agentteams-database",
                        help="postgres password secret (L5: agentteams-database, "
                             "kind: postgresql)")
    parser.add_argument("--skip-kubectl", action="store_true",
                        help="skip kubectl-backed checks (membership/mcp script)")
    args = parser.parse_args()

    token = fetch_token(args.keycloak_url, args.username, args.password)
    subject = token_subject(token) or args.username
    print(f"token acquired for subject={subject}")

    # membership 改库后任何后续失败都必须走 finally 还原（kind 版同约定）；
    # 哨兵 None：未改库（或 skip-kubectl）即跳过还原。
    previous_role: str | None = None
    if not args.skip_kubectl:
        previous_role = ensure_memberships(args.namespace, subject, args.tenant,
                                           args.project)
        assert_mcp_tooling(args.namespace)

    try:
        run_acceptance(args, token, subject)
    finally:
        if not args.skip_kubectl:
            try:
                restore_project_membership(args.namespace, subject, args.tenant,
                                           args.project, previous_role)
                print(f"project membership restored for subject={subject}")
            except (RuntimeError, urllib.error.URLError) as error:
                print(f"WARNING: project membership restore failed: {error}",
                      file=sys.stderr)
    return 0


def run_acceptance(args, token: str, subject: str) -> None:
    """验收主体：任务 A 确定性层 + 任务 B 真模型自主拆解观察。"""
    long_prompt = ("写一篇约 300 字的中文短文，主题是分布式系统中的事务发件箱模式。"
                   "直接输出正文，不要任何解释。")
    task_a = create_and_queue_task(args.base_url, token, args.tenant, args.project,
                                   args.team, "l5-subtask-decomp-deterministic",
                                   long_prompt)
    print(f"task A created and queued: {task_a}")
    run_a = wait_for_run_id(args.base_url, token, task_a)
    print(f"run started: {run_a}")

    first_id, second_id = str(uuid.uuid4()), str(uuid.uuid4())
    specs = [
        {"subtaskId": first_id, "title": "草拟提纲", "sequence": 1, "dependencyIds": []},
        {"subtaskId": second_id, "title": "扩写成文", "sequence": 2,
         "dependencyIds": [first_id]},
    ]
    plan_subtasks(args.base_url, token, task_a, specs)
    print(f"plan accepted: 2 subtasks (seq1={first_id[:8]}, seq2={second_id[:8]})")
    # 与设计决策表一致：agent 先置 RUNNING（发 subtask.started）再推进终态。
    update_status(args.base_url, token, task_a, first_id, "RUNNING", "开始梳理提纲")
    update_status(args.base_url, token, task_a, second_id, "RUNNING", "等待前置完成")
    update_status(args.base_url, token, task_a, first_id, "SUCCEEDED",
                  "提纲完成（验收注入）")
    update_status(args.base_url, token, task_a, second_id, "FAILED",
                  "模拟阻塞失败（验收注入）")

    children = wait_for_tree(args.base_url, token, task_a, run_a)
    for node in sorted(children, key=lambda item: item.get("sequence", 0)):
        print(f"  subtask {node['taskId'][:8]} seq={node.get('sequence')} "
              f"status={node.get('status')}")
    seq_children = sorted(children, key=lambda n: n.get("sequence", 0))
    if seq_children[0]["taskId"] != first_id or seq_children[1]["taskId"] != second_id:
        fail("tree nodes do not match planned subtask ids/sequences")
    if first_id not in seq_children[1].get("dependencyIds", []):
        fail("second subtask does not reference first via dependencyIds")

    events_a = wait_for_events(args.base_url, token, task_a, run_a)
    assert_event_invariants(events_a, run_a)
    phase_a = wait_for_terminal_phase(args.base_url, token, task_a, "SUCCEEDED")
    print(f"  main task terminal phase: {phase_a} (subtask FAILED isolated)")

    # ── 任务 B：真模型自主拆解（best-effort 观察）──────────────────────────
    agent_prompt = (
        "你连接了 agentteams-task MCP 工具。当前任务已注入平台上下文。"
        "请调用 plan_subtasks 工具把本任务拆解为 2 个子任务（标题分别为"
        "「梳理要点」「整理输出」，sequence 1 与 2，第二个依赖第一个），"
        "然后用 update_subtask_status 把第一个置为 SUCCEEDED、第二个置为 FAILED，"
        "最后正常完成任务并输出 DONE。")
    task_b = create_and_queue_task(args.base_url, token, args.tenant, args.project,
                                   args.team, "l5-subtask-decomp-agent", agent_prompt)
    print(f"task B created and queued: {task_b}")
    run_b = wait_for_run_id(args.base_url, token, task_b)
    phase_b = wait_for_terminal_phase(args.base_url, token, task_b)
    print(f"  task B terminal phase: {phase_b}")
    events_b = wait_for_events(args.base_url, token, task_b, run_b, min_planned=0)
    agent_planned = [event for event in events_b
                     if event.get("eventType") == "subtask.planned"]
    if agent_planned:
        print(f"  agent-initiated decomposition observed: {len(agent_planned)} planned")
        if any(event.get("eventType", "").startswith("subtask.") for event in events_b):
            assert_event_invariants(events_b, run_b)
    else:
        print("  NOTE: agent did not self-decompose (best-effort item, allowed by design)")

    print("PASS l5-subtask-decomposition:")
    print(f"  task A={task_a} run={run_a} phase={phase_a}")
    print(f"  task B={task_b} run={run_b} phase={phase_b} "
          f"agentSelfDecomposition={'yes' if agent_planned else 'no'}")

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"L5_SUBTASK_DECOMPOSITION_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
