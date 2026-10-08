#!/usr/bin/env python3
"""Verify one L5 evaluation PDF through its short-lived delivery URL."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import AgentTeamsClient, EvalError, SAFE_STORAGE_REF

MAX_PDF_BYTES = 20_000_000


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def fetch_pdf(url: str, limit: int) -> bytes:
    try:
        with build_opener(ProxyHandler({}), _NoRedirect()).open(Request(url, method="GET"), timeout=20) as response:
            return response.read(limit + 1)
    except (HTTPError, URLError, OSError, ValueError):
        raise EvalError("PDF delivery request failed") from None


def verify_pdf(artifact: Mapping[str, Any], expected_host: str,
               fetcher: Callable[[str, int], bytes] = fetch_pdf) -> dict[str, Any]:
    name = artifact.get("name")
    size = artifact.get("sizeBytes")
    digest = artifact.get("sha256")
    storage_ref = artifact.get("storageRef")
    url = artifact.get("downloadUrl")
    if (not isinstance(name, str) or not name.lower().endswith(".pdf")
            or artifact.get("contentType") != "application/pdf"
            or not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= MAX_PDF_BYTES
            or not isinstance(digest, str) or len(digest) != 64
            or any(char not in "0123456789abcdef" for char in digest)
            or not isinstance(storage_ref, str) or not SAFE_STORAGE_REF.fullmatch(storage_ref)
            or not isinstance(url, str)):
        raise EvalError("PDF artifact metadata is incomplete or invalid")
    try:
        parts = urlsplit(url)
        matches_endpoint = parts.scheme == "http" and parts.hostname == expected_host and parts.port == 30090
    except ValueError:
        matches_endpoint = False
    if (not matches_endpoint
            or parts.username or parts.password or not parts.query):
        raise EvalError("PDF delivery URL does not match the expected L5 endpoint")
    data = fetcher(url, size)
    if len(data) != size or not data.startswith(b"%PDF-") or b"%%EOF" not in data[-1024:]:
        raise EvalError("downloaded PDF size or signature is invalid")
    actual_digest = hashlib.sha256(data).hexdigest()
    if actual_digest != digest:
        raise EvalError("downloaded PDF SHA-256 does not match the result manifest")
    try:
        pages = len(PdfReader(io.BytesIO(data), strict=True).pages)
    except Exception:
        raise EvalError("downloaded PDF page tree could not be parsed") from None
    if pages < 1:
        raise EvalError("downloaded PDF has no pages")
    return {"storageRef": storage_ref, "sizeBytes": size, "sha256": actual_digest,
            "pageCount": pages}


def _uuid(value: Any, field: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (TypeError, ValueError, AttributeError):
        raise EvalError(f"evaluation report has no valid {field}") from None


def _cluster_url() -> str:
    result = subprocess.run(["kubectl", "-n", "agentteams", "get", "svc",
                             "agentteams-agentteams-java-control-plane", "-o", "jsonpath={.spec.clusterIP}"],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise EvalError("cannot resolve L5 Control Plane service")
    return f"http://{result.stdout.strip()}:8080"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default="")
    parser.add_argument("--expected-host", default="192.168.122.55")
    parser.add_argument("--token-env", default="AGENTTEAMS_EVAL_TOKEN")
    args = parser.parse_args(argv)
    try:
        token = os.environ.get(args.token_env, "")
        if not token:
            raise EvalError(f"environment variable {args.token_env} is not set")
        report = json.loads(args.report.read_text(encoding="utf-8"))
        cases = report.get("cases", [])
        if not isinstance(cases, list) or len(cases) != 1 or cases[0].get("caseId") != "multi-agent-pdf-positive":
            raise EvalError("report must contain the single positive PDF case")
        case = cases[0]
        task_id, run_id = _uuid(case.get("taskId"), "taskId"), _uuid(case.get("runId"), "runId")
        client = AgentTeamsClient(args.base_url or _cluster_url(), token)
        result = client._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/result")
        artifacts = result.get("artifacts", []) if isinstance(result, dict) else []
        pdfs = [item for item in artifacts if isinstance(item, dict)
                and str(item.get("name", "")).lower().endswith(".pdf")]
        if len(pdfs) != 1:
            raise EvalError("result must contain exactly one PDF artifact")
        proof = verify_pdf(pdfs[0], args.expected_host)
        safe_report = {"caseId": case["caseId"], "taskId": task_id, "runId": run_id,
                       "verifiedAt": datetime.now(timezone.utc).isoformat(), "pdf": proof}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(safe_report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"PDF verified: {proof['pageCount']} pages, {proof['sizeBytes']} bytes")
        return 0
    except (EvalError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"L5 PDF verification failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
