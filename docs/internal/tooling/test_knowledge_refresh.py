from __future__ import annotations

import json
import plistlib
import subprocess
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from functools import partial
from pathlib import Path

import pytest

import knowledge_refresh as refresh
import publication


def native_repository(tmp_path: Path) -> tuple[refresh.Plan, Path]:
    """Own only fixture files; use the locked native reader, never a model or Host."""
    wt = tmp_path / "candidate"
    wt.mkdir()
    tooling = wt / "docs/internal/tooling"
    (tooling / "markdown").mkdir(parents=True)
    (tooling / "markdown/node_modules").symlink_to(
        Path(__file__).parent / "markdown/node_modules", target_is_directory=True
    )
    _ = (tooling / "knowledge-refresh-prompt.md").write_text("Review through native tools.\n")
    _ = (wt / ".gitignore").write_text("node_modules/\n")
    _ = (wt / "docs/okf-public.json").write_text("{}\n")
    _ = (wt / "source.kt").write_text("val answer = 41\n")
    for args in (
        ("init", "-q"),
        ("add", "."),
        (
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "fixture",
        ),
    ):
        _ = subprocess.run(("git", *args), cwd=wt, check=True, capture_output=True)
    return refresh.make_plan(wt, tmp_path / "state", refresh.Host.PI, None, False), wt


class HostEffect:
    def __init__(self, effect: Callable[[], int]) -> None:
        self.effect = effect
        self.calls = 0

    def __call__(self, command: refresh.Command, *, capture: bool = False) -> tuple[int, bytes]:
        if command.argv[0] == "pi":
            assert not capture
            self.calls += 1
            return self.effect(), b""
        return refresh.execute(command, capture=capture)


def node_fixture(wt: Path, program: str) -> None:
    _ = subprocess.run(
        (
            "node",
            "--input-type=module",
            "--eval",
            "import {pathToFileURL} from 'node:url';"
            "import {resolve} from 'node:path';"
            "const root = process.cwd();"
            "const load = name => import(pathToFileURL(resolve(root,"
            "'docs/internal/tooling/markdown/node_modules/openwiki/dist', name)).href);" + program,
        ),
        cwd=wt,
        check=True,
        capture_output=True,
        timeout=20,
    )


def completed_repository(tmp_path: Path, *, qualifier: bool = False) -> tuple[refresh.Plan, Path]:
    plan, wt = native_repository(tmp_path)
    if qualifier:
        _ = (wt / "docs/internal/tooling/publication.py").write_text(
            "# External qualification owner marker.\n"
        )
    node_fixture(
        wt,
        """
      const {ensureCodeModeRepoSetup} = await load('ingestion/code-mode.js');
      await ensureCodeModeRepoSetup(root);
    """,
    )
    _ = subprocess.run(("git", "add", "."), cwd=wt, check=True, capture_output=True)
    _ = subprocess.run(
        (
            "git",
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "native fixture setup",
        ),
        cwd=wt,
        check=True,
        capture_output=True,
    )
    node_fixture(
        wt,
        """
      import {mkdir, writeFile} from 'node:fs/promises';
      import {createHash} from 'node:crypto';
      const hash = value => 'sha256:' + createHash('sha256').update(value).digest('hex');
      const {ClaimsStore} = await load('claims/brains/code/store.js');
      const {createRepositorySourceSnapshot, writeLastUpdateMetadata} = await load('agent/utils.js');
      const {OpenWikiIgnore} = await load('agent/openwiki-ignore.js');
      const {replaceRepositoryPageManifest} = await load('generation/page-manifest.js');
      const page = '/openwiki/contracts/example.md';
      const text = `---
type: Contract
title: Example
verified:
  - by: fixture/1
    at: 2026-01-01T00:00:00Z
---

# Example

The source declares answer 41.
`;
      await mkdir(resolve(root, 'openwiki/contracts'), {recursive: true});
      await writeFile(resolve(root, page.slice(1)), text);
      await new ClaimsStore(root).writePage(page, {
        schemaVersion: 1, pageVersion: hash(text),
        claims: [{id: 'source-answer', statement: 'The source declares answer 41.',
          evidence: [{resource: 'repo://source.kt', version: 'repo-file-v1:' + hash(`val answer = 41
`)}]}],
        verification: {by: 'fixture/1', at: '2026-01-01T00:00:00Z'},
      });
      const source = await createRepositorySourceSnapshot(root, await OpenWikiIgnore.load(root));
      await replaceRepositoryPageManifest(root, [page], {
        gitHead: source.gitHead, sourceFingerprint: source.fingerprint,
      });
      await writeLastUpdateMetadata('update', root, 'fixture/no-model', 'repository', 'complete', 'en');
    """,
    )
    return plan, wt


def begin_fixture(wt: Path) -> None:
    node_fixture(
        wt,
        """
      const {beginRepositoryRun} = await load('generation/repository-run.js');
      const result = await beginRepositoryRun({root, mode: 'update', force: true,
        actor: {producerActor: 'fixture/no-model', metadataModel: 'fixture/no-model'}});
      if (result.view.status !== 'active') throw new Error('Expected an active fixture run');
    """,
    )


def test_checkpoint_mismatch_stops_before_host_and_preserves_native_state(tmp_path: Path) -> None:
    plan, wt = completed_repository(tmp_path)
    begin_fixture(wt)
    checkpoint = wt / "openwiki/.run.json"
    before = checkpoint.read_bytes()
    _ = (wt / "docs/okf-public.json").write_text('{"changed": true}\n')
    host = HostEffect(lambda: 0)
    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", host)
    assert result.outcome is refresh.Outcome.CHECKPOINT_MISMATCH
    assert result.qualification is None
    assert host.calls == 0
    assert checkpoint.read_bytes() == before


@pytest.mark.parametrize("exit_code", [0, 1, 130])
def test_unfinished_native_run_never_qualifies_and_keeps_progress(
    tmp_path: Path, exit_code: int
) -> None:
    plan, wt = completed_repository(tmp_path)
    accepted = (wt / "openwiki/contracts/example.md").read_bytes()
    begin_fixture(wt)
    checkpoint = (wt / "openwiki/.run.json").read_bytes()

    def partial_progress() -> int:
        with (wt / "openwiki/contracts/example.md").open("a") as stream:
            _ = stream.write("\nUnsubmitted candidate progress.\n")
        return exit_code

    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", HostEffect(partial_progress))
    assert result.outcome is (
        refresh.Outcome.NATIVE_INCOMPLETE
        if exit_code == 0
        else refresh.Outcome.INTERRUPTED
        if exit_code == 130
        else refresh.Outcome.FAILED
    )
    assert result.qualification is None
    assert (wt / "openwiki/.run.json").read_bytes() == checkpoint
    assert (
        wt / "openwiki/contracts/example.md"
    ).read_bytes() == accepted + b"\nUnsubmitted candidate progress.\n"
    assert (wt / "source.kt").read_text() == "val answer = 41\n"


def test_completed_native_candidate_without_qualification_is_not_eligible(tmp_path: Path) -> None:
    plan, wt = completed_repository(tmp_path)
    host = HostEffect(lambda: 0)
    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", host)
    assert result.outcome is refresh.Outcome.COMPLETED_UNVERIFIED
    assert result.qualification is None
    native = refresh.inspect_native(wt, refresh.execute)
    assert isinstance(native, refresh.NativeInspection) and native.complete


def test_noop_preserves_claim_and_prose_bytes_without_qualification(tmp_path: Path) -> None:
    plan, wt = completed_repository(tmp_path)
    _ = subprocess.run(("git", "add", "."), cwd=wt, check=True, capture_output=True)
    _ = subprocess.run(
        (
            "git",
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "accepted fixture",
        ),
        cwd=wt,
        check=True,
        capture_output=True,
    )
    node_fixture(
        wt,
        """
      const {writeLastUpdateMetadata} = await load('agent/utils.js');
      await writeLastUpdateMetadata('update', root, 'fixture/no-model', 'repository', 'complete', 'en');
    """,
    )
    paths = (wt / "openwiki/contracts/example.md", wt / "openwiki/.claims/contracts/example.json")
    before = tuple(path.read_bytes() for path in paths)
    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", HostEffect(lambda: 0))
    assert result.outcome is refresh.Outcome.NO_CHANGES
    assert result.qualification is None
    assert tuple(path.read_bytes() for path in paths) == before


def test_readiness_never_exposes_or_authenticates_credentials() -> None:
    missing = refresh.readiness(refresh.Host.NATIVE, {})
    present = refresh.readiness(refresh.Host.NATIVE, {"OPENAI_API_KEY": "test-only-placeholder"})
    assert missing.credential is refresh.CredentialStatus.MISSING
    assert present.credential is refresh.CredentialStatus.PRESENT_UNVERIFIED
    assert present.authentication == "not-assessed"
    assert present.daily_schedule == "blocked"
    assert "test-only-placeholder" not in json.dumps(asdict(present))
    assert (
        refresh.readiness(refresh.Host.CODEX, {}).credential
        is refresh.CredentialStatus.HOST_MANAGED_UNVERIFIED
    )


def test_eligibility_requires_matching_qualification() -> None:
    with pytest.raises(ValueError, match="qualification"):
        _ = refresh.Result(refresh.Stage.COMPLETE, refresh.Outcome.INTEGRATION_ELIGIBLE)


@pytest.mark.parametrize(
    "exit_code, outcome",
    [(1, refresh.Outcome.QUALIFICATION_FAILED), (127, refresh.Outcome.COMPLETED_UNVERIFIED)],
)
def test_failed_or_unavailable_qualification_never_qualifies(
    tmp_path: Path, exit_code: int, outcome: refresh.Outcome
) -> None:
    plan, wt = completed_repository(tmp_path, qualifier=True)
    native = refresh.inspect_native(wt, refresh.execute)
    assert isinstance(native, refresh.NativeInspection) and native.complete
    # The narrow qualifier adapter supplies a typed snapshot then rejects or
    # becomes unavailable. It never supplies a semantic acceptance witness.
    snapshot = publication.Snapshot(
        native.source.revision,
        (publication.InputDigest("docs/okf-public.json", "a" * 64),),
        "b" * 64,
    )

    class QualificationFailure(HostEffect):
        def __call__(self, command: refresh.Command, *, capture: bool = False) -> tuple[int, bytes]:
            if command.argv[0] == "uv":
                assert capture
                if "--snapshot" in command.argv:
                    return 0, json.dumps(asdict(snapshot)).encode()
                return exit_code, b""
            return super().__call__(command, capture=capture)

    result = refresh.run_worktree(
        plan, wt, tmp_path / "result.json", QualificationFailure(lambda: 0)
    )
    assert result.outcome is outcome
    assert result.qualification is None
    assert not (wt / "openwiki/.run.json").exists()


@pytest.mark.parametrize("mismatch", ["none", "source", "inputs", "snapshot", "source-only"])
def test_qualification_adapter_binds_exact_owner_snapshot(tmp_path: Path, mismatch: str) -> None:
    # Inject only the qualification owner's typed response. This proves adapter
    # identity preservation, not native completion or a real publication decision.
    _ = (tmp_path / "docs/internal/tooling").mkdir(parents=True)
    _ = (tmp_path / "docs/internal/tooling/publication.py").write_text(
        "# Qualification owner marker.\n"
    )
    source = refresh.SourceIdentity("a" * 40, "sha256:" + "b" * 64)
    inputs = (publication.InputDigest("openwiki/contracts/example.md", "c" * 64),)
    snapshot = publication.Snapshot(source.revision, inputs, "d" * 64)
    report = publication.PublicationReport(
        source.revision,
        inputs,
        snapshot.inputsDigest,
        (
            publication.Obligation(
                "fixture-contract", publication.State.PASSED, "Typed owner response"
            ),
        ),
        "accepted",
        None,
        (),
    )
    response = (
        replace(report, sourceRevision="e" * 40)
        if mismatch == "source"
        else replace(report, inputsDigest="e" * 64)
        if mismatch == "inputs"
        else publication.conclude_source(snapshot, report.obligations, None, ())
        if mismatch == "source-only"
        else report
    )
    after = replace(snapshot, inputsDigest="e" * 64) if mismatch == "snapshot" else snapshot
    script = Script(
        [
            ("--snapshot", 0, json.dumps(asdict(snapshot)).encode()),
            ("docs/internal/tooling/publication.py", 0, json.dumps(asdict(response)).encode()),
            ("--snapshot", 0, json.dumps(asdict(after)).encode()),
        ]
    )
    result = refresh.qualify(tmp_path, source, script)
    assert result == (
        refresh.Outcome.QUALIFICATION_FAILED
        if mismatch != "none"
        else refresh.Qualification(source, snapshot.inputsDigest)
    )
    script.done()


def test_resume_source_mismatch_does_not_invoke_host(tmp_path: Path) -> None:
    plan, wt = native_repository(tmp_path)
    original = refresh.inspect_native(wt, refresh.execute)
    assert isinstance(original, refresh.NativeInspection)
    _ = (wt / "docs/okf-public.json").write_text('{"changed": true}\n')
    host = HostEffect(lambda: 0)
    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", host, original.source)
    assert result.outcome is refresh.Outcome.CHECKPOINT_MISMATCH
    assert host.calls == 0


def test_resume_plan_does_not_clone_fetch_or_replace_candidate(tmp_path: Path) -> None:
    run_id = "a" * 32
    plan = refresh.make_plan(
        tmp_path / "repo", tmp_path / "state", refresh.Host.CODEX, None, False, resume=run_id
    )
    assert not any(
        arg in {"clone", "fetch", "add"} for command in plan.commands for arg in command.argv
    )
    assert plan.commands[-1].cwd == str(tmp_path / "state" / run_id / "worktree")


def test_resume_mismatch_preserves_recorded_identity_and_caller_snapshot(tmp_path: Path) -> None:
    caller = tmp_path / "accepted"
    caller.mkdir()
    _ = (caller / "accepted.md").write_text("Accepted snapshot.\n")
    state = tmp_path / "state"
    run_id = "a" * 32
    owned = state / run_id
    owned.mkdir(parents=True)
    _, candidate = native_repository(owned)
    wt = candidate.rename(owned / "worktree")
    native = refresh.inspect_native(wt, refresh.execute)
    assert isinstance(native, refresh.NativeInspection)
    log = owned / "result.json"
    _ = log.write_text(
        json.dumps(
            asdict(
                refresh.Result(
                    refresh.Stage.AGENT,
                    refresh.Outcome.INTERRUPTED,
                    str(wt),
                    str(log),
                    native.source,
                )
            )
        )
    )
    saved = log.read_bytes()
    _ = (wt / "docs/okf-public.json").write_text('{"changed": true}\n')

    class ResumeExecutor(HostEffect):
        def __call__(self, command: refresh.Command, *, capture: bool = False) -> tuple[int, bytes]:
            if command.cwd == str(caller):
                assert capture
                if command.argv == ("git", "rev-parse", "--show-toplevel"):
                    return 0, (str(caller) + "\n").encode()
                assert command.argv == ("git", "remote", "get-url", "origin")
                return 0, b"https://example.invalid/repository.git\n"
            return super().__call__(command, capture=capture)

    host = ResumeExecutor(lambda: 0)
    plan = refresh.make_plan(caller, state, refresh.Host.PI, None, False, resume=run_id)
    result = refresh.run(plan, host)
    assert result.outcome is refresh.Outcome.CHECKPOINT_MISMATCH
    assert host.calls == 0
    assert log.read_bytes() == saved
    assert (caller / "accepted.md").read_text() == "Accepted snapshot.\n"
    assert not (state / "lock").exists()


@pytest.mark.parametrize("interrupt", ["return-code", "host", "post-host"])
def test_interrupted_isolated_run_keeps_caller_snapshot_and_candidate_checkpoint(
    tmp_path: Path,
    interrupt: str,
) -> None:
    _, caller = completed_repository(tmp_path)
    for args in (
        ("add", "."),
        (
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "accepted fixture",
        ),
        ("branch", "-M", "main"),
        ("remote", "add", "origin", str(caller)),
    ):
        _ = subprocess.run(("git", *args), cwd=caller, check=True, capture_output=True)
    protected = (
        caller / "openwiki/contracts/example.md",
        caller / "openwiki/.claims/contracts/example.json",
        caller / "openwiki/.last-update.json",
    )
    accepted = tuple(path.read_bytes() for path in protected)

    class IsolatedRuntime:
        def __init__(self) -> None:
            self.host_calls = 0

        def __call__(self, command: refresh.Command, *, capture: bool = False) -> tuple[int, bytes]:
            if command.argv[0] == "pi":
                self.host_calls += 1
                candidate = Path(command.cwd)
                begin_fixture(candidate)
                with (candidate / "openwiki/contracts/example.md").open("a") as output:
                    _ = output.write("\nInterrupted candidate work.\n")
                if interrupt == "host":
                    raise KeyboardInterrupt
                return 130, b""
            if self.host_calls and command.argv[0] == "node" and interrupt == "post-host":
                raise KeyboardInterrupt
            return refresh.execute(command, capture=capture)

    runtime = IsolatedRuntime()
    try:
        result = refresh.run(
            refresh.make_plan(caller, tmp_path / "state", refresh.Host.PI, None, False), runtime
        )
    except KeyboardInterrupt:
        pytest.fail("Coordinator must preserve interrupted identity outside the host call")
    assert result.outcome is refresh.Outcome.INTERRUPTED
    assert result.qualification is None
    assert runtime.host_calls == 1
    assert tuple(path.read_bytes() for path in protected) == accepted
    assert result.worktree is not None and result.log is not None
    candidate = Path(result.worktree)
    assert (candidate / "openwiki/.run.json").is_file()
    assert (candidate / "openwiki/contracts/example.md").read_bytes() == accepted[
        0
    ] + b"\nInterrupted candidate work.\n"
    journal = json.loads(Path(result.log).read_bytes())
    assert journal["outcome"] in {"interrupted", "pending"}
    assert journal["source"]["revision"]
    assert not (tmp_path / "state/lock").exists()


def test_source_bytes_change_without_head_change_rejects_candidate(tmp_path: Path) -> None:
    plan, wt = native_repository(tmp_path)

    def change_input() -> int:
        _ = (wt / "docs/okf-public.json").write_text('{"changed": true}\n')
        return 0

    result = refresh.run_worktree(plan, wt, tmp_path / "result.json", HostEffect(change_input))
    assert result.outcome == "source_changed"


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
    prompt = repo / "docs/internal/tooling/knowledge-refresh-prompt.md"
    prompt.parent.mkdir(parents=True)
    _ = prompt.write_text("review", encoding="utf-8")
    wt = tmp_path / "work tree"
    package = wt / "docs/internal/tooling/markdown/node_modules/openwiki"
    (package / "dist/cli").mkdir(parents=True)
    (package / "dist/integrations/pi").mkdir(parents=True)

    @dataclass(frozen=True)
    class PackageFixture:
        version: str = "0.9.0"

    _ = (package / "package.json").write_text(
        json.dumps(asdict(PackageFixture())), encoding="utf-8"
    )
    owned_prompt = wt / "docs/internal/tooling/knowledge-refresh-prompt.md"
    _ = owned_prompt.write_text("review", encoding="utf-8")
    (package / "dist/cli/cli.js").touch()
    (package / "dist/integrations/pi/openwiki.js").touch()
    return refresh.make_plan(repo, tmp_path / "state", refresh.Host.PI, None, False), wt


def test_scope_violation_after_failed_agent_is_preserved(tmp_path: Path) -> None:
    plan, wt = native_repository(tmp_path)

    def change_outside_scope() -> int:
        _ = (wt / "source.kt").write_text("val answer = 42\n")
        return 1

    script = HostEffect(change_outside_scope)
    result = refresh.run_worktree(plan, wt, tmp_path / "log", script)
    assert result.stage == refresh.Stage.SCOPE
    assert result.outcome == "outside_scope"
    assert script.calls == 1


def test_agent_failure_and_success(tmp_path: Path) -> None:
    plan, wt = native_repository(tmp_path)
    for exit_code, stage in [(1, refresh.Stage.AGENT), (0, refresh.Stage.COMPLETE)]:
        script = HostEffect(partial(int, exit_code))
        assert refresh.run_worktree(plan, wt, tmp_path / "log", script).stage == stage
        assert script.calls == 1


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
    _ = protected.write_text("user work")
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
