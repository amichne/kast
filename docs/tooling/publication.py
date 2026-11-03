"""Admit documentation from exact source inputs and distinct evidence obligations."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import Literal, TypeGuard

import okf_public
import reader_contracts

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = "docs/okf-public.json"
PROSE_DISPOSITIONS = "docs/reviews/documentation-migration/prose-dispositions.json"
INPUT_PATHS = (
    "docs/public",
    "docs/tooling",
    "openwiki",
    MANIFEST,
    "docs/glossary.json",
    "docs/documentation-structure.md",
    ".vale.ini",
    "config/vale",
    ".github/scripts/ci/verify_documentation.py",
    ".github/workflows/docs.yml",
    ".github/workflows/openwiki-update.yml",
    "docs/reviews/navigation-density",
    PROSE_DISPOSITIONS,
)
REQUIRED_RENDERED = frozenset(
    {
        "representative-pages",
        "keyboard",
        "focus",
        "mobile",
        "zoom-200",
        "themes",
        "screenshots",
        "bounded-measurements",
        "candidate-preview-provenance",
    }
)
REQUIRED_PROSE = frozenset(
    {
        "vale",
        "terminology",
        "examples",
        "reader-audit",
        "mint-validate",
        "broken-links",
        "callable-parity",
        "glossary-parity",
    }
)


class State(StrEnum):
    PASSED = "passed"
    FAILED = "failed"
    SKIPPED = "skipped"
    NOT_RUN = "not-run"
    NOT_ASSESSED = "not-assessed"
    STALE = "stale"
    UNRESOLVED = "unresolved"
    MISMATCHED = "mismatched"


class ReceiptKind(StrEnum):
    RENDERED = "KAST_DOCUMENTATION_RENDERED"
    PROSE = "KAST_DOCUMENTATION_PROSE"


class ReceiptStatus(StrEnum):
    COMPLETED = "completed"
    FAILED = "failed"
    NOT_ASSESSED = "not-assessed"


@dataclass(frozen=True, slots=True)
class Failure:
    stage: str
    detail: str
    type: Literal["FAILED"] = "FAILED"


@dataclass(frozen=True, slots=True)
class InputDigest:
    path: str
    sha256: str


@dataclass(frozen=True, slots=True)
class Snapshot:
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    inputsDigest: str
    type: Literal["DOCUMENTATION_INPUTS"] = "DOCUMENTATION_INPUTS"


@dataclass(frozen=True, slots=True)
class Obligation:
    name: str
    status: State
    detail: str


@dataclass(frozen=True, slots=True)
class Association:
    route: str
    content: tuple[InputDigest, ...]
    claims: tuple[tuple[str, str, str], ...]
    generatedOwners: tuple[InputDigest, ...]
    reviewedSourceRevision: str


@dataclass(frozen=True, slots=True)
class Receipt:
    kind: ReceiptKind
    status: ReceiptStatus
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    checks: tuple[Obligation, ...]
    evidence: tuple[InputDigest, ...]
    limits: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class PublicationReport:
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    inputsDigest: str
    obligations: tuple[Obligation, ...]
    decision: Literal["accepted", "rejected"]
    nativeAssessment: object
    limits: tuple[str, ...]
    type: Literal["DOCUMENTATION_PUBLICATION"] = "DOCUMENTATION_PUBLICATION"
    producer: Literal["kast-documentation-publication-v1"] = "kast-documentation-publication-v1"


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return isinstance(value, dict)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _strings(value: object) -> TypeGuard[list[str]]:
    return _objects(value) and all(isinstance(item, str) for item in value)


def _sha(value: object) -> TypeGuard[str]:
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None


def _safe_path(value: object) -> TypeGuard[str]:
    if not isinstance(value, str) or not value or "\\" in value or "\0" in value:
        return False
    path = PurePosixPath(value)
    return (
        not path.is_absolute()
        and ".." not in path.parts
        and path.as_posix() == value
        and path.parts[0] != ".git"
    )


def _digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _read(root: Path, path: str) -> bytes | Failure:
    if not _safe_path(path):
        return Failure("input", "Unsafe repository path: " + path)
    candidate = root / path
    try:
        if candidate.is_symlink() or not candidate.resolve().is_relative_to(root.resolve()):
            return Failure("input", "Aliased repository input: " + path)
        return candidate.read_bytes()
    except OSError:
        return Failure("input", "Cannot read repository input: " + path)


def _load(root: Path, path: str) -> object:
    content = _read(root, path)
    if isinstance(content, Failure):
        return content
    try:
        result: object = json.loads(content)
        return result
    except (ValueError, UnicodeError):
        return Failure("input", "Invalid JSON: " + path)


def snapshot(root: Path) -> Snapshot | Failure:
    git = shutil.which("git")
    if git is None:
        return Failure("identity", "Git is unavailable")
    try:
        revision = subprocess.run(
            [git, "-C", str(root), "rev-parse", "HEAD"], check=True, capture_output=True, text=True
        ).stdout.strip()
        tracked = (
            subprocess.run(
                [git, "-C", str(root), "ls-files", "--cached", "-z"],
                check=True,
                capture_output=True,
            )
            .stdout.decode()
            .split("\0")
        )
        added = (
            subprocess.run(
                [
                    git,
                    "-C",
                    str(root),
                    "ls-files",
                    "--others",
                    "--exclude-standard",
                    "-z",
                    "--",
                    *INPUT_PATHS,
                ],
                check=True,
                capture_output=True,
            )
            .stdout.decode()
            .split("\0")
        )
    except (OSError, UnicodeError, subprocess.CalledProcessError):
        return Failure("identity", "Cannot read Git identity and documentation inputs")
    inputs: list[InputDigest] = []
    paths = {
        path
        for path in (*tracked, *added)
        if path
        and (
            not path.startswith("docs/reviews/")
            or path == PROSE_DISPOSITIONS
            or (path.startswith("docs/reviews/navigation-density/") and "/evidence/" not in path)
        )
    }
    for path in sorted(paths):
        content = _read(root, path)
        if isinstance(content, Failure):
            return content
        inputs.append(InputDigest(path, _digest(content)))
    if not inputs:
        return Failure("input", "The documentation input scope is empty")
    encoded = json.dumps(
        [asdict(item) for item in inputs], sort_keys=True, separators=(",", ":")
    ).encode()
    return Snapshot(revision, tuple(inputs), _digest(encoded))


def _parse_inputs(value: object) -> tuple[InputDigest, ...] | Failure:
    if not _objects(value):
        return Failure("input", "Input digests must be an array")
    result: list[InputDigest] = []
    for item in value:
        if not _record(item) or set(item) != {"path", "sha256"}:
            return Failure("input", "Each input needs a path and SHA-256")
        path, sha = item["path"], item["sha256"]
        if not _safe_path(path) or not _sha(sha):
            return Failure("input", "Input identity is invalid")
        result.append(InputDigest(path, sha))
    if len({item.path for item in result}) != len(result):
        return Failure("input", "Input paths must be unique")
    return tuple(sorted(result, key=lambda item: item.path))


def parse_associations(value: object) -> tuple[Association, ...] | Failure:
    if not _objects(value):
        return Failure("associations", "Public associations are absent")
    result: list[Association] = []
    fields = {"route", "content", "claims", "generatedOwners", "reviewedSourceRevision"}
    for item in value:
        if not _record(item) or set(item) != fields:
            return Failure(
                "associations", "Each public association needs its complete review binding"
            )
        route, revision, refs = item["route"], item["reviewedSourceRevision"], item["claims"]
        content, owners = _parse_inputs(item["content"]), _parse_inputs(item["generatedOwners"])
        if isinstance(content, Failure):
            return content
        if isinstance(owners, Failure):
            return owners
        if (
            not isinstance(route, str)
            or not route
            or not isinstance(revision, str)
            or re.fullmatch(r"[a-f0-9]{40}", revision) is None
            or not _objects(refs)
            or not content
        ):
            return Failure("associations", "The public review binding is incomplete")
        claims: list[tuple[str, str, str]] = []
        for ref in refs:
            if not _record(ref) or set(ref) != {"page", "claimId", "statementSha256"}:
                return Failure("associations", "A Claim binding is incomplete")
            page, claim_id, statement_sha = ref["page"], ref["claimId"], ref["statementSha256"]
            if (
                not _safe_path(page)
                or not page.startswith("openwiki/")
                or not page.endswith(".md")
                or not isinstance(claim_id, str)
                or not claim_id.strip()
                or not _sha(statement_sha)
            ):
                return Failure("associations", "A Claim binding is invalid")
            claims.append((page, claim_id, statement_sha))
        if not claims and not owners:
            return Failure(
                "associations", "A public association needs canonical Claims or generated owners"
            )
        if len(set(claims)) != len(claims):
            return Failure("associations", "Claim bindings must be unique")
        result.append(Association(route, content, tuple(claims), owners, revision))
    if len({item.route for item in result}) != len(result):
        return Failure("associations", "Public association routes must be unique")
    return tuple(result)


def assess_associations(root: Path, manifest: object, native: object) -> tuple[Obligation, ...]:
    if not _record(manifest) or not _objects(manifest.get("authored")):
        return (Obligation("associations", State.FAILED, "The authored route inventory is absent"),)
    associations = parse_associations(manifest.get("associations"))
    if isinstance(associations, Failure):
        return (Obligation("associations", State.NOT_ASSESSED, associations.detail),)
    routes: set[str] = set()
    authored_routes = manifest["authored"]
    if not _objects(authored_routes):
        return (
            Obligation("associations", State.FAILED, "The authored route inventory is invalid"),
        )
    projected_views = manifest.get("views", [])
    if not _objects(projected_views):
        return (
            Obligation("associations", State.FAILED, "The projected route inventory is invalid"),
        )
    for authored in (*authored_routes, *projected_views):
        if not _record(authored) or not isinstance(authored.get("route"), str):
            return (Obligation("associations", State.FAILED, "An authored route is invalid"),)
        route = authored["route"]
        if not isinstance(route, str):
            return (Obligation("associations", State.FAILED, "An authored route is invalid"),)
        routes.add(route)
    if routes != {item.route for item in associations}:
        return (
            Obligation(
                "associations",
                State.NOT_ASSESSED,
                "Accepted bindings must cover every authored and projected route exactly once",
            ),
        )
    git = shutil.which("git")
    if git is None:
        return (
            Obligation("associations", State.FAILED, "Git is unavailable for review provenance"),
        )
    revisions = tuple(sorted({item.reviewedSourceRevision for item in associations}))
    try:
        reviewed = subprocess.run(
            [git, "-C", str(root), "cat-file", "--batch-check=%(objecttype)"],
            input="\n".join(revisions) + "\n",
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()
    except (OSError, subprocess.CalledProcessError):
        return (
            Obligation("associations", State.FAILED, "Cannot read the reviewed source objects"),
        )
    if len(reviewed) != len(revisions) or any(kind != "commit" for kind in reviewed):
        return (
            Obligation(
                "associations",
                State.UNRESOLVED,
                "A reviewed source revision does not identify an existing commit",
            ),
        )
    claim_index: dict[tuple[str, str], tuple[str, str]] = {}
    if _record(native) and native.get("status") == "completed" and _objects(native.get("claims")):
        native_claims = native["claims"]
        if not _objects(native_claims):
            return (
                Obligation("associations", State.FAILED, "The native Claim inventory is invalid"),
            )
        for claim in native_claims:
            if not _record(claim):
                continue
            page, claim_id, statement, status = (
                claim.get("page"),
                claim.get("id"),
                claim.get("statement"),
                claim.get("status"),
            )
            if (
                isinstance(page, str)
                and isinstance(claim_id, str)
                and isinstance(statement, str)
                and isinstance(status, str)
            ):
                claim_index[(page.removeprefix("/"), claim_id)] = (
                    _digest(statement.encode()),
                    status,
                )
    obligations: list[Obligation] = []
    for association in associations:
        name = "association:" + association.route
        route = "/" if association.route == "index" else "/" + association.route
        closure = reader_contracts.content_inputs(root, route)
        if isinstance(closure, reader_contracts.Failure):
            obligations.append(Obligation(name, State.MISMATCHED, closure.detail))
            continue
        accepted_content = {item.path: item.sha256 for item in association.content}
        changed_content = tuple(
            item.path for item in closure if accepted_content.get(item.path) != item.sha256
        )
        if changed_content:
            obligations.append(
                Obligation(
                    name,
                    State.MISMATCHED,
                    "Changed or absent imported content: " + ", ".join(changed_content),
                )
            )
            continue
        mismatched: list[str] = []
        for item in (*association.content, *association.generatedOwners):
            content = _read(root, item.path)
            if isinstance(content, Failure) or _digest(content) != item.sha256:
                mismatched.append(item.path)
        if mismatched:
            obligations.append(
                Obligation(
                    name, State.MISMATCHED, "Changed accepted inputs: " + ", ".join(mismatched)
                )
            )
            continue
        issues: list[Obligation] = []
        for page, claim_id, statement_digest in association.claims:
            current = claim_index.get((page, claim_id))
            if current is None:
                issues.append(
                    Obligation(name, State.UNRESOLVED, "Unknown or unassessed Claim: " + claim_id)
                )
            elif current[0] != statement_digest:
                issues.append(
                    Obligation(
                        name, State.MISMATCHED, "The accepted Claim statement changed: " + claim_id
                    )
                )
            elif current[1] != "current":
                state = State.STALE if current[1] == "stale" else State.UNRESOLVED
                issues.append(
                    Obligation(
                        name, state, "The associated Claim is " + current[1] + ": " + claim_id
                    )
                )
        obligations.extend(
            issues
            or [
                Obligation(
                    name,
                    State.PASSED,
                    "Authored content and canonical statements match their accepted binding",
                )
            ]
        )
    return tuple(obligations)


def parse_receipt(value: object) -> Receipt | Failure:
    fields = {"schemaVersion", "kind", "status", "sourceRevision", "inputs", "checks", "limits"}
    if (
        not _record(value)
        or set(value) != fields
        or type(value.get("schemaVersion")) is not int
        or value.get("schemaVersion") != 1
    ):
        return Failure("receipt", "The receipt contract is invalid")
    kind, status, revision = value["kind"], value["status"], value["sourceRevision"]
    if (
        not isinstance(kind, str)
        or kind not in {"KAST_DOCUMENTATION_RENDERED", "KAST_DOCUMENTATION_PROSE"}
        or not isinstance(status, str)
        or status not in {"completed", "failed", "not-assessed"}
        or not isinstance(revision, str)
    ):
        return Failure("receipt", "The receipt identity is invalid")
    inputs = _parse_inputs(value["inputs"])
    if isinstance(inputs, Failure):
        return inputs
    if not _objects(value["checks"]) or not _strings(value["limits"]):
        return Failure("receipt", "The receipt checks or limits are invalid")
    checks: list[Obligation] = []
    evidence: list[InputDigest] = []
    for check in value["checks"]:
        required = {"name", "status", "evidence"}
        if not _record(check) or not required <= set(check) <= required | {"reason"}:
            return Failure("receipt", "A receipt check is incomplete")
        name, state, reason = check["name"], check["status"], check.get("reason", "")
        refs = _parse_inputs(check["evidence"])
        if isinstance(refs, Failure):
            return refs
        if (
            not isinstance(name, str)
            or not name
            or not isinstance(state, str)
            or state not in {"passed", "failed", "skipped", "not-run"}
            or not isinstance(reason, str)
            or (state == "passed" and not refs)
        ):
            return Failure("receipt", "A receipt check lacks its status or evidence")
        checks.append(Obligation(name, State(state), reason))
        evidence.extend(refs)
    if len({item.name for item in checks}) != len(checks):
        return Failure("receipt", "Receipt check names must be unique")
    limits = value["limits"]
    if not _strings(limits):
        return Failure("receipt", "Receipt limits are invalid")
    return Receipt(
        ReceiptKind(kind),
        ReceiptStatus(status),
        revision,
        inputs,
        tuple(checks),
        tuple(evidence),
        tuple(limits),
    )


def assess_receipt(
    root: Path, value: object, current: Snapshot, kind: ReceiptKind
) -> tuple[tuple[Obligation, ...], tuple[str, ...]]:
    name = "rendered" if kind == "KAST_DOCUMENTATION_RENDERED" else "prose"
    if isinstance(value, Failure):
        return (Obligation(name, State.NOT_ASSESSED, value.detail),), ()
    receipt = parse_receipt(value)
    if isinstance(receipt, Failure):
        return (Obligation(name, State.NOT_ASSESSED, receipt.detail),), ()
    if (
        receipt.kind != kind
        or receipt.sourceRevision != current.sourceRevision
        or receipt.inputs != current.inputs
    ):
        return (
            Obligation(
                name, State.MISMATCHED, "The receipt does not describe the exact candidate inputs"
            ),
        ), receipt.limits
    obligations = [
        Obligation(name + ":" + item.name, item.status, item.detail) for item in receipt.checks
    ]
    if receipt.status != "completed":
        obligations.append(
            Obligation(
                name,
                State.FAILED if receipt.status == "failed" else State.NOT_ASSESSED,
                "The assessment status is " + receipt.status,
            )
        )
    required = REQUIRED_RENDERED if kind == "KAST_DOCUMENTATION_RENDERED" else REQUIRED_PROSE
    for missing in sorted(required - {item.name for item in receipt.checks}):
        obligations.append(
            Obligation(name + ":" + missing, State.NOT_RUN, "The necessary check has no receipt")
        )
    for item in receipt.evidence:
        content = _read(root, item.path)
        if isinstance(content, Failure) or _digest(content) != item.sha256:
            obligations.append(
                Obligation(
                    name + ":evidence", State.MISMATCHED, "Changed receipt evidence: " + item.path
                )
            )
    return tuple(obligations), receipt.limits


def native_assessment(root: Path) -> object:
    node = shutil.which("node")
    if node is None:
        return Failure("native", "Node is unavailable")
    try:
        child = subprocess.run(
            [
                node,
                str(Path(__file__).with_name("markdown") / "evidence-assess.ts"),
                "--root",
                str(root),
                "--scope",
                "public",
            ],
            capture_output=True,
            timeout=120,
            check=False,
        )
        result: object = json.loads(child.stdout)
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        return Failure("native", "The native assessment did not return a report")
    if (
        not _record(result)
        or not isinstance(result.get("status"), str)
        or result.get("status") not in {"completed", "failed"}
        or child.returncode not in {0, 1, 2}
    ):
        return Failure("native", "The native assessment returned an incompatible report")
    return result


def assess(root: Path, rendered_path: str, prose_path: str) -> PublicationReport | Failure:
    current = snapshot(root)
    if isinstance(current, Failure):
        return current
    native = native_assessment(root)
    obligations: list[Obligation] = []
    if not _record(native) or native.get("status") != "completed":
        obligations.append(
            Obligation("native-assessment", State.FAILED, "The native assessment failed")
        )
    elif native.get("sourceRevision") != current.sourceRevision or native.get("scope") != "public":
        obligations.append(
            Obligation(
                "native-assessment",
                State.MISMATCHED,
                "The native assessment identity differs from the candidate",
            )
        )
    else:
        obligations.append(
            Obligation(
                "native-assessment",
                State.PASSED,
                "The read-only native preflight completed for the public scope",
            )
        )
        pages, claims, orphans = (
            native.get("pages"),
            native.get("claims"),
            native.get("orphanSidecars"),
        )
        if (
            not _objects(pages)
            or not pages
            or not _objects(claims)
            or not claims
            or not _strings(orphans)
        ):
            obligations.append(
                Obligation(
                    "native-coverage",
                    State.NOT_ASSESSED,
                    "The native page or Claim inventory is incomplete",
                )
            )
        else:
            for page in pages:
                if (
                    not _record(page)
                    or page.get("claims") != "present"
                    or page.get("digestAgreement") != "matched"
                ):
                    obligations.append(
                        Obligation("native-page", State.MISMATCHED, json.dumps(page))
                    )
            for claim in claims:
                if (
                    not _record(claim)
                    or not isinstance(claim.get("status"), str)
                    or claim.get("status")
                    not in {
                        "current",
                        "stale",
                        "unresolved",
                    }
                ):
                    obligations.append(
                        Obligation(
                            "native-claim", State.NOT_ASSESSED, "A Claim lacks a native outcome"
                        )
                    )
                elif claim.get("status") != "current":
                    obligations.append(
                        Obligation(
                            "native-claim",
                            State.STALE if claim.get("status") == "stale" else State.UNRESOLVED,
                            str(claim.get("id")),
                        )
                    )
            for orphan in orphans:
                obligations.append(Obligation("native-orphan", State.UNRESOLVED, orphan))
    manifest = _load(root, MANIFEST)
    obligations.extend(assess_associations(root, manifest, native))
    projection = okf_public.run(root, False)
    obligations.append(
        Obligation(
            "generated-projection",
            State.FAILED if isinstance(projection, okf_public.Failure) else State.PASSED,
            str(projection)
            if isinstance(projection, okf_public.Failure)
            else "The projector output matches current owners",
        )
    )
    readers = reader_contracts.assess(root, current.sourceRevision)
    if isinstance(readers, reader_contracts.Failure):
        obligations.append(Obligation("reader-contracts", State.FAILED, readers.detail))
    else:
        obligations.append(
            Obligation(
                "reader-contracts", State.PASSED, "Current routes and reader contracts agree"
            )
        )
        first = readers.firstUse
        obligations.append(
            Obligation(
                "first-use-complexity",
                State.PASSED if first.D <= 1 and first.K == 0 and first.H <= 1 else State.FAILED,
                f"D={first.D}, K={first.K}, H={first.H}, W={first.W}, L={first.L}",
            )
        )
        for page in readers.pages:
            if page.contract.densityDisposition.status != "ACCEPTED":
                obligations.append(
                    Obligation(
                        "density:" + page.contract.route,
                        State.NOT_ASSESSED,
                        page.contract.densityDisposition.reason,
                    )
                )
    limits: list[str] = [
        "Evidence currentness does not establish semantic truth, native task completion, or reader comprehension."
    ]
    for path, kind in ((rendered_path, ReceiptKind.RENDERED), (prose_path, ReceiptKind.PROSE)):
        receipt_obligations, receipt_limits = assess_receipt(root, _load(root, path), current, kind)
        obligations.extend(receipt_obligations)
        limits.extend(receipt_limits)
    after = snapshot(root)
    if isinstance(after, Failure) or after != current:
        obligations.append(
            Obligation(
                "candidate-stability",
                State.MISMATCHED,
                "Candidate inputs changed during publication assessment",
            )
        )
    decision = (
        "accepted"
        if obligations and all(item.status == State.PASSED for item in obligations)
        else "rejected"
    )
    return PublicationReport(
        current.sourceRevision,
        current.inputs,
        current.inputsDigest,
        tuple(obligations),
        decision,
        asdict(native) if isinstance(native, Failure) else native,
        tuple(limits),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--root", type=Path, default=ROOT)
    _ = parser.add_argument("--report", type=Path)
    _ = parser.add_argument("--snapshot", action="store_true")
    _ = parser.add_argument(
        "--rendered-report", default="build/reports/documentation/rendered.json"
    )
    _ = parser.add_argument("--prose-report", default="build/reports/documentation/prose.json")
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    result = (
        snapshot(root)
        if arguments.snapshot
        else assess(root, arguments.rendered_report, arguments.prose_report)
    )
    encoded = json.dumps(asdict(result), indent=2) + "\n"
    if arguments.report is None:
        print(encoded, end="")
    else:
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(encoded)
    return (
        2
        if isinstance(result, Failure)
        else 0
        if isinstance(result, Snapshot) or result.decision == "accepted"
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
