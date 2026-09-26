#!/usr/bin/env python3
"""Deterministic qualification control tests; native epoch movement is a separate gate."""
from dataclasses import asdict, dataclass, field, replace
import hashlib
import io
import json
import re
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

from hosted_authority_read_regression import (AuthorityOutcome, AuthorityFailure, AuthorityCaseName,
    run_authority_read_regression, _foreign_refusal, AuthorityRejected, ForeignBoundaryRefusal,
    AuthoritySurface)
from hosted_read_transport import HostedReadTransport, ReadTransportRejected, ReadTransportFailure
from hosted_source_read_regression import SourceFunctionRequest, SymbolAnchor


@dataclass(frozen=True)
class LiveFixture:
    root: str
    epoch: int
    host: str = '11111111-1111-4111-8111-111111111111'
    contentView: str = 'SAVED_PSI_COMMITTED'
    version: int = 1


@dataclass(frozen=True)
class ItemFixture:
    ref: str
    name: str = 'ReadPageBudget'


@dataclass(frozen=True)
class SearchFixture:
    live: LiveFixture
    items: tuple[ItemFixture, ...]
    status: str = 'complete'


@dataclass(frozen=True)
class CursorFixture:
    continuation: str
    type: str = 'available'


@dataclass(frozen=True)
class CheckpointFixture:
    token: str
    type: str = 'upstream'


@dataclass(frozen=True)
class ProgressFixture:
    checkpoint: CheckpointFixture
    type: str = 'resumable'
    next_action: str = 'resume'


@dataclass(frozen=True)
class QualificationFixture:
    continuation: CursorFixture
    progress: ProgressFixture
    limitations: tuple[str, ...] = ('entity-limit-reached',)


@dataclass(frozen=True)
class EntityFixture:
    name: str


@dataclass(frozen=True)
class PageFixture:
    live: LiveFixture
    entities: tuple[EntityFixture, ...]
    qualification: QualificationFixture
    status: str = 'qualified'


@dataclass(frozen=True)
class ReacquiredReferenceFixture:
    previous: str
    current: str


@dataclass(frozen=True)
class ReferenceAcquisitionsFixture:
    references: tuple[ReacquiredReferenceFixture, ...]


@dataclass(frozen=True, kw_only=True)
class ReacquiredPageFixture(PageFixture):
    reference_acquisitions: ReferenceAcquisitionsFixture


@dataclass(frozen=True)
class UnavailableSymbolReference:
    type: str = 'reference-rejected'
    role: str = 'symbol'
    reason: str = 'unavailable'


@dataclass(frozen=True)
class RejectionFixture:
    reason: str | UnavailableSymbolReference
    status: str = 'rejected'
    operation: str = 'source.read'


@dataclass(frozen=True)
class ProbeEvidenceFixture:
    savedSha256: str
    documentSha256: str
    documentState: str = 'SAVED_COMMITTED'


@dataclass(frozen=True)
class ProbeReadyFixture:
    evidence: ProbeEvidenceFixture
    outcome: str = 'SETUP_READY'


class AuthorityReadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.workspace = self.root / 'workspace'
        self.source = self.workspace / 'src/main/kotlin/Fixture.kt'
        self.source.parent.mkdir(parents=True)
        self.original = b'class Fixture { fun operation() = 1 }\n'
        self.source.write_bytes(self.original)
        self.live = LiveFixture(str(self.workspace), 1)
        self.epoch = 1
        self.transport = Mock()
        self.transport.invoke_observed.side_effect = self.invoke
        self.probe = Mock()
        self.probe.request.side_effect = self.ready
        self.calls = []
        self.transitions = []
        self.foreign = Mock(return_value=None)
        self.stack = [patch('hosted_authority_read_regression.NativeFixtureProbe', return_value=self.probe),
                      patch('hosted_authority_read_regression._foreign_refusal', self.foreign, create=True)]
        for patcher in self.stack:
            patcher.start()
            self.addCleanup(patcher.stop)

    def ready(self, command, digest, **kwargs):
        self.assertEqual('AWAIT_REOPEN_READY', command)
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(), digest)
        self.epoch += 1
        self.transitions.append(self.source.read_bytes())
        return asdict(ProbeReadyFixture(ProbeEvidenceFixture(digest, digest)))

    def invoke(self, surface, tool, arguments):
        self.calls.append((surface, tool, arguments))
        live = replace(self.live, epoch=self.epoch)
        selector = 'ref-' + str(self.epoch)
        if tool == 'query_symbols':
            document = SearchFixture(live, (ItemFixture(selector),))
        elif arguments['page']['type'] == 'continue' and arguments['page']['continuation'] != 'cursor-' + str(self.epoch):
            document = RejectionFixture('source-snapshot-mismatch')
        else:
            name = 'pageItem01' if arguments['page']['type'] == 'continue' else 'pageItem00'
            token = 'cursor-' + str(self.epoch)
            qualification = QualificationFixture(CursorFixture(token), ProgressFixture(CheckpointFixture(token)))
            previous = arguments['anchor']['selector']
            if previous != selector:
                document = ReacquiredPageFixture(live, (EntityFixture(name),), qualification,
                    reference_acquisitions=ReferenceAcquisitionsFixture((ReacquiredReferenceFixture(previous, selector),)))
            else:
                document = PageFixture(live, (EntityFixture(name),), qualification)
        return json.loads(json.dumps(asdict(document))), 'sha256:' + '1' * 64

    def run_fixture(self):
        return run_authority_read_regression(SimpleNamespace(root=self.root),
            SimpleNamespace(workspace=self.workspace, environment={}), self.transport, asdict(self.live))

    def test_edit_reacquires_read_handle_rejects_old_cursor_and_restores_exact_source(self):
        report = self.run_fixture()
        self.assertEqual(AuthorityOutcome.PASSED, report.outcome)
        self.assertEqual((1, 2, 3), (report.beforeEpoch, report.editedEpoch, report.restoredEpoch))
        self.assertTrue(report.sourceRestored)
        self.assertEqual(self.original, self.source.read_bytes())
        self.assertEqual(2, len(self.transitions))
        self.assertNotEqual(self.original, self.transitions[0])
        self.assertEqual(self.original, self.transitions[1])
        self.assertEqual(2, self.foreign.call_count)
        self.assertEqual(2, sum(row.name == AuthorityCaseName.OLD_CURSOR for row in report.cases))
        self.assertEqual(2, sum(row.name == AuthorityCaseName.OLD_REFERENCE for row in report.cases))
        self.assertNotIn('ref-', json.dumps(asdict(report)))
        self.assertNotIn('cursor-', json.dumps(asdict(report)))





    def test_restoration_reports_stale_document_separately_from_saved_file_restore(self):
        ready = self.ready
        def stale_document(command, digest, **kwargs):
            result = ready(command, digest, **kwargs)
            if self.epoch == 3:
                return asdict(ProbeReadyFixture(ProbeEvidenceFixture(digest, 'a' * 64)))
            return result
        self.probe.request.side_effect = stale_document
        report = self.run_fixture()
        self.assertEqual('AUTHORITY_RESTORATION_DOCUMENT_IMAGE_CHANGED', report.failure.value)
        self.assertTrue(report.sourceRestored)
        self.assertEqual(self.original, self.source.read_bytes())

    def test_no_epoch_movement_fails_closed_and_still_restores_source(self):
        self.probe.request.side_effect = lambda command, digest, **kwargs: asdict(
            ProbeReadyFixture(ProbeEvidenceFixture(digest, digest)))
        report = self.run_fixture()
        self.assertEqual(AuthorityOutcome.REJECTED, report.outcome)
        self.assertEqual(AuthorityFailure.EPOCH, report.failure)
        self.assertEqual(self.original, self.source.read_bytes())

    def test_reacquisition_without_handle_metadata_fails_and_restores_source(self):
        original = self.invoke
        def accept_stale(surface, tool, arguments):
            if tool == 'source_read' and arguments['anchor']['selector'] == 'ref-1' and self.epoch == 2:
                arguments = {**arguments, 'anchor': {**arguments['anchor'], 'selector': 'ref-2'}}
            return original(surface, tool, arguments)
        self.transport.invoke_observed.side_effect = accept_stale
        report = self.run_fixture()
        self.assertEqual(AuthorityFailure.REVALIDATION, report.failure)
        self.assertEqual(self.original, self.source.read_bytes())

    def test_unexpected_intervening_source_is_not_overwritten_during_restore(self):
        ready = self.ready
        def change_source(command, digest, **kwargs):
            result = ready(command, digest, **kwargs)
            if self.epoch == 2:
                self.source.write_bytes(b'class Intervening {}\n')
            return result
        self.probe.request.side_effect = change_source
        report = self.run_fixture()
        self.assertEqual(AuthorityFailure.RESTORATION, report.failure)
        self.assertFalse(report.sourceRestored)
        self.assertEqual(b'class Intervening {}\n', self.source.read_bytes())

    def test_foreign_cli_requires_exact_finite_refusal_and_unchanged_owned_root(self):
        self.stack[1].stop()
        fixture = SimpleNamespace(workspace=self.workspace, environment={})
        transport = SimpleNamespace(product=self.root / 'product', cli_commands={'source_read': ('source', 'read')})
        request = SourceFunctionRequest(SymbolAnchor('private-issued-reference'))
        accepted = json.dumps(asdict(ForeignBoundaryRefusal())).encode()
        with patch('hosted_authority_read_regression.subprocess.run',
                   return_value=SimpleNamespace(returncode=1, stdout=b'', stderr=accepted)) as execute:
            _foreign_refusal(transport, fixture, request)
            self.assertEqual(self.root / 'foreign-read-workspace', execute.call_args.kwargs['cwd'])
            self.assertEqual(json.loads(json.dumps(asdict(request))), json.loads(execute.call_args.kwargs['input']))
        for malformed in (b'{"status":"rejected","boundary":"runtime","reason":"unknown"}',
                          b'{"status":"rejected","boundary":"runtime","reason":"ide-host-unavailable","extra":1}'):
            with patch('hosted_authority_read_regression.subprocess.run',
                       return_value=SimpleNamespace(returncode=1, stdout=b'', stderr=malformed)):
                with self.assertRaises(AuthorityRejected):
                    _foreign_refusal(transport, fixture, request)




@dataclass(frozen=True)
class ProviderEnvelopeFixture:
    document: RejectionFixture
    status: str = 'completed'


@dataclass(frozen=True)
class ProviderResponseFixture:
    envelope: ProviderEnvelopeFixture
    success: bool = False
    kind: str = 'completed'


@dataclass(frozen=True)
class SchemaAdmissionFixture:
    schemaDigest: str = 'sha256:' + '1' * 64
    kind: str = 'validation_accepted'


class ActualEnvelopeTransportTest(unittest.TestCase):
    def test_actual_provider_envelope_is_forwarded_to_the_schema_without_rebuilding(self):
        transport = HostedReadTransport(None, None, None, None, None)
        transport.provider = SimpleNamespace(stdin=io.BytesIO())
        expected = ProviderResponseFixture(ProviderEnvelopeFixture(RejectionFixture('stale-generation')))
        transport._response = Mock(side_effect=(json.dumps(asdict(expected)).encode(),
            json.dumps(asdict(SchemaAdmissionFixture())).encode()))
        document, digest = transport.invoke_observed('provider', 'source_read',
            asdict(SourceFunctionRequest(SymbolAnchor('private-issued-reference'))))
        requests = [json.loads(line) for line in transport.provider.stdin.getvalue().splitlines()]
        self.assertEqual('invoke', requests[0]['action'])
        self.assertEqual('validate_envelope', requests[1]['action'])
        self.assertEqual(asdict(expected.envelope), requests[1]['envelope'])
        self.assertEqual(asdict(expected.envelope.document), document)
        self.assertEqual(SchemaAdmissionFixture().schemaDigest, digest)



if __name__ == '__main__':
    unittest.main()
