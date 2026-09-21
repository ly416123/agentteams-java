"""Promptfoo assertion for the AgentTeams provider result."""

from __future__ import annotations

import json
from typing import Any


def get_assert(output: str, context: dict[str, Any]) -> dict[str, Any]:
    try:
        result = json.loads(output)
    except json.JSONDecodeError:
        return {"pass": False, "score": 0.0, "reason": "provider output is not JSON"}
    return {
        "pass": bool(result.get("passed")),
        "score": float(result.get("score", 0.0)),
        "reason": "all deterministic assertions passed" if result.get("passed") else "an AgentTeams assertion failed",
    }
