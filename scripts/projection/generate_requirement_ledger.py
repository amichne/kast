#!/usr/bin/env python3
"""Generate the checked-in projection ledger from its admitted source and issue owners."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn

from verify_requirement_ledger import (
    EXPECTED_ATOMIC_COMPLETION_GROUPS,
    EXPECTED_CATEGORY_COUNTS,
    EXPECTED_GATE_DEPENDENCIES,
    EXPECTED_GATE_EXIT_CONDITIONS,
    EXPECTED_GATE_OWNERS,
    EXPECTED_MATRIX_SHA256,
    EXPECTED_OWNER_DELIVERIES,
    EXPECTED_REQUIREMENT_IDS,
    EXPECTED_SOURCE_SHA256,
    KPS01_BOUNDARY_TEST_COMMAND,
    OWNER_GATE,
    SAME_GATE_PREREQUISITE_RULE,
    SCHEMA_VERSION,
    VERIFICATION_METHOD_DESCRIPTIONS,
    load_payload,
    validate_payload,
)


REQUIREMENT_ROW = re.compile(
    r"^\| (?P<id>[A-Z]{2,3}-[0-9]{3}) "
    r"\| (?P<requirement>.*?) "
    r"\| (?P<evidence>.*?) "
    r"\| (?P<verification>.*?) "
    r"\| (?P<depends_on>.*?) \|$"
)
PRIMARY_REQUIREMENTS = re.compile(r"^Primary requirements: (?P<requirements>.+)$", re.MULTILINE)
DRAFT_KEY = re.compile(r"Draft key: `(?P<draft_key>KPS-[0-9]{2})`")
RANGE = re.compile(
    r"^(?P<start_category>[A-Z]{2,3})-(?P<start>[0-9]{3}) "
    r"through (?P<end_category>[A-Z]{2,3})-(?P<end>[0-9]{3})$"
)

class GenerationError(ValueError):
    """The admitted source or owner graph cannot produce one exact ledger."""


def reject(message: str) -> NoReturn:
    raise GenerationError(message)


@dataclass(frozen=True)
class SourceRequirement:
    identifier: str
    requirement: str
    acceptance_evidence: str
    verification_methods: tuple[str, ...]
    depends_on: tuple[dict[str, str], ...]


@dataclass(frozen=True)
class IssueOwner:
    draft_key: str
    number: int
    url: str
    evidence_command: str
    regression_command: str


def expand_requirement_token(token: str) -> tuple[str, ...]:
    token = token.strip().rstrip(".")
    match = RANGE.fullmatch(token)
    if match:
        start_category = match.group("start_category")
        end_category = match.group("end_category")
        if start_category != end_category:
            reject(f"requirement range crosses categories: {token}")
        start = int(match.group("start"))
        end = int(match.group("end"))
        if start > end:
            reject(f"requirement range is reversed: {token}")
        return tuple(f"{start_category}-{number:03d}" for number in range(start, end + 1))
    if token not in EXPECTED_REQUIREMENT_IDS:
        reject(f"unknown requirement identifier in owner expression: {token}")
    return (token,)


def parse_prerequisites(raw: str) -> tuple[dict[str, str], ...]:
    if raw == "None":
        return ()
    if raw == "All requirements":
        return ({"kind": "all"},)
    parsed: list[dict[str, str]] = []
    for token in raw.split(","):
        token = token.strip()
        if re.fullmatch(r"G[0-8]", token):
            parsed.append({"kind": "gate", "id": token})
            continue
        for identifier in expand_requirement_token(token):
            parsed.append({"kind": "requirement", "id": identifier})
    return tuple(parsed)


def parse_source(path: Path) -> tuple[SourceRequirement, ...]:
    source_bytes = path.read_bytes()
    digest = hashlib.sha256(source_bytes).hexdigest()
    if digest != EXPECTED_SOURCE_SHA256:
        reject(f"source digest changed: expected {EXPECTED_SOURCE_SHA256}, got {digest}")
    rows: list[SourceRequirement] = []
    for line in source_bytes.decode("utf-8").splitlines():
        match = REQUIREMENT_ROW.fullmatch(line)
        if not match:
            continue
        rows.append(
            SourceRequirement(
                identifier=match.group("id"),
                requirement=match.group("requirement"),
                acceptance_evidence=match.group("evidence"),
                verification_methods=tuple(
                    method.strip() for method in match.group("verification").split(",")
                ),
                depends_on=parse_prerequisites(match.group("depends_on")),
            )
        )
    identifiers = [row.identifier for row in rows]
    if len(identifiers) != len(set(identifiers)):
        reject("source contains duplicate requirement rows")
    if set(identifiers) != EXPECTED_REQUIREMENT_IDS:
        reject(
            "source requirement set mismatch: "
            f"missing={sorted(EXPECTED_REQUIREMENT_IDS - set(identifiers))} "
            f"unexpected={sorted(set(identifiers) - EXPECTED_REQUIREMENT_IDS)}"
        )
    return tuple(rows)


def fetch_child_issues(repository: str, parent_issue: int) -> tuple[dict[str, Any], ...]:
    owner, name = repository.split("/", 1)
    query = """
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    issue(number: $number) {
      subIssues(first: 100) {
        nodes { number url body }
      }
    }
  }
}
"""
    result = subprocess.run(
        [
            "gh",
            "api",
            "graphql",
            "-f",
            f"query={query}",
            "-F",
            f"owner={owner}",
            "-F",
            f"name={name}",
            "-F",
            f"number={parent_issue}",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    payload = json.loads(result.stdout)
    nodes = payload["data"]["repository"]["issue"]["subIssues"]["nodes"]
    if len(nodes) != 40:
        reject(f"parent issue must have 40 child owners, got {len(nodes)}")
    return tuple(nodes)


def verify_admitted_deliveries(repository: str) -> None:
    for primary_owner, expected in EXPECTED_OWNER_DELIVERIES.items():
        result = subprocess.run(
            [
                "gh",
                "pr",
                "view",
                str(expected["pullRequest"]),
                "--repo",
                repository,
                "--json",
                "number,url,headRefName,body",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        actual = json.loads(result.stdout)
        for field in ("number", "url", "headRefName"):
            expected_field = "pullRequest" if field == "number" else field
            if actual[field] != expected[expected_field]:
                reject(
                    f"{primary_owner} delivery {field} must be "
                    f"{expected[expected_field]!r}, got {actual[field]!r}"
                )
        issue = expected["issue"]
        if not re.search(rf"(?<![0-9])#{issue}(?![0-9])", actual["body"]):
            reject(f"{primary_owner} delivery does not link issue #{issue}")


def evidence_command(issue_number: int, body: str) -> str:
    if issue_number == 508:
        return "python3 scripts/projection/verify_requirement_ledger.py"
    for line in body.splitlines():
        if not line.startswith("| RED |"):
            continue
        commands = re.findall(r"`([^`]+)`", line)
        if commands:
            return commands[0]
    reject(f"issue {issue_number} has no exact RED command")


def regression_command(issue_number: int, body: str) -> str:
    for line in body.splitlines():
        if not line.startswith("| Regression |"):
            continue
        commands = re.findall(r"`([^`]+)`", line)
        if commands:
            return commands[0]
    reject(f"issue {issue_number} has no exact Regression command")


def parse_owners(nodes: tuple[dict[str, Any], ...]) -> dict[str, IssueOwner]:
    owners: dict[str, IssueOwner] = {}
    for node in nodes:
        body = node["body"]
        draft_match = DRAFT_KEY.search(body)
        primary_match = PRIMARY_REQUIREMENTS.search(body)
        if not draft_match or not primary_match:
            reject(f"issue {node['number']} lacks its draft key or primary requirements")
        owner = IssueOwner(
            draft_key=draft_match.group("draft_key"),
            number=node["number"],
            url=node["url"],
            evidence_command=evidence_command(node["number"], body),
            regression_command=regression_command(node["number"], body),
        )
        for token in primary_match.group("requirements").split(";"):
            for identifier in expand_requirement_token(token):
                if identifier in owners:
                    reject(
                        f"{identifier} has duplicate primary owners "
                        f"{owners[identifier].number} and {owner.number}"
                    )
                owners[identifier] = owner
    if set(owners) != EXPECTED_REQUIREMENT_IDS:
        reject(
            "primary owner set mismatch: "
            f"missing={sorted(EXPECTED_REQUIREMENT_IDS - set(owners))} "
            f"unexpected={sorted(set(owners) - EXPECTED_REQUIREMENT_IDS)}"
        )
    return owners


def make_evidence_references(owner: IssueOwner) -> list[dict[str, str]]:
    references = [
        {"kind": "inspection", "reference": owner.url},
        {"kind": "command", "reference": owner.evidence_command},
        {"kind": "test", "reference": owner.regression_command},
    ]
    if owner.draft_key == "KPS-01":
        references.append({"kind": "test", "reference": KPS01_BOUNDARY_TEST_COMMAND})
    return references


def make_ledger(
    source_rows: tuple[SourceRequirement, ...], owners: dict[str, IssueOwner]
) -> dict[str, Any]:
    gates = [
        {
            "id": gate,
            "ownerDraftKeys": sorted(EXPECTED_GATE_OWNERS[gate]),
            "exitCondition": EXPECTED_GATE_EXIT_CONDITIONS[gate],
            "dependsOn": EXPECTED_GATE_DEPENDENCIES[gate],
            "completionState": {"state": "incomplete", "evidence": []},
        }
        for gate in EXPECTED_GATE_DEPENDENCIES
    ]
    requirements: list[dict[str, Any]] = []
    for row in source_rows:
        owner = owners[row.identifier]
        references = make_evidence_references(owner)
        requirements.append(
            {
                "id": row.identifier,
                "requirement": row.requirement,
                "acceptanceEvidence": row.acceptance_evidence,
                "verificationMethods": list(row.verification_methods),
                "dependsOn": list(row.depends_on),
                "gate": OWNER_GATE[owner.draft_key],
                "primaryOwner": {
                    "draftKey": owner.draft_key,
                    "issue": owner.number,
                    "url": owner.url,
                },
                "evidenceReferences": references,
                "completionState": {"state": "incomplete", "evidence": []},
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "name": "Kast Projection Spec.md",
            "status": "Normative reduction",
            "sha256": EXPECTED_SOURCE_SHA256,
            "matrixSha256": EXPECTED_MATRIX_SHA256,
        },
        "verificationMethods": [
            {"code": method.value, "description": description}
            for method, description in VERIFICATION_METHOD_DESCRIPTIONS.items()
        ],
        "gates": gates,
        "completionSemantics": {
            "sameGatePrerequisite": SAME_GATE_PREREQUISITE_RULE,
            "atomicRequirementGroups": [
                {
                    "primaryOwner": owner,
                    "requirements": list(group_requirements),
                }
                for owner, group_requirements in EXPECTED_ATOMIC_COMPLETION_GROUPS
            ],
        },
        "admittedDeliveries": [
            {
                "primaryOwner": owner,
                **delivery,
            }
            for owner, delivery in sorted(EXPECTED_OWNER_DELIVERIES.items())
        ],
        "retiredRequirementIds": [],
        "requirements": requirements,
    }


def preserve_completion_state(
    generated: dict[str, Any],
    previous: dict[str, Any],
) -> None:
    validate_payload(generated)
    validate_payload(previous)
    generated_requirements = {
        requirement["id"]: requirement for requirement in generated["requirements"]
    }
    previous_requirements = {
        requirement["id"]: requirement for requirement in previous["requirements"]
    }
    if set(generated_requirements) != set(previous_requirements):
        reject("cannot preserve completion state across a requirement set change")
    admitted_urls = {
        delivery["url"] for delivery in generated["admittedDeliveries"]
    }
    for identifier, generated_requirement in generated_requirements.items():
        previous_requirement = previous_requirements[identifier]
        generated_requirement["evidenceReferences"].extend(
            copy.deepcopy(reference)
            for reference in previous_requirement["evidenceReferences"]
            if reference["kind"] == "delivery"
            and reference["reference"] in admitted_urls
            and reference not in generated_requirement["evidenceReferences"]
        )
        generated_requirement["completionState"] = copy.deepcopy(
            previous_requirement["completionState"]
        )
    generated_gates = {gate["id"]: gate for gate in generated["gates"]}
    previous_gates = {gate["id"]: gate for gate in previous["gates"]}
    for identifier, generated_gate in generated_gates.items():
        generated_gate["completionState"] = copy.deepcopy(
            previous_gates[identifier]["completionState"]
        )
    validate_payload(generated)


def complete_owner(ledger: dict[str, Any], primary_owner: str) -> None:
    validate_payload(ledger)
    delivery = EXPECTED_OWNER_DELIVERIES.get(primary_owner)
    if delivery is None:
        reject(f"{primary_owner} has no admitted delivery")
    delivery_reference = {
        "kind": "delivery",
        "reference": delivery["url"],
    }
    owned = [
        requirement
        for requirement in ledger["requirements"]
        if requirement["primaryOwner"]["draftKey"] == primary_owner
    ]
    if not owned:
        reject(f"{primary_owner} owns no active requirements")
    for requirement in owned:
        if delivery_reference not in requirement["evidenceReferences"]:
            requirement["evidenceReferences"].append(copy.deepcopy(delivery_reference))
        requirement["completionState"] = {
            "state": "complete",
            "evidence": [
                copy.deepcopy(reference)
                for reference in requirement["evidenceReferences"]
                if reference["kind"] in {"command", "test", "delivery"}
            ],
        }
    validate_payload(ledger)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--repository", default="amichne/kast")
    parser.add_argument("--parent-issue", type=int, default=548)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--complete-owner", action="append", default=[])
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    verify_admitted_deliveries(args.repository)
    ledger = make_ledger(
        parse_source(args.source),
        parse_owners(fetch_child_issues(args.repository, args.parent_issue)),
    )
    if args.output.is_file():
        preserve_completion_state(ledger, load_payload(args.output))
    for primary_owner in args.complete_owner:
        complete_owner(ledger, primary_owner)
    validate_payload(ledger)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(ledger, indent=2) + "\n", encoding="utf-8")
    print(
        f"wrote {args.output} with {len(ledger['requirements'])} requirements "
        f"from {len(EXPECTED_CATEGORY_COUNTS)} categories"
    )


if __name__ == "__main__":
    main()
