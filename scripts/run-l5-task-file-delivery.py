#!/usr/bin/env python3
"""L5 验收：任务文件交付真模型全链（G05 best-effort 层，规格 §7.3/§8.3）。

在 L5 主机内执行（对齐 run-l5-subtask-delegation 的执行约定）：脚本自行解析
ClusterIP 直连 control-plane / manager / Keycloak，经 k3s kubectl 完成 membership
fixture。确定性断言（上传、sha256、413、409、302、去重）全部归 kind 验收脚本
（run-kind-task-file-delivery.py）；本脚本只做真模型一轮完整交付：alice token →
授权 project ADMIN（还原）→ 会话域 multipart 上传输入附件（G02 既有链路）→ 建
任务（inputJson.attachments 下发投影 + attachments 账本登记，MCP create_task 同
语义）→ 排队 → 真模型轮询终态（「执行成功」与「交付成功」分离：run 失败不直接
fail）→ result 聚合 taskFiles 中 OUTPUT 存在则逐个回读验证可下载，缺失则默认
WARN（--strict 下 fail）。
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

POLL_TIMEOUT_SECONDS = 900.0   # 真模型（deepseek）下载/生成/上传全链，放宽轮询上限
POLL_INTERVAL_SECONDS = 5.0
TOKEN_TTL_SECONDS = 240.0
KUBECTL = ("sudo", "/usr/local/bin/k3s", "kubectl")

MODEL_ROUND_PROMPT = (
    "你收到一份输入附件（见平台上下文的[输入附件]清单）。必须先用 download_task_file "
    "把它下载到工作区读取，然后基于内容生成一份单页 PDF 报告，保存到工作区后"
    "调用 upload_task_file 上传到任务交付清单。")


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
    （access token 约 5 分钟，全链含真模型 run 需要刷新）。"""

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
    """任务文件端点要求 project_memberships 上的写入授权（ADMIN 含全部动作）；
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


def get_task(base_url: str, tokens: TokenSource, task_id: str) -> dict:
    _, task = request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}",
                           "GET", None, tokens.get())
    return task if isinstance(task, dict) else {}


def task_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    return str(get_task(base_url, tokens, task_id).get("phase"))


def wait_for_terminal_phase(base_url: str, tokens: TokenSource, task_id: str) -> str:
    def check():
        phase = task_phase(base_url, tokens, task_id)
        return phase if phase in ("SUCCEEDED", "FAILED", "CANCELLED") else None

    return poll_until(check, "the task to reach a terminal phase")


def multipart_body(field: str, filename: str, content: bytes,
                   content_type: str) -> tuple[bytes, str]:
    boundary = "----agentteams" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return part + content + f"\r\n--{boundary}--\r\n".encode(), boundary


def upload_multipart(url: str, token: str, filename: str, content: bytes,
                     content_type: str) -> dict:
    body, boundary = multipart_body("file", filename, content, content_type)
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        fail(f"upload_multipart HTTP {error.code} from {url}: "
             f"{error.read().decode(errors='replace')[:300]}")


def request_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        fail(f"request_bytes HTTP {error.code} from {url}: "
             f"{error.read().decode(errors='replace')[:300]}")


def model_round(base_url: str, manager_url: str, tokens: TokenSource,
                args: argparse.Namespace) -> None:
    """best-effort 真模型交付轮（规格 §7.3）：默认宽松（WARN），--strict 缺失即 fail。"""
    # 1) 会话域上传输入附件（G02 既有链路，真引用）。
    session_id = str(uuid.uuid4())
    status, _ = request_json(
        f"{manager_url}/api/v1/conversations", "POST",
        {"sessionId": session_id, "projectId": args.project, "teamId": args.team,
         "workerId": "qwenpaw"},
        tokens.get(), f"l5-task-file-conv-{session_id}")
    if status != 201:
        fail(f"create conversation expected 201, got {status}")
    content = "关键背景：本季度营收增长 12%。".encode()
    uploaded = upload_multipart(
        f"{manager_url}/api/v1/conversations/{session_id}/files", tokens.get(),
        "背景资料.txt", content, "text/plain")
    if not uploaded.get("fileId"):
        fail(f"conversation upload returned no fileId: {uploaded}")

    # 2) 建任务：inputJson.attachments 下发投影 + attachments 账本登记
    # （MCP create_task 同语义）。
    attachments = [{"sessionId": session_id, "fileId": uploaded["fileId"],
                    "name": uploaded.get("name", "背景资料.txt"),
                    "sizeBytes": uploaded.get("sizeBytes", len(content))}]
    body = {
        "title": f"l5-task-file-delivery-{uuid.uuid4()}",
        "description": "Task file delivery true model acceptance (G05)",
        "spec": {
            "scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": MODEL_ROUND_PROMPT, "attachments": attachments},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"l5-task-file-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    print(f"real-model task created: {task_id}")
    status, _ = request_json(f"{base_url}/api/v1/tasks/{task_id}/attachments", "POST",
                             {"attachments": attachments}, tokens.get(),
                             f"l5-task-file-attach-{uuid.uuid4()}")
    if status not in (200, 201):
        fail(f"attachments registration expected 2xx, got {status}")
    status, _ = request_json(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                             tokens.get(), f"l5-task-file-queue-{uuid.uuid4()}")
    if status != 200:
        fail(f"queue expected 200, got {status}")

    # 3) 轮询终态（真模型：POLL_TIMEOUT_SECONDS 已放宽 900s，沿用）。
    phase = wait_for_terminal_phase(base_url, tokens, task_id)
    if phase != "SUCCEEDED":
        # 「执行成功」与「交付成功」分离（规格 §8.2）：run 失败不直接 fail，
        # 交由 OUTPUT 缺失分支按 --strict 决断。
        print(f"NOTE: real-model run ended in {phase}")

    # 4) result 聚合：OUTPUT 存在 → 逐个回读；缺失 → 宽松 WARN / --strict fail。
    _, runs = request_json(f"{base_url}/api/v1/tasks/{task_id}/runs", "GET", None,
                           tokens.get())
    if not runs:
        fail("no runs recorded after terminal phase")
    latest = max(runs, key=lambda run: run.get("createdAt", ""))
    _, result = request_json(
        f"{base_url}/api/v1/tasks/{task_id}/runs/{latest['id']}/result", "GET", None,
        tokens.get())
    outputs = [f for f in (result.get("taskFiles") or [])
               if f.get("role") == "OUTPUT" and f.get("status") == "AVAILABLE"]
    if not outputs:
        message = "真模型未产出任务文件（best-effort 层，prompt 引导未生效）"
        if args.strict:
            fail(message)
        print(f"WARN: {message}")
        return
    for item in outputs:
        echoed = request_bytes(
            f"{base_url}/api/v1/tasks/{task_id}/files/{item['fileId']}/content",
            tokens.get())
        print(f"  PASS 真模型 OUTPUT {item.get('name')} 可下载（{len(echoed)} 字节）")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default="")     # control-plane，空则 ClusterIP 解析
    parser.add_argument("--keycloak-url", default="") # 空则 ClusterIP 解析
    parser.add_argument("--manager-url", default="")  # 空则解析 agentteams-agentteams-java-manager
    parser.add_argument("--tenant", default="tenant-a")
    parser.add_argument("--project", default="project-a")
    parser.add_argument("--team", default="team-a")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--skip-best-effort", action="store_true")
    args = parser.parse_args()

    previous_role: str | None = None
    subject: str | None = None
    try:
        # 入口：外部未给地址时自行解析 ClusterIP（L5 主机内直连 svc）
        base_url = args.base_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-control-plane")
        keycloak_url = args.keycloak_url.rstrip("/") or cluster_service_url(
            args.namespace, "keycloak")
        manager_url = args.manager_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-manager")
        print(f"control-plane={base_url} manager={manager_url}")
        tokens = TokenSource(keycloak_url)
        subject = token_subject(tokens.get())
        print(f"authenticated as subject={subject}")
        previous_role = ensure_project_membership(
            args.namespace, subject, args.tenant, args.project)
        print(f"project membership ensured (previous role: {previous_role!r})")
        if not args.skip_best_effort:
            model_round(base_url, manager_url, tokens, args)
        print("PASS l5-task-file-delivery")
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
        print(f"L5_TASK_FILE_DELIVERY_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
