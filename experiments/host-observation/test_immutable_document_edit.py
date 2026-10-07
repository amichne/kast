"""Owned mutation boundary tests. No IDE or native semantic claim is made here."""
from dataclasses import asdict, replace
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import immutable_callback_oracle as oracle
import immutable_document_edit as edit
from static_callback_oracle import FixtureIntegrityError


class ImmutableDocumentEditTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.fixture = edit.create_owned_fixture(self.root)

    def prepare(self):
        return edit.prepare_edit(self.fixture, oracle.MUTATIONS[0].edit, 12345, self.root / 'action')

    def receipt(self, prepared):
        return edit.DocumentEditReceipt('APPLIED_UNSAVED', 12345, prepared.request.file,
            edit.digest(prepared.request.before), edit.digest(prepared.request.after), prepared.saved_sha256)

    def test_preparation_is_launch_free_preserves_saved_source_and_exact_rollback(self):
        before = oracle.load_sources(self.fixture.root)
        prepared = self.prepare()
        self.assertEqual(before, oracle.load_sources(self.fixture.root))
        self.assertFalse(prepared.receipt.exists())
        self.assertEqual('betaTarget', prepared.request.after.split('val callback = { ')[1].split('(')[0])
        rollback = prepared.rollback()
        self.assertEqual(prepared.request.before, rollback.after)
        self.assertEqual(prepared.request.after, rollback.before)
        restored = edit.prepare_request(self.fixture, rollback, self.root / 'rollback')
        self.assertEqual(prepared.saved_sha256, restored.saved_sha256)
        self.assertNotIn('@INPUT_BASE64@', prepared.script.read_text())

    def test_exact_hash_receipt_is_required_and_unknown_fields_reject(self):
        prepared = self.prepare()
        receipt = self.receipt(prepared)
        prepared.receipt.write_text(json.dumps(asdict(receipt)))
        self.assertEqual(receipt, edit.admit_receipt(prepared))
        for field, value in (('type', 'COMPLETE'), ('hostPid', True), ('hostPid', 9), ('file', 'other.kt'),
                             ('beforeSha256', '0' * 64), ('afterSha256', '0' * 64), ('savedSha256', '0' * 64),
                             ('unexpected', 'field')):
            with self.subTest(field=field, value=value):
                prepared.receipt.write_text(json.dumps(asdict(receipt) | {field: value}))
                with self.assertRaises(FixtureIntegrityError):
                    edit.admit_receipt(prepared)

    def test_native_rejection_remains_finite_data(self):
        prepared = self.prepare()
        for failure in edit.EditFailure:
            prepared.receipt.write_text(json.dumps({'type': 'REJECTED', 'failure': failure.value}))
            self.assertEqual(edit.DocumentEditRejected('REJECTED', failure), edit.admit_receipt(prepared))
        prepared.receipt.write_text('{"type":"REJECTED","failure":"UNKNOWN"}')
        with self.assertRaises(FixtureIntegrityError):
            edit.admit_receipt(prepared)

    def test_saved_edit_requires_saved_preimage_and_distinct_saved_receipt(self):
        prepared = edit.prepare_edit(self.fixture, oracle.MUTATIONS[0].edit, 12345, self.root / 'save', edit.EditMode.SAVED)
        unsaved = self.receipt(prepared)
        prepared.receipt.write_text(json.dumps(asdict(unsaved)))
        with self.assertRaises(FixtureIntegrityError): edit.admit_receipt(prepared)
        saved = replace(unsaved, type='APPLIED_SAVED', savedSha256=edit.digest(prepared.request.after))
        prepared.receipt.write_text(json.dumps(asdict(saved)))
        self.assertEqual(saved, edit.admit_receipt(prepared))
        rollback = prepared.rollback()
        self.assertEqual(edit.EditMode.SAVED, rollback.mode)
        with self.assertRaises(FixtureIntegrityError): edit.prepare_request(self.fixture, rollback, self.root / 'rollback')
        (self.fixture.root / prepared.request.file).write_text(prepared.request.after)
        edit.prepare_request(self.fixture, rollback, self.root / 'rollback')
        with self.assertRaises(FixtureIntegrityError):
            edit.prepare_request(self.fixture, replace(rollback, mode='SAVED'), self.root / 'invalid')

    def test_wrong_owner_pid_or_source_path_cannot_prepare_mutation(self):
        with self.assertRaises(FixtureIntegrityError):
            edit.prepare_edit(replace(self.fixture, token='wrong'), oracle.MUTATIONS[0].edit, 12345, self.root / 'bad')
        for pid in (0, -1, True, 9223372036854775808):
            with self.subTest(pid=pid), self.assertRaises(FixtureIntegrityError):
                edit.prepare_edit(self.fixture, oracle.MUTATIONS[0].edit, pid, self.root / 'bad')
        with self.assertRaises(FixtureIntegrityError):
            edit.owned_source(self.fixture, 'build.gradle.kts')
        target = self.fixture.root / oracle.SUPPLIERS
        target.unlink()
        outside = self.root / 'outside.kt'
        outside.write_text('protected')
        target.symlink_to(outside)
        with self.assertRaises(FixtureIntegrityError):
            edit.owned_source(self.fixture, oracle.SUPPLIERS)
        self.assertEqual('protected', outside.read_text())

    def test_duplicate_receipt_fields_do_not_manufacture_success(self):
        prepared = self.prepare()
        prepared.receipt.write_text('{"type":"REJECTED","type":"APPLIED_UNSAVED"}')
        with self.assertRaises(FixtureIntegrityError):
            edit.admit_receipt(prepared)


if __name__ == '__main__':
    unittest.main()
