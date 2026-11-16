"""The source guard rejects native drift without turning failure into no findings."""

from dataclasses import asdict, dataclass, replace
from pathlib import Path

import pytest

import knowledge_check
from knowledge_check import NativeIssue, native_issues
from test_publication import ManifestFixture, binding, root, sha


@dataclass(frozen=True)
class PageFixture:
    claims: str = "present"
    digestAgreement: str = "matched"


@dataclass(frozen=True)
class ClaimFixture:
    status: str = "current"


@dataclass(frozen=True)
class NativeFixture:
    status: str = "completed"
    sourceRevision: str = "candidate"
    scope: str = "canonical"
    pages: tuple[PageFixture, ...] = (PageFixture(),)
    claims: tuple[ClaimFixture, ...] = (ClaimFixture(),)
    orphanSidecars: tuple[str, ...] = ()


def wire(value: NativeFixture) -> object:
    # JSON arrays at the native process boundary, rather than Python tuples.
    import json

    result: object = json.loads(json.dumps(asdict(value)))
    return result


def test_all_current_native_inventory_is_accepted() -> None:
    assert native_issues(wire(NativeFixture()), "candidate") == ()


@pytest.mark.parametrize(
    ("fixture", "issue"),
    [
        (replace(NativeFixture(), status="failed"), NativeIssue.FAILED),
        (replace(NativeFixture(), sourceRevision="old"), NativeIssue.IDENTITY),
        (replace(NativeFixture(), scope="public"), NativeIssue.IDENTITY),
        (replace(NativeFixture(), pages=()), NativeIssue.INVENTORY),
        (replace(NativeFixture(), claims=()), NativeIssue.INVENTORY),
        (
            replace(NativeFixture(), pages=(PageFixture(digestAgreement="mismatched"),)),
            NativeIssue.PAGE,
        ),
        (replace(NativeFixture(), claims=(ClaimFixture(status="unknown"),)), NativeIssue.CLAIM),
        (replace(NativeFixture(), orphanSidecars=("orphan",)), NativeIssue.ORPHAN),
    ],
)
def test_native_failure_remains_a_finite_rejection(
    fixture: NativeFixture, issue: NativeIssue
) -> None:
    assert native_issues(wire(fixture), "candidate") == (issue,)


@pytest.mark.parametrize("state", ["stale", "unresolved"])
def test_known_currentness_outcomes_do_not_become_integrity_failures(state: str) -> None:
    assert (
        native_issues(wire(replace(NativeFixture(), claims=(ClaimFixture(state),))), "candidate")
        == ()
    )


@dataclass(frozen=True)
class Resource:
    resource: str


@dataclass(frozen=True)
class AssessedClaim:
    status: str = "current"
    page: str = "/openwiki/contracts/source.md"
    id: str = "source-value"
    statement: str = "The source supplies the value."
    evidence: tuple[Resource, ...] = (Resource("repo://docs/internal/tooling/owner.py#L1-L1"),)


@pytest.mark.usefixtures(root.__name__)
def test_current_declaration_drift_is_unresolved_but_malformed_identity_blocks(
    request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch
) -> None:
    import json

    import publication

    root_path = request.getfixturevalue("root")
    assert isinstance(root_path, Path)
    (root_path / "docs/internal/tooling/owner.py").write_text("def owner(): pass\n")
    source_owner = publication.InputDigest(
        "docs/internal/tooling/owner.py", sha("def owner(): pass\n")
    )
    (root_path / "docs/okf-public.json").write_text(
        json.dumps(
            asdict(ManifestFixture((replace(binding(root_path), generatedOwners=(source_owner,)),)))
        )
    )
    state = publication.snapshot(root_path)
    assert isinstance(state, publication.Snapshot)
    native = json.loads(json.dumps(asdict(NativeFixture(sourceRevision=state.sourceRevision))))
    native["claims"] = json.loads(json.dumps([asdict(AssessedClaim())]))

    def observed_native(_root: Path, _scope: str) -> object:
        return native

    monkeypatch.setattr(publication, "native_assessment", observed_native)
    established = knowledge_check.assess(root_path)
    assert isinstance(established, knowledge_check.Report)
    assert established.assessment is knowledge_check.Assessment.CURRENT
    authored = root_path / "docs/public/start.mdx"
    original = authored.read_text()
    authored.write_text(original + "A changed authored explanation.\n")
    incoherent = knowledge_check.assess(root_path)
    assert isinstance(incoherent, knowledge_check.Report)
    assert incoherent.decision is knowledge_check.Decision.REJECTED
    authored.write_text(original)
    (root_path / "docs/internal/tooling/owner.py").write_text("VALUE = 42\n")
    result = knowledge_check.assess(root_path)
    assert isinstance(result, knowledge_check.Report)
    assert result.decision is knowledge_check.Decision.ACCEPTED
    assert result.assessment.value == "unresolved"
    assert result.evidence.failures[0].reason.value == "no-declaration"
    assert result.bindings[0].status is publication.State.MISMATCHED
    native["claims"] = json.loads(
        json.dumps([asdict(replace(AssessedClaim(), evidence=(Resource("repo://../escape.py"),)))])
    )
    rejected = knowledge_check.assess(root_path)
    assert isinstance(rejected, knowledge_check.Report)
    assert rejected.decision is knowledge_check.Decision.REJECTED
