"""The source guard rejects native drift without turning failure into no findings."""

from dataclasses import asdict, dataclass, replace

import pytest

from knowledge_check import NativeIssue, native_issues


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
        (replace(NativeFixture(), claims=(ClaimFixture(status="stale"),)), NativeIssue.CLAIM),
        (replace(NativeFixture(), claims=(ClaimFixture(status="unresolved"),)), NativeIssue.CLAIM),
        (replace(NativeFixture(), claims=(ClaimFixture(status="unknown"),)), NativeIssue.CLAIM),
        (replace(NativeFixture(), orphanSidecars=("orphan",)), NativeIssue.ORPHAN),
    ],
)
def test_native_failure_remains_a_finite_rejection(
    fixture: NativeFixture, issue: NativeIssue
) -> None:
    assert native_issues(wire(fixture), "candidate") == (issue,)
