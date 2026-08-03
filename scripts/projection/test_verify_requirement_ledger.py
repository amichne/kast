#!/usr/bin/env python3
"""Public-boundary tests for the projection requirement ledger validator."""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_requirement_ledger as validator


PROJECT_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = PROJECT_ROOT / "scripts/projection/verify_requirement_ledger.py"
LEDGER = PROJECT_ROOT / "docs/internal/projection/requirement-ledger.json"


class RequirementLedgerValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.valid_payload = json.loads(LEDGER.read_text(encoding="utf-8"))

    def run_validator(self, payload: dict | None = None) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        if payload is None:
            return subprocess.run(
                [sys.executable, str(VALIDATOR)],
                cwd=PROJECT_ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
        with tempfile.TemporaryDirectory(prefix="kast-projection-ledger-") as directory:
            ledger = Path(directory) / "requirement-ledger.json"
            ledger.write_text(json.dumps(payload), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(VALIDATOR), "--ledger", str(ledger)],
                cwd=PROJECT_ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )

    def requirement(self, payload: dict, identifier: str) -> dict:
        return next(item for item in payload["requirements"] if item["id"] == identifier)

    def expected_gate(self, requirement: dict) -> str:
        owner_number = int(requirement["primaryOwner"]["draftKey"].removeprefix("KPS-"))
        for gate, first_owner, last_owner in (
            ("G0", 1, 8),
            ("G1", 9, 16),
            ("G2", 17, 20),
            ("G3", 21, 23),
            ("G4", 24, 26),
            ("G5", 27, 29),
            ("G6", 30, 33),
            ("G7", 34, 37),
            ("G8", 38, 40),
        ):
            if first_owner <= owner_number <= last_owner:
                return gate
        self.fail(f"owner is outside the permanent gate map: {owner_number}")

    def mark_complete(
        self,
        requirement: dict,
        delivery_url: str = "https://github.com/amichne/kast/pull/549",
    ) -> None:
        delivery = {
            "kind": "delivery",
            "reference": delivery_url,
        }
        if delivery not in requirement["evidenceReferences"]:
            requirement["evidenceReferences"].append(delivery)
        completion_evidence = [
            reference
            for reference in requirement["evidenceReferences"]
            if reference["kind"] in {"command", "test", "delivery"}
        ]
        requirement["completionState"] = {
            "state": "complete",
            "evidence": completion_evidence,
        }

    def validate_with_test_deliveries(self, payload: dict, owners: set[str]) -> None:
        registry = {}
        records = []
        for owner in sorted(owners):
            owner_number = int(owner.removeprefix("KPS-"))
            delivery = {
                "issue": 507 + owner_number,
                "pullRequest": 1000 + owner_number,
                "url": f"https://github.com/amichne/kast/pull/{1000 + owner_number}",
                "headRefName": f"test/{owner.lower()}",
            }
            registry[owner] = delivery
            records.append({"primaryOwner": owner, **delivery})
        payload["admittedDeliveries"] = records
        with patch.object(validator, "EXPECTED_OWNER_DELIVERIES", registry):
            validator.validate_payload(payload)

    def test_checked_in_ledger_parses_every_permanent_identifier(self) -> None:
        result = self.run_validator()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified 229 permanent requirement identifiers", result.stdout)

    def test_duplicate_active_identifier_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        payload["requirements"].append(copy.deepcopy(payload["requirements"][0]))

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("duplicate requirement identifier CTL-001", result.stderr)

    def test_retired_identifier_reuse_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        payload["retiredRequirementIds"] = ["CTL-001"]

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("retired requirement identifiers were reused", result.stderr)

    def test_missing_permanent_identifier_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        payload["requirements"] = [
            item for item in payload["requirements"] if item["id"] != "NS-001"
        ]

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("permanent requirement identifier set mismatch", result.stderr)
        self.assertIn("NS-001", result.stderr)

    def test_documentation_only_behavioral_evidence_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        requirement["evidenceReferences"] = [
            {"kind": "inspection", "reference": requirement["primaryOwner"]["url"]}
        ]

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("is behavioral and must reference executable evidence", result.stderr)

    def test_normative_row_drift_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        requirement["requirement"] = requirement["requirement"].replace("MUST", "MAY", 1)

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("normalized requirement matrix digest mismatch", result.stderr)

    def test_primary_owner_sequence_is_rejected_when_issue_does_not_match_key(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        requirement["primaryOwner"]["issue"] = 510
        requirement["primaryOwner"]["url"] = "https://github.com/amichne/kast/issues/510"

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("issue must be 509 for KPS-02", result.stderr)

    def test_exact_requirement_owner_swap_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        first = self.requirement(payload, "SYS-001")
        second = self.requirement(payload, "SYS-006")
        first["primaryOwner"], second["primaryOwner"] = (
            second["primaryOwner"],
            first["primaryOwner"],
        )
        first["evidenceReferences"], second["evidenceReferences"] = (
            second["evidenceReferences"],
            first["evidenceReferences"],
        )

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SYS-001 primary owner must be KPS-02", result.stderr)

    def test_fabricated_owner_command_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        command = next(
            reference
            for reference in requirement["evidenceReferences"]
            if reference["kind"] == "command"
        )
        command["reference"] = "not-a-real-command"

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SYS-001 must reference the exact KPS-02 evidence command", result.stderr)

    def test_missing_exact_owner_regression_command_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        requirement["evidenceReferences"] = [
            reference
            for reference in requirement["evidenceReferences"]
            if reference
            != {"kind": "test", "reference": "./gradlew :analysis-api:test"}
        ]

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SYS-001 must reference the exact KPS-02 regression command", result.stderr)

    def test_fabricated_delivery_reference_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "CTL-001")
        self.mark_complete(requirement, "https://github.com/amichne/kast/pull/999")

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("CTL-001 completion must cite the admitted KPS-01 delivery", result.stderr)

    def test_completion_without_delivery_evidence_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "SYS-001")
        command = next(
            reference
            for reference in requirement["evidenceReferences"]
            if reference["kind"] == "command"
        )
        requirement["completionState"] = {"state": "complete", "evidence": [command]}

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SYS-001 completion must cite the admitted KPS-02 delivery", result.stderr)

    def test_checked_in_completion_starts_incomplete(self) -> None:
        requirement_states = {
            item["completionState"]["state"] for item in self.valid_payload["requirements"]
        }
        gate_states = {item["completionState"]["state"] for item in self.valid_payload["gates"]}

        self.assertEqual(requirement_states, {"incomplete"})
        self.assertEqual(gate_states, {"incomplete"})

    def test_boolean_schema_version_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        payload["schemaVersion"] = True

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("schemaVersion must be the integer 1", result.stderr)

    def test_duplicate_gate_owner_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        gate = next(item for item in payload["gates"] if item["id"] == "G0")
        gate["ownerDraftKeys"].append(gate["ownerDraftKeys"][0])

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("gates[0].ownerDraftKeys contains duplicates", result.stderr)

    def test_owner_gate_assignment_and_same_gate_completion_are_accepted(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        completed_owners = set()
        for requirement in payload["requirements"]:
            requirement["gate"] = self.expected_gate(requirement)
            if requirement["gate"] <= "G6":
                owner = requirement["primaryOwner"]["draftKey"]
                owner_number = int(owner.removeprefix("KPS-"))
                self.mark_complete(
                    requirement,
                    f"https://github.com/amichne/kast/pull/{1000 + owner_number}",
                )
                completed_owners.add(owner)
        for gate in payload["gates"]:
            if gate["id"] < "G6":
                gate["completionState"] = {
                    "state": "complete",
                    "evidence": [
                        {
                            "kind": "delivery",
                            "reference": "https://github.com/amichne/kast/pull/999",
                        }
                    ],
                }

        self.validate_with_test_deliveries(payload, completed_owners)

    def test_same_gate_completion_rejects_another_incomplete_requirement(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "OUT-007")
        self.mark_complete(requirement)

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn(
            "OUT-007 cannot be complete while other requirements in gate G6 are incomplete",
            result.stderr,
        )

    def test_atomic_completion_group_cannot_partially_complete(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "OUT-012")
        requirement["gate"] = "G7"
        self.mark_complete(requirement)

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn(
            "atomic completion group ['OUT-012', 'SEC-006'] must transition together",
            result.stderr,
        )

    def test_atomic_completion_group_can_complete_together(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        for identifier in ("OUT-012", "SEC-006"):
            requirement = self.requirement(payload, identifier)
            self.mark_complete(requirement, "https://github.com/amichne/kast/pull/1036")

        self.validate_with_test_deliveries(payload, {"KPS-36"})

    def test_completion_before_requirement_prerequisite_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        requirement = self.requirement(payload, "INP-002")
        command = next(
            reference
            for reference in requirement["evidenceReferences"]
            if reference["kind"] == "command"
        )
        requirement["completionState"] = {"state": "complete", "evidence": [command]}

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn(
            "INP-002 cannot be complete while prerequisite INP-001 is incomplete",
            result.stderr,
        )

    def test_gate_completion_before_owned_requirements_is_rejected(self) -> None:
        payload = copy.deepcopy(self.valid_payload)
        gate = next(item for item in payload["gates"] if item["id"] == "G0")
        gate["completionState"] = {
            "state": "complete",
            "evidence": [
                {
                    "kind": "command",
                    "reference": "python3 scripts/projection/verify_requirement_ledger.py",
                }
            ],
        }

        result = self.run_validator(payload)

        self.assertEqual(result.returncode, 1)
        self.assertIn("gate G0 cannot be complete: incomplete=", result.stderr)


if __name__ == "__main__":
    unittest.main()
