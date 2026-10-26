#!/usr/bin/env python3
"""Bind affected source obligations to a collected consumer graph and exact source identity."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import TypeGuard

GRAPH = "config/ci/verification-graph.json"
MANIFEST = "config/kotlin-semantic-coverage/cases.tsv"
# These direct JVM consumers read the hosted protocol without a Gradle dependency on the IDE host.
HOST_CONSUMERS = frozenset({":query:protocol", ":cli"})
SOURCE_GUARDS = frozenset(
    {
        ":verifyKastArchitecture",
        ":verifyJsonContracts",
        ":verifyConfigurationIngress",
        ":verifyKnowledgeBase",
        ":verifyVerificationGraph",
    }
)
SEMANTIC_TASKS = frozenset(
    {
        ":verifyKotlinSemanticInputs",
        ":verifyKotlinSemanticInventory",
        ":verifySelectedKotlinSemanticCoverage",
    }
)
SHARED_INPUTS = {
    "cli/src/main/js/": frozenset({":cli", ":query:protocol"}),
    "experiments/host-observation/": frozenset({":cli", ":query:protocol"}),
}
# File consumers without a Gradle project-dependency edge. Match exact paths so
# neighboring inputs keep their existing owner closure.
FILE_CONSUMERS = {
    # kast.public-query-contract: verifyPublicQueryGeneration / verifyPublicQueryGeneratorRules.
    "cli/src/main/js/query-delivery.mjs": frozenset({":app-server"}),
    # HostedFailureEncodingFixtureTest compares IntelliJ encoder output with this CLI fixture.
    "cli/src/test/resources/hosted-read-failure-encodings.json": frozenset({":workspace:intellij-read"}),
}


class Failure(str, Enum):
    STALE_GRAPH = "STALE_GRAPH"
    INVALID_GRAPH = "INVALID_GRAPH"
    UNKNOWN_OWNER = "UNKNOWN_OWNER"
    INVALID_MANIFEST = "INVALID_MANIFEST"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"


@dataclass(frozen=True)
class InputIdentity:
    path: str
    sha256: str


@dataclass(frozen=True)
class ConsumerGraph:
    inputs: tuple[InputIdentity, ...]
    consumers: tuple[tuple[str, tuple[str, ...]], ...]
    source_roots: tuple[tuple[str, tuple[str, ...]], ...]
    digest: str


@dataclass(frozen=True)
class SemanticCase:
    case_id: str
    test_result_id: str
    family: str
    verification_owner: str


@dataclass(frozen=True)
class Plan:
    schemaVersion: int
    sourceRevision: str
    sourceTree: str
    baseRevision: str
    comparisonBasis: str
    event: str
    graphDigest: str
    manifestDigest: str
    changedPaths: tuple[str, ...]
    owners: tuple[str, ...]
    consumers: tuple[str, ...]
    tasks: tuple[str, ...]
    caseIds: tuple[str, ...]
    testResultIds: tuple[str, ...]
    classes: tuple[str, ...]


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def strings(value: object) -> tuple[str, ...] | None:
    if not sequence(value) or not all(isinstance(item, str) for item in value):
        return None
    return tuple(item for item in value if isinstance(item, str))


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate field")
    return result


def graph(root: Path, modules: frozenset[str]) -> ConsumerGraph | Failure:
    try:
        raw = (root / GRAPH).read_bytes()
        document: object = json.loads(raw, object_pairs_hook=unique_object)
        if not mapping(document) or set(document) != {
            "schemaVersion",
            "evidence",
            "inputs",
            "consumers",
        }:
            return Failure.INVALID_GRAPH
        if type(document["schemaVersion"]) is not int or document["schemaVersion"] != 1:
            return Failure.INVALID_GRAPH
        if document["evidence"] != "DECLARED_PRODUCTION_TEST_FIXTURE_CONSUMERS":
            return Failure.INVALID_GRAPH
        raw_inputs, raw_consumers = document["inputs"], document["consumers"]
        if not sequence(raw_inputs) or not raw_inputs or not sequence(raw_consumers):
            return Failure.INVALID_GRAPH
        inputs: list[InputIdentity] = []
        for row in raw_inputs:
            if not mapping(row) or set(row) != {"path", "sha256"}:
                return Failure.INVALID_GRAPH
            path, expected = row["path"], row["sha256"]
            if (
                not isinstance(path, str)
                or not isinstance(expected, str)
                or not re.fullmatch(r"[0-9a-f]{64}", expected)
            ):
                return Failure.INVALID_GRAPH
            if not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or any(
                part in {"", ".", ".."} for part in path.split("/")
            ):
                return Failure.INVALID_GRAPH
            source = root / path
            if (
                source.is_symlink()
                or not source.is_file()
                or digest(source.read_bytes()) != expected
            ):
                return Failure.STALE_GRAPH
            inputs.append(InputIdentity(path, expected))
        if len({item.path for item in inputs}) != len(inputs):
            return Failure.INVALID_GRAPH
        consumers: list[tuple[str, tuple[str, ...]]] = []
        source_roots: list[tuple[str, tuple[str, ...]]] = []
        for row in raw_consumers:
            if not mapping(row) or set(row) != {"owner", "affected", "sourceRoots"}:
                return Failure.INVALID_GRAPH
            owner, affected = row["owner"], strings(row["affected"])
            if not isinstance(owner, str) or owner not in modules or affected is None:
                return Failure.INVALID_GRAPH
            if (
                owner not in affected
                or set(affected) - modules
                or tuple(sorted(set(affected))) != affected
            ):
                return Failure.INVALID_GRAPH
            consumers.append((owner, affected))
            roots = strings(row["sourceRoots"])
            if (
                roots is None
                or tuple(sorted(set(roots))) != roots
                or any(
                    not re.fullmatch(r"[A-Za-z0-9_./-]+", path)
                    or any(part in {"", ".", ".."} for part in path.split("/"))
                    for path in roots
                )
            ):
                return Failure.INVALID_GRAPH
            source_roots.append((owner, roots))
        if len(consumers) != len(modules) or frozenset(owner for owner, _ in consumers) != modules:
            return Failure.INVALID_GRAPH
        return ConsumerGraph(tuple(inputs), tuple(consumers), tuple(source_roots), digest(raw))
    except (OSError, ValueError, UnicodeDecodeError):
        return Failure.INVALID_GRAPH


def cases(root: Path, modules: frozenset[str]) -> tuple[tuple[SemanticCase, ...], str] | Failure:
    try:
        raw = (root / MANIFEST).read_bytes()
        reader = csv.DictReader(io.StringIO(raw.decode()), delimiter="\t")
        required = {"case_id", "test_result_id", "family", "verification_owner"}
        if not required.issubset(reader.fieldnames or ()):
            return Failure.INVALID_MANIFEST
        rows = tuple(
            SemanticCase(
                row["case_id"], row["test_result_id"], row["family"], row["verification_owner"]
            )
            for row in reader
        )
        if not rows or any(
            not row.case_id
            or not row.family
            or row.verification_owner not in modules
            or not re.fullmatch(r"[A-Za-z0-9_.]+#[^#]+", row.test_result_id)
            for row in rows
        ):
            return Failure.INVALID_MANIFEST
        if len({row.case_id for row in rows}) != len(rows) or len(
            {row.test_result_id for row in rows}
        ) != len(rows):
            return Failure.INVALID_MANIFEST
        return rows, digest(raw)
    except (OSError, ValueError, KeyError, TypeError):
        return Failure.INVALID_MANIFEST


def plan(
    root: Path,
    modules: frozenset[str],
    paths: tuple[str, ...],
    revision: str,
    tree: str,
    base: str,
    basis: str,
    event: str,
) -> Plan | Failure:
    if event not in {"pull_request", "push"} or any(
        not re.fullmatch(r"[0-9a-f]{40}", item) or item == "0" * 40
        for item in (revision, tree, base, basis)
    ):
        return Failure.IDENTITY_MISMATCH
    admitted = graph(root, modules)
    if isinstance(admitted, Failure):
        return admitted
    manifest = cases(root, modules)
    if isinstance(manifest, Failure):
        return manifest
    rows, manifest_digest = manifest
    owners: set[str] = set()
    for path in paths:
        if not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or any(
            part in {"", ".", ".."} for part in path.split("/")
        ):
            return Failure.UNKNOWN_OWNER
        owners.update(FILE_CONSUMERS.get(path, ()))
        shared = next(
            (value for prefix, value in SHARED_INPUTS.items() if path.startswith(prefix)), None
        )
        if shared is not None:
            owners.update(shared)
            continue
        matched = {
            owner
            for owner, roots in admitted.source_roots
            if any(path.startswith(source_root + "/") for source_root in roots)
        }
        if not matched:
            return Failure.UNKNOWN_OWNER
        owners.update(matched)
    if not owners:
        return Failure.UNKNOWN_OWNER
    closure = dict(admitted.consumers)
    affected = {consumer for owner in owners for consumer in closure[owner]}
    if ":runtime:hosted" in affected:
        affected.update(HOST_CONSUMERS)
    tasks = set(SOURCE_GUARDS) | {
        f"{owner}:{task}" for owner in affected for task in ("check", "scopedCheck")
    }
    # Each selectable family is an owner's whole fixture population, including its oracles and rejection controls.
    selected = tuple(row for row in rows if row.verification_owner in affected)
    if selected:
        tasks.update(SEMANTIC_TASKS)
    if any(path.startswith("experiments/host-observation/") for path in paths):
        tasks.add(":hostObservationTest")
    return Plan(
        1,
        revision,
        tree,
        base,
        basis,
        event,
        admitted.digest,
        manifest_digest,
        tuple(sorted(set(paths))),
        tuple(sorted(owners)),
        tuple(sorted(affected)),
        tuple(sorted(tasks)),
        tuple(sorted(row.case_id for row in selected)),
        tuple(sorted(row.test_result_id for row in selected)),
        tuple(sorted({row.test_result_id.split("#")[0] for row in selected})),
    )


def admit(document: object, expected: Plan) -> Plan | Failure:
    # Exact independently recomputed plans reject unknown fields, stale bases, omitted cases, and altered tasks.
    canonical: object = json.loads(json.dumps(asdict(expected)))
    return (
        expected
        if mapping(document)
        and type(document.get("schemaVersion")) is int
        and document == canonical
        else Failure.IDENTITY_MISMATCH
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-generated", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    if (root / GRAPH).read_bytes() != args.check_generated.read_bytes():
        raise SystemExit(
            "verification-plan: STALE_GRAPH; regenerate config/ci/verification-graph.json with generateVerificationGraph"
        )
    print("verification-plan: generated graph matches authored build inputs")


if __name__ == "__main__":
    main()
