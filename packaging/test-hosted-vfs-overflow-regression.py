"""Portable helper proof only: these fakes do not qualify a native IDE."""
from dataclasses import asdict
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import hosted_vfs_overflow_regression as subject
from hosted_source_read_regression import SourceFunctionRequest, SymbolAnchor

HOST = '10000000-0000-0000-0000-000000000001'


class OverflowHelperTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.log = self.root / 'idea.log'
        self.log.write_bytes(b'')

    def append(self, document):
        with self.log.open('ab') as output:
            output.write(b'INFO ' + json.dumps(document).encode() + b'\n')

    def test_receipt_requires_new_complete_same_host_exact_shape(self):
        receipt = asdict(subject.OverflowReceipt(HOST))
        self.append(receipt)
        with subject.OverflowLogWindow(self.log) as window:
            with self.assertRaises(subject.OverflowRejected):
                window.observe(HOST)
        for invalid in [dict(receipt, host='foreign'), dict(receipt, outcome='rejected'),
                        dict(receipt, reason='UNKNOWN'), dict(receipt, paths=['secret'])]:
            with subject.OverflowLogWindow(self.log) as window:
                self.append(invalid)
                with self.assertRaises(subject.OverflowRejected):
                    window.observe(HOST)
        with subject.OverflowLogWindow(self.log) as window:
            self.append(receipt)
            self.assertEqual(subject.OverflowReceipt(HOST), window.observe(HOST))

    def test_preexisting_partial_receipt_cannot_be_completed_into_evidence(self):
        body = json.dumps(asdict(subject.OverflowReceipt(HOST))).encode()
        self.log.write_bytes(body[:-1])
        with subject.OverflowLogWindow(self.log) as window:
            with self.log.open('ab') as output:
                output.write(body[-1:] + b'\n')
            with self.assertRaises(subject.OverflowRejected):
                window.observe(HOST)

    def test_log_rotation_and_byte_capacity_reject(self):
        with subject.OverflowLogWindow(self.log) as window:
            self.log.rename(self.root / 'old.log')
            self.log.write_bytes(b'')
            with self.assertRaises(subject.OverflowRejected) as caught:
                window.observe(HOST)
            self.assertEqual(subject.OverflowFailure.LOG_CHANGED, caught.exception.failure)
        with subject.OverflowLogWindow(self.log) as window:
            window.MAX_BYTES = 1
            self.append(asdict(subject.OverflowReceipt(HOST)))
            with self.assertRaises(subject.OverflowRejected) as caught:
                window.observe(HOST)
            self.assertEqual(subject.OverflowFailure.LOG_BOUND, caught.exception.failure)

    def test_exclusive_files_restore_only_unchanged_owned_images(self):
        owned = []
        subject._create_burst(self.root, owned)
        self.assertEqual(3, len(owned))
        changed = owned[-1].path
        original = changed.read_bytes()
        changed.write_bytes(b'user replacement')
        with self.assertRaises(subject.OverflowRejected):
            subject._restore_burst(owned)
        self.assertTrue(all(entry.path.exists() for entry in owned))
        changed.write_bytes(original)
        subject._restore_burst(owned)
        self.assertFalse(any(entry.path.exists() for entry in owned))
        occupied = self.root / 'KastOverflowAcceptance0.kt'
        occupied.write_bytes(b'previous owner')
        with self.assertRaises(FileExistsError):
            subject._create_burst(self.root, [])
        self.assertEqual(b'previous owner', occupied.read_bytes())

    def test_symlink_burst_directory_is_rejected(self):
        link = self.root / 'link'
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(subject.OverflowRejected):
            subject._create_burst(link, [])

    def run_fake(self, receipt=True, strict=True, restoration=None):
        workspace = self.root / 'workspace'
        source = workspace / 'src/main/kotlin/Fixture.kt'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'package fixture\nclass ReadPageBudget\n')
        self.log = self.root / 'ide/log/idea.log'
        self.log.parent.mkdir(parents=True)
        self.log.write_bytes(b'')
        state = SimpleNamespace(epoch=1, ready=0, strict=0, cursor=0)
        live = lambda: {'host': HOST, 'epoch': state.epoch}
        class Replay:
            def __init__(self, *_):
                self.cases = []
            def search(self, surface):
                if state.ready == 2 and restoration == 'search':
                    raise subject.AuthorityRejected(subject.AuthorityFailure.ISSUER)
                if state.ready == 2 and restoration == 'agreement' and surface is subject.AuthoritySurface.PROVIDER:
                    state.epoch += 1
                return SourceFunctionRequest(SymbolAnchor(f'opaque-{state.epoch}')), live()
            def page(self, *args):
                if state.ready == 2 and restoration == 'page':
                    raise subject.AuthorityRejected(subject.AuthorityFailure.ISSUER)
                return 'opaque-cursor'
            def call(self, surface, tool, request):
                self_test.assertEqual('symbol_inspect', tool)
                self_test.assertEqual('exact', request.target.type)
                self_test.assertEqual('opaque-1', request.target.selector)
                state.strict += 1
                return {'status': 'rejected' if strict else 'complete', 'operation': 'symbol.inspect',
                        'reason': 'exact-selector-stale'}, 'sha256:' + 'a' * 64
            def reject(self, name, surface, request, reason):
                self_test.assertEqual('opaque-cursor', request.page.continuation)
                self_test.assertEqual('opaque-2', request.anchor.selector)
                state.cursor += 1
        self_test = self
        def ready(*_):
            state.epoch += 1
            state.ready += 1
            if state.ready == 2 and restoration == 'epoch':
                state.epoch -= 1
            if receipt and state.ready == 1:
                self.append(asdict(subject.OverflowReceipt(HOST)))
        with patch.object(subject, '_AuthorityReplay', Replay), patch.object(subject, '_ready', ready), \
                patch.object(subject, 'admitted_live', lambda value, _: value), \
                patch.object(subject, 'NativeFixtureProbe', lambda *_: object()):
            result = subject.run_vfs_overflow_regression(SimpleNamespace(root=self.root),
                         SimpleNamespace(workspace=workspace), object(), live())
        self.assertEqual([], list(source.parent.glob('KastOverflowAcceptance*.kt')))
        self.assertEqual(b'package fixture\nclass ReadPageBudget\n', source.read_bytes())
        self.assertEqual(2, state.ready)
        return result, state

    def test_fake_success_checks_both_surfaces_then_restores_successor(self):
        (report, successor), state = self.run_fake()
        self.assertEqual(subject.OverflowOutcome.PASSED, report.outcome)
        self.assertEqual(3, successor['epoch'])
        self.assertEqual((2, 2), (state.strict, state.cursor))
        self.assertEqual(3, report.filesCreated)
        self.assertTrue(report.filesRestored)
        self.assertEqual(subject.OverflowReceipt(HOST), report.receipt)

    def test_epoch_movement_without_overflow_receipt_is_failure_and_restores(self):
        (report, successor), state = self.run_fake(receipt=False)
        self.assertEqual(subject.OverflowFailure.RECEIPT, report.failure)
        self.assertEqual(subject.OverflowOutcome.REJECTED, report.outcome)
        self.assertEqual(0, state.strict)
        self.assertEqual(3, successor['epoch'])

    def test_strict_success_cannot_pass_stale_refusal_gate(self):
        (report, _), _ = self.run_fake(strict=False)
        self.assertEqual(subject.OverflowFailure.STRICT, report.failure)
        self.assertEqual(subject.OverflowOutcome.REJECTED, report.outcome)
        self.assertTrue(report.filesRestored)

    def test_strict_observation_retains_known_refusal_and_exact_mismatch_without_payload(self):
        valid = {'operation': 'symbol.inspect', 'status': 'rejected', 'reason': 'exact-selector-stale'}
        observed = subject._strict_observation(valid)
        self.assertEqual(subject.InspectionRefusal.EXACT_SELECTOR_STALE, observed.refusal)
        for raw in [dict(valid, reason='exact_selector_stale'), dict(valid, reason='private-token-payload')]:
            observed = subject._strict_observation(raw)
            self.assertIsInstance(observed, subject.InspectionUnknownRefusal)
            self.assertNotIn('private-token', json.dumps(asdict(observed)))
        for raw, expected in [(None, subject.InspectionMismatch.DOCUMENT),
                              (dict(valid, operation='wrong'), subject.InspectionMismatch.OPERATION),
                              (dict(valid, status='complete'), subject.InspectionMismatch.STATUS)]:
            self.assertEqual(expected, subject._strict_observation(raw).mismatch)
        observed = subject._strict_observation(dict(valid, reason='workspace-not-ready'))
        self.assertEqual(subject.InspectionRefusal.WORKSPACE_NOT_READY, observed.refusal)

    def test_restoration_preserves_failed_predicate_and_original_failure(self):
        for mode, stage, cause in [('epoch', subject.RestorationStage.EPOCH_ADVANCE, subject.OverflowFailure.EPOCH),
                                   ('agreement', subject.RestorationStage.BASIS_AGREEMENT, subject.OverflowFailure.EPOCH),
                                   ('search', subject.RestorationStage.SEARCH, subject.OverflowFailure.AUTHORITY),
                                   ('page', subject.RestorationStage.SOURCE_PAGE, subject.OverflowFailure.AUTHORITY)]:
            with self.subTest(mode=mode):
                # Each fake run owns a distinct disposable root.
                with tempfile.TemporaryDirectory() as directory:
                    self.root = Path(directory).resolve()
                    (report, successor), _ = self.run_fake(strict=False, restoration=mode)
                self.assertIsNone(successor)
                self.assertEqual(subject.OverflowFailure.STRICT, report.failure)
                self.assertEqual(stage, report.restorationStage)
                self.assertEqual(cause, report.restorationFailure)
                self.assertEqual(1, len(report.strictObservations))
                self.assertEqual(subject.InspectionMismatch.STATUS,
                                 report.strictObservations[0].observation.mismatch)
                if cause is subject.OverflowFailure.AUTHORITY:
                    self.assertEqual(subject.AuthorityFailure.ISSUER, report.restorationAuthorityFailure)


if __name__ == '__main__':
    unittest.main()
