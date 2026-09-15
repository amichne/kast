#!/usr/bin/env python3
"""Independent fixed source failure observations; no native qualification is inferred."""
from dataclasses import asdict, dataclass, field as dataclass_field
import unittest
from hosted_source_failure_regression import admit_source_failure, SourceFailureCause
from hosted_read_transport import _provider_result, ReadTransportRejected


@dataclass(frozen=True)
class Field:
    path: str = 'anchor.type'


@dataclass(frozen=True)
class Alternatives:
    type: str = 'alternatives'
    values: tuple[str, ...] = ('candidate', 'symbol', 'source')


@dataclass(frozen=True)
class RequestCause:
    field: Field = dataclass_field(default_factory=Field)
    type: str = 'request-rejected'
    reason: str = 'required'
    expected: Alternatives = dataclass_field(default_factory=Alternatives)


@dataclass(frozen=True)
class ReferenceCause:
    reason: str
    type: str = 'reference-rejected'
    role: str = 'symbol'


@dataclass(frozen=True)
class InternalCause:
    type: str = 'internal-contract-failure'
    obligation: str = 'anchor-snapshot'


@dataclass(frozen=True)
class Rejected:
    sourceCause: RequestCause | InternalCause
    kind: str = 'rejected'
    failure: str = 'SOURCE_INPUT_REJECTED'


class SourceFailureTest(unittest.TestCase):
    def test_native_broker_refusal_retains_typed_ingress_origin(self):
        with self.assertRaises(ReadTransportRejected) as caught:
            _provider_result(asdict(Rejected(RequestCause())))
        self.assertEqual(SourceFailureCause.ANCHOR_TYPE_REQUIRED, caught.exception.source_cause.cause)
        self.assertEqual('anchor-type-required', caught.exception.evidence()['sourceFailure']['cause'])

    def test_native_internal_obligation_is_not_erased_to_request_or_protocol_failure(self):
        cause = InternalCause()
        response = Rejected(cause, failure='SOURCE_INTERNAL_CONTRACT_FAILURE')
        with self.assertRaises(ReadTransportRejected) as caught:
            _provider_result(asdict(response))
        self.assertEqual('anchor-snapshot', caught.exception.source_cause.cause)
        self.assertEqual('internal-contract-failure', caught.exception.source_cause.origin)

    def test_known_stale_and_unknown_remain_disjoint(self):
        for reason in ('wrong-family', 'stale-authority', 'unavailable'):
            self.assertEqual(reason, admit_source_failure(asdict(ReferenceCause(reason))).cause)
        with self.assertRaises(ValueError):
            admit_source_failure(asdict(ReferenceCause('arbitrary caller bytes')))

    def test_generic_provider_failure_cannot_prove_source_ingress(self):
        with self.assertRaises(ReadTransportRejected) as caught:
            _provider_result({'kind': 'rejected', 'failure': 'SOURCE_INPUT_REJECTED'})
        self.assertIsNone(caught.exception.source_cause)


if __name__ == '__main__':
    unittest.main()
