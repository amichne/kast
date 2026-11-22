from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import acceptance_inputs as inputs

VALID = {
    "schemaVersion": 1,
    "runId": 43372523369,
    "runAttempt": 1,
    "revision": "f" * 40,
    "recordSha256": "a" * 64,
}


class AcceptanceInputsTest(unittest.TestCase):
    def test_checked_in_pin_decodes(self) -> None:
        pin = inputs.read_pin()
        self.assertGreater(pin.run_id, 0)
        self.assertEqual(40, len(pin.revision))

    def test_valid_pin_projects_exact_outputs(self) -> None:
        pin = inputs.decode(json.dumps(VALID))
        self.assertEqual(
            {"run_id": "43372523369", "run_attempt": "1", "revision": "f" * 40, "record_sha256": "a" * 64},
            pin.outputs(),
        )

    def test_github_output_receives_one_line_per_field(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "output"
            inputs.write_outputs(inputs.decode(json.dumps(VALID)), destination)
            self.assertEqual(
                ["run_id=43372523369", "run_attempt=1", f"revision={'f' * 40}", f"record_sha256={'a' * 64}"],
                destination.read_text().splitlines(),
            )

    def test_malformed_pins_reject_with_pin_invalid(self) -> None:
        cases = {
            "missing field": {key: value for key, value in VALID.items() if key != "recordSha256"},
            "extra field": VALID | {"note": "x"},
            "schema version": VALID | {"schemaVersion": 2},
            "zero run": VALID | {"runId": 0},
            "boolean attempt": VALID | {"runAttempt": True},
            "short revision": VALID | {"revision": "abc"},
            "uppercase digest": VALID | {"recordSha256": "A" * 64},
        }
        for name, document in cases.items():
            with self.subTest(name), self.assertRaises(inputs.Rejected) as raised:
                inputs.decode(json.dumps(document))
            self.assertIs(inputs.Failure.PIN_INVALID, raised.exception.failure)

    def test_non_json_rejects_with_pin_invalid(self) -> None:
        with self.assertRaises(inputs.Rejected) as raised:
            inputs.decode("not json")
        self.assertIs(inputs.Failure.PIN_INVALID, raised.exception.failure)

    def test_missing_pin_rejects_with_pin_unreadable(self) -> None:
        with self.assertRaises(inputs.Rejected) as raised:
            inputs.read_pin(Path("/nonexistent/acceptance-baseline.json"))
        self.assertIs(inputs.Failure.PIN_UNREADABLE, raised.exception.failure)


if __name__ == "__main__":
    unittest.main()
