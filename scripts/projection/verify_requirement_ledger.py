#!/usr/bin/env python3
"""Validate the permanent projection requirement ledger."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from enum import Enum, unique
from pathlib import Path
from typing import Any, NoReturn


SCHEMA_VERSION = 1
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LEDGER = PROJECT_ROOT / "docs/internal/projection/requirement-ledger.json"
EXPECTED_SOURCE_SHA256 = "060dd99ff3c3811b094b17009b5ed892e5fa1e4d5840ceda4a1f6034d32a558d"
EXPECTED_MATRIX_SHA256 = "d06f248828f29149b80e869a86a3ee9adbe427eadc0f4e7ea65aad4f2df3f9ee"
REQUIREMENT_ID_PATTERN = re.compile(r"^[A-Z]{2,3}-[0-9]{3}$")

EXPECTED_CATEGORY_COUNTS = {
    "CTL": 5,
    "BAS": 8,
    "SYS": 8,
    "INP": 12,
    "AUT": 12,
    "IDN": 9,
    "EVD": 15,
    "INV": 15,
    "FLW": 15,
    "PLN": 18,
    "MUT": 14,
    "VER": 15,
    "OUT": 12,
    "FLR": 10,
    "BND": 10,
    "SCL": 12,
    "SEC": 8,
    "OBS": 8,
    "NGL": 8,
    "DEM": 14,
    "NS": 1,
}

EXPECTED_REQUIREMENT_IDS = frozenset(
    f"{category}-{number:03d}"
    for category, count in EXPECTED_CATEGORY_COUNTS.items()
    for number in range(1, count + 1)
)

EXPECTED_GATE_OWNERS = {
    "G0": frozenset(f"KPS-{number:02d}" for number in range(1, 9)),
    "G1": frozenset(f"KPS-{number:02d}" for number in range(9, 17)),
    "G2": frozenset(f"KPS-{number:02d}" for number in range(17, 21)),
    "G3": frozenset(f"KPS-{number:02d}" for number in range(21, 24)),
    "G4": frozenset(f"KPS-{number:02d}" for number in range(24, 27)),
    "G5": frozenset(f"KPS-{number:02d}" for number in range(27, 30)),
    "G6": frozenset(f"KPS-{number:02d}" for number in range(30, 34)),
    "G7": frozenset(f"KPS-{number:02d}" for number in range(34, 38)),
    "G8": frozenset(f"KPS-{number:02d}" for number in range(38, 41)),
}

OWNER_GATE = {
    owner: gate
    for gate, owners in EXPECTED_GATE_OWNERS.items()
    for owner in owners
}

EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS = {
    "KPS-01": "CTL-001 through CTL-005",
    "KPS-02": "SYS-001; INP-001 through INP-005; INP-010; INP-012",
    "KPS-03": "SYS-006; INP-006 through INP-009; INP-011",
    "KPS-04": "SYS-002 through SYS-005; SYS-007; SYS-008",
    "KPS-05": "BND-001 through BND-009",
    "KPS-06": "NGL-001 through NGL-005; NGL-007",
    "KPS-07": "OUT-001 through OUT-002; OUT-008 through OUT-010",
    "KPS-08": "FLR-001 through FLR-003; FLR-005; FLR-007",
    "KPS-09": "BAS-001; AUT-001; AUT-005; AUT-012; OUT-011; FLR-009",
    "KPS-10": "BAS-002; BAS-004; BAS-005; AUT-002; AUT-003; AUT-008 through AUT-010",
    "KPS-11": "AUT-004; AUT-006; AUT-007",
    "KPS-12": "IDN-001 through IDN-005; IDN-009",
    "KPS-13": "IDN-006 through IDN-008",
    "KPS-14": "BAS-003; BND-010; EVD-001 through EVD-004",
    "KPS-15": "BAS-006; BAS-007; EVD-005 through EVD-008; EVD-010 through EVD-013; OUT-003; FLR-010; NGL-008",
    "KPS-16": "EVD-009; EVD-014; EVD-015",
    "KPS-17": "INV-001 through INV-005",
    "KPS-18": "INV-006 through INV-010",
    "KPS-19": "INV-011 through INV-014",
    "KPS-20": "INV-015",
    "KPS-21": "FLW-001 through FLW-003; FLW-005",
    "KPS-22": "FLW-004; FLW-006 through FLW-009",
    "KPS-23": "FLW-010 through FLW-015",
    "KPS-24": "PLN-001 through PLN-006",
    "KPS-25": "PLN-007 through PLN-010; PLN-018; NGL-006",
    "KPS-26": "PLN-011 through PLN-017",
    "KPS-27": "BAS-008; AUT-011; MUT-001; MUT-002; MUT-005; MUT-007; SEC-001; SEC-003; SEC-007; SEC-008",
    "KPS-28": "MUT-003; MUT-004; MUT-006; MUT-008; MUT-009; MUT-012",
    "KPS-29": "MUT-010; MUT-011; MUT-013; MUT-014; FLR-004; FLR-008",
    "KPS-30": "VER-001 through VER-005",
    "KPS-31": "VER-006 through VER-011",
    "KPS-32": "VER-012 through VER-015",
    "KPS-33": "OUT-004 through OUT-007",
    "KPS-34": "SCL-001 through SCL-005; SCL-011; SCL-012; FLR-006",
    "KPS-35": "SCL-006 through SCL-010",
    "KPS-36": "SEC-002; SEC-004 through SEC-006; OUT-012",
    "KPS-37": "OBS-001 through OBS-008",
    "KPS-38": "DEM-001 through DEM-006; DEM-010; DEM-011",
    "KPS-39": "DEM-007 through DEM-009; DEM-012 through DEM-014",
    "KPS-40": "NS-001",
}

EXPECTED_OWNER_COMMANDS = {
    "KPS-01": "python3 scripts/projection/verify_requirement_ledger.py",
    "KPS-02": "./gradlew :analysis-api:test --tests '*RepositoryOperationAdmissionTest'",
    "KPS-03": "./gradlew :analysis-api:test --tests '*TransformationInputAdmissionTest'",
    "KPS-04": "./gradlew :analysis-api:test --tests '*TransformationOutcomeTest'",
    "KPS-05": "./gradlew :analysis-api:test --tests '*EvidenceBoundaryPolicyTest'",
    "KPS-06": "./gradlew :analysis-server:test --tests '*ProhibitedClaimAdmissionTest'",
    "KPS-07": "./gradlew :analysis-api:test --tests '*CanonicalTransformationResultTest'",
    "KPS-08": "./gradlew :analysis-api:test --tests '*TransformationFailureContractTest'",
    "KPS-09": "./gradlew :analysis-api:test :index-store:test --tests '*SnapshotIdentity*'",
    "KPS-10": "./gradlew :index-store:test :indexer:test --tests '*RepositorySnapshot*'",
    "KPS-11": "./gradlew :index-store:test :indexer:test --tests '*Inventory*'",
    "KPS-12": "./gradlew :analysis-api:test :analysis-server:test --tests '*Selector*'",
    "KPS-13": "./gradlew :indexer:test --tests '*SemanticFamily*'",
    "KPS-14": "./gradlew :analysis-api:test :index-store:test :indexer:test --tests '*EvidenceProvenance*'",
    "KPS-15": "./gradlew :analysis-api:test :indexer:test --tests '*Coverage*'",
    "KPS-16": "./gradlew :analysis-api:test :indexer:test --tests '*Continuation*'",
    "KPS-17": "./gradlew :indexer:test --tests '*InvocationTarget*'",
    "KPS-18": "./gradlew :indexer:test --tests '*InvocationArgumentMapping*'",
    "KPS-19": "./gradlew :indexer:test --tests '*InvocationEffect*'",
    "KPS-20": "./gradlew :analysis-server:test --tests '*ExactInvocationAdmissionTest'",
    "KPS-21": "./gradlew :indexer:test --tests '*IntraproceduralValueFlow*'",
    "KPS-22": "./gradlew :indexer:test --tests '*InterproceduralPropagationWitness*'",
    "KPS-23": "./gradlew :analysis-server:test :indexer:test --tests '*PropagationAnalysis*'",
    "KPS-24": "./gradlew :analysis-server:test --tests '*PlanningScopeAdmissionTest'",
    "KPS-25": "./gradlew :analysis-server:test --tests '*TransformationObligationLedgerTest'",
    "KPS-26": "./gradlew :analysis-server:test --tests '*DeterministicTransformationPlanTest'",
    "KPS-27": "./gradlew :analysis-api:test :indexer:test --tests '*MutationAuthority*'",
    "KPS-28": "./gradlew :analysis-server:test :indexer:test --tests '*ExactMutation*'",
    "KPS-29": "./gradlew :indexer:test --tests '*MutationRecovery*'",
    "KPS-30": "./gradlew :analysis-server:test :indexer:test --tests '*PostMutationEvaluation*'",
    "KPS-31": "./gradlew :analysis-server:test --tests '*TransformationReconciliationTest'",
    "KPS-32": "./gradlew :analysis-server:test --tests '*FinalTransformationProofTest'",
    "KPS-33": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent",
    "KPS-34": "python3 scripts/projection/benchmark/verify_scale_profile.py",
    "KPS-35": "./gradlew :index-store:test :indexer:test --tests '*Concurrency*'",
    "KPS-36": "./gradlew :analysis-api:test :analysis-server:test --tests '*Tamper*'",
    "KPS-37": "./gradlew :analysis-api:test :indexer:test --tests '*Readiness*'",
    "KPS-38": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test projection_transformation_smoke fixture_contract",
    "KPS-39": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test projection_transformation_smoke end_to_end",
    "KPS-40": "python3 scripts/projection/verify_north_star.py",
}

EXPECTED_OWNER_REGRESSION_COMMANDS = {
    "KPS-01": "git rev-parse HEAD && git status --short",
    "KPS-02": "./gradlew :analysis-api:test",
    "KPS-03": "./gradlew :analysis-api:test",
    "KPS-04": "./gradlew :analysis-api:test",
    "KPS-05": "./gradlew :analysis-api:test",
    "KPS-06": "./gradlew :analysis-server:test",
    "KPS-07": "./gradlew :analysis-api:test",
    "KPS-08": "./gradlew :analysis-api:test",
    "KPS-09": "./gradlew :analysis-api:test :index-store:test",
    "KPS-10": "./gradlew :index-store:test :indexer:test",
    "KPS-11": "./gradlew :index-store:test :indexer:test",
    "KPS-12": "./gradlew :analysis-api:test :analysis-server:test",
    "KPS-13": "./gradlew :indexer:test",
    "KPS-14": "./gradlew :analysis-api:test :index-store:test :indexer:test",
    "KPS-15": "./gradlew :analysis-api:test :indexer:test",
    "KPS-16": "./gradlew :analysis-api:test :indexer:test",
    "KPS-17": "./gradlew :indexer:test",
    "KPS-18": "./gradlew :indexer:test",
    "KPS-19": "./gradlew :indexer:test",
    "KPS-20": "./gradlew :analysis-server:test",
    "KPS-21": "./gradlew :indexer:test",
    "KPS-22": "./gradlew :indexer:test",
    "KPS-23": "./gradlew :analysis-server:test :indexer:test",
    "KPS-24": "./gradlew :analysis-server:test",
    "KPS-25": "./gradlew :analysis-server:test",
    "KPS-26": "./gradlew :analysis-server:test",
    "KPS-27": "./gradlew :analysis-api:test :indexer:test",
    "KPS-28": "./gradlew :analysis-server:test :indexer:test",
    "KPS-29": "./gradlew :indexer:test",
    "KPS-30": "./gradlew :analysis-server:test :indexer:test",
    "KPS-31": "./gradlew :analysis-server:test",
    "KPS-32": "./gradlew :analysis-server:test",
    "KPS-33": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent",
    "KPS-34": "git rev-parse HEAD && git status --short",
    "KPS-35": "./gradlew :index-store:test :indexer:test",
    "KPS-36": "./gradlew :analysis-api:test :analysis-server:test",
    "KPS-37": "./gradlew :analysis-api:test :indexer:test",
    "KPS-38": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test projection_transformation_smoke fixture_contract",
    "KPS-39": "cargo test --manifest-path cli-rs/Cargo.toml --locked --test projection_transformation_smoke end_to_end",
    "KPS-40": "git rev-parse HEAD && git status --short",
}

EXPECTED_OWNER_DELIVERIES = {
    "KPS-01": {
        "issue": 508,
        "pullRequest": 549,
        "url": "https://github.com/amichne/kast/pull/549",
        "headRefName": "feature/proof-carrying-transformations",
    },
}

KPS01_BOUNDARY_TEST_COMMAND = (
    "PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover "
    "-s scripts/projection -p 'test_*.py' -v"
)
SAME_GATE_PREREQUISITE_RULE = "allOtherRequirementsInGate"
EXPECTED_ATOMIC_COMPLETION_GROUPS = (
    ("KPS-36", ("OUT-012", "SEC-006")),
)

OWNER_RANGE_PATTERN = re.compile(
    r"^(?P<start_category>[A-Z]{2,3})-(?P<start>[0-9]{3}) through "
    r"(?P<end_category>[A-Z]{2,3})-(?P<end>[0-9]{3})$"
)


def expand_expected_owner_expression(expression: str) -> frozenset[str]:
    identifiers: set[str] = set()
    for raw_token in expression.split(";"):
        token = raw_token.strip()
        match = OWNER_RANGE_PATTERN.fullmatch(token)
        if match:
            start_category = match.group("start_category")
            end_category = match.group("end_category")
            if start_category != end_category:
                raise RuntimeError(f"owner range crosses categories: {token}")
            identifiers.update(
                f"{start_category}-{number:03d}"
                for number in range(int(match.group("start")), int(match.group("end")) + 1)
            )
        else:
            identifiers.add(token)
    return frozenset(identifiers)


EXPECTED_REQUIREMENT_OWNER: dict[str, str] = {}
for expected_owner, expected_expression in EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS.items():
    for expected_identifier in expand_expected_owner_expression(expected_expression):
        if expected_identifier in EXPECTED_REQUIREMENT_OWNER:
            raise RuntimeError(f"duplicate expected owner for {expected_identifier}")
        EXPECTED_REQUIREMENT_OWNER[expected_identifier] = expected_owner
if set(EXPECTED_REQUIREMENT_OWNER) != EXPECTED_REQUIREMENT_IDS:
    raise RuntimeError("expected primary owner map does not cover the permanent requirement set")
if set(EXPECTED_OWNER_COMMANDS) != set(EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS):
    raise RuntimeError("expected owner commands do not cover the permanent owner set")
if set(EXPECTED_OWNER_REGRESSION_COMMANDS) != set(EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS):
    raise RuntimeError("expected regression commands do not cover the permanent owner set")
if set(OWNER_GATE) != set(EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS):
    raise RuntimeError("gate owner ranges do not cover the permanent owner set")

EXPECTED_GATE_DEPENDENCIES = {
    "G0": None,
    "G1": "G0",
    "G2": "G1",
    "G3": "G2",
    "G4": "G3",
    "G5": "G4",
    "G6": "G5",
    "G7": "G6",
    "G8": "G7",
}

EXPECTED_GATE_EXIT_CONDITIONS = {
    "G0": "Inputs, outcomes, evidence policy, boundaries, and prohibited claims are mechanically defined.",
    "G1": "The system can make exact, snapshot-bound, evidence-bearing read claims without false completeness.",
    "G2": "Every supported invocation has exact semantic mappings or a precise unsupported outcome.",
    "G3": "Value-propagation claims have distinct meanings, bounded witnesses, and sound negative-proof rules.",
    "G4": "A target condition can be converted into a deterministic, obligation-complete plan without source mutation.",
    "G5": "A valid plan can be applied within exact authority, identity, generation, content, and write boundaries.",
    "G6": "The changed repository is re-evaluated and the target condition is mechanically proven or rejected.",
    "G7": "The accepted behavior remains correct under enterprise scale, failure, concurrency, tampering, and operational diagnosis.",
    "G8": "One repository-wide transformation satisfies the full specification.",
}


class LedgerError(ValueError):
    """A closed ledger contract was violated."""


@unique
class VerificationMethod(str, Enum):
    INSPECTION = "INS"
    CONTRACT_TEST = "CT"
    UNIT_TEST = "UT"
    INTEGRATION_TEST = "IT"
    PROPERTY_TEST = "PBT"
    END_TO_END = "E2E"
    FAULT_INJECTION = "FI"
    BENCHMARK = "BENCH"
    ADVERSARIAL = "ADV"


VERIFICATION_METHOD_DESCRIPTIONS = {
    VerificationMethod.INSPECTION: "Inspection of specifications, schemas, generated artifacts, or source boundaries",
    VerificationMethod.CONTRACT_TEST: "Contract test against public input or output behavior",
    VerificationMethod.UNIT_TEST: "Focused deterministic test",
    VerificationMethod.INTEGRATION_TEST: "Integration test across system boundaries",
    VerificationMethod.PROPERTY_TEST: "Property-based, replay, or determinism test",
    VerificationMethod.END_TO_END: "End-to-end repository scenario",
    VerificationMethod.FAULT_INJECTION: "Fault injection, crash recovery, or concurrency test",
    VerificationMethod.BENCHMARK: "Performance or scale benchmark",
    VerificationMethod.ADVERSARIAL: "Tamper, containment, or adversarial test",
}


@unique
class GateId(str, Enum):
    G0 = "G0"
    G1 = "G1"
    G2 = "G2"
    G3 = "G3"
    G4 = "G4"
    G5 = "G5"
    G6 = "G6"
    G7 = "G7"
    G8 = "G8"


@unique
class EvidenceKind(str, Enum):
    INSPECTION = "inspection"
    COMMAND = "command"
    TEST = "test"
    ARTIFACT = "artifact"
    BENCHMARK = "benchmark"
    DELIVERY = "delivery"


@unique
class CompletionValue(str, Enum):
    INCOMPLETE = "incomplete"
    COMPLETE = "complete"


@unique
class PrerequisiteKind(str, Enum):
    REQUIREMENT = "requirement"
    GATE = "gate"
    ALL = "all"


def reject(message: str) -> NoReturn:
    raise LedgerError(message)


def require_object(value: Any, location: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        reject(f"{location} must be an object")
    return value


def require_array(value: Any, location: str) -> list[Any]:
    if not isinstance(value, list):
        reject(f"{location} must be an array")
    return value


def require_text(value: Any, location: str) -> str:
    if not isinstance(value, str) or not value.strip():
        reject(f"{location} must be non-empty text")
    return value


def require_exact_keys(value: dict[str, Any], expected: set[str], location: str) -> None:
    actual = set(value)
    if actual != expected:
        reject(
            f"{location} fields must be {sorted(expected)}; "
            f"missing={sorted(expected - actual)} unexpected={sorted(actual - expected)}"
        )


def parse_enum(enum_type: type[Enum], value: Any, location: str) -> Any:
    text = require_text(value, location)
    try:
        return enum_type(text)
    except ValueError:
        allowed = sorted(member.value for member in enum_type)
        reject(f"{location} must be one of {allowed}, got {text!r}")


@dataclass(frozen=True, order=True)
class RequirementId:
    value: str

    @classmethod
    def parse(cls, value: Any, location: str) -> RequirementId:
        text = require_text(value, location)
        if not REQUIREMENT_ID_PATTERN.fullmatch(text):
            reject(f"{location} is not a permanent requirement identifier: {text!r}")
        return cls(text)

    @property
    def category(self) -> str:
        return self.value.split("-", 1)[0]


@dataclass(frozen=True)
class EvidenceReference:
    kind: EvidenceKind
    reference: str

    @classmethod
    def parse(cls, value: Any, location: str) -> EvidenceReference:
        payload = require_object(value, location)
        require_exact_keys(payload, {"kind", "reference"}, location)
        kind = parse_enum(EvidenceKind, payload["kind"], f"{location}.kind")
        reference = require_text(payload["reference"], f"{location}.reference")
        if kind is EvidenceKind.DELIVERY and not re.fullmatch(
            r"https://github\.com/amichne/kast/pull/[1-9][0-9]*", reference
        ):
            reject(f"{location}.reference must be an amichne/kast pull request URL")
        return cls(kind=kind, reference=reference)

    @property
    def is_executable(self) -> bool:
        return self.kind in {
            EvidenceKind.COMMAND,
            EvidenceKind.TEST,
            EvidenceKind.BENCHMARK,
        }


@dataclass(frozen=True)
class CompletionState:
    value: CompletionValue
    evidence: tuple[EvidenceReference, ...]

    @classmethod
    def parse(cls, value: Any, location: str) -> CompletionState:
        payload = require_object(value, location)
        require_exact_keys(payload, {"state", "evidence"}, location)
        state = parse_enum(CompletionValue, payload["state"], f"{location}.state")
        evidence = tuple(
            EvidenceReference.parse(item, f"{location}.evidence[{index}]")
            for index, item in enumerate(require_array(payload["evidence"], f"{location}.evidence"))
        )
        if state is CompletionValue.COMPLETE and not evidence:
            reject(f"{location} cannot be complete without completion evidence")
        if state is CompletionValue.INCOMPLETE and evidence:
            reject(f"{location} cannot retain completion evidence while incomplete")
        return cls(state, evidence)


@dataclass(frozen=True)
class PrimaryOwner:
    draft_key: str
    issue: int
    url: str

    @classmethod
    def parse(cls, value: Any, location: str) -> PrimaryOwner:
        payload = require_object(value, location)
        require_exact_keys(payload, {"draftKey", "issue", "url"}, location)
        draft_key = require_text(payload["draftKey"], f"{location}.draftKey")
        if not re.fullmatch(r"KPS-[0-9]{2}", draft_key):
            reject(f"{location}.draftKey must match KPS-NN")
        issue = payload["issue"]
        if type(issue) is not int or issue <= 0:
            reject(f"{location}.issue must be a positive integer")
        url = require_text(payload["url"], f"{location}.url")
        expected_issue = 507 + int(draft_key.removeprefix("KPS-"))
        if issue != expected_issue:
            reject(f"{location}.issue must be {expected_issue} for {draft_key}")
        expected_url = f"https://github.com/amichne/kast/issues/{issue}"
        if url != expected_url:
            reject(f"{location}.url must be {expected_url}")
        return cls(draft_key, issue, url)


@dataclass(frozen=True)
class AdmittedDelivery:
    primary_owner: str
    issue: int
    pull_request: int
    url: str
    head_ref_name: str

    @classmethod
    def parse(cls, value: Any, location: str) -> AdmittedDelivery:
        payload = require_object(value, location)
        require_exact_keys(
            payload,
            {"primaryOwner", "issue", "pullRequest", "url", "headRefName"},
            location,
        )
        primary_owner = require_text(payload["primaryOwner"], f"{location}.primaryOwner")
        if not re.fullmatch(r"KPS-[0-9]{2}", primary_owner):
            reject(f"{location}.primaryOwner must match KPS-NN")
        issue = payload["issue"]
        if type(issue) is not int or issue <= 0:
            reject(f"{location}.issue must be a positive integer")
        expected_issue = 507 + int(primary_owner.removeprefix("KPS-"))
        if issue != expected_issue:
            reject(f"{location}.issue must be {expected_issue} for {primary_owner}")
        pull_request = payload["pullRequest"]
        if type(pull_request) is not int or pull_request <= 0:
            reject(f"{location}.pullRequest must be a positive integer")
        url = require_text(payload["url"], f"{location}.url")
        expected_url = f"https://github.com/amichne/kast/pull/{pull_request}"
        if url != expected_url:
            reject(f"{location}.url must be {expected_url}")
        head_ref_name = require_text(payload["headRefName"], f"{location}.headRefName")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", head_ref_name):
            reject(f"{location}.headRefName is not a valid branch reference")
        return cls(primary_owner, issue, pull_request, url, head_ref_name)


@dataclass(frozen=True)
class Prerequisite:
    kind: PrerequisiteKind
    requirement_id: RequirementId | None = None
    gate_id: GateId | None = None

    @classmethod
    def parse(cls, value: Any, location: str) -> Prerequisite:
        payload = require_object(value, location)
        kind = parse_enum(PrerequisiteKind, payload.get("kind"), f"{location}.kind")
        if kind is PrerequisiteKind.ALL:
            require_exact_keys(payload, {"kind"}, location)
            return cls(kind)
        require_exact_keys(payload, {"kind", "id"}, location)
        if kind is PrerequisiteKind.REQUIREMENT:
            return cls(kind, requirement_id=RequirementId.parse(payload["id"], f"{location}.id"))
        return cls(kind, gate_id=parse_enum(GateId, payload["id"], f"{location}.id"))


@dataclass(frozen=True)
class Gate:
    identifier: GateId
    owner_draft_keys: frozenset[str]
    exit_condition: str
    depends_on: GateId | None
    completion: CompletionState

    @classmethod
    def parse(cls, value: Any, location: str) -> Gate:
        payload = require_object(value, location)
        require_exact_keys(
            payload,
            {"id", "ownerDraftKeys", "exitCondition", "dependsOn", "completionState"},
            location,
        )
        identifier = parse_enum(GateId, payload["id"], f"{location}.id")
        owner_draft_key_items = tuple(
            require_text(item, f"{location}.ownerDraftKeys[{index}]")
            for index, item in enumerate(
                require_array(payload["ownerDraftKeys"], f"{location}.ownerDraftKeys")
            )
        )
        if len(set(owner_draft_key_items)) != len(owner_draft_key_items):
            reject(f"{location}.ownerDraftKeys contains duplicates")
        owner_draft_keys = frozenset(owner_draft_key_items)
        depends_on_value = payload["dependsOn"]
        depends_on = (
            None
            if depends_on_value is None
            else parse_enum(GateId, depends_on_value, f"{location}.dependsOn")
        )
        return cls(
            identifier=identifier,
            owner_draft_keys=owner_draft_keys,
            exit_condition=require_text(payload["exitCondition"], f"{location}.exitCondition"),
            depends_on=depends_on,
            completion=CompletionState.parse(payload["completionState"], f"{location}.completionState"),
        )


@dataclass(frozen=True)
class Requirement:
    identifier: RequirementId
    statement: str
    acceptance_evidence: str
    verification_methods: tuple[VerificationMethod, ...]
    prerequisites: tuple[Prerequisite, ...]
    gate: GateId
    primary_owner: PrimaryOwner
    evidence_references: tuple[EvidenceReference, ...]
    completion: CompletionState

    @classmethod
    def parse(cls, value: Any, location: str) -> Requirement:
        payload = require_object(value, location)
        require_exact_keys(
            payload,
            {
                "id",
                "requirement",
                "acceptanceEvidence",
                "verificationMethods",
                "dependsOn",
                "gate",
                "primaryOwner",
                "evidenceReferences",
                "completionState",
            },
            location,
        )
        identifier = RequirementId.parse(payload["id"], f"{location}.id")
        statement = require_text(payload["requirement"], f"{location}.requirement")
        methods = tuple(
            parse_enum(VerificationMethod, item, f"{location}.verificationMethods[{index}]")
            for index, item in enumerate(
                require_array(payload["verificationMethods"], f"{location}.verificationMethods")
            )
        )
        if not methods:
            reject(f"{location}.verificationMethods must not be empty")
        if len(set(methods)) != len(methods):
            reject(f"{location}.verificationMethods contains duplicates")
        prerequisites = tuple(
            Prerequisite.parse(item, f"{location}.dependsOn[{index}]")
            for index, item in enumerate(require_array(payload["dependsOn"], f"{location}.dependsOn"))
        )
        if len(set(prerequisites)) != len(prerequisites):
            reject(f"{location}.dependsOn contains duplicates")
        references = tuple(
            EvidenceReference.parse(item, f"{location}.evidenceReferences[{index}]")
            for index, item in enumerate(
                require_array(payload["evidenceReferences"], f"{location}.evidenceReferences")
            )
        )
        if not references:
            reject(f"{location}.evidenceReferences must not be empty")
        if len(set(references)) != len(references):
            reject(f"{location}.evidenceReferences contains duplicates")
        behavioral = any(method is not VerificationMethod.INSPECTION for method in methods)
        if behavioral and not any(reference.is_executable for reference in references):
            reject(f"{location} is behavioral and must reference executable evidence")
        completion = CompletionState.parse(payload["completionState"], f"{location}.completionState")
        if any(reference not in references for reference in completion.evidence):
            reject(f"{location}.completionState cites evidence outside evidenceReferences")
        return cls(
            identifier=identifier,
            statement=statement,
            acceptance_evidence=require_text(
                payload["acceptanceEvidence"], f"{location}.acceptanceEvidence"
            ),
            verification_methods=methods,
            prerequisites=prerequisites,
            gate=parse_enum(GateId, payload["gate"], f"{location}.gate"),
            primary_owner=PrimaryOwner.parse(payload["primaryOwner"], f"{location}.primaryOwner"),
            evidence_references=references,
            completion=completion,
        )


def parse_verification_methods(value: Any) -> None:
    records = require_array(value, "verificationMethods")
    parsed: dict[VerificationMethod, str] = {}
    for index, item in enumerate(records):
        location = f"verificationMethods[{index}]"
        payload = require_object(item, location)
        require_exact_keys(payload, {"code", "description"}, location)
        method = parse_enum(VerificationMethod, payload["code"], f"{location}.code")
        if method in parsed:
            reject(f"duplicate verification method {method.value}")
        parsed[method] = require_text(payload["description"], f"{location}.description")
    if parsed != VERIFICATION_METHOD_DESCRIPTIONS:
        reject("verificationMethods must contain the exact permanent method definitions")


def validate_gate_contract(gates: tuple[Gate, ...]) -> dict[GateId, Gate]:
    by_id: dict[GateId, Gate] = {}
    for gate in gates:
        if gate.identifier in by_id:
            reject(f"duplicate gate {gate.identifier.value}")
        by_id[gate.identifier] = gate
    if set(by_id) != set(GateId):
        reject("gates must contain G0 through G8 exactly once")
    for gate_id, gate in by_id.items():
        expected_owners = EXPECTED_GATE_OWNERS[gate_id.value]
        if gate.owner_draft_keys != expected_owners:
            reject(
                f"gate {gate_id.value} ownerDraftKeys must be {sorted(expected_owners)}, "
                f"got {sorted(gate.owner_draft_keys)}"
            )
        expected_dependency = EXPECTED_GATE_DEPENDENCIES[gate_id.value]
        actual_dependency = gate.depends_on.value if gate.depends_on else None
        if actual_dependency != expected_dependency:
            reject(f"gate {gate_id.value} must depend on {expected_dependency!r}")
        expected_exit_condition = EXPECTED_GATE_EXIT_CONDITIONS[gate_id.value]
        if gate.exit_condition != expected_exit_condition:
            reject(f"gate {gate_id.value} exitCondition does not match the normative matrix")
        if gate.completion.value is CompletionValue.COMPLETE:
            if gate.depends_on and by_id[gate.depends_on].completion.value is not CompletionValue.COMPLETE:
                reject(
                    f"gate {gate_id.value} cannot be complete while "
                    f"{gate.depends_on.value} is incomplete"
                )
    return by_id


def validate_completion_semantics(
    value: Any,
) -> tuple[tuple[str, frozenset[RequirementId]], ...]:
    payload = require_object(value, "completionSemantics")
    require_exact_keys(
        payload,
        {"sameGatePrerequisite", "atomicRequirementGroups"},
        "completionSemantics",
    )
    if (
        require_text(payload["sameGatePrerequisite"], "completionSemantics.sameGatePrerequisite")
        != SAME_GATE_PREREQUISITE_RULE
    ):
        reject(
            "completionSemantics.sameGatePrerequisite must be "
            f"{SAME_GATE_PREREQUISITE_RULE!r}"
        )
    parsed_groups: list[tuple[str, frozenset[RequirementId]]] = []
    normalized_groups: list[tuple[str, tuple[str, ...]]] = []
    for index, item in enumerate(
        require_array(
            payload["atomicRequirementGroups"],
            "completionSemantics.atomicRequirementGroups",
        )
    ):
        location = f"completionSemantics.atomicRequirementGroups[{index}]"
        group = require_object(item, location)
        require_exact_keys(group, {"primaryOwner", "requirements"}, location)
        owner = require_text(group["primaryOwner"], f"{location}.primaryOwner")
        identifiers = tuple(
            RequirementId.parse(identifier, f"{location}.requirements[{requirement_index}]")
            for requirement_index, identifier in enumerate(
                require_array(group["requirements"], f"{location}.requirements")
            )
        )
        if len(identifiers) < 2 or len(set(identifiers)) != len(identifiers):
            reject(f"{location}.requirements must contain at least two unique identifiers")
        if tuple(sorted(identifier.value for identifier in identifiers)) != tuple(
            identifier.value for identifier in identifiers
        ):
            reject(f"{location}.requirements must be sorted")
        parsed_groups.append((owner, frozenset(identifiers)))
        normalized_groups.append((owner, tuple(identifier.value for identifier in identifiers)))
    if tuple(normalized_groups) != EXPECTED_ATOMIC_COMPLETION_GROUPS:
        reject("completionSemantics.atomicRequirementGroups does not match the admitted graph")
    return tuple(parsed_groups)


def validate_admitted_deliveries(value: Any) -> dict[str, AdmittedDelivery]:
    records = require_array(value, "admittedDeliveries")
    by_owner: dict[str, AdmittedDelivery] = {}
    for index, item in enumerate(records):
        delivery = AdmittedDelivery.parse(item, f"admittedDeliveries[{index}]")
        if delivery.primary_owner in by_owner:
            reject(f"duplicate admitted delivery for {delivery.primary_owner}")
        by_owner[delivery.primary_owner] = delivery
    actual = {
        owner: {
            "issue": delivery.issue,
            "pullRequest": delivery.pull_request,
            "url": delivery.url,
            "headRefName": delivery.head_ref_name,
        }
        for owner, delivery in by_owner.items()
    }
    if actual != EXPECTED_OWNER_DELIVERIES:
        reject("admittedDeliveries does not match the reviewed delivery registry")
    return by_owner


def validate_requirement_graph(
    requirements: tuple[Requirement, ...],
    retired_ids: frozenset[RequirementId],
    gates: dict[GateId, Gate],
    atomic_groups: tuple[tuple[str, frozenset[RequirementId]], ...],
    admitted_deliveries: dict[str, AdmittedDelivery],
) -> None:
    by_id: dict[RequirementId, Requirement] = {}
    for requirement in requirements:
        if requirement.identifier in by_id:
            reject(f"duplicate requirement identifier {requirement.identifier.value}")
        by_id[requirement.identifier] = requirement

    active_values = {identifier.value for identifier in by_id}
    retired_values = {identifier.value for identifier in retired_ids}
    reused = active_values & retired_values
    if reused:
        reject(f"retired requirement identifiers were reused: {sorted(reused)}")
    permanent_values = active_values | retired_values
    if permanent_values != EXPECTED_REQUIREMENT_IDS:
        reject(
            "permanent requirement identifier set mismatch: "
            f"missing={sorted(EXPECTED_REQUIREMENT_IDS - permanent_values)} "
            f"unexpected={sorted(permanent_values - EXPECTED_REQUIREMENT_IDS)}"
        )

    expected_owners = set(EXPECTED_OWNER_REQUIREMENT_EXPRESSIONS)
    actual_owners = {requirement.primary_owner.draft_key for requirement in requirements}
    if actual_owners != expected_owners:
        reject(
            "primary owner set mismatch: "
            f"missing={sorted(expected_owners - actual_owners)} "
            f"unexpected={sorted(actual_owners - expected_owners)}"
        )

    for requirement in requirements:
        identifier = requirement.identifier.value
        expected_owner = EXPECTED_REQUIREMENT_OWNER[identifier]
        if requirement.primary_owner.draft_key != expected_owner:
            reject(
                f"{identifier} primary owner must be {expected_owner}, "
                f"got {requirement.primary_owner.draft_key}"
            )
        expected_gate = OWNER_GATE[expected_owner]
        if requirement.gate.value != expected_gate:
            reject(
                f"{identifier} must belong to {expected_gate} through {expected_owner}, "
                f"got {requirement.gate.value}"
            )
        expected_inspection = EvidenceReference(
            EvidenceKind.INSPECTION,
            requirement.primary_owner.url,
        )
        if expected_inspection not in requirement.evidence_references:
            reject(f"{identifier} must reference its exact {expected_owner} issue URL")
        expected_command = EvidenceReference(
            EvidenceKind.COMMAND,
            EXPECTED_OWNER_COMMANDS[expected_owner],
        )
        if expected_command not in requirement.evidence_references:
            reject(f"{identifier} must reference the exact {expected_owner} evidence command")
        expected_regression = EvidenceReference(
            EvidenceKind.TEST,
            EXPECTED_OWNER_REGRESSION_COMMANDS[expected_owner],
        )
        if expected_regression not in requirement.evidence_references:
            reject(f"{identifier} must reference the exact {expected_owner} regression command")
        if expected_owner == "KPS-01":
            expected_boundary_test = EvidenceReference(
                EvidenceKind.TEST,
                KPS01_BOUNDARY_TEST_COMMAND,
            )
            if expected_boundary_test not in requirement.evidence_references:
                reject(f"{identifier} must reference the exact KPS-01 boundary test command")
        for prerequisite in requirement.prerequisites:
            if prerequisite.kind is PrerequisiteKind.REQUIREMENT:
                assert prerequisite.requirement_id is not None
                if prerequisite.requirement_id == requirement.identifier:
                    reject(f"{requirement.identifier.value} cannot depend on itself")
                if prerequisite.requirement_id not in by_id:
                    reject(
                        f"{requirement.identifier.value} depends on missing active requirement "
                        f"{prerequisite.requirement_id.value}"
                    )

    for owner, group in atomic_groups:
        members = tuple(by_id[identifier] for identifier in sorted(group))
        if any(member.primary_owner.draft_key != owner for member in members):
            reject(
                f"atomic completion group {[identifier.value for identifier in sorted(group)]} "
                f"must have primary owner {owner}"
            )
        if len({member.completion.value for member in members}) != 1:
            reject(
                f"atomic completion group {[identifier.value for identifier in sorted(group)]} "
                "must transition together"
            )

    for requirement in requirements:
        if requirement.completion.value is not CompletionValue.COMPLETE:
            continue
        for prerequisite in requirement.prerequisites:
            if prerequisite.kind is PrerequisiteKind.REQUIREMENT:
                assert prerequisite.requirement_id is not None
                if by_id[prerequisite.requirement_id].completion.value is not CompletionValue.COMPLETE:
                    reject(
                        f"{requirement.identifier.value} cannot be complete while prerequisite "
                        f"{prerequisite.requirement_id.value} is incomplete"
                    )
            elif prerequisite.kind is PrerequisiteKind.GATE:
                assert prerequisite.gate_id is not None
                if prerequisite.gate_id == requirement.gate:
                    incomplete = sorted(
                        candidate.identifier.value
                        for candidate in requirements
                        if candidate.gate == requirement.gate
                        and candidate.identifier != requirement.identifier
                        and candidate.completion.value is not CompletionValue.COMPLETE
                    )
                    if incomplete:
                        reject(
                            f"{requirement.identifier.value} cannot be complete while other "
                            f"requirements in gate {requirement.gate.value} are incomplete: "
                            f"{incomplete}"
                        )
                elif gates[prerequisite.gate_id].completion.value is not CompletionValue.COMPLETE:
                    reject(
                        f"{requirement.identifier.value} cannot be complete while prerequisite "
                        f"gate {prerequisite.gate_id.value} is incomplete"
                    )
            else:
                incomplete = sorted(
                    candidate.identifier.value
                    for candidate in requirements
                    if candidate.identifier != requirement.identifier
                    and candidate.completion.value is not CompletionValue.COMPLETE
                )
                if incomplete:
                    reject(
                        f"{requirement.identifier.value} cannot be complete while requirements "
                        f"remain incomplete: {incomplete}"
                    )

        expected_command = EvidenceReference(
            EvidenceKind.COMMAND,
            EXPECTED_OWNER_COMMANDS[requirement.primary_owner.draft_key],
        )
        if expected_command not in requirement.completion.evidence:
            reject(
                f"{requirement.identifier.value} completion must cite the exact "
                f"{requirement.primary_owner.draft_key} evidence command"
            )
        delivery = admitted_deliveries.get(requirement.primary_owner.draft_key)
        expected_delivery = (
            None
            if delivery is None
            else EvidenceReference(EvidenceKind.DELIVERY, delivery.url)
        )
        if expected_delivery is None or expected_delivery not in requirement.completion.evidence:
            reject(
                f"{requirement.identifier.value} completion must cite the admitted "
                f"{requirement.primary_owner.draft_key} delivery"
            )
        expected_regression = EvidenceReference(
            EvidenceKind.TEST,
            EXPECTED_OWNER_REGRESSION_COMMANDS[requirement.primary_owner.draft_key],
        )
        if expected_regression not in requirement.completion.evidence:
            reject(
                f"{requirement.identifier.value} completion must cite the exact "
                f"{requirement.primary_owner.draft_key} regression command"
            )
        if requirement.primary_owner.draft_key == "KPS-01":
            expected_boundary_test = EvidenceReference(
                EvidenceKind.TEST,
                KPS01_BOUNDARY_TEST_COMMAND,
            )
            if expected_boundary_test not in requirement.completion.evidence:
                reject(
                    f"{requirement.identifier.value} completion must cite the exact "
                    "KPS-01 boundary test command"
                )

    for gate in gates.values():
        if gate.completion.value is not CompletionValue.COMPLETE:
            continue
        incomplete = sorted(
            requirement.identifier.value
            for requirement in requirements
            if requirement.gate == gate.identifier
            and requirement.completion.value is not CompletionValue.COMPLETE
        )
        if incomplete:
            reject(f"gate {gate.identifier.value} cannot be complete: incomplete={incomplete}")


def unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    for key, value in pairs:
        if key in payload:
            reject(f"duplicate JSON object key {key!r}")
        payload[key] = value
    return payload


def matrix_sha256(requirements: tuple[Requirement, ...]) -> str:
    rows: list[dict[str, Any]] = []
    for requirement in sorted(requirements, key=lambda item: item.identifier.value):
        prerequisites: list[dict[str, str]] = []
        for prerequisite in requirement.prerequisites:
            if prerequisite.kind is PrerequisiteKind.ALL:
                prerequisites.append({"kind": "all"})
            elif prerequisite.kind is PrerequisiteKind.REQUIREMENT:
                assert prerequisite.requirement_id is not None
                prerequisites.append(
                    {"kind": "requirement", "id": prerequisite.requirement_id.value}
                )
            else:
                assert prerequisite.gate_id is not None
                prerequisites.append({"kind": "gate", "id": prerequisite.gate_id.value})
        rows.append(
            {
                "id": requirement.identifier.value,
                "requirement": requirement.statement,
                "acceptanceEvidence": requirement.acceptance_evidence,
                "verificationMethods": [method.value for method in requirement.verification_methods],
                "dependsOn": prerequisites,
            }
        )
    canonical = json.dumps(
        rows,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def load_payload(path: Path) -> dict[str, Any]:
    if not path.is_file():
        display = path.relative_to(PROJECT_ROOT) if path.is_relative_to(PROJECT_ROOT) else path
        reject(f"requirement ledger not found: {display}")
    try:
        with path.open(encoding="utf-8") as handle:
            value = json.load(handle, object_pairs_hook=unique_json_object)
    except json.JSONDecodeError as error:
        reject(f"{path} is not valid JSON: {error}")
    return require_object(value, "ledger")


def validate_payload(payload: dict[str, Any]) -> tuple[int, int, int]:
    require_exact_keys(
        payload,
        {
            "schemaVersion",
            "source",
            "verificationMethods",
            "gates",
            "completionSemantics",
            "admittedDeliveries",
            "retiredRequirementIds",
            "requirements",
        },
        "ledger",
    )
    if type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != SCHEMA_VERSION:
        reject(f"schemaVersion must be the integer {SCHEMA_VERSION}")

    source = require_object(payload["source"], "source")
    require_exact_keys(source, {"name", "status", "sha256", "matrixSha256"}, "source")
    if require_text(source["name"], "source.name") != "Kast Projection Spec.md":
        reject("source.name must be 'Kast Projection Spec.md'")
    if require_text(source["status"], "source.status") != "Normative reduction":
        reject("source.status must be 'Normative reduction'")
    if require_text(source["sha256"], "source.sha256") != EXPECTED_SOURCE_SHA256:
        reject("source.sha256 does not match the admitted normative matrix")
    if require_text(source["matrixSha256"], "source.matrixSha256") != EXPECTED_MATRIX_SHA256:
        reject("source.matrixSha256 does not match the admitted normalized rows")

    parse_verification_methods(payload["verificationMethods"])
    gates = tuple(
        Gate.parse(item, f"gates[{index}]")
        for index, item in enumerate(require_array(payload["gates"], "gates"))
    )
    gates_by_id = validate_gate_contract(gates)
    atomic_groups = validate_completion_semantics(payload["completionSemantics"])
    admitted_deliveries = validate_admitted_deliveries(payload["admittedDeliveries"])

    retired_ids = tuple(
        RequirementId.parse(item, f"retiredRequirementIds[{index}]")
        for index, item in enumerate(
            require_array(payload["retiredRequirementIds"], "retiredRequirementIds")
        )
    )
    if len(set(retired_ids)) != len(retired_ids):
        reject("retiredRequirementIds contains duplicates")

    requirements = tuple(
        Requirement.parse(item, f"requirements[{index}]")
        for index, item in enumerate(require_array(payload["requirements"], "requirements"))
    )
    validate_requirement_graph(
        requirements,
        frozenset(retired_ids),
        gates_by_id,
        atomic_groups,
        admitted_deliveries,
    )
    actual_matrix_sha256 = matrix_sha256(requirements)
    if actual_matrix_sha256 != EXPECTED_MATRIX_SHA256:
        reject(
            "normalized requirement matrix digest mismatch: "
            f"expected {EXPECTED_MATRIX_SHA256}, got {actual_matrix_sha256}"
        )
    return len(requirements), len(retired_ids), len(gates)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    try:
        active_count, retired_count, gate_count = validate_payload(load_payload(args.ledger))
    except LedgerError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    print(
        f"verified {active_count + retired_count} permanent requirement identifiers "
        f"({active_count} active, {retired_count} retired); "
        f"{gate_count} gates; completion states valid"
    )


if __name__ == "__main__":
    main()
