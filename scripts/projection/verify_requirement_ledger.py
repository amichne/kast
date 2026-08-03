#!/usr/bin/env python3
"""Validate the permanent projection requirement ledger."""

from __future__ import annotations

import argparse
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
REQUIREMENT_ID_PATTERN = re.compile(r"^[A-Z]{2,3}-[0-9]{3}$")
NORMATIVE_WORD_PATTERN = re.compile(r"\b(?:MUST(?: NOT)?|SHOULD(?: NOT)?|MAY)\b")

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

EXPECTED_GATE_CATEGORIES = {
    "G0": frozenset({"CTL", "SYS", "INP", "BND", "NGL"}),
    "G1": frozenset({"BAS", "AUT", "IDN", "EVD", "OUT", "FLR"}),
    "G2": frozenset({"INV"}),
    "G3": frozenset({"FLW"}),
    "G4": frozenset({"PLN"}),
    "G5": frozenset({"MUT"}),
    "G6": frozenset({"VER"}),
    "G7": frozenset({"SCL", "SEC", "OBS"}),
    "G8": frozenset({"DEM", "NS"}),
}

CATEGORY_GATE = {
    category: gate
    for gate, categories in EXPECTED_GATE_CATEGORIES.items()
    for category in categories
}

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
        return cls(
            kind=parse_enum(EvidenceKind, payload["kind"], f"{location}.kind"),
            reference=require_text(payload["reference"], f"{location}.reference"),
        )

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
        expected_url = f"https://github.com/amichne/kast/issues/{issue}"
        if url != expected_url:
            reject(f"{location}.url must be {expected_url}")
        return cls(draft_key, issue, url)


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
    required_categories: frozenset[str]
    exit_condition: str
    depends_on: GateId | None
    completion: CompletionState

    @classmethod
    def parse(cls, value: Any, location: str) -> Gate:
        payload = require_object(value, location)
        require_exact_keys(
            payload,
            {"id", "requiredCategories", "exitCondition", "dependsOn", "completionState"},
            location,
        )
        identifier = parse_enum(GateId, payload["id"], f"{location}.id")
        categories = frozenset(
            require_text(item, f"{location}.requiredCategories[{index}]")
            for index, item in enumerate(
                require_array(payload["requiredCategories"], f"{location}.requiredCategories")
            )
        )
        depends_on_value = payload["dependsOn"]
        depends_on = (
            None
            if depends_on_value is None
            else parse_enum(GateId, depends_on_value, f"{location}.dependsOn")
        )
        return cls(
            identifier=identifier,
            required_categories=categories,
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
        if not NORMATIVE_WORD_PATTERN.search(statement):
            reject(f"{location}.requirement must contain a normative requirement word")
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
        expected_categories = EXPECTED_GATE_CATEGORIES[gate_id.value]
        if gate.required_categories != expected_categories:
            reject(
                f"gate {gate_id.value} categories must be {sorted(expected_categories)}, "
                f"got {sorted(gate.required_categories)}"
            )
        expected_dependency = EXPECTED_GATE_DEPENDENCIES[gate_id.value]
        actual_dependency = gate.depends_on.value if gate.depends_on else None
        if actual_dependency != expected_dependency:
            reject(f"gate {gate_id.value} must depend on {expected_dependency!r}")
        if gate.completion.value is CompletionValue.COMPLETE:
            if gate.depends_on and by_id[gate.depends_on].completion.value is not CompletionValue.COMPLETE:
                reject(
                    f"gate {gate_id.value} cannot be complete while "
                    f"{gate.depends_on.value} is incomplete"
                )
    return by_id


def validate_requirement_graph(
    requirements: tuple[Requirement, ...],
    retired_ids: frozenset[RequirementId],
    gates: dict[GateId, Gate],
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

    for requirement in requirements:
        expected_gate = CATEGORY_GATE[requirement.identifier.category]
        if requirement.gate.value != expected_gate:
            reject(
                f"{requirement.identifier.value} must belong to {expected_gate}, "
                f"got {requirement.gate.value}"
            )
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

    visiting: set[RequirementId] = set()
    visited: set[RequirementId] = set()

    def visit(identifier: RequirementId) -> None:
        if identifier in visiting:
            reject(f"requirement dependency cycle reaches {identifier.value}")
        if identifier in visited:
            return
        visiting.add(identifier)
        for prerequisite in by_id[identifier].prerequisites:
            if prerequisite.kind is PrerequisiteKind.REQUIREMENT:
                assert prerequisite.requirement_id is not None
                visit(prerequisite.requirement_id)
        visiting.remove(identifier)
        visited.add(identifier)

    for identifier in sorted(by_id):
        visit(identifier)

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
                if gates[prerequisite.gate_id].completion.value is not CompletionValue.COMPLETE:
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
            "retiredRequirementIds",
            "requirements",
        },
        "ledger",
    )
    if payload["schemaVersion"] != SCHEMA_VERSION:
        reject(f"schemaVersion must be {SCHEMA_VERSION}")

    source = require_object(payload["source"], "source")
    require_exact_keys(source, {"name", "status", "sha256"}, "source")
    if require_text(source["name"], "source.name") != "Kast Projection Spec.md":
        reject("source.name must be 'Kast Projection Spec.md'")
    if require_text(source["status"], "source.status") != "Normative reduction":
        reject("source.status must be 'Normative reduction'")
    if require_text(source["sha256"], "source.sha256") != EXPECTED_SOURCE_SHA256:
        reject("source.sha256 does not match the admitted normative matrix")

    parse_verification_methods(payload["verificationMethods"])
    gates = tuple(
        Gate.parse(item, f"gates[{index}]")
        for index, item in enumerate(require_array(payload["gates"], "gates"))
    )
    gates_by_id = validate_gate_contract(gates)

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
    validate_requirement_graph(requirements, frozenset(retired_ids), gates_by_id)
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
