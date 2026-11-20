"""Reject broken knowledge integrity and report current evidence without publication authority."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, TypeGuard

import claim_evidence
import publication

INTEGRITY_FAILURES = frozenset(
    {
        claim_evidence.Reason.INVALID_INPUT,
        claim_evidence.Reason.MISSING_CLAIM,
        claim_evidence.Reason.DUPLICATE_CLAIM,
        claim_evidence.Reason.INVALID_RESOURCE,
        claim_evidence.Reason.PARSER_UNAVAILABLE,
    }
)


class NativeIssue(StrEnum):
    FAILED = "failed"
    IDENTITY = "identity-mismatch"
    INVENTORY = "incomplete-inventory"
    PAGE = "page-disagreement"
    CLAIM = "claim-not-current"
    ORPHAN = "orphan-sidecar"


class Decision(StrEnum):
    ACCEPTED = "accepted"
    REJECTED = "rejected"


class Assessment(StrEnum):
    CURRENT = "current"
    REVIEW_REQUIRED = "review-required"
    UNRESOLVED = "unresolved"


@dataclass(frozen=True)
class Report:
    sourceRevision: str
    nativeIssues: tuple[NativeIssue, ...]
    evidence: claim_evidence.Report
    reviews: tuple[publication.Obligation, ...]
    decision: Decision
    assessment: Assessment
    inputs: tuple[publication.InputDigest, ...]
    inputsDigest: str
    nativeAssessment: object
    bindings: tuple[publication.Obligation, ...]
    authoredInputs: tuple[publication.Obligation, ...]
    publication: Literal["not-assessed"] = "not-assessed"


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return _mapping(value) and all(isinstance(key, str) for key in value)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def native_issues(native: object, revision: str) -> tuple[NativeIssue, ...]:
    if not _record(native) or native.get("status") != "completed":
        return (NativeIssue.FAILED,)
    if native.get("sourceRevision") != revision or native.get("scope") != "canonical":
        return (NativeIssue.IDENTITY,)
    pages, claims, orphans = native.get("pages"), native.get("claims"), native.get("orphanSidecars")
    if (
        not _objects(pages)
        or not pages
        or not _objects(claims)
        or not claims
        or not _objects(orphans)
    ):
        return (NativeIssue.INVENTORY,)
    issues: list[NativeIssue] = []
    if any(
        not _record(p) or p.get("claims") != "present" or p.get("digestAgreement") != "matched"
        for p in pages
    ):
        issues.append(NativeIssue.PAGE)
    if any(
        not _record(c)
        or not isinstance(c.get("status"), str)
        or c.get("status") not in {"current", "stale", "unresolved"}
        for c in claims
    ):
        issues.append(NativeIssue.CLAIM)
    if orphans:
        issues.append(NativeIssue.ORPHAN)
    return tuple(issues)


def current_assessment(
    native: object,
    evidence: claim_evidence.Report,
    reviews: tuple[publication.Obligation, ...],
    bindings: tuple[publication.Obligation, ...] = (),
) -> Assessment:
    if not _record(native) or not _objects(native.get("claims")):
        return Assessment.UNRESOLVED
    claims = native["claims"]
    if not _objects(claims):
        return Assessment.UNRESOLVED
    statuses = tuple(c.get("status") if _record(c) else None for c in claims)
    if (
        not claims
        or evidence.failures
        or any(not isinstance(s, str) or s not in {"current", "stale"} for s in statuses)
        or any(r.status is not publication.State.REVIEW_REQUIRED for r in reviews)
        or any(
            b.status
            not in {publication.State.PASSED, publication.State.MISMATCHED, publication.State.STALE}
            for b in bindings
        )
    ):
        return Assessment.UNRESOLVED
    if (
        "stale" in statuses
        or reviews
        or any(b.status is not publication.State.PASSED for b in bindings)
    ):
        return Assessment.REVIEW_REQUIRED
    return Assessment.CURRENT


def assess(root: Path) -> Report | publication.Failure:
    snapshot = publication.snapshot(root)
    if isinstance(snapshot, publication.Failure):
        return snapshot
    try:
        manifest: object = json.loads((root / publication.MANIFEST).read_bytes())
    except (OSError, ValueError, UnicodeError):
        return publication.Failure("manifest", "Cannot decode public associations")
    if not _record(manifest):
        return publication.Failure("manifest", "Public manifest must be an object")
    associations = publication.parse_associations(manifest.get("associations"))
    if isinstance(associations, publication.Failure):
        return associations
    native = publication.native_assessment(root, "canonical")
    issues = native_issues(native, snapshot.sourceRevision)
    evidence = claim_evidence.assess(root, native, manifest)
    reviews = publication.assess_review_revisions(
        root, tuple(sorted({a.reviewedSourceRevision for a in associations}))
    )
    bindings = publication.assess_associations(root, manifest, native)
    authored = tuple(publication.assess_authored_content(root, a) for a in associations)
    after = publication.snapshot(root)
    if after != snapshot:
        return publication.Failure("snapshot", "Documentation inputs changed during qualification")
    broken_evidence = any(item.reason in INTEGRITY_FAILURES for item in evidence.failures)
    broken_reviews = any(item.status is not publication.State.REVIEW_REQUIRED for item in reviews)
    broken_bindings = any(
        item.status in {publication.State.FAILED, publication.State.NOT_ASSESSED}
        for item in bindings
    ) or any(
        item.status not in {publication.State.PASSED, publication.State.MISMATCHED}
        for item in authored
    )
    return Report(
        snapshot.sourceRevision,
        issues,
        evidence,
        reviews,
        Decision.REJECTED
        if issues or broken_evidence or broken_reviews or broken_bindings
        else Decision.ACCEPTED,
        current_assessment(native, evidence, reviews, bindings),
        snapshot.inputs,
        snapshot.inputsDigest,
        native,
        bindings,
        authored,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=publication.ROOT)
    parser.add_argument(
        "--require-current",
        action="store_true",
        help="Reject maintenance demand as well as integrity defects; this does not authorize publication.",
    )
    parser.add_argument("--report", type=Path, help="Write the full JSON report to this path.")
    args = parser.parse_args()
    result = assess(args.root.resolve())
    payload = json.dumps(asdict(result), indent=2)
    if args.report is None:
        print(payload)
    else:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(payload + "\n", encoding="utf-8")
        if isinstance(result, publication.Failure):
            print(f"{result.stage}: {result.detail}")
        else:
            print(f"integrity={result.decision} assessment={result.assessment}")
            for issue in result.nativeIssues:
                print(f"native: {issue}")
            for failure in result.evidence.failures:
                if failure.reason in INTEGRITY_FAILURES:
                    print(f"{failure.page} {failure.claim_id}: {failure.reason}")
            for review in result.reviews:
                if review.status is not publication.State.REVIEW_REQUIRED:
                    print(f"{review.name}: {review.status}: {review.detail}")
            for item in result.bindings:
                if item.status in {publication.State.FAILED, publication.State.NOT_ASSESSED}:
                    print(f"{item.name}: {item.status}: {item.detail}")
            for item in result.authoredInputs:
                if item.status is publication.State.MISMATCHED:
                    print(f"{item.name}: {item.status}: {item.detail}")
        print(f"Full knowledge assessment: {args.report}")
    return (
        0
        if (
            isinstance(result, Report)
            and result.decision is Decision.ACCEPTED
            and (not args.require_current or result.assessment is Assessment.CURRENT)
        )
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
