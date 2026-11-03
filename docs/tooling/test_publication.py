"""Preserve authored review bindings and reject replayed publication evidence."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from collections.abc import Iterator
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import pytest

import okf_public
import reader_contracts
from publication import (
    Failure,
    InputDigest,
    ReceiptKind,
    Snapshot,
    State,
    assess_associations,
    assess_receipt,
    parse_associations,
    parse_receipt,
    snapshot,
)
from test_okf_public import fixture as projection_fixture


def sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


@dataclass(frozen=True)
class ClaimReference:
    page: str = "openwiki/contracts/source.md"
    claimId: str = "source-value"
    statementSha256: str = sha("The source supplies the value.")


@dataclass(frozen=True)
class BindingFixture:
    route: str
    content: tuple[InputDigest, ...]
    claims: tuple[ClaimReference, ...]
    generatedOwners: tuple[InputDigest, ...]
    reviewedSourceRevision: str


@dataclass(frozen=True)
class AuthoredFixture:
    route: str = "start"
    purpose: str = "Complete the first query."


@dataclass(frozen=True)
class ManifestFixture:
    associations: tuple[BindingFixture, ...]
    authored: tuple[AuthoredFixture, ...] = (AuthoredFixture(),)
    views: tuple[okf_public.View, ...] = ()


@dataclass(frozen=True)
class NativeClaimFixture:
    statement: str = "The source supplies the value."
    page: str = "/openwiki/contracts/source.md"
    id: str = "source-value"
    status: str = "current"


@dataclass(frozen=True)
class NativeFixture:
    claims: tuple[NativeClaimFixture, ...] = (NativeClaimFixture(),)
    status: str = "completed"


@dataclass(frozen=True)
class CheckFixture:
    name: str
    status: str
    evidence: tuple[InputDigest, ...]
    reason: str = "The focused assertion ran."


@dataclass(frozen=True)
class ReceiptFixture:
    kind: str
    status: str
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    checks: tuple[CheckFixture, ...]
    limits: tuple[str, ...] = ("The fixture does not establish live-site behavior.",)
    schemaVersion: int = 1


@dataclass(frozen=True)
class ProseDispositionsFixture:
    schemaVersion: int = 1
    dispositions: tuple[str, ...] = ()


def wire(value: ManifestFixture | NativeFixture | ReceiptFixture) -> object:
    result: object = json.loads(json.dumps(asdict(value)))
    return result


@pytest.fixture
def root(tmp_path: Path) -> Iterator[Path]:
    (tmp_path / "docs/public").mkdir(parents=True)
    (tmp_path / "docs/tooling").mkdir()
    (tmp_path / "docs/reviews").mkdir()
    _ = (tmp_path / "docs/public/start.mdx").write_text("# Start\n\nAsk one question.\n")
    _ = (tmp_path / "docs/tooling/owner.py").write_text("VALUE = 42\n")
    _ = (tmp_path / "docs/reviews/proof.txt").write_text("passed\n")
    git = shutil.which("git")
    assert git is not None
    _ = subprocess.run([git, "init", "-q", str(tmp_path)], check=True)
    _ = subprocess.run([git, "add", "."], cwd=tmp_path, check=True)
    _ = subprocess.run(
        [
            git,
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "fixture",
        ],
        cwd=tmp_path,
        check=True,
    )
    yield tmp_path


def current(root: Path) -> Snapshot:
    result = snapshot(root)
    assert not isinstance(result, Failure)
    return result


def binding(root: Path) -> BindingFixture:
    return BindingFixture(
        "start",
        (InputDigest("docs/public/start.mdx", sha((root / "docs/public/start.mdx").read_text())),),
        (ClaimReference(),),
        (InputDigest("docs/tooling/owner.py", sha("VALUE = 42\n")),),
        current(root).sourceRevision,
    )


def test_accepted_binding_preserves_authored_content_claim_statement_and_owner(root: Path) -> None:
    obligations = assess_associations(
        root, wire(ManifestFixture((binding(root),))), wire(NativeFixture())
    )
    assert [(item.name, item.status) for item in obligations] == [
        ("association:start", State.PASSED)
    ]


def test_authored_edit_invalidates_accepted_association(root: Path) -> None:
    manifest = wire(ManifestFixture((binding(root),)))
    _ = (root / "docs/public/start.mdx").write_text("# Start\n\nA changed instruction.\n")
    obligations = assess_associations(root, manifest, wire(NativeFixture()))
    assert [item.status for item in obligations] == [State.MISMATCHED]
    assert "docs/public/start.mdx" in obligations[0].detail


def test_claim_statement_edit_invalidates_stable_claim_id(root: Path) -> None:
    obligations = assess_associations(
        root,
        wire(ManifestFixture((binding(root),))),
        wire(
            NativeFixture((NativeClaimFixture(statement="The source now supplies another value."),))
        ),
    )
    assert [item.status for item in obligations] == [State.MISMATCHED]
    assert "source-value" in obligations[0].detail


def test_generated_owner_edit_invalidates_accepted_association(root: Path) -> None:
    manifest = wire(ManifestFixture((binding(root),)))
    _ = (root / "docs/tooling/owner.py").write_text("VALUE = 43\n")
    obligations = assess_associations(root, manifest, wire(NativeFixture()))
    assert [item.status for item in obligations] == [State.MISMATCHED]
    assert "docs/tooling/owner.py" in obligations[0].detail


def test_review_revision_must_identify_an_existing_commit(root: Path) -> None:
    association = replace(binding(root), reviewedSourceRevision="0" * 40)
    obligations = assess_associations(
        root, wire(ManifestFixture((association,))), wire(NativeFixture())
    )
    assert [item.status for item in obligations] == [State.UNRESOLVED]


@pytest.mark.parametrize("status", ["stale", "unresolved"])
def test_native_outcomes_keep_their_distinct_rejection(root: Path, status: str) -> None:
    obligations = assess_associations(
        root,
        wire(ManifestFixture((binding(root),))),
        wire(NativeFixture((NativeClaimFixture(status=status),))),
    )
    assert [item.status for item in obligations] == [State(status)]


def test_unknown_claim_does_not_inherit_a_current_association(root: Path) -> None:
    obligations = assess_associations(
        root, wire(ManifestFixture((binding(root),))), wire(NativeFixture(()))
    )
    assert [item.status for item in obligations] == [State.UNRESOLVED]


def test_empty_associations_remain_not_assessed(root: Path) -> None:
    obligations = assess_associations(root, wire(ManifestFixture(())), wire(NativeFixture()))
    assert [item.status for item in obligations] == [State.NOT_ASSESSED]


def test_imported_content_must_join_the_review_binding(root: Path) -> None:
    (root / "docs/public/snippets").mkdir()
    _ = (root / "docs/public/snippets/shared.mdx").write_text("An imported material assertion.\n")
    _ = (root / "docs/public/start.mdx").write_text(
        'import Shared from "/snippets/shared.mdx"\n\n<Shared />\n'
    )
    # The route digest alone does not bind the imported assertion.
    obligations = assess_associations(
        root, wire(ManifestFixture((binding(root),))), wire(NativeFixture())
    )
    assert [item.status for item in obligations] == [State.MISMATCHED]


def test_projected_view_needs_its_authored_introduction_binding(root: Path) -> None:
    view = okf_public.View(
        "reference/example", 'title: "Example"', (), (), (), (), "An authored qualification."
    )
    obligations = assess_associations(
        root, wire(ManifestFixture((binding(root),), views=(view,))), wire(NativeFixture())
    )
    assert [item.status for item in obligations] == [State.NOT_ASSESSED]


def test_changed_introduction_and_real_regeneration_invalidate_view_binding(root: Path) -> None:
    manifest, tracked = projection_fixture(root)
    manifest = replace(
        manifest,
        views=tuple(
            replace(view, introduction="An accepted reader qualification.")
            for view in manifest.views
        ),
    )

    def regenerate(value: okf_public.Manifest) -> None:
        projected = okf_public.project(root, value, tracked)
        assert isinstance(projected, okf_public.Projection)
        for artifact in projected.artifacts:
            destination = root / artifact.path
            destination.parent.mkdir(parents=True, exist_ok=True)
            _ = destination.write_text(artifact.text)

    regenerate(manifest)
    content = reader_contracts.content_inputs(root, "/reference/example")
    assert not isinstance(content, reader_contracts.Failure)
    view_binding = BindingFixture(
        "reference/example",
        tuple(InputDigest(item.path, item.sha256) for item in content),
        (ClaimReference(),),
        (),
        current(root).sourceRevision,
    )
    accepted = wire(ManifestFixture((binding(root), view_binding), views=manifest.views))
    assert all(
        item.status == State.PASSED
        for item in assess_associations(root, accepted, wire(NativeFixture()))
    )
    regenerate(
        replace(
            manifest,
            views=tuple(
                replace(view, introduction="A changed reader qualification.")
                for view in manifest.views
            ),
        )
    )
    obligations = assess_associations(root, accepted, wire(NativeFixture()))
    assert [item.status for item in obligations] == [State.PASSED, State.MISMATCHED]


def test_completed_rendered_receipt_keeps_each_gate_and_limit(root: Path) -> None:
    state = current(root)
    names = (
        "representative-pages",
        "keyboard",
        "focus",
        "mobile",
        "zoom-200",
        "themes",
        "screenshots",
        "bounded-measurements",
        "candidate-preview-provenance",
    )
    evidence = (InputDigest("docs/reviews/proof.txt", sha("passed\n")),)
    receipt = wire(
        ReceiptFixture(
            "KAST_DOCUMENTATION_RENDERED",
            "completed",
            state.sourceRevision,
            state.inputs,
            tuple(CheckFixture(name, "passed", evidence) for name in names),
        )
    )
    obligations, limits = assess_receipt(root, receipt, state, ReceiptKind.RENDERED)
    assert len(obligations) == 9
    assert all(item.status == State.PASSED for item in obligations)
    assert limits == ("The fixture does not establish live-site behavior.",)


def test_old_receipt_rejects_after_uncommitted_candidate_edit(root: Path) -> None:
    state = current(root)
    receipt = wire(
        ReceiptFixture(
            "KAST_DOCUMENTATION_RENDERED", "completed", state.sourceRevision, state.inputs, ()
        )
    )
    _ = (root / "docs/public/start.mdx").write_text("Changed after the rendered assessment.\n")
    obligations, _ = assess_receipt(root, receipt, current(root), ReceiptKind.RENDERED)
    assert [item.status for item in obligations] == [State.MISMATCHED]


def test_missing_rendered_obligations_remain_not_run(root: Path) -> None:
    state = current(root)
    receipt = wire(
        ReceiptFixture(
            "KAST_DOCUMENTATION_RENDERED", "completed", state.sourceRevision, state.inputs, ()
        )
    )
    obligations, _ = assess_receipt(root, receipt, state, ReceiptKind.RENDERED)
    assert len(obligations) == 9
    assert all(item.status == State.NOT_RUN for item in obligations)


@pytest.mark.parametrize("state", ["failed", "skipped", "not-run"])
def test_failed_or_unrun_check_never_becomes_passed(root: Path, state: str) -> None:
    source = current(root)
    receipt = wire(
        ReceiptFixture(
            "KAST_DOCUMENTATION_RENDERED",
            "completed",
            source.sourceRevision,
            source.inputs,
            (CheckFixture("keyboard", state, ()),),
        )
    )
    obligations, _ = assess_receipt(root, receipt, source, ReceiptKind.RENDERED)
    assert obligations[0].status == State(state)


def test_changed_receipt_evidence_rejects_replay(root: Path) -> None:
    state = current(root)
    receipt = wire(
        ReceiptFixture(
            "KAST_DOCUMENTATION_RENDERED",
            "completed",
            state.sourceRevision,
            state.inputs,
            (
                CheckFixture(
                    "keyboard", "passed", (InputDigest("docs/reviews/proof.txt", sha("passed\n")),)
                ),
            ),
        )
    )
    _ = (root / "docs/reviews/proof.txt").write_text("changed\n")
    obligations, _ = assess_receipt(root, receipt, state, ReceiptKind.RENDERED)
    assert obligations[-1].status == State.MISMATCHED


@pytest.mark.parametrize("path", ["../outside", "docs/public/\0.mdx"])
def test_unsafe_association_content_rejects_at_ingress(path: str) -> None:
    raw: object = [
        {
            "route": "start",
            "content": [{"path": path, "sha256": "0" * 64}],
            "claims": [],
            "generatedOwners": [],
            "reviewedSourceRevision": "1" * 40,
        }
    ]
    result = parse_associations(raw)
    assert isinstance(result, Failure)


@pytest.mark.parametrize("invalid", [{}, []])
def test_malformed_receipt_state_preserves_a_typed_failure(invalid: object) -> None:
    # Deliberately malformed wire data proves rejection before enum admission.
    check: dict[str, object] = {"name": "keyboard", "status": invalid, "evidence": []}
    raw: dict[str, object] = {
        "schemaVersion": 1,
        "kind": "KAST_DOCUMENTATION_RENDERED",
        "status": "completed",
        "sourceRevision": "1" * 40,
        "inputs": [],
        "limits": [],
        "checks": [check],
    }
    assert isinstance(parse_receipt(raw), Failure)


def test_snapshot_binds_current_prose_dispositions_while_excluding_report_artifacts(
    root: Path,
) -> None:
    directory = root / "docs/reviews/documentation-migration"
    directory.mkdir()
    path = directory / "prose-dispositions.json"
    _ = path.write_text(json.dumps(asdict(ProseDispositionsFixture()), separators=(",", ":")))
    first = current(root)
    assert any(
        item.path == "docs/reviews/documentation-migration/prose-dispositions.json"
        for item in first.inputs
    )
    _ = path.write_text(json.dumps(asdict(ProseDispositionsFixture()), indent=2))
    second = current(root)
    assert second.inputsDigest != first.inputsDigest
    _ = (root / "docs/reviews/proof.txt").write_text("The report artifact changed.\n")
    assert current(root).inputsDigest == second.inputsDigest


def test_source_qualification_keeps_rendering_unassessed() -> None:
    import publication

    result = publication.conclude_source(
        Snapshot("1" * 40, (), "2" * 64),
        (publication.Obligation("native-assessment", State.PASSED, "Current evidence"),),
        {},
        (),
    )
    assert result.type == "DOCUMENTATION_SOURCE_QUALIFICATION"
    assert result.decision == "qualified"
    assert result.publication == "not-assessed"
    assert result.rendered.status == State.NOT_ASSESSED
    assert "publication" in result.rendered.detail.lower()


def test_source_qualification_rejects_incomplete_source_evidence() -> None:
    import publication

    for state in (State.FAILED, State.STALE, State.UNRESOLVED, State.NOT_RUN, State.MISMATCHED):
        result = publication.conclude_source(
            Snapshot("1" * 40, (), "2" * 64),
            (publication.Obligation("native-assessment", state, "Not accepted"),),
            {},
            (),
        )
        assert result.decision == "rejected"
        assert result.publication == "not-assessed"


def test_source_report_cannot_supply_a_rendered_receipt(root: Path) -> None:
    import publication

    state = current(root)
    result = publication.conclude_source(
        state,
        (publication.Obligation("native-assessment", State.PASSED, "Current evidence"),),
        {},
        (),
    )
    obligations, _ = assess_receipt(root, asdict(result), state, ReceiptKind.RENDERED)
    assert all(item.status != State.PASSED for item in obligations)


def test_assessment_modes_share_source_failures_but_only_publication_reads_rendered(
    root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import publication

    def unavailable_native(_root: Path) -> Failure:
        return Failure("native", "Controlled unavailable store")

    def unavailable_projection(_root: Path, _write: bool) -> okf_public.Failure:
        return okf_public.Failure(okf_public.Code.IO, "Controlled unavailable projection")

    def unavailable_readers(_root: Path, _revision: str) -> reader_contracts.Failure:
        return reader_contracts.Failure(
            reader_contracts.Code.INPUT, "Controlled invalid reader contract"
        )

    monkeypatch.setattr(publication, "native_assessment", unavailable_native)
    monkeypatch.setattr(okf_public, "run", unavailable_projection)
    monkeypatch.setattr(reader_contracts, "assess", unavailable_readers)
    source = publication.assess(root, publication.SourceRequest("missing-prose.json"))
    full = publication.assess(
        root, publication.PublicationRequest("missing-rendered.json", "missing-prose.json")
    )
    assert isinstance(source, publication.SourceReport)
    assert isinstance(full, publication.PublicationReport)
    assert source.decision == full.decision == "rejected"
    assert source.publication == "not-assessed"
    assert source.rendered.status == State.NOT_ASSESSED
    assert all(item.name != "rendered" for item in source.obligations)
    assert any(
        item.name == "prose" and item.status == State.NOT_ASSESSED for item in source.obligations
    )
    assert any(
        item.name == "rendered" and item.status == State.NOT_ASSESSED for item in full.obligations
    )
    assert tuple(item for item in full.obligations if item.name != "rendered") == source.obligations
