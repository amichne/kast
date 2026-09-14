#!/usr/bin/env python3
"""Deterministic qualification control tests; native epoch movement is a separate gate."""
from dataclasses import asdict, dataclass, field, replace
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

from hosted_authority_read_regression import (AuthorityOutcome, AuthorityFailure, AuthorityCaseName,
    run_authority_read_regression)


@dataclass(frozen=True)
class LiveFixture:
    root: str
    epoch: int
    host: str = '11111111-1111-4111-8111-111111111111'
    contentView: str = 'SAVED_PSI_COMMITTED'
    version: int = 1


@dataclass(frozen=True)
class ItemFixture:
    symbol_ref: str
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
    continuation: str
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
class RejectionFixture:
    reason: str
    status: str = 'rejected'
    operation: str = 'source.read'


class AuthorityReadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
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
        return {'outcome': 'SETUP_READY'}  # Existing probe admits the full typed native response.

    def invoke(self, surface, tool, arguments):
        self.calls.append((surface, tool, arguments))
        live = replace(self.live, epoch=self.epoch)
        selector = 'ref-' + str(self.epoch)
        if tool == 'search_classes':
            document = SearchFixture(live, (ItemFixture(selector),))
        elif arguments['anchor']['selector'] != selector:
            document = RejectionFixture('stale-generation')
        elif arguments['page']['type'] == 'continue' and arguments['page']['continuation'] != 'cursor-' + str(self.epoch):
            document = RejectionFixture('contract-violation')
        else:
            name = 'pageItem01' if arguments['page']['type'] == 'continue' else 'pageItem00'
            token = 'cursor-' + str(self.epoch)
            document = PageFixture(live, (EntityFixture(name),),
                QualificationFixture(CursorFixture(token), ProgressFixture(CheckpointFixture(token))))
        return json.loads(json.dumps(asdict(document))), 'sha256:' + '1' * 64

    def run_fixture(self):
        return run_authority_read_regression(SimpleNamespace(root=self.root),
            SimpleNamespace(workspace=self.workspace, environment={}), self.transport, asdict(self.live))

    def test_edit_changes_epoch_rejects_old_authority_and_restores_exact_source(self):
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

    def test_no_epoch_movement_fails_closed_and_still_restores_source(self):
        self.probe.request.side_effect = lambda *args, **kwargs: {'outcome': 'SETUP_READY'}
        report = self.run_fixture()
        self.assertEqual(AuthorityOutcome.REJECTED, report.outcome)
        self.assertEqual(AuthorityFailure.EPOCH, report.failure)
        self.assertEqual(self.original, self.source.read_bytes())

    def test_unexpected_stale_success_fails_and_restores_source(self):
        original = self.invoke
        def accept_stale(surface, tool, arguments):
            if tool == 'source_read' and arguments['anchor']['selector'] == 'ref-1' and self.epoch == 2:
                arguments = {**arguments, 'anchor': {**arguments['anchor'], 'selector': 'ref-2'}}
            return original(surface, tool, arguments)
        self.transport.invoke_observed.side_effect = accept_stale
        report = self.run_fixture()
        self.assertEqual(AuthorityFailure.REJECTION, report.failure)
        self.assertEqual(self.original, self.source.read_bytes())


if __name__ == '__main__':
    unittest.main()
