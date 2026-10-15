from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_full_script_pytest_installs_the_deepeval_test_dependency():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    start = workflow.index("- name: Test Kind recovery scripts")
    end = workflow.find("- name:", start + 1)
    step = workflow[start:] if end < 0 else workflow[start:end]

    assert "python -m pytest scripts -q" in step
    assert "-r eval/requirements-deepeval.txt" in step
