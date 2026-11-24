"""Detached installed-product evidence. Decoding is not proof of native execution."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, replace
from enum import StrEnum
from pathlib import Path

import managed_release as product

MAXIMUM_RECEIPT_BYTES = 65_536


class Case(StrEnum):
    FRESH = "FRESH_INSTALL"
    PASSIVE = "PASSIVE_DISCOVERY"
    CLI = "CLI_SEMANTICS"
    MCP = "MCP_SEMANTICS"
    RESTART = "STOP_RESTART"
    PARALLEL = "SAME_ROOT_PARALLEL_FIRST_QUERIES"
    ROOTS = "TWO_ROOTS"
    EDIT = "EDIT_QUERY"
    UPGRADE = "DIFFERENT_BUILD_UPGRADE_QUERY"
    UNINSTALL = "UNINSTALL"
    SENTINELS = "DELETION_SENTINELS"
    CALLERS = "EXACT_CALLER_PROVENANCE"
    JAVA = "JAVA_REFERENCE_CONTEXTS_OFFSETS"
    UNKNOWN = "UNKNOWN_RESULT_REJECTION"
    HOST = "INSTALLED_HOST_RUNTIME_IDENTITY"
    CLEANUP = "STOP_ENDPOINT_SOCKET_CLEANUP"
    REANALYSIS = "CALLER_REANALYSIS_AFTER_EDIT"


# Published acceptance v1 proved only the original lifecycle inventory.


@dataclass(frozen=True)
class Receipt:
    buildRecordSha256: str
    sourceRevision: str
    target: str
    controlSha256: str
    producer: product.ProductProducer
    baselineRecordSha256: str
    baselineSourceRevision: str
    baselineBundleSha256: str
    baselineRuntimeSha256: str
    baselineControlSha256: str
    completedCases: tuple[Case, ...]
    schemaVersion: int = 3
    qualificationStage: str = "INSTALLED_PRODUCT_NATIVE"
    qualification: str = "COMPLETE"


def filename(target: str) -> str:
    if target not in product.PRODUCT_TARGETS:
        raise ValueError("unsupported acceptance target")
    return f"acceptance-{target}.json"


class Part(StrEnum):
    """One installed run in its own disposable HOME. Two runs together prove every case."""

    UPGRADE = "upgrade"
    FRESH = "fresh"


PART_CASES: dict[Part, tuple[Case, ...]] = {
    Part.UPGRADE: (Case.UPGRADE,),
    Part.FRESH: tuple(case for case in Case if case is not Case.UPGRADE),
}


@dataclass(frozen=True)
class PartReceipt:
    """Evidence of one part. No release reader admits it; only `join` makes a receipt."""

    part: Part
    buildRecordSha256: str
    sourceRevision: str
    target: str
    controlSha256: str
    producer: product.ProductProducer
    baselineRecordSha256: str
    baselineSourceRevision: str
    baselineBundleSha256: str
    baselineRuntimeSha256: str
    baselineControlSha256: str
    completedCases: tuple[Case, ...]
    schemaVersion: int = 1
    qualificationStage: str = "INSTALLED_PRODUCT_NATIVE_PART"
    qualification: str = "PARTIAL"


def part_filename(target: str, part: Part) -> str:
    return filename(target).removesuffix(".json") + f"-{part}.part.json"


def encode(receipt: Receipt | PartReceipt) -> bytes:
    return (json.dumps(asdict(receipt), separators=(",", ":")) + "\n").encode()


@dataclass(frozen=True)
class _Bindings:
    """The admitted identities that a complete receipt and each of its parts share."""

    controlSha256: str
    baselineRecordSha256: str
    baselineSourceRevision: str
    baselineBundleSha256: str
    baselineRuntimeSha256: str
    baselineControlSha256: str


def decode(raw: bytes, record: product.ProductRecord, digest: str, target: str) -> Receipt:
    required = tuple(Case)
    bindings = _admit(raw, record, digest, target, Receipt, required)
    return Receipt(
        digest,
        record.sourceRevision,
        target,
        bindings.controlSha256,
        record.producer,
        bindings.baselineRecordSha256,
        bindings.baselineSourceRevision,
        bindings.baselineBundleSha256,
        bindings.baselineRuntimeSha256,
        bindings.baselineControlSha256,
        required,
    )


def decode_part(
    raw: bytes, record: product.ProductRecord, digest: str, target: str, part: Part
) -> PartReceipt:
    required = PART_CASES[part]
    bindings = _admit(raw, record, digest, target, PartReceipt, required)
    value = product.document(raw)
    if not product.mapping(value) or value.get("part") != part.value:
        raise ValueError("installed acceptance part")
    return PartReceipt(
        part,
        digest,
        record.sourceRevision,
        target,
        bindings.controlSha256,
        record.producer,
        bindings.baselineRecordSha256,
        bindings.baselineSourceRevision,
        bindings.baselineBundleSha256,
        bindings.baselineRuntimeSha256,
        bindings.baselineControlSha256,
        required,
    )


def join(parts: tuple[PartReceipt, ...]) -> Receipt:
    """Make the complete receipt from each part exactly once, on one product and one baseline."""
    if len(parts) != len(Part) or {item.part for item in parts} != set(Part):
        raise ValueError("installed acceptance parts")
    if len({replace(item, part=Part.FRESH, completedCases=()) for item in parts}) != 1:
        raise ValueError("installed acceptance parts used different products or baselines")
    if sorted(case for item in parts for case in item.completedCases) != sorted(Case):
        raise ValueError("installed acceptance parts do not prove each case once")
    shared = parts[0]
    return Receipt(
        shared.buildRecordSha256,
        shared.sourceRevision,
        shared.target,
        shared.controlSha256,
        shared.producer,
        shared.baselineRecordSha256,
        shared.baselineSourceRevision,
        shared.baselineBundleSha256,
        shared.baselineRuntimeSha256,
        shared.baselineControlSha256,
        tuple(Case),
    )


def _admit(
    raw: bytes,
    record: product.ProductRecord,
    digest: str,
    target: str,
    form: type[Receipt] | type[PartReceipt],
    required: tuple[Case, ...],
) -> _Bindings:
    fields = form.__dataclass_fields__
    value = product.document(raw)
    if (
        len(raw) > MAXIMUM_RECEIPT_BYTES
        or not product.mapping(value)
        or set(value) != set(fields)
        or type(value.get("schemaVersion")) is not int
        or value.get("schemaVersion") != fields["schemaVersion"].default
        or value.get("qualificationStage") != fields["qualificationStage"].default
        or value.get("qualification") != fields["qualification"].default
        or value.get("buildRecordSha256") != digest
        or value.get("sourceRevision") != record.sourceRevision
        or value.get("target") != target
        or product.product_producer(value.get("producer"), ".github/workflows/ci.yml")
        != record.producer
    ):
        raise ValueError("installed acceptance identity")
    facts = next((item for item in record.targets if item.target == target), None)
    baseline, revision, cases = (
        value[key] for key in ("baselineRecordSha256", "baselineSourceRevision", "completedCases")
    )
    composition: list[str] = []
    for key in ("baselineBundleSha256", "baselineRuntimeSha256", "baselineControlSha256"):
        supplied = value[key]
        if not isinstance(supplied, str) or not product.DIGEST.fullmatch(supplied):
            raise ValueError("baseline composition digest")
        composition.append(supplied)
    if (
        facts is None
        or value.get("controlSha256") != facts.qualification.controlSha256
        or not isinstance(baseline, str)
        or not product.DIGEST.fullmatch(baseline)
        or baseline == digest
        or not product.revision(revision)
        or composition
        == [facts.bundle.sha256, facts.runtime.sha256, facts.qualification.controlSha256]
        or not product.sequence(cases)
        or cases != [item.value for item in required]
    ):
        raise ValueError("installed acceptance cases or baseline")
    bundle, runtime, control = composition
    return _Bindings(
        facts.qualification.controlSha256, baseline, revision, bundle, runtime, control
    )


def assets(directory: Path, record: product.ProductRecord) -> tuple[product.ProductAsset, ...]:
    """Read the original receipt bytes for every target in the admitted record."""
    digest = product.file_digest(directory / "build-record.json")
    result: list[product.ProductAsset] = []
    baselines: set[tuple[str, str]] = set()
    for facts in record.targets:
        target = facts.target
        path = directory / "payload" / filename(target)
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAXIMUM_RECEIPT_BYTES:
            raise ValueError("missing installed acceptance")
        receipt = decode(path.read_bytes(), record, digest, target)
        baselines.add((receipt.baselineRecordSha256, receipt.baselineSourceRevision))
        result.append(
            product.ProductAsset(path.name, product.file_digest(path), path.stat().st_size)
        )
    if len(baselines) != 1:
        raise ValueError("native targets used different baseline products")
    return tuple(result)
