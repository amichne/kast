"""Block source qualification on canonical Claim drift and public evidence gaps."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard

import claim_evidence
import publication


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


@dataclass(frozen=True)
class Report:
    sourceRevision: str
    nativeIssues: tuple[NativeIssue, ...]
    evidence: claim_evidence.Report
    reviews: tuple[publication.Obligation, ...]
    decision: Decision


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
    if any(not _record(c) or c.get("status") != "current" for c in claims):
        issues.append(NativeIssue.CLAIM)
    if orphans:
        issues.append(NativeIssue.ORPHAN)
    return tuple(issues)


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
    after = publication.snapshot(root)
    if after != snapshot:
        return publication.Failure("snapshot", "Documentation inputs changed during qualification")
    return Report(
        snapshot.sourceRevision,
        issues,
        evidence,
        reviews,
        Decision.REJECTED if issues or evidence.failures or reviews else Decision.ACCEPTED,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=publication.ROOT)
    args = parser.parse_args()
    result = assess(args.root.resolve())
    print(json.dumps(asdict(result), indent=2))
    return 0 if isinstance(result, Report) and result.decision is Decision.ACCEPTED else 1


if __name__ == "__main__":
    raise SystemExit(main())
