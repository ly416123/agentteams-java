from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def test_ci_workflow_limits_github_token_to_read_only_repository_contents():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "\npermissions:\n  contents: read\n\njobs:\n" in workflow


def test_full_script_pytest_installs_the_deepeval_test_dependency():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    start = workflow.index("- name: Test Kind recovery scripts")
    end = workflow.find("- name:", start + 1)
    step = workflow[start:] if end < 0 else workflow[start:end]

    assert "python -m pytest scripts -q" in step
    assert "-r requirements/release-ci-py312.lock" in step
    assert "alibabacloud-agentcore20260804==" in (
        ROOT / "requirements/release-ci-py312.lock"
    ).read_text(encoding="utf-8")


def test_offline_contract_jobs_use_small_pinned_python_dependency_lock():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    lock = (ROOT / "requirements/eval-ci-py312.lock").read_text(encoding="utf-8")
    assert "python-version: '3.12'" in workflow
    assert "-r requirements/eval-ci-py312.lock" in workflow
    assert "pyyaml==6.0.3" in lock
    assert "pypdf==6.19.0" in lock
    assert "pymupdf==1.27.2.3" in lock
    assert "pip install --disable-pip-version-check pyyaml" not in workflow


def test_ci_python_locks_pin_hashes_for_every_distribution():
    for filename in (
        "eval-ci-py312.lock",
        "pip-installer-py312.lock",
        "release-ci-py312.lock",
    ):
        lines = (ROOT / "requirements" / filename).read_text(encoding="utf-8").splitlines()
        entries = []
        for index, line in enumerate(lines):
            if not re.match(r"^[A-Za-z0-9_.-]+==", line):
                continue
            hashes = []
            cursor = index + 1
            while cursor < len(lines) and lines[cursor].lstrip().startswith("--hash="):
                hashes.append(lines[cursor].strip().rstrip("\\").strip())
                cursor += 1
            entries.append((line, hashes))

        assert entries, f"{filename} must contain pinned packages"
        for requirement, hashes in entries:
            assert hashes, f"{filename}: {requirement} has no artifact hash"
            assert all(re.fullmatch(r"--hash=sha256:[0-9a-f]{64}", value) for value in hashes)


def test_workflow_python_installs_must_reference_a_versioned_lock_file():
    for filename in ("ci.yml", "release.yml"):
        workflow = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
        for line_number, line in enumerate(workflow.splitlines(), start=1):
            if "pip install" not in line:
                continue
            window = "\n".join(workflow.splitlines()[line_number - 1:line_number + 4])
            assert "requirements/" in window and ".lock" in window, (
                f"{filename}:{line_number}: Python install must use a pinned lock file"
            )


def test_workflow_python_lock_installs_enforce_hashes():
    for filename in ("ci.yml", "release.yml"):
        workflow = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
        lines = workflow.splitlines()
        for line_number, line in enumerate(lines):
            if "pip install" not in line:
                continue
            window = "\n".join(lines[line_number:line_number + 5])
            if "requirements/" in window and ".lock" in window:
                assert "--require-hashes" in window, (
                    f"{filename}:{line_number + 1}: locked Python installs must enforce hashes"
                )


def test_source_build_dependencies_use_the_hashed_release_lock_as_build_constraints():
    release_lock = (ROOT / "requirements/release-ci-py312.lock").read_text(encoding="utf-8")
    assert "setuptools==84.0.0" in release_lock
    assert "--hash=sha256:" in release_lock[release_lock.index("setuptools==84.0.0"):]
    pip_lock = (ROOT / "requirements/pip-installer-py312.lock").read_text(encoding="utf-8")
    assert "pip==26.2.1" in pip_lock

    for filename in ("ci.yml", "release.yml"):
        workflow = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
        assert "--build-constraint requirements/release-ci-py312.lock" in workflow
        assert "requirements/pip-installer-py312.lock" in workflow
        bootstrap = workflow.index("requirements/pip-installer-py312.lock")
        release_install = workflow.index("--build-constraint requirements/release-ci-py312.lock")
        assert bootstrap < release_install, f"{filename}: pip must be upgraded before using build constraints"
        bootstrap_window = workflow[max(0, bootstrap - 150):bootstrap + 100]
        assert "--require-hashes" in bootstrap_window
        assert "--only-binary=:all:" in bootstrap_window


def test_release_unittest_discovery_installs_pytest_imported_by_a_script_test():
    workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    start = workflow.index("- name: Install Python verification dependencies")
    end = workflow.find("- name:", start + 1)
    step = workflow[start:] if end < 0 else workflow[start:end]
    lockfile = ROOT / "requirements/release-ci-py312.lock"
    assert "-r requirements/release-ci-py312.lock" in step
    assert any(line.startswith("pytest==") for line in lockfile.read_text(encoding="utf-8").splitlines())
