"""Native timing admits one unchanged authority and bounded ordered stage evidence."""
from dataclasses import asdict, dataclass, replace
import json
import unittest

from hosted_repair_time_observation import ReadStage, ReadStageDuration, ReadOutcome, admit_native_time, NativeRepairTimeWindow
from hosted_transport_observation import TransportWitnessRejected


@dataclass(frozen=True)
class Correlation:
    host: str = '00000000-0000-0000-0000-000000000001'
    epoch: int = 2
    type: str = 'bound'


@dataclass(frozen=True)
class Budget:
    remainingHostMillis: int = 3999
    completionReserveMillis: int = 250
    semanticMillis: int = 3749
    diagnosticScopeMillis: int = 2000
    type: str = 'admitted'


@dataclass(frozen=True)
class Outcome:
    outcome: str = 'COMPLETE'
    type: str = 'evaluated'


@dataclass(frozen=True)
class Receipt:
    correlation: Correlation = Correlation()
    semanticBudget: Budget = Budget()
    outcome: Outcome = Outcome()
    stages: tuple[ReadStageDuration, ...] = tuple(ReadStageDuration(stage, n * 10, 10)
        for n, stage in enumerate(ReadStage))
    durationNanos: int = 70
    schemaVersion: int = 4


LIVE = asdict(Correlation())


class NativeTimeTest(unittest.TestCase):
    def test_actual_grant_reserve_and_ordered_stages_survive_projection(self):
        result = admit_native_time(json.loads(json.dumps(asdict(Receipt()))), LIVE)
        self.assertEqual(3749, result.semanticMillis)
        self.assertEqual(250, result.completionReserveMillis)
        self.assertEqual(tuple(ReadStage), tuple(s.stage for s in result.stages))
        self.assertNotIn('host', json.dumps(asdict(result)))

    def test_changed_authority_unknown_stage_and_impossible_times_reject(self):
        for receipt in (
            replace(Receipt(), correlation=Correlation(epoch=3)),
            replace(Receipt(), stages=(ReadStageDuration('unknown', 0, 1),)),
            replace(Receipt(), durationNanos=1),
            replace(Receipt(), semanticBudget=Budget(semanticMillis=5000)),
            replace(Receipt(), stages=tuple(reversed(Receipt().stages))),
            replace(Receipt(), outcome=Outcome('UNKNOWN')),
        ):
            with self.assertRaises(TransportWitnessRejected):
                admit_native_time(json.loads(json.dumps(asdict(receipt))), LIVE)

    def test_closed_host_outcomes_remain_distinct_from_projected_semantic_status(self):
        for outcome in ReadOutcome:
            raw = json.loads(json.dumps(asdict(replace(Receipt(), outcome=Outcome(outcome)))))
            self.assertEqual(outcome, admit_native_time(raw, LIVE).outcome)

    def test_second_semantic_record_cannot_be_misattributed_to_one_request(self):
        window = NativeRepairTimeWindow(None, LIVE)
        line = b'log kast_semantic_read ' + json.dumps(asdict(Receipt())).encode()
        window.admit_line(line)
        with self.assertRaises(TransportWitnessRejected):
            window.admit_line(line)


if __name__ == '__main__':
    unittest.main()
