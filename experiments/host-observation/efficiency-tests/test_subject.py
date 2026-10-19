"""Independent subject identity, complete input, and behavioral repair oracles."""

import hashlib
import json
import stat
import subprocess
import sys
from pathlib import Path

import pytest
from pydantic import TypeAdapter, ValidationError

from agent_efficiency import (
    REVISION,
    CommandEvidence,
    Configuration,
    Environment,
    ExactRestoration,
    Oracle,
    Replacement,
    ScopedRepair,
    SubjectCommitment,
    Task,
    Verdict,
    corpus_digest,
    digest,
    examine_postcondition,
    examine_prepared_inputs,
    onboarding,
    run_trial,
)
from agent_efficiency_integrity import WorkspaceSnapshot
from agent_efficiency_subject import (
    SUBJECT,
    AdmissionCode,
    GeneratedSubject,
    GitSubject,
    InputFile,
    SubjectAdmissionError,
    admit_subject,
    subject_digest,
)


def fixture(root: Path) -> GeneratedSubject:
    root.mkdir()
    (root / "src").mkdir()
    (root / "src/Order.kt").write_text("class Order\n")
    (root / "build.gradle.kts").write_text('plugins { kotlin("jvm") }\n')
    for path in (root / "src/Order.kt", root / "build.gradle.kts"):
        path.chmod(0o644)
    return GeneratedSubject(
        identity="orders-v1",
        workspace=str(root),
        inputs=(
            InputFile(
                path="build.gradle.kts",
                sha256=hashlib.sha256(b'plugins { kotlin("jvm") }\n').hexdigest(),
                mode=0o644,
            ),
            InputFile(
                path="src/Order.kt",
                sha256=hashlib.sha256(b"class Order\n").hexdigest(),
                mode=0o644,
            ),
        ),
    )


def test_generated_subject_admits_complete_manifest_and_typed_wire(tmp_path: Path) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    admit_subject(root, subject)
    raw = SUBJECT.dump_json(subject)
    assert json.loads(raw) == {
        "kind": "generated",
        "identity": "orders-v1",
        "workspace": str(root),
        "inputs": [
            {"path": item.path, "sha256": item.sha256, "mode": 420} for item in subject.inputs
        ],
    }
    assert SUBJECT.validate_json(raw) == subject
    assert stat.S_IMODE((root / "src/Order.kt").stat().st_mode) == 0o644


@pytest.mark.parametrize("path", ["src/Order.kt", "build.gradle.kts"])
@pytest.mark.parametrize("mutation", ["alter", "missing", "mode"])
def test_generated_rejects_source_and_build_mutations(
    tmp_path: Path, path: str, mutation: str
) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    changed = root / path
    if mutation == "alter":
        changed.write_text("substituted")
    elif mutation == "missing":
        changed.unlink()
    else:
        changed.chmod(0o755)
    with pytest.raises(SubjectAdmissionError) as rejected:
        admit_subject(root, subject)
    assert rejected.value.code == (
        AdmissionCode.INPUT_MISSING if mutation == "missing" else AdmissionCode.INPUT_ALTERED
    )


@pytest.mark.parametrize("path", ["src/Added.kt", "settings.gradle.kts", ".hidden-input"])
def test_generated_rejects_any_added_input(tmp_path: Path, path: str) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    (root / path).write_text("unadmitted")
    with pytest.raises(SubjectAdmissionError) as rejected:
        admit_subject(root, subject)
    assert rejected.value.code == AdmissionCode.INPUT_ADDED


@pytest.mark.parametrize("directory", [False, True])
def test_generated_rejects_symlink_escape(tmp_path: Path, directory: bool) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    outside = tmp_path / "outside"
    if directory:
        outside.mkdir()
        (root / "escape").symlink_to(outside, target_is_directory=True)
    else:
        outside.write_text("class Order\n")
        (root / "src/Order.kt").unlink()
        (root / "src/Order.kt").symlink_to(outside)
    with pytest.raises(SubjectAdmissionError) as rejected:
        admit_subject(root, subject)
    assert rejected.value.code == AdmissionCode.SYMLINK


@pytest.mark.parametrize("relative", ["../Other.kt", "/Other.kt", "src/../Order.kt", "./Order.kt"])
def test_manifest_rejects_noncanonical_paths(relative: str) -> None:
    with pytest.raises(ValidationError):
        InputFile(path=relative, sha256="a" * 64, mode=0o644)


def test_generated_rejects_substituted_workspace(tmp_path: Path) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    other = tmp_path / "other"
    _ = fixture(other)
    with pytest.raises(SubjectAdmissionError) as rejected:
        admit_subject(other, subject)
    assert rejected.value.code == AdmissionCode.IDENTITY_MISMATCH


def case() -> Task:
    return Task(
        id="repair",
        family="changes",
        scope="file",
        difficulty="easy",
        prompt="Repair Order behavior.",
        source_paths=("Order.kt",),
        oracle=Oracle(answer=True, sources=("Order.kt",), qualification="CURATED_BEHAVIOR"),
        setup=(Replacement(path="Order.kt", before="original", after="broken"),),
        allowed_changes=("Order.kt",),
    )


def test_subject_commitment_rejects_substituted_identity_before_inference(tmp_path: Path) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    benchmark = tmp_path / "benchmark"
    vault = benchmark / "vault"
    vault.mkdir(parents=True)
    task = case().model_copy(update={"source_paths": ("src/Order.kt",)})
    (vault / "corpus.json").write_bytes(TypeAdapter(tuple[Task, ...]).dump_json((task,)))
    sources = vault / "sources.json"
    sources.write_text(json.dumps({"src/Order.kt": digest(b"class Order\n")}))
    commitment = SubjectCommitment(
        subject=subject, sha256=subject_digest(subject), producer_workspace=str(tmp_path)
    )
    manifest = TypeAdapter(dict[str, object]).validate_python(
        {
            "revision": REVISION,
            "corpusSha256": corpus_digest((task,)),
            "sourcesSha256": digest(sources.read_bytes()),
            "subjectAdmission": commitment.model_dump(mode="json"),
        }
    )
    # Decode the legacy dynamic envelope just as the coordinator does.
    from pydantic import JsonValue

    admitted_manifest = TypeAdapter(dict[str, JsonValue]).validate_json(json.dumps(manifest))
    examine_prepared_inputs(benchmark, root, (task,), admitted_manifest, subject, tmp_path)
    substituted = subject.model_copy(update={"identity": "orders-v2"})
    with pytest.raises(SubjectAdmissionError) as rejected:
        examine_prepared_inputs(benchmark, root, (task,), admitted_manifest, substituted, tmp_path)
    assert rejected.value.code == AdmissionCode.IDENTITY_MISMATCH
    (root / "settings.gradle.kts").write_text("added build input")
    with pytest.raises(SubjectAdmissionError) as rejected:
        examine_prepared_inputs(benchmark, root, (task,), admitted_manifest, subject, tmp_path)
    assert rejected.value.code == AdmissionCode.INPUT_ADDED


def test_generated_mutation_stops_before_snapshot_or_participant(tmp_path: Path) -> None:
    root = tmp_path / "subject"
    subject = fixture(root)
    (root / "src/Order.kt").write_text("changed")
    env = Environment(
        workspace=str(root),
        subject=subject,
        pi_package="/absent",
        pi_version="1",
        auth="/absent",
        mcp_command="/absent",
    )
    with pytest.raises(SubjectAdmissionError) as rejected:
        run_trial(case(), "treatment", 0, configuration(), env, tmp_path, "unused")
    assert rejected.value.code == AdmissionCode.INPUT_ALTERED
    assert not (tmp_path / "repair-0-treatment").exists()


def configuration() -> Configuration:
    return Configuration(name="test", exposure="direct", onboarding="canonical", hypothesis="test")


def test_onboarding_uses_producer_references_and_actual_subject(tmp_path: Path) -> None:
    producer = Path(__file__).resolve().parents[3]
    subject = tmp_path / "subject"
    _ = fixture(subject)
    text = onboarding(producer, configuration(), subject)
    assert text.startswith((producer / "agent-tools/references/onboarding.md").read_text())
    assert f'Workspace: "{subject}"' in text
    assert str(producer / "agent-tools/references/query-schema.json") in text
    assert str(producer / "agent-tools/references/query-examples.json") in text
    assert not (subject / "agent-tools").exists()


def test_pinned_git_subject_is_independent_of_producer_revision(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import agent_efficiency_subject

    root = tmp_path / "git-subject"
    revision = "a" * 40
    calls: list[tuple[str, ...]] = []

    def observed_git(
        argv: list[str], *, cwd: Path, capture_output: bool, text: bool, check: bool
    ) -> subprocess.CompletedProcess[str]:
        assert cwd == root and capture_output and text and check
        assert argv == ["git", "rev-parse", "HEAD"]
        calls.append(tuple(argv))
        return subprocess.CompletedProcess(argv, 0, stdout=revision + "\n", stderr="")

    monkeypatch.setattr(agent_efficiency_subject.subprocess, "run", observed_git)
    admit_subject(root, GitSubject(revision=revision))
    with pytest.raises(SubjectAdmissionError) as rejected:
        admit_subject(root, GitSubject())
    assert rejected.value.code == AdmissionCode.REVISION_MISMATCH
    assert len(calls) == 2


def repair(
    command: str = "assert Path('Order.kt').read_text() == 'behaviorally repaired'",
) -> ScopedRepair:
    return ScopedRepair(
        files=("Order.kt",),
        commands=(
            CommandEvidence(
                argv=(sys.executable, "-c", "from pathlib import Path; " + command),
            ),
        ),
    )


def workspace(root: Path) -> WorkspaceSnapshot:
    subprocess.run(["git", "init", "--quiet", str(root)], check=True)
    (root / "Order.kt").write_text("original")
    (root / "Protected.kt").write_text("protected")
    return WorkspaceSnapshot.capture(root)


def test_scoped_repair_accepts_nonidentical_behavior_with_independent_command(
    tmp_path: Path,
) -> None:
    root = tmp_path / "workspace"
    snapshot = workspace(root)
    task = case().model_copy(update={"postcondition": repair()})
    (root / "Order.kt").write_text("behaviorally repaired")
    assert examine_postcondition(
        task, root, snapshot, Verdict(correct=True, reason="CORRECT")
    ).correct
    snapshot.restore()
    assert (root / "Order.kt").read_text() == "original"


def test_failed_session_keeps_its_cause_when_the_unrepaired_oracle_also_fails(
    tmp_path: Path,
) -> None:
    root = tmp_path / "workspace"
    snapshot = workspace(root)
    task = case().model_copy(update={"postcondition": repair("assert False")})
    result = examine_postcondition(
        task, root, snapshot, Verdict(correct=False, reason="RUN_FAILED")
    )
    assert result.reason == "RUN_FAILED"


def test_default_exact_restoration_does_not_accept_behavioral_success(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    snapshot = workspace(root)
    task = case()
    assert isinstance(task.postcondition, ExactRestoration)
    (root / "Order.kt").write_text("original plus unrequested suffix")
    assert (
        examine_postcondition(task, root, snapshot, Verdict(correct=True, reason="CORRECT")).reason
        == "FILE_MISMATCH"
    )
    (root / "Order.kt").write_text("original")
    assert examine_postcondition(
        task, root, snapshot, Verdict(correct=True, reason="CORRECT")
    ).correct
    # Independently reconstruct the pre-postcondition canonical commitment.
    old = task.model_dump(mode="json", exclude={"postcondition"})
    expected = hashlib.sha256(
        json.dumps([old], sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    assert corpus_digest((task,)) == expected


@pytest.mark.parametrize("effect", ["protected", "added", "command-write", "command-fail"])
def test_scoped_repair_nearest_rejections(tmp_path: Path, effect: str) -> None:
    root = tmp_path / "workspace"
    snapshot = workspace(root)
    postcondition = repair()
    (root / "Order.kt").write_text("behaviorally repaired")
    if effect == "protected":
        (root / "Protected.kt").write_text("tampered")
    elif effect == "added":
        (root / "Added.kt").write_text("tampered")
    elif effect == "command-write":
        postcondition = repair("Path('Protected.kt').write_text('tampered')")
    else:
        postcondition = repair("assert False")
    task = case().model_copy(update={"postcondition": postcondition})
    result = examine_postcondition(task, root, snapshot, Verdict(correct=True, reason="CORRECT"))
    assert result.reason == ("COMMAND_FAILED" if effect == "command-fail" else "FORBIDDEN_CHANGE")
    snapshot.restore()
    assert (root / "Protected.kt").read_text() == "protected"


def test_scoped_repair_requires_named_scope_and_command_evidence() -> None:
    with pytest.raises(ValidationError):
        ScopedRepair(files=("Order.kt",), commands=())
    with pytest.raises(ValidationError, match="REPAIR_SCOPE_MISMATCH"):
        Task.model_validate(
            {
                **case().model_dump(),
                "postcondition": repair().model_copy(update={"files": ("Protected.kt",)}),
            }
        )
    for raw in (
        '{"kind":"unknown"}',
        '{"kind":"generated","identity":"x"}',
        '{"kind":"git","skip_hash":true}',
    ):
        with pytest.raises(ValidationError):
            SUBJECT.validate_json(raw)
