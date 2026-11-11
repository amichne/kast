from __future__ import annotations

import json
import plistlib
from dataclasses import asdict, dataclass
from pathlib import Path

import knowledge_refresh as refresh


def test_plan_preserves_spaces_and_host_options(tmp_path: Path) -> None:
    plan = refresh.make_plan(
        tmp_path / "repo space", tmp_path / "state space", refresh.Host.CODEX, "model x", True
    )
    command = plan.commands[-1]
    assert command.argv[:4] == ("codex", "exec", "--sandbox", "workspace-write")
    assert '"mcp","--host","codex"' in command.argv[command.argv.index("-c", 6) + 1]
    assert "state space" in command.argv[command.argv.index("-c", 6) + 1]
    assert command.argv[command.argv.index("--model") + 1] == "model x"
    assert not (tmp_path / "state space").exists()


def test_pi_default_model_and_extension(tmp_path: Path) -> None:
    command = refresh.agent_command(refresh.Host.PI, None, tmp_path / "a b", "prompt")
    assert command.argv[command.argv.index("--model") + 1] == "gpt-6.1-terra"
    assert command.argv[command.argv.index("--extension") + 1].endswith(
        "/integrations/pi/openwiki.js"
    )
    assert "--no-session" in command.argv


class Script:
    def __init__(self, replies: list[tuple[str, int, bytes]]) -> None:
        self.replies = replies

    def __call__(self, command: refresh.Command, *, capture: bool = False) -> tuple[int, bytes]:
        assert self.replies, f"unexpected call: {command.argv}"
        expected, code, data = self.replies.pop(0)
        assert expected in command.argv
        assert capture == bool(data)
        return code, data

    def done(self) -> None:
        assert not self.replies


def prepare(tmp_path: Path) -> tuple[refresh.Plan, Path]:
    repo = tmp_path / "repo"
    repo.mkdir()
    prompt = repo / "docs/tooling/knowledge-refresh-prompt.md"
    prompt.parent.mkdir(parents=True)
    _ = prompt.write_text("review", encoding="utf-8")
    wt = tmp_path / "work tree"
    package = wt / "docs/tooling/markdown/node_modules/openwiki"
    (package / "dist/cli").mkdir(parents=True)
    (package / "dist/integrations/pi").mkdir(parents=True)

    @dataclass(frozen=True)
    class PackageFixture:
        version: str = "0.9.0"

    _ = (package / "package.json").write_text(
        json.dumps(asdict(PackageFixture())), encoding="utf-8"
    )
    owned_prompt = wt / "docs/tooling/knowledge-refresh-prompt.md"
    _ = owned_prompt.write_text("review", encoding="utf-8")
    (package / "dist/cli/cli.js").touch()
    (package / "dist/integrations/pi/openwiki.js").touch()
    return refresh.make_plan(repo, tmp_path / "state", refresh.Host.PI, None, False), wt


def test_scope_violation_after_failed_agent_is_preserved(tmp_path: Path) -> None:
    plan, wt = prepare(tmp_path)
    script = Script(
        [
            ("rev-parse", 0, b"abc\n"),
            ("pi", 1, b""),
            ("rev-parse", 0, b"abc\n"),
            ("status", 0, b" M source.kt\0"),
        ]
    )
    result = refresh.run_worktree(plan, wt, tmp_path / "log", script)
    assert result.stage == refresh.Stage.SCOPE
    assert result.outcome == "outside_scope"
    script.done()


def test_agent_failure_and_success(tmp_path: Path) -> None:
    plan, wt = prepare(tmp_path)
    for exit_code, stage in [(1, refresh.Stage.AGENT), (0, refresh.Stage.COMPLETE)]:
        script = Script(
            [
                ("rev-parse", 0, b"abc\n"),
                ("pi", exit_code, b""),
                ("rev-parse", 0, b"abc\n"),
                ("status", 0, b"?? docs/public/a.mdx\0"),
            ]
        )
        assert refresh.run_worktree(plan, wt, tmp_path / "log", script).stage == stage
        script.done()


def test_bootstrap_failure_stops_before_agent(tmp_path: Path) -> None:
    plan, wt = prepare(tmp_path)
    plan = refresh.make_plan(Path(plan.repo), Path(plan.state_dir), refresh.Host.PI, None, True)
    script = Script([("npm", 1, b"")])
    assert refresh.run_worktree(plan, wt, tmp_path / "log", script).stage == refresh.Stage.BOOTSTRAP
    script.done()


def test_launchd_recipe_has_literal_arguments_and_daily_schedule(tmp_path: Path) -> None:
    plan = refresh.make_plan(
        tmp_path / "repo space", tmp_path / "state", refresh.Host.PI, None, True
    )
    parsed = plistlib.loads(refresh.launchd_document(plan, Path("/tools/mise")))
    assert parsed["StartCalendarInterval"] == {"Hour": 9, "Minute": 0}
    assert parsed["WorkingDirectory"] == str(tmp_path / "repo space")
    assert parsed["ProgramArguments"][-1] == "--run"
    assert str(tmp_path / "repo space") in parsed["ProgramArguments"]
    assert not (tmp_path / "state").exists()


def test_rejection_does_not_modify_checkout_or_clone(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    protected = repo / "work.txt"
    protected.write_text("user work")
    plan = refresh.make_plan(repo, repo / "state", refresh.Host.PI, None, True)
    script = Script([])
    result = refresh.run(plan, script)
    assert result.outcome is refresh.Outcome.STATE_INSIDE_CHECKOUT
    assert protected.read_text() == "user work"
    assert not (repo / "state").exists()
    script.done()


def test_clone_failure_retains_structured_stage_and_releases_owned_lock(tmp_path: Path) -> None:
    plan, _ = prepare(tmp_path)
    script = Script(
        [
            ("rev-parse", 0, (plan.repo + "\n").encode()),
            ("remote", 0, b"git@example.test:repo\n"),
            ("clone", 1, b""),
        ]
    )
    result = refresh.run(plan, script)
    assert result.stage is refresh.Stage.CLONE
    assert result.outcome is refresh.Outcome.FAILED
    assert not (Path(plan.state_dir) / "lock").exists()
    assert result.log is not None
    stored = json.loads(Path(result.log).read_text())
    assert stored["stage"] == "clone"
    assert stored["outcome"] == "failed"
    script.done()


def test_lock_contention(tmp_path: Path) -> None:
    plan, _ = prepare(tmp_path)
    (Path(plan.state_dir) / "lock").mkdir(parents=True)
    script = Script(
        [("rev-parse", 0, (plan.repo + "\n").encode()), ("remote", 0, b"git@example.test:repo\n")]
    )
    assert refresh.run(plan, script).outcome == "contended"
    script.done()
