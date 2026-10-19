"""Independent oracles for admission, strict answers, and holdout isolation."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

import agent_efficiency
from agent_efficiency import (
    REVISION,
    Configuration,
    Environment,
    Oracle,
    Replacement,
    Task,
    adjudicate,
    corpus_digest,
    digest,
    examine_prepared_inputs,
    examine_sandbox,
    onboarding,
    run_trial,
    sandbox_policy,
    scoped_path,
    split_tasks,
)


def file_brief(name: str, content: bytes) -> Configuration:
    return Configuration.model_validate(
        {
            "name": "file-brief",
            "exposure": "codemode",
            "onboarding": {"kind": "file", "name": name, "sha256": digest(content)},
            "hypothesis": "Bind the experiment to the exact brief bytes.",
        }
    )


def test_versioned_brief_uses_the_committed_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    folder = tmp_path / "agent-efficiency-configs"
    folder.mkdir()
    content = b"Use the known query shape.\n"
    _ = (folder / "candidate.md").write_bytes(content)
    monkeypatch.setattr(agent_efficiency, "HERE", tmp_path)
    config = file_brief("candidate.md", content)
    rendered = onboarding(tmp_path / "producer", config, tmp_path / "subject")
    assert rendered.startswith(content.decode())
    assert f'Workspace: "{tmp_path / "subject"}"' in rendered
    assert "query-schema.json" in rendered


def test_changed_brief_cannot_start_a_different_experiment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    folder = tmp_path / "agent-efficiency-configs"
    folder.mkdir()
    config = file_brief("candidate.md", b"Use the known query shape.\n")
    _ = (folder / "candidate.md").write_text("Use a different instruction.\n")
    monkeypatch.setattr(agent_efficiency, "HERE", tmp_path)
    with pytest.raises(ValueError, match="BRIEF_DIGEST_MISMATCH"):
        _ = onboarding(tmp_path, config)


@pytest.mark.parametrize("name", ["../secret.md", "/secret.md", "nested/a.md", "a.txt", ""])
def test_brief_filename_stays_in_the_experiment_catalog(name: str) -> None:
    with pytest.raises(ValueError):
        _ = file_brief(name, b"Use the known query shape.\n")


def test_a_brief_link_cannot_substitute_another_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    folder = tmp_path / "agent-efficiency-configs"
    folder.mkdir()
    content = b"Use the known query shape.\n"
    secret = tmp_path / "private.md"
    _ = secret.write_bytes(content)
    (folder / "candidate.md").symlink_to(secret)
    monkeypatch.setattr(agent_efficiency, "HERE", tmp_path)
    with pytest.raises(ValueError, match="BRIEF_SOURCE_UNAVAILABLE"):
        _ = onboarding(tmp_path, file_brief("candidate.md", content))


@pytest.mark.parametrize(
    ("content", "reason"),
    [(None, "BRIEF_SOURCE_UNAVAILABLE"), (b"\xff", "BRIEF_INVALID_UTF8")],
)
def test_unreadable_brief_input_has_a_finite_rejection(
    content: bytes | None,
    reason: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    folder = tmp_path / "agent-efficiency-configs"
    folder.mkdir()
    if content is not None:
        _ = (folder / "candidate.md").write_bytes(content)
    config = file_brief("candidate.md", content if content is not None else b"expected")
    monkeypatch.setattr(agent_efficiency, "HERE", tmp_path)
    with pytest.raises(ValueError, match=reason):
        _ = onboarding(tmp_path, config)


def oracle(answer: object) -> Oracle:
    return Oracle.model_validate(
        {"answer": answer, "sources": ["Example.kt"], "qualification": "SOURCE_ANNOTATION"}
    )


def test_answer_needs_exact_json_without_unreviewed_claims() -> None:
    expected = oracle(["sample.Order", "sample.Invoice"])
    assert adjudicate('{"answer":["sample.Order","sample.Invoice"]}', expected).correct
    assert not adjudicate('{"answer":["sample.Order"]}', expected).correct
    assert not adjudicate(
        '{"answer":["sample.Order","sample.Invoice"],"exhaustive":true}', expected
    ).correct
    assert not adjudicate('```json\n{"answer":[]}\n```', expected).correct
    assert not adjudicate('{"answer":true}', oracle(1)).correct
    assert not adjudicate('{"answer":1}', oracle(True)).correct


def test_unqualified_oracle_cannot_grant_correctness() -> None:
    expected = Oracle(answer=[], sources=(), qualification="UNQUALIFIED_NATIVE_STATE")
    assert adjudicate('{"answer":[]}', expected).reason == "UNQUALIFIED_ORACLE"


def task(index: int, family: str) -> Task:
    return Task.model_validate(
        {
            "id": f"case-{index}",
            "family": family,
            "scope": "module",
            "difficulty": "medium",
            "prompt": "Find Order.",
            "source_paths": [],
            "oracle": oracle(["sample.Order"]).model_dump(),
            "setup": [],
            "allowed_changes": [],
        }
    )


def test_split_is_reproducible_disjoint_and_preserves_each_family() -> None:
    families = (
        "discovery",
        "resolution",
        "relationships",
        "traversal",
        "impact",
        "diagnostics",
        "source",
        "changes",
        "verification",
        "negative",
        "freshness",
    )
    corpus = tuple(task(index, families[index % 11]) for index in range(132))
    dev, holdout = split_tasks(corpus, 42)
    assert len(dev) == 99
    assert len(holdout) == 33
    assert set(item.id for item in dev).isdisjoint(item.id for item in holdout)
    assert {item.family for item in dev} == set(families)
    assert {item.family for item in holdout} == set(families)
    assert (dev, holdout) == split_tasks(corpus, 42)
    with pytest.raises(ValueError, match="120_UNIQUE"):
        _ = split_tasks(corpus[:119], 42)
    with pytest.raises(ValueError, match="120_UNIQUE"):
        _ = split_tasks((*corpus, corpus[0]), 42)


def test_path_rejects_traversal_absolute_and_symlink_escape(tmp_path: Path) -> None:
    project = tmp_path / "project"
    project.mkdir()
    (project / "escape").symlink_to(tmp_path, target_is_directory=True)
    assert scoped_path(project, "Order.kt") == project / "Order.kt"
    for relative in ("../oracle.json", str(tmp_path / "oracle.json"), "escape/oracle.json"):
        with pytest.raises(ValueError, match="PATH_OUTSIDE"):
            _ = scoped_path(project, relative)


@pytest.mark.skipif(sys.platform != "darwin", reason="Native holdout isolation needs macOS")
def test_real_os_denies_holdout_read(tmp_path: Path) -> None:
    vault = tmp_path / "holdout vault"
    vault.mkdir()
    secret = vault / "oracle.json"
    _ = secret.write_text('"reserved"')
    examine_sandbox(sandbox_policy((vault,)), secret)
    result = subprocess.run(
        [
            "/usr/bin/sandbox-exec",
            "-p",
            sandbox_policy((vault,)),
            sys.executable,
            "-c",
            "from pathlib import Path; Path(__import__('sys').argv[1]).write_text('tampered')",
            str(secret),
        ],
        capture_output=True,
        check=False,
    )
    assert result.returncode != 0
    assert secret.read_text() == '"reserved"'


@pytest.mark.skipif(sys.platform != "darwin", reason="Native holdout isolation needs macOS")
def test_a_missing_file_does_not_prove_denial(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="ISOLATION_UNPROVEN"):
        examine_sandbox("(version 1)(allow default)", tmp_path / "absent")


def test_onboarding_failure_restores_the_setup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import agent_efficiency

    project = tmp_path / "project"
    _ = subprocess.run(["git", "init", "--quiet", str(project)], check=True)
    source = project / "Order.kt"
    _ = source.write_text("class Order")
    case = task(1, "changes").model_copy(
        update={
            "setup": (Replacement(path="Order.kt", before="class Order", after="broken"),),
            "allowed_changes": ("Order.kt",),
        }
    )

    def unavailable(_root: Path, _config: Configuration, _workspace: Path | None = None) -> str:
        raise OSError("resource unavailable")

    monkeypatch.setattr(agent_efficiency, "onboarding", unavailable)
    env = Environment(
        workspace=str(project),
        pi_package="/pi",
        pi_version="1.3.0",
        auth="/auth",
        mcp_command="/mcp",
    )
    with pytest.raises(OSError, match="resource unavailable"):
        _ = run_trial(
            case,
            "treatment",
            0,
            Configuration(
                name="case", exposure="codemode", onboarding="canonical", hypothesis="case"
            ),
            env,
            tmp_path,
            "(version 1)(allow default)",
        )
    assert source.read_text() == "class Order"


@pytest.mark.parametrize("oversized", [False, True])
def test_failed_session_keeps_source_evidence_before_restore(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, oversized: bool
) -> None:
    project = tmp_path / "project"
    project.mkdir()
    _ = subprocess.run(["git", "init", "--quiet"], cwd=project, check=True)
    source = project / "Order.kt"
    original = b"class Order"
    _ = source.write_bytes(original)
    changed = b"x" * 4194305 if oversized else b"partial task change"
    case = task(1, "changes").model_copy(update={"allowed_changes": ("Order.kt",)})

    class SessionLauncher:
        calls: int = 0

        def run(self, argv: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
            self.calls += 1
            assert argv[:2] == ["/usr/bin/sandbox-exec", "-p"]
            _ = source.write_bytes(changed)
            return subprocess.CompletedProcess(argv, 1, "", "Controlled worker failure")

    launcher = SessionLauncher()
    monkeypatch.setattr(agent_efficiency, "subprocess", launcher)
    env = Environment(
        workspace=str(project),
        pi_package="/pi",
        pi_version="1.3.0",
        auth="/auth",
        mcp_command="/mcp",
    )
    trial = run_trial(
        case,
        "control",
        0,
        Configuration(name="case", exposure="codemode", onboarding="none", hypothesis="case"),
        env,
        tmp_path,
        "(version 1)(allow default)",
    )
    owned = tmp_path / "case-1-0-control"
    assert launcher.calls == 1
    assert not trial.correct
    assert json.loads((owned / "verdict.json").read_text())["reason"] == "RUN_FAILED"
    assert source.read_bytes() == original
    outcome = json.loads((owned / "source-overlay.json").read_text())["outcome"]
    if oversized:
        assert outcome == {"kind": "REJECTED", "reason": "BYTE_LIMIT"}
        assert not (owned / "source-artifacts").exists()
    else:
        assert outcome["kind"] == "PRESERVED"
        record = outcome["entries"][0]
        assert record["relative_path"] == "Order.kt"
        assert record["sha256"] == digest(changed)
        assert (owned / record["artifact_path"]).read_bytes() == changed


def test_changed_prepared_inputs_stop_before_sessions(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from pydantic import TypeAdapter

    import agent_efficiency

    vault = tmp_path / "vault"
    vault.mkdir()
    case = task(1, "source").model_copy(update={"source_paths": ("Order.kt",)})
    source = tmp_path / "Order.kt"
    _ = source.write_text("class Order")
    corpus = TypeAdapter(tuple[Task, ...]).dump_json((case,))
    _ = (vault / "corpus.json").write_bytes(corpus)
    sources = vault / "sources.json"
    _ = sources.write_text(json.dumps({"Order.kt": digest(source.read_bytes())}))
    manifest = {
        "revision": REVISION,
        "corpusSha256": corpus_digest((case,)),
        "sourcesSha256": digest(sources.read_bytes()),
    }

    def pinned_revision(_root: Path, *_args: str) -> str:
        return REVISION

    monkeypatch.setattr(agent_efficiency, "git", pinned_revision)
    examine_prepared_inputs(tmp_path, tmp_path, (case,), manifest)
    _ = source.write_text("class Changed")
    with pytest.raises(ValueError, match="SOURCE_CONTENT_MISMATCH"):
        examine_prepared_inputs(tmp_path, tmp_path, (case,), manifest)
    _ = source.write_text("class Order")
    changed = case.model_copy(update={"prompt": "Different input"})
    with pytest.raises(ValueError, match="TASK_COMMITMENT_MISMATCH"):
        examine_prepared_inputs(tmp_path, tmp_path, (changed,), manifest)

    def wrong_revision(_root: Path, *_args: str) -> str:
        return "wrong revision"

    monkeypatch.setattr(agent_efficiency, "git", wrong_revision)
    with pytest.raises(ValueError, match="SOURCE_REVISION_MISMATCH"):
        examine_prepared_inputs(tmp_path, tmp_path, (case,), manifest)


def test_dictionary_order_does_not_change_corpus_identity() -> None:
    case = task(1, "source")
    left = case.model_copy(update={"oracle": oracle({"b": 2, "a": 1})})
    right = case.model_copy(update={"oracle": oracle({"a": 1, "b": 2})})
    assert corpus_digest((left,)) == corpus_digest((right,))
