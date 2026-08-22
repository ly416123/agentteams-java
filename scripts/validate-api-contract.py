#!/usr/bin/env python3
"""Check that the public Task and Artifact HTTP surface stays aligned."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"API_CONTRACT_FAIL: {message}")


def require_all(label: str, text: str, required: tuple[str, ...]) -> None:
    missing = [value for value in required if value not in text]
    if missing:
        fail(f"{label} missing {', '.join(missing)}")


def main() -> None:
    task_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/TaskController.java").read_text(
        encoding="utf-8")
    artifact_controller = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/api/ArtifactController.java").read_text(
        encoding="utf-8")
    authorization = (ROOT / "control-plane/src/main/java/io/agentteams/controlplane/security/ApiAuthorizationPolicy.java").read_text(
        encoding="utf-8")
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    require_all("TaskController", task_controller, (
        '@PostMapping("/{id}/queue")', '@PostMapping("/{id}/cancel")',
        '@PostMapping("/{id}/retry")', '@PostMapping("/{id}/pause")',
        '@PostMapping("/{id}/approve")', '@PostMapping("/{id}/reject")',
    ))
    require_all("ArtifactController", artifact_controller, (
        '@RequestMapping("/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts")',
        '@PostMapping("/uploads")', '@PostMapping("/complete")',
        '@org.springframework.web.bind.annotation.GetMapping',
        "findTaskAttempt", "prepareDownload",
    ))
    require_all("ApiAuthorizationPolicy", authorization, (
        "Permission.TASK_CANCEL", "Permission.TASK_RETRY", "Permission.TASK_PAUSE",
        "Permission.TASK_APPROVE", "Permission.TASK_REJECT", "Permission.ARTIFACT_READ",
        "Permission.ARTIFACT_WRITE",
    ))
    require_all("README", readme, (
        "/retry", "/pause", "/approve", "/reject", "/artifacts/uploads", "/artifacts/complete",
    ))
    if "validate-api-contract.py" not in workflow:
        fail("CI must execute validate-api-contract.py")
    print("API_CONTRACT_OK")


if __name__ == "__main__":
    main()
