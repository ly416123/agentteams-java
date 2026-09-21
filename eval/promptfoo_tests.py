"""Generate Promptfoo tests from the canonical AgentTeams YAML cases."""

from __future__ import annotations

from pathlib import Path
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import load_cases


def generate_tests(config: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    cases = load_cases(Path(__file__).with_name("cases") / "core.yaml")
    tests = []
    for case in cases:
        input_json = case.task.get("inputJson", {})
        tests.append({
            "description": case.name,
            "vars": {
                "case_id": case.case_id,
                "prompt": str(input_json.get("prompt", "")),
            },
        })
    return tests
