#!/usr/bin/env python3
"""L5 验收：会话文件交付（确定性层 + 真模型 best-effort 层）。

确定性层：alice token 创建会话 → multipart 上传 PDF → 匿名 302 → presigned 比对 → 404 负例。
best-effort 层：发消息请 AI 生成 txt 并调用 upload_file，超时未交付记 NOTE。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

FAILURES: list[str] = []
NOTES: list[str] = []

PDF_BYTES = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
             b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
             b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
             b"trailer<</Size 4/Root 1 0 R>>\n%%EOF")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def fetch_token(keycloak_base: str, username: str, password: str) -> str:
    url = keycloak_base.rstrip("/") + "/realms/agentteams/protocol/openid-connect/token"
    form = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "agentteams-api",
        "username": username, "password": password}).encode()
    request = urllib.request.Request(url, data=form, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode())
    token = payload.get("access_token")
    if not token:
        raise SystemExit("token endpoint returned no access_token")
    return token


def http_request(url: str, method: str = "GET", *, token: str | None = None,
                 idempotency_key: str | None = None, json_body: dict | None = None,
                 data: bytes | None = None, content_type: str | None = None,
                 timeout: int = 60):
    """返回 (status, headers, body_bytes)；302 不跟随（Location 在 headers）。"""
    request = urllib.request.Request(url, method=method)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if idempotency_key:
        request.add_header("Idempotency-Key", idempotency_key)
    if json_body is not None:
        request.data = json.dumps(json_body).encode()  # 必须设 request.data，否则空 body
        request.add_header("Content-Type", "application/json")
    elif data is not None:
        request.data = data
        if content_type:
            request.add_header("Content-Type", content_type)
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def multipart_body(field: str, filename: str, content: bytes,
                   content_type: str) -> tuple[bytes, str]:
    boundary = "----agentteams" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return part + content + f"\r\n--{boundary}--\r\n".encode(), boundary


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  PASS {name}")
    else:
        print(f"  FAIL {name} {detail}")
        FAILURES.append(name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://192.168.122.55:30080")
    parser.add_argument("--keycloak-url", default="http://192.168.122.55:30082")
    parser.add_argument("--username", default="alice")
    parser.add_argument("--password", default="alice-dev")
    parser.add_argument("--skip-best-effort", action="store_true")
    parser.add_argument("--best-effort-timeout", type=int, default=600)
    args = parser.parse_args()
    manager = args.base_url.rstrip("/")
    token = fetch_token(args.keycloak_url, args.username, args.password)

    print("=== 确定性层 ===")
    session_id = str(uuid.uuid4())
    status, _, body = http_request(
        f"{manager}/api/v1/conversations", "POST", token=token,
        idempotency_key=f"l5-file-delivery-create-{session_id}",
        json_body={"sessionId": session_id, "projectId": "project-a",
                   "teamId": "team-a", "workerId": "qwenpaw"})
    check("create conversation 201", status == 201, f"got {status} {body[:200]}")

    upload_body, boundary = multipart_body("file", "l5-file-delivery.pdf",
                                           PDF_BYTES, "application/pdf")
    status, _, body = http_request(
        f"{manager}/api/v1/conversations/{session_id}/files", "POST", token=token,
        data=upload_body, content_type=f"multipart/form-data; boundary={boundary}")
    check("upload 201", status == 201, f"got {status} {body[:200]}")
    file_url = None
    try:
        payload = json.loads(body)
        file_url = payload.get("url")
        check("response has fileId/url", bool(payload.get("fileId")) and bool(file_url),
              str(payload)[:200])
    except Exception as error:
        check("response has fileId/url", False, str(error))

    if file_url:
        status, headers, _ = http_request(manager + file_url)
        check("anonymous download 302", status == 302, f"got {status}")
        location = headers.get("Location", "")
        check("Location is presigned absolute URL", location.startswith("http"),
              location[:120])
        if location.startswith("http"):
            status, _, content = http_request(location)
            check("presigned fetch 200 + bytes equal",
                  status == 200 and content == PDF_BYTES, f"got {status}")

    status, _, _ = http_request(
        f"{manager}/api/v1/conversations/{session_id}/files/{uuid.uuid4()}")
    check("missing file 404", status == 404, f"got {status}")

    if not args.skip_best_effort:
        print("=== best-effort 层（真模型生成并上传，最长 %ss）===" % args.best_effort_timeout)
        status, _, body = http_request(
            f"{manager}/api/v1/conversations/{session_id}", token=token)
        version = json.loads(body).get("version") if status == 200 else None
        message_body = {"content": (
            "请生成一个文本文件 hello-acceptance.txt，内容为一行 ok，"
            "然后调用 upload_file 工具上传，并在回复中给出下载链接。")}
        if version is not None:
            message_body["expectedVersion"] = version
        status, _, body = http_request(
            f"{manager}/api/v1/conversations/{session_id}/messages", "POST",
            token=token, idempotency_key=f"l5-file-delivery-msg-{session_id}",
            json_body=message_body)
        check("message accepted", status == 200, f"got {status} {body[:200]}")
        marker = f"/api/v1/conversations/{session_id}/files/"
        link = None
        deadline = time.time() + args.best_effort_timeout
        while time.time() < deadline and link is None:
            status, _, body = http_request(
                f"{manager}/api/v1/conversations/{session_id}/history", token=token)
            text = body.decode("utf-8", "replace")
            if marker in text:
                start = text.index(marker)
                link = text[start:start + len(marker) + 36].split('"')[0]
            else:
                time.sleep(20)
        if link is None:
            NOTES.append("best-effort：真模型未在时限内交付上传链接（记 NOTE，不算失败）")
        else:
            status, headers, _ = http_request(manager + link)
            if status == 302 and headers.get("Location", "").startswith("http"):
                status, _, content = http_request(headers["Location"])
                check("best-effort delivered file downloadable",
                      status == 200 and b"ok" in content, f"got {status}")
            else:
                check("best-effort delivered file downloadable", False,
                      f"link {link} got {status}")

    print()
    for note in NOTES:
        print("NOTE:", note)
    if FAILURES:
        print(f"FAIL：{len(FAILURES)} 项失败：{FAILURES}")
        return 1
    print("PASS：会话文件交付验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
