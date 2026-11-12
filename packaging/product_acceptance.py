"""Detached installed-product evidence. Decoding is not proof of native execution."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

import managed_release as product

MAXIMUM_RECEIPT_BYTES = 65_536


class Case(StrEnum):
    FRESH = "FRESH_INSTALL"
    PASSIVE = "PASSIVE_DISCOVERY"
    CLI = "CLI_SEMANTICS"
    RPC = "RPC_SEMANTICS"
    MCP = "MCP_SEMANTICS"
    RESTART = "STOP_RESTART"
    PARALLEL = "SAME_ROOT_PARALLEL_FIRST_QUERIES"
    ROOTS = "TWO_ROOTS"
    EDIT = "EDIT_QUERY"
    UPGRADE = "DIFFERENT_BUILD_UPGRADE_QUERY"
    UNINSTALL = "UNINSTALL"
    SENTINELS = "DELETION_SENTINELS"


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
    schemaVersion: int = 1
    qualificationStage: str = "INSTALLED_PRODUCT_NATIVE"
    qualification: str = "COMPLETE"


def filename(target: str) -> str:
    if target not in product.PRODUCT_TARGETS:
        raise ValueError("unsupported acceptance target")
    return f"acceptance-{target}.json"


def encode(receipt: Receipt) -> bytes:
    return (json.dumps(asdict(receipt), separators=(",", ":")) + "\n").encode()


def decode(raw: bytes, record: product.ProductRecord, digest: str, target: str) -> Receipt:
    value = product.document(raw)
    if (
        len(raw) > MAXIMUM_RECEIPT_BYTES
        or not product.mapping(value)
        or set(value) != set(Receipt.__dataclass_fields__)
        or type(value.get("schemaVersion")) is not int
        or value.get("schemaVersion") != 1
        or value.get("qualificationStage") != "INSTALLED_PRODUCT_NATIVE"
        or value.get("qualification") != "COMPLETE"
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
        or cases != [item.value for item in Case]
    ):
        raise ValueError("installed acceptance cases or baseline")
    bundle, runtime, control = composition
    return Receipt(
        digest,
        record.sourceRevision,
        target,
        facts.qualification.controlSha256,
        record.producer,
        baseline,
        revision,
        bundle,
        runtime,
        control,
        tuple(Case),
    )


def assets(directory: Path, record: product.ProductRecord) -> tuple[product.ProductAsset, ...]:
    """Require both receipts; original bytes are retained, never reserialized."""
    digest = product.file_digest(directory / "build-record.json")
    result: list[product.ProductAsset] = []
    baselines: set[tuple[str, str]] = set()
    for target in product.PRODUCT_TARGETS:
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
