"""Session mining: archive identity, transcript accounting, and task-draft admission."""

from __future__ import annotations

import json
import shutil
import subprocess
from datetime import UTC, datetime
from pathlib import Path

import pytest

import session_tasks as st

T0 = datetime(2026, 11, 21, 5, 0, tzinfo=UTC)
T1 = datetime(2026, 11, 21, 6, 0, tzinfo=UTC)


def record(**fields: object) -> str:
    return json.dumps({"sessionId": "s-1", "timestamp": "2026-11-21T05:00:00Z", **fields})


def bash(call: str, command: str) -> str:
    return record(
        type="assistant",
        message={
            "model": "m-1",
            "usage": {
                "input_tokens": 3,
                "output_tokens": 5,
                "cache_read_input_tokens": 7,
                "cache_creation_input_tokens": 11,
            },
            "content": [
                {"type": "tool_use", "id": call, "name": "Bash", "input": {"command": command}}
            ],
        },
    )


def result(call: str, text: str, *, is_error: bool = False) -> str:
    return record(
        type="user",
        message={
            "content": [
                {"type": "tool_result", "tool_use_id": call, "content": text, "is_error": is_error}
            ]
        },
    )


TRANSCRIPT = [
    record(type="user", message={"content": "Remove the legacy binding."}),
    bash("a", "kast query --name Legacy --relation references"),
    result("a", '{"type":"complete","status":"complete","exhaustive":true}'),
    bash("b", "cd repo && kast query --ref r | jq .status"),
    result("b", '{"type":"rejected_document","reason":"COMPLETION_UNPROVEN"}'),
    bash("c", "kast --version && kast stop --all"),
    result("c", "0.0.1"),
    record(type="user", message={"content": "[Request interrupted by user]"}),
    record(type="user", message={"content": "<command-name>/login</command-name>"}),
    record(type="system", subtype="compact_boundary"),
    record(type="pr-link", prUrl="https://example.invalid/pull/1"),
    '{"type": "assistant", "message": ',
]


def test_transcript_accounting_separates_semantic_operational_and_human_turns() -> None:
    acc = st.accumulate(TRANSCRIPT)
    use = st.kast_use(acc)

    assert (use.semantic, use.operational, use.exposure) == (2, 2, st.KastExposure.SEMANTIC)
    assert use.semantic_outcomes == {st.KastOutcome.COMPLETE: 1, st.KastOutcome.REJECTED: 1}
    assert (acc.human_prompts, acc.interruptions, acc.compactions, acc.malformed) == (1, 1, 1, 1)
    assert st.tokens(acc.tokens) == st.Tokens(input=9, output=15, cache_read=21, cache_write=33)
    assert acc.pull_requests == {"https://example.invalid/pull/1"}


def test_operational_kast_use_is_not_control_eligible_and_absence_is() -> None:
    operational = st.kast_use(st.accumulate([bash("a", "kast upgrade --channel preview")]))
    absent = st.kast_use(st.accumulate([bash("a", "rg -n skast_like ./gradlew")]))

    assert operational.exposure is st.KastExposure.OPERATIONAL_ONLY
    assert absent.exposure is st.KastExposure.NONE


def test_reference_reads_count_as_semantic_exposure() -> None:
    assert st.kast_commands("kast describe query; kast query --help") == [
        st.KastCallKind.REFERENCE,
        st.KastCallKind.SEMANTIC,
    ]
    assert (
        st.kast_use(st.accumulate([bash("a", "kast describe")])).exposure
        is st.KastExposure.SEMANTIC
    )


def test_snapshot_keeps_every_version_and_summary_reads_the_latest(tmp_path: Path) -> None:
    project = tmp_path / "projects" / "-code-kast"
    (project / "s-1" / "subagents").mkdir(parents=True)
    _ = (tmp_path / "projects" / "-code-other").mkdir()
    transcript = project / "s-1.jsonl"
    _ = transcript.write_text("\n".join(TRANSCRIPT[:3]) + "\n")
    _ = (project / "s-1" / "subagents" / "agent-1.jsonl").write_text(bash("z", "ls") + "\n")
    archive = tmp_path / "archive"

    first = st.snapshot(tmp_path / "projects", "kast", archive, T0)
    again = st.snapshot(tmp_path / "projects", "kast", archive, T0)
    _ = transcript.write_text("\n".join(TRANSCRIPT) + "\n")
    grown = st.snapshot(tmp_path / "projects", "kast", archive, T1)

    assert (first.captured, again.captured, again.unchanged, grown.captured) == (2, 0, 2, 1)
    assert len(st.read_manifest(archive)) == 3
    [summary] = st.summarize(archive, resolve_base=lambda instant: f"main-at-{instant.hour}")
    assert summary.kast.semantic == 2
    assert summary.subagents == 1
    assert summary.subagent_tokens.input == 3
    assert summary.inferred_base_commit == "main-at-5"


def test_summary_rejects_a_blob_that_no_longer_matches_its_digest(tmp_path: Path) -> None:
    project = tmp_path / "projects" / "kast"
    project.mkdir(parents=True)
    _ = (project / "s-1.jsonl").write_text(TRANSCRIPT[0] + "\n")
    archive = tmp_path / "archive"
    _ = st.snapshot(tmp_path / "projects", "kast", archive, T0)
    [blob] = (archive / "blobs").iterdir()
    _ = blob.write_text("tampered\n")

    with pytest.raises(SystemExit, match="does not match its digest"):
        _ = st.summarize(archive, resolve_base=None)


# ---------------------------------------------------------------- task admission


def git(repository: Path, *arguments: str) -> str:
    return subprocess.run(
        ("git", "-C", str(repository), *arguments), check=True, capture_output=True, text=True
    ).stdout.strip()


@pytest.fixture
def repository(tmp_path: Path) -> tuple[Path, str, str]:
    if shutil.which("git") is None:
        pytest.skip("git is not installed")
    root = tmp_path / "repository"
    (root / "a" / "src" / "main").mkdir(parents=True)
    (root / "a" / "src" / "test").mkdir(parents=True)
    _ = git(root.parent, "init", "-q", "-b", "main", str(root))
    _ = git(root, "config", "user.email", "fixture@example.invalid")
    _ = git(root, "config", "user.name", "fixture")
    _ = (root / "a/src/main/Decl.kt").write_text("class Legacy\nfun use(x: Legacy) = x\n")
    _ = (root / "a/src/test/UseTest.kt").write_text("val y = Legacy()\n")
    _ = (root / "a/src/main/Other.kt").write_text("val z = 1\n")
    _ = git(root, "add", ".")
    _ = git(root, "commit", "-q", "-m", "base")
    base = git(root, "rev-parse", "HEAD")
    (root / "a/src/main/Decl.kt").unlink()
    (root / "a/src/test/UseTest.kt").unlink()
    _ = git(root, "add", "-A")
    _ = git(root, "commit", "-q", "-m", "remove")
    return root, base, git(root, "rev-parse", "HEAD")


def answer(
    base: str, expected: list[str], prompt: str = "List the files that use Legacy."
) -> st.TaskDraft:
    return st.TaskDraft.model_validate(
        {
            "schema_version": 1,
            "id": "legacy-uses",
            "status": "DRAFT",
            "kind": "answer",
            "base_commit": base,
            "prompt": prompt,
            "kast_relevance": "EXPECTED",
            "source": {
                "session_id": "s-1",
                "project": "p",
                "observed_at": T0.isoformat(),
                "evidence": ["e"],
            },
            "oracle": {
                "type": "exact_paths",
                "expected_paths": expected,
                "derivation": {
                    "pattern": "Legacy",
                    "pathspecs": ["*.kt"],
                    "exclude_paths": ["a/src/main/Decl.kt"],
                },
            },
        }
    )


def edit(base: str, reference: str, allowed: list[str], absent: list[str]) -> st.TaskDraft:
    return st.TaskDraft.model_validate(
        {
            "schema_version": 1,
            "id": "remove-legacy",
            "status": "DRAFT",
            "kind": "edit",
            "base_commit": base,
            "prompt": "Remove Legacy.",
            "kast_relevance": "EXPECTED",
            "allowed_changed_paths": allowed,
            "source": {
                "session_id": "s-1",
                "project": "p",
                "observed_at": T0.isoformat(),
                "evidence": ["e"],
            },
            "oracle": {
                "type": "reference_commit",
                "reference_commit": reference,
                "absent_symbols": absent,
                "absent_pathspecs": ["*.kt"],
                "commands": [{"argv": ["true"]}],
            },
        }
    )


def no_terms(_: str) -> list[str]:
    return []


def test_answer_admits_only_the_derived_path_set(repository: tuple[Path, str, str]) -> None:
    root, base, _ = repository
    git_repo = st.Git(root)

    assert st.check_task(answer(base, ["a/src/test/UseTest.kt"]), git_repo, no_terms) == []
    [finding] = st.check_task(
        answer(base, ["a/src/test/UseTest.kt", "a/src/main/Other.kt"]), git_repo, no_terms
    )
    assert finding.code == "ANSWER_MISMATCH"


def test_edit_rejects_a_reference_outside_scope_and_a_surviving_symbol(
    repository: tuple[Path, str, str],
) -> None:
    root, base, reference = repository
    git_repo = st.Git(root)
    both = ["a/src/main/Decl.kt", "a/src/test/UseTest.kt"]

    assert st.check_task(edit(base, reference, both, ["Legacy"]), git_repo, no_terms) == []
    codes = {
        f.code for f in st.check_task(edit(base, reference, both[:1], ["z"]), git_repo, no_terms)
    }
    assert codes == {"REFERENCE_OUT_OF_SCOPE", "SYMBOL_PRESENT_AT_REFERENCE"}
    reversed_order = st.check_task(edit(reference, base, both, []), git_repo, no_terms)
    # The reference deleted both files, so the reversed base also lacks the allowed paths.
    assert [f.code for f in reversed_order] == [
        "PATH_MISSING",
        "PATH_MISSING",
        "REFERENCE_NOT_DESCENDANT",
    ]


def test_blinding_uses_the_participant_surface_vocabulary(
    repository: tuple[Path, str, str],
) -> None:
    if shutil.which("node") is None:
        pytest.skip("node is not installed")
    root, base, _ = repository
    leaked = answer(
        base, ["a/src/test/UseTest.kt"], prompt="For this eval, list the files that use Legacy."
    )

    [finding] = st.check_task(leaked, st.Git(root), st.node_measurement_terms)
    assert (finding.code, finding.detail) == ("BLINDING", "prompt names the measurement: eval")


def test_checked_in_drafts_parse_and_match_their_file_names() -> None:
    drafts = sorted((Path(st.__file__).parent / "session-tasks").glob("*.json"))
    assert drafts
    for path in drafts:
        assert isinstance(st.load_task(path), st.TaskDraft), path.name
