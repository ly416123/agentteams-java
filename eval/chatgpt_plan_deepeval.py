"""DeepEval-compatible model client for the ChatGPT plan OAuth Responses API."""

from __future__ import annotations

import asyncio
import json
import os
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from deepeval.models import DeepEvalBaseLLM


class ChatGptPlanDeepEvalModel(DeepEvalBaseLLM):
    """Use an already-authorized ChatGPT plan access token; never persist it."""

    def __init__(self, model: str | None = None, access_token: str | None = None,
                 endpoint: str | None = None, timeout: float = 120.0):
        self.access_token = access_token or os.environ.get("CHATGPT_OAUTH_ACCESS_TOKEN", "")
        if not self.access_token:
            raise RuntimeError("CHATGPT_OAUTH_ACCESS_TOKEN is required")
        self.endpoint = endpoint or os.environ.get(
            "CHATGPT_RESPONSES_URL", "https://api.openai.com/v1/responses")
        self.timeout = timeout
        super().__init__(model=model or os.environ.get("CHATGPT_OAUTH_MODEL", "gpt-5.6-luna"))

    def load_model(self) -> "ChatGptPlanDeepEvalModel":
        return self

    def get_model_name(self, *_args: Any, **_kwargs: Any) -> str:
        return self.name

    def generate(self, prompt: str, schema: Any = None, *_args: Any, **_kwargs: Any) -> str:
        body: dict[str, Any] = {
            "model": self.name,
            "input": [{"role": "user", "content": [{"type": "input_text", "text": str(prompt)}]}],
            "stream": True,
            "store": False,
            "reasoning": {"effort": "low"},
            "max_output_tokens": 2048,
            "text": {"format": {"type": "json_object"}},
        }
        if schema is not None:
            body["text"]["format"] = {"type": "json_schema", "name": "deepeval_judgement",
                                        "strict": True, "schema": schema}
        request = Request(self.endpoint, data=json.dumps(body).encode("utf-8"), method="POST",
                          headers={"Authorization": "Bearer " + self.access_token,
                                   "Content-Type": "application/json", "Accept": "text/event-stream"})
        try:
            with urlopen(request, timeout=self.timeout) as response:
                deadline = time.monotonic() + min(self.timeout, 240.0)
                chunks: list[bytes] = []
                while True:
                    line = response.readline()
                    if not line:
                        break
                    chunks.append(line)
                    if time.monotonic() > deadline:
                        raise RuntimeError("ChatGPT Responses API stream exceeded the evaluation time limit")
                    if line.startswith(b"data:"):
                        try:
                            event = json.loads(line[5:].decode("utf-8"))
                        except (UnicodeDecodeError, json.JSONDecodeError):
                            continue
                        if event.get("type") in {"response.completed", "response.failed", "error"}:
                            break
                payload = _completed_response(b"".join(chunks).decode("utf-8"))
        except HTTPError as error:
            try:
                detail = json.loads(error.read().decode("utf-8")).get("error", {})
            except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
                detail = {}
            safe_detail = detail.get("param") or detail.get("code") or detail.get("type")
            suffix = f" ({safe_detail})" if isinstance(safe_detail, str) and safe_detail.isascii() else ""
            raise RuntimeError(f"ChatGPT Responses API returned HTTP {error.code}{suffix}") from None
        except (URLError, OSError, json.JSONDecodeError) as error:
            raise RuntimeError(f"ChatGPT Responses API request failed ({type(error).__name__})") from None
        output_text = payload.get("output_text")
        if not isinstance(output_text, str):
            chunks: list[str] = []
            for item in payload.get("output", []) or []:
                for content in item.get("content", []) or []:
                    if content.get("type") == "output_text" and isinstance(content.get("text"), str):
                        chunks.append(content["text"])
            output_text = "\n".join(chunks)
        if not output_text.strip():
            raise RuntimeError("ChatGPT Responses API returned no text output")
        return output_text

    async def a_generate(self, prompt: str, schema: Any = None, *_args: Any, **_kwargs: Any) -> str:
        return await asyncio.to_thread(self.generate, prompt, schema)


def _completed_response(stream: str) -> dict[str, Any]:
    completed: dict[str, Any] | None = None
    text_deltas: list[str] = []
    event_types: list[str] = []
    incomplete_reason: str | None = None
    for line in stream.splitlines():
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        try:
            event = json.loads(data)
        except json.JSONDecodeError:
            continue
        event_type = event.get("type")
        if isinstance(event_type, str) and event_type not in event_types and event_type.isascii():
            event_types.append(event_type[:80])
        if event_type == "response.incomplete":
            response = event.get("response")
            details = response.get("incomplete_details") if isinstance(response, dict) else None
            reason = details.get("reason") if isinstance(details, dict) else None
            if isinstance(reason, str) and reason in {"max_output_tokens", "content_filter"}:
                incomplete_reason = reason
        if event_type == "response.completed":
            response = event.get("response")
            if isinstance(response, dict):
                completed = response
        elif event.get("type") == "response.output_text.delta":
            delta = event.get("delta")
            if isinstance(delta, str):
                text_deltas.append(delta)
        elif event.get("type") in {"error", "response.failed"}:
            error = event.get("error") or event.get("response", {}).get("error") or {}
            code = error.get("code") or error.get("type")
            suffix = f" ({code})" if isinstance(code, str) and code.isascii() else ""
            raise RuntimeError("ChatGPT Responses API stream failed" + suffix)
    if completed is None:
        if incomplete_reason:
            raise RuntimeError(f"ChatGPT Responses API response incomplete ({incomplete_reason})")
        observed = ",".join(event_types[:12]) or "no recognized events"
        raise RuntimeError(f"ChatGPT Responses API stream did not complete (observed: {observed})")
    if text_deltas and not completed.get("output_text"):
        completed["output_text"] = "".join(text_deltas)
    return completed

