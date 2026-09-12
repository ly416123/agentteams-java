#!/usr/bin/env python3
"""Run the Kind task-file delivery acceptance (G05) against the deployed stack.

Drives the deterministic delivery chain over the real control-plane APIs: a
task whose prompt carries the task-file marker makes the conversation mock
upload a deliverable on the agent's behalf (the MCP upload_task_file call
chain equivalent); the script itself registers a fake INPUT reference (the
best-effort contract performs no cross-domain existence check), uploads an
OUTPUT directly, and then verifies the server-side SHA-256, the proxied
content read-back, idempotent re-upload dedup, the 50 MiB multipart limit
(413), the INPUT content guard (409), the presigned-URL 302 exit, the result
aggregation taskFiles (including the mock deliverable), and finally deletes
all workers to prove that content stays downloadable after worker loss.

Requires a Keycloak bearer token (alice) via --token or
AGENTTEAMS_API_BEARER_TOKEN; when absent the script obtains one itself through
a temporary port-forward to the Keycloak service and refreshes it before
expiry.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
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
# 与 scripts/qwenpaw-conversation-mock.py 的 TASK_FILE_MARKER 同值：prompt 携带它时
# mock 剧本会代表 agent 直传一份 mock-deliverable.pdf（MCP 工具调用链等价物）。
TASK_FILE_MARKER = "TASK_FILE_UPLOAD_PROMPT"
PDF_BYTES = b"%PDF-1.4\n%agentteams-g05-acceptance\n%%EOF\n"
OVER_LIMIT_BYTES = b"x" * (50 * 1024 * 1024 + 1)


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
        "description": "Task file delivery acceptance (G05)",
        "spec": {
            "scope": {"tenant": tenant, "project": project, "team": team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": prompt},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url.rstrip('/')}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"kind-task-file-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    if queue:
        request_json(f"{base_url.rstrip('/')}/api/v1/tasks/{task_id}/queue", "POST", {},
                     tokens.get(), f"kind-task-file-queue-{uuid.uuid4()}")
    return task_id


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


def sha256_hex(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def check(name: str, ok: bool, detail: str = "") -> None:
    if not ok:
        fail(f"{name} failed: {detail}")
    print(f"  PASS {name}")


class ApiError(RuntimeError):
    def __init__(self, status: int, detail: str):
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status


def multipart_payload(field: str, filename: str, content: bytes,
                      content_type: str) -> tuple[bytes, str]:
    boundary = "----agentteams-g05" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return part + content + f"\r\n--{boundary}--\r\n".encode(), boundary


def upload_multipart(url: str, token: str, filename: str, content: bytes,
                     content_type: str) -> dict:
    body, boundary = multipart_payload("file", filename, content, content_type)
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode("utf-8", "replace")[:300]) from error


def request_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode("utf-8", "replace")[:300]) from error


def request_location(url: str, token: str) -> str | None:
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with opener.open(request, timeout=30) as response:
            return response.headers.get("Location")
    except urllib.error.HTTPError as error:
        if error.code in (301, 302, 303, 307, 308):
            return error.headers.get("Location")
        raise ApiError(error.code, "") from error


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
            tokens = TokenSource(args.token, "")
        else:
            keycloak_pf = start_port_forward(args.namespace, "keycloak", args.keycloak_port)
            tokens = TokenSource("", f"http://127.0.0.1:{args.keycloak_port}")
        subject = token_subject(tokens.get())
        print(f"authenticated as subject={subject}")
        previous_role = ensure_project_membership(
            args.namespace, subject, args.tenant, args.project)
        print(f"project membership ensured (previous role: {previous_role!r})")
        # manager 无需 port-forward：本链全部调 control-plane（INPUT 假引用不校验
        # 跨域存在性，规格 §4.1；mock 剧本直传的也是 control-plane）。
        # 各调用点每次经 tokens.get() 取 token，保持过期前刷新语义。

        # 1) 建任务并排队；prompt 携带 marker → mock 剧本代表 agent 直传一份 OUTPUT。
        task_id = create_and_queue_task(base_url, tokens, args.tenant, args.project,
                                        args.team, "G05 任务文件交付验收",
                                        f"{TASK_FILE_MARKER} 生成一段说明文字即可。")
        print(f"task created and queued: {task_id}")

        # 2) INPUT 登记（假引用——API 不做跨域存在性校验，best-effort 契约 §4.1）。
        request_json(f"{base_url}/api/v1/tasks/{task_id}/attachments", "POST",
                     {"attachments": [{"sessionId": str(uuid.uuid4()),
                                       "fileId": str(uuid.uuid4()),
                                       "name": "输入.txt", "sizeBytes": 12}]},
                     tokens.get(), str(uuid.uuid4()))
        _, manifest = request_json(f"{base_url}/api/v1/tasks/{task_id}/files", token=tokens.get())
        manifest = manifest or []
        check("INPUT 登记入清单", any(f["role"] == "INPUT" for f in manifest))
        input_id = next(f["fileId"] for f in manifest if f["role"] == "INPUT")

        # 3) OUTPUT 上传：服务端直传 + 服务端 SHA-256。
        created = upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", tokens.get(),
                                   "top10.pdf", PDF_BYTES, "application/pdf")
        check("上传返回 sha256 与本地一致",
              created.get("sha256") == sha256_hex(PDF_BYTES), str(created))
        file_id = created["fileId"]

        # 4) 集群内代理流回读一致。
        echoed = request_bytes(f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/content", tokens.get())
        check("content 回读一致", echoed == PDF_BYTES)

        # 5) 幂等重传去重（验收总表第 2 条：不产生失控重复）。mock 剧本直传
        # 与 worker 会话并发进行，OUTPUT 恰 2 条用轮询吸收时序：提前断言会
        # 在 mock 上传落库前抽检失败（本轮时序已在验收中实际观察到）。
        again = upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", tokens.get(),
                                 "top10.pdf", PDF_BYTES, "application/pdf")
        check("重传返回既有记录", again.get("fileId") == file_id, str(again))

        def outputs_settled():
            _, current = request_json(f"{base_url}/api/v1/tasks/{task_id}/files", token=tokens.get())
            current_outputs = [f for f in (current or []) if f["role"] == "OUTPUT"]
            return current_outputs if len(current_outputs) >= 2 else None

        outputs = poll_until(outputs_settled,
                             "the mock deliverable upload to land as the 2nd OUTPUT")
        check("OUTPUT 无重复（本脚本 1 条 + mock 剧本 1 条）", len(outputs) == 2, str(len(outputs)))

        # 6) 50MB 超限 413；INPUT content 409。
        try:
            upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", tokens.get(),
                             "big.bin", OVER_LIMIT_BYTES, "application/octet-stream")
            check("50MB 超限 413", False)
        except ApiError as error:
            check("50MB 超限 413", error.status == 413, str(error))
        try:
            request_bytes(f"{base_url}/api/v1/tasks/{task_id}/files/{input_id}/content", tokens.get())
            check("INPUT content 409", False)
        except ApiError as error:
            check("INPUT content 409", error.status == 409, str(error))

        # 7) 浏览器 302 出口（presignEndpoint 受众）。
        location = request_location(
            f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/download", tokens.get())
        check("302 Location 是 presigned URL",
              location is not None and ("X-Amz" in location or "presign" in location.lower()),
              str(location))

        # 8) run 终态 + result 聚合 taskFiles（含 mock 剧本直传的那条 → MCP 调用链闭环）。
        wait_for_terminal_phase(base_url, tokens, task_id)
        _, runs = request_json(f"{base_url}/api/v1/tasks/{task_id}/runs", token=tokens.get())
        if not runs:
            fail("no runs recorded after terminal phase")
        latest = max(runs, key=lambda run: run.get("createdAt", ""))
        _, result = request_json(
            f"{base_url}/api/v1/tasks/{task_id}/runs/{latest['id']}/result", token=tokens.get())
        task_files = result.get("taskFiles") or []
        check("result 聚合 taskFiles 含 OUTPUT 且 AVAILABLE",
              any(f["fileId"] == file_id and f["status"] == "AVAILABLE" for f in task_files),
              str(task_files))
        check("mock 剧本直传的 OUTPUT 也在清单",
              any(f["name"] == "mock-deliverable.pdf" for f in task_files), str(task_files))

        # 9) Worker 删除后仍可下载（验收总表第 1 条；kind 验收专用破坏性操作）。
        kubectl(args.namespace, "delete", "workers", "--all")

        def content_survives():
            try:
                return request_bytes(
                    f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/content",
                    tokens.get()) == PDF_BYTES or None
            except (ApiError, urllib.error.URLError):
                return None  # 轮询窗口内的瞬时错误（含 port-forward 抖动）按未就绪处理

        poll_until(content_survives,
                   "content to stay downloadable after workers are deleted")
        print("G05 task-file delivery kind acceptance: PASS")
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
        print(f"KIND_TASK_FILE_DELIVERY_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
