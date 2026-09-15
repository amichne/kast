#!/usr/bin/env python3
"""Actual grant receipt admission rejects absent/contradictory budget evidence."""
from dataclasses import asdict, dataclass, field, replace
import json
import unittest

from hosted_repair_budget_regression import admit_time_receipt, RepairReceiptRejected


@dataclass(frozen=True)
class Limit:
    selection: str = 'configured_default'
    requested: int | None = None
    configuredDefault: int = 2000
    operatorCeiling: int = 2147483646
    effective: int = 2000
    clamping: tuple[str, ...] = ()


@dataclass(frozen=True)
class Grant:
    max_elapsed_ms: Limit
    max_work_units: Limit = field(default_factory=Limit)
    max_results: Limit = field(default_factory=Limit)
    max_returned_bytes: Limit = field(default_factory=Limit)


@dataclass(frozen=True)
class Response:
    execution_budget: Grant
    status: str = 'complete'


def response(millis=10000, effective=2750, clamps=('deadline_remaining',)):
    return Response(Grant(Limit('caller', millis, effective=effective, clamping=clamps)))


class RepairTimeReceiptTest(unittest.TestCase):
    def test_default_and_enlarged_grants_retain_actual_amounts(self):
        for surface in ('cli', 'provider'):
            for millis in (10000, 20000):
                for effective in (2750, millis):
                    clamps = ('deadline_remaining',) if effective < millis else ()
                    receipt = admit_time_receipt(surface, 'source_read', millis,
                        asdict(response(millis, effective, clamps)), 321)
                    actual = json.loads(json.dumps(asdict(receipt)))
                    self.assertEqual(effective, actual['actualGrantMillis'])
                    self.assertEqual(millis, actual['requestedMillis'])
                    self.assertEqual(2000, actual['configuredDefaultMillis'])
                    self.assertEqual(2147483646, actual['operatorCeilingMillis'])
                    self.assertEqual(list(clamps), actual['clamping'])
                    self.assertEqual('kast_repair_time_request', actual['event'])
                    self.assertNotIn('source', actual)
                    self.assertNotIn('ref', actual)

    def test_unknown_status_and_missing_or_contradictory_grant_reject(self):
        cases = (
            asdict(replace(response(), status='unknown')),
            asdict(response(effective=20000)),
            asdict(response(clamps=())),
            asdict(response(clamps=('unknown',))),
            {},  # Deliberately malformed boundary input proves required report rejection.
        )
        for value in cases:
            with self.subTest(value=value):
                self.assertIsInstance(admit_time_receipt('cli', 'source_read', 10000, value, 123), RepairReceiptRejected)


if __name__ == '__main__':
    unittest.main()
