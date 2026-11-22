"""Reader-journey selection from explicit revisions and real Git comparison statuses."""

import subprocess
from pathlib import Path

import pytest
from reader_path import PATHS, Decision, decide, main, revisions


class ScriptedDiff:
    """Return one scripted `git diff` status and reject any further call."""

    def __init__(self, status: int) -> None:
        self.status = status
        self.commands: list[tuple[str, ...]] = []

    def __call__(self, command: tuple[str, ...]) -> int:
        assert not self.commands, "the selector compares once"
        self.commands.append(command)
        return self.status


def test_pull_request_compares_from_the_merge_base() -> None:
    assert revisions("pull_request", "b", "h") == ("b...h",)


@pytest.mark.parametrize("event", ["push", "workflow_dispatch", "workflow_call", ""])
def test_other_events_compare_the_revisions_directly(event: str) -> None:
    assert revisions(event, "b", "h") == ("b", "h")


def test_the_comparison_names_every_reader_input() -> None:
    diff = ScriptedDiff(0)
    decide("pull_request", "b", "h", diff)
    assert diff.commands == [("git", "diff", "--quiet", "b...h", "--", *PATHS)]


@pytest.mark.parametrize(
    ("status", "expected"),
    [(0, Decision.SKIP), (1, Decision.RUN), (128, Decision.RUN), (-9, Decision.RUN)],
)
def test_only_a_completed_unchanged_comparison_skips(status: int, expected: Decision) -> None:
    assert decide("push", "b", "h", ScriptedDiff(status)) is expected


def test_an_unknown_base_runs_without_comparing() -> None:
    diff = ScriptedDiff(0)
    assert decide("pull_request", "", "h", diff) is Decision.RUN
    assert diff.commands == []


def git(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        (
            "git",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
            "-C",
            str(repository),
            *arguments,
        ),
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def test_real_git_statuses_map_to_decisions(tmp_path: Path) -> None:
    git(tmp_path, "init", "-q")
    git(tmp_path, "config", "user.email", "fixture@example.invalid")
    git(tmp_path, "config", "user.name", "fixture")
    (tmp_path / "unrelated.txt").write_text("a\n")
    git(tmp_path, "add", ".")
    git(tmp_path, "commit", "-q", "-m", "base")
    base = git(tmp_path, "rev-parse", "HEAD")
    (tmp_path / "unrelated.txt").write_text("b\n")
    git(tmp_path, "commit", "-q", "-am", "unrelated")
    unrelated = git(tmp_path, "rev-parse", "HEAD")
    start = tmp_path / "docs/public/start.mdx"
    start.parent.mkdir(parents=True)
    start.write_text("# Start\n")
    git(tmp_path, "add", ".")
    git(tmp_path, "commit", "-q", "-m", "reader")
    reader = git(tmp_path, "rev-parse", "HEAD")

    def diff(command: tuple[str, ...]) -> int:
        return subprocess.run(command, cwd=tmp_path, check=False, capture_output=True).returncode

    assert decide("pull_request", base, unrelated, diff) is Decision.SKIP
    assert decide("push", base, reader, diff) is Decision.RUN
    assert decide("push", "0" * 40, reader, diff) is Decision.RUN


def test_main_appends_the_decision_to_the_step_output(tmp_path: Path) -> None:
    output = tmp_path / "output"
    output.write_text("earlier=kept\n")
    assert main({"GITHUB_OUTPUT": str(output), "GITHUB_EVENT_NAME": "push"}) == 0
    assert output.read_text() == "earlier=kept\nrun=true\n"


def test_main_without_step_output_prints_the_decision(capsys: pytest.CaptureFixture[str]) -> None:
    assert main({}) == 0
    assert capsys.readouterr().out == "run=true\n"
