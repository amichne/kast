#!/usr/bin/env python3
"""Independent fixed source failure observations; no native qualification is inferred."""
from dataclasses import asdict, dataclass, field as dataclass_field
import unittest
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from hosted_source_failure_regression import admit_source_failure, SourceFailureCause, admit_source_cli_boundary, _observe_source_failure, SourceFailureCase, SourceFailureBoundary, SourceSchemaEvidence, SourceCliBoundaryRejected, SourceCliBoundaryFailure, SourceJvmNotice
from hosted_read_transport import _provider_result, ReadTransportRejected, HostedReadTransport, ReadTransportFailure


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


@dataclass(frozen=True)
class CliSourceBoundary:
    reason: RequestCause | ReferenceCause
    next_action: str = 'correct_request'
    operation: str = 'source.read'
    status: str = 'rejected'


class SourceFailureTest(unittest.TestCase):
    def test_cli_usage_stderr_is_distinct_and_schema_checked(self):
        document = CliSourceBoundary(RequestCause())
        process = SimpleNamespace(returncode=2, stdout=b'', stderr=json.dumps(asdict(document)).encode())
        fixture = SimpleNamespace(workspace=Path('/owned-fixture'), environment={})
        transport = HostedReadTransport(None, fixture, Path('/owned-product'), None, None)
        transport.cli_commands = {'source_read': ['source', 'read']}
        with patch('hosted_read_transport.product_executable', return_value=Path('/unused')), patch('hosted_read_transport.subprocess.run', return_value=process), patch.object(transport, 'validate', return_value='digest') as validate:
            with self.assertRaises(ReadTransportRejected) as caught:
                transport.invoke('cli', 'source_read', asdict(document))
        self.assertEqual(ReadTransportFailure.SOURCE_CLI_BOUNDARY, caught.exception.reason)
        self.assertTrue(caught.exception.source_schema_admitted)
        self.assertEqual(SourceFailureCause.ANCHOR_TYPE_REQUIRED, caught.exception.source_cause.cause)
        validate.assert_called_once_with('source_read', json.loads(process.stderr))

    def test_exact_private_jvm_startup_notices_preserve_cli_source_boundary(self):
        environment = {'JAVA_TOOL_OPTIONS': '-Duser.home="/owned/home"', '_JAVA_OPTIONS': '-Duser.home="/owned/home"'}
        document = CliSourceBoundary(RequestCause())
        notices = ''.join('Picked up ' + key + ': ' + environment[key] + '\n' for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS')).encode()
        process = SimpleNamespace(returncode=2, stdout=b'', stderr=notices + json.dumps(asdict(document)).encode())
        fixture = SimpleNamespace(workspace=Path('/owned-fixture'), environment=environment)
        transport = HostedReadTransport(None, fixture, Path('/owned-product'), None, None)
        transport.cli_commands = {'source_read': ['source', 'read']}
        with patch('hosted_read_transport.product_executable', return_value=Path('/unused')), patch('hosted_read_transport.subprocess.run', return_value=process), patch.object(transport, 'validate', return_value='digest'):
            with self.assertRaises(ReadTransportRejected) as caught:
                transport.invoke('cli', 'source_read', asdict(document))
        self.assertEqual(ReadTransportFailure.SOURCE_CLI_BOUNDARY, caught.exception.reason)
        self.assertTrue(caught.exception.source_schema_admitted)
        self.assertEqual((SourceJvmNotice.TOOL_OPTIONS, SourceJvmNotice.JAVA_OPTIONS), caught.exception.source_jvm_notices)

    def test_cli_stderr_requires_usage_exit_and_exact_source_envelope(self):
        stderr = json.dumps(asdict(CliSourceBoundary(RequestCause()))).encode()
        for exit_code, stdout, body in ((0, b'', stderr), (2, b'other', stderr), (2, b'', b'plain error'), (2, b'', stderr.replace(b'source.read', b'query.run'))):
            with self.assertRaises(ValueError):
                admit_source_cli_boundary(exit_code, stdout, body)
        reference = CliSourceBoundary(ReferenceCause('wrong-family'), next_action='reacquire_authority')
        self.assertEqual(SourceFailureCause.WRONG_FAMILY, admit_source_cli_boundary(2, b'', json.dumps(asdict(reference)).encode()).cause.cause)

    def test_only_exact_supplied_jvm_notices_are_admitted(self):
        body = json.dumps(asdict(CliSourceBoundary(RequestCause()))).encode()
        environment = {'JAVA_TOOL_OPTIONS': '-Duser.home="/owned/home"'}
        notice = b'Picked up JAVA_TOOL_OPTIONS: -Duser.home="/owned/home"\n'
        admission = admit_source_cli_boundary(2, b'', notice + body, environment)
        self.assertEqual((SourceJvmNotice.TOOL_OPTIONS,), admission.notices)
        for raw in (b'arbitrary warning\n' + body, notice + notice + body, notice.replace(b'/owned/home', b'/other/home') + body):
            with self.assertRaises(SourceCliBoundaryRejected) as caught:
                admit_source_cli_boundary(2, b'', raw, environment)
            self.assertEqual(SourceCliBoundaryFailure.JSON, caught.exception.failure)
        with self.assertRaises(SourceCliBoundaryRejected) as caught:
            admit_source_cli_boundary(1, b'', notice + body, environment)
        self.assertEqual(SourceCliBoundaryFailure.EXIT, caught.exception.failure)

    def test_failed_native_assertion_retains_actual_transport_evidence(self):
        error = ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
        transport = SimpleNamespace(invoke_observed=lambda *args: None)
        replay = SimpleNamespace(transport=transport, surface='provider')
        with patch.object(transport, 'invoke_observed', side_effect=error):
            passed, evidence = _observe_source_failure(replay, SourceFailureCase.WRONG_FAMILY, {}, SourceFailureCause.WRONG_FAMILY)
        self.assertFalse(passed)
        self.assertEqual(SourceFailureBoundary.TRANSPORT, evidence.boundary)
        self.assertEqual(ReadTransportFailure.PROVIDER_PROTOCOL, evidence.transport_failure)
        self.assertEqual(SourceSchemaEvidence.NOT_CHECKED, evidence.schema)

    def test_known_cause_does_not_turn_schema_rejection_into_success(self):
        error = ReadTransportRejected('READ_PROVIDER_REJECTED')
        error.source_cause = admit_source_failure(asdict(ReferenceCause('wrong-family')))
        transport = SimpleNamespace(invoke_observed=lambda *args: None)
        replay = SimpleNamespace(transport=transport, surface='cli')
        with patch.object(transport, 'invoke_observed', side_effect=error):
            passed, evidence = _observe_source_failure(replay, SourceFailureCase.WRONG_FAMILY, {}, SourceFailureCause.WRONG_FAMILY)
        self.assertFalse(passed)
        self.assertEqual(SourceFailureCause.WRONG_FAMILY, evidence.cause.cause)

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
        for reason in ('wrong-family', 'stale-authority', 'unavailable', 'revalidation-unretained'):
            self.assertEqual(reason, admit_source_failure(asdict(ReferenceCause(reason))).cause)
        with self.assertRaises(ValueError):
            admit_source_failure(asdict(ReferenceCause('arbitrary caller bytes')))

    def test_generic_provider_failure_cannot_prove_source_ingress(self):
        with self.assertRaises(ReadTransportRejected) as caught:
            _provider_result({'kind': 'rejected', 'failure': 'SOURCE_INPUT_REJECTED'})
        self.assertIsNone(caught.exception.source_cause)


if __name__ == '__main__':
    unittest.main()
