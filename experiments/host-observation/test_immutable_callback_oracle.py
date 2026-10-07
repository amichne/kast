"""Checks acceptance fixture integrity, not semantic implementation or native behavior."""
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
import shutil
import unittest

import immutable_callback_oracle as oracle
from static_callback_oracle import FixtureIntegrityError


class ImmutableCallbackOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = oracle.load_sources(Path(__file__).parent / 'immutable-callback-fixture')

    def test_capture_suppliers_defaults_and_receiver_cases_have_independent_expectations(self):
        cases = {case.name: case.expected for case in oracle.CASES}
        self.assertEqual(('alphaTarget',), cases['capture-alpha'].targets)
        self.assertEqual(('betaTarget',), cases['capture-beta'].targets)
        self.assertEqual(('alphaTarget',), cases['identity-return'].targets)
        self.assertEqual(('betaTarget',), cases['captured-alias'].targets)
        self.assertNotEqual(cases['default-omission'], cases['default-override'])
        self.assertEqual(('Receiver.member',), cases['bound-receiver'].targets)
        self.assertEqual(cases['bound-receiver'], cases['unbound-receiver'])
        self.assertEqual(('alphaTarget', 'betaTarget'), cases['branch-alternatives'].targets)
        self.assertEqual(cases['branch-alternatives'], cases['direct-branch'])
        self.assertEqual(cases['branch-alternatives'], cases['local-branch'])
        self.assertEqual(('alphaTarget',), cases['local-reference'].targets)
        self.assertEqual(oracle.CompleteEmpty(), cases['stored-not-invoked'])
        self.assertEqual(oracle.CompleteEmpty(), cases['formal-empty-inventory'])
        self.assertEqual(('alphaTarget', 'betaTarget'), cases['formal-supplier-inventory'].targets)
        self.assertEqual(oracle.Rejected(oracle.Boundary.MUTABLE_STORAGE), cases['mutable-control'])
        self.assertEqual(oracle.Rejected(oracle.Boundary.EXTERNAL_TRANSFER), cases['external-control'])

    def test_each_edit_changes_only_its_preimage_and_preserves_independent_module(self):
        independent = self.sources[-1]
        for mutation in oracle.MUTATIONS:
            with self.subTest(mutation=mutation.name):
                changed = oracle.apply_edit(self.sources, mutation.edit)
                self.assertEqual(1, sum(before != after for before, after in zip(self.sources, changed)))
                self.assertEqual(independent, changed[-1])
                self.assertNotEqual(oracle.source_digest(self.sources), oracle.source_digest(changed))
                rollback = oracle.SourceEdit(mutation.edit.file, mutation.edit.after, mutation.edit.before)
                self.assertEqual(self.sources, oracle.apply_edit(changed, rollback))
                self.assertEqual('independentEntry', mutation.independently_reusable_seed)

    def test_new_overload_preserves_existing_subject_and_target_source_positions(self):
        mutation = next(item for item in oracle.MUTATIONS if item.name == 'add-overload')
        changed = oracle.apply_edit(self.sources, mutation.edit)
        before, after = self.sources[0].text, changed[0].text
        for declaration in ('fun overload(value: Any): String = alphaTarget()',
                            'fun overloadEntry(): String = overload("text")'):
            self.assertEqual(before.index(declaration), after.index(declaration))
        self.assertIn('fun overload(value: String): String = betaTarget()', after)

    def test_changed_missing_or_ambiguous_preimages_reject(self):
        mutation = oracle.MUTATIONS[0]
        for replacement in ('', mutation.edit.before + '\n' + mutation.edit.before):
            changed = tuple(replace(source, text=source.text.replace(mutation.edit.before, replacement))
                            if source.relative_path == mutation.edit.file else source for source in self.sources)
            with self.subTest(replacement=replacement), self.assertRaises(FixtureIntegrityError):
                oracle.apply_edit(changed, mutation.edit)
        with self.assertRaises(FixtureIntegrityError):
            oracle.apply_edit(self.sources[1:], mutation.edit)

    def test_source_inventory_and_alias_anchor_are_required(self):
        for sources in (self.sources[:-1], self.sources[::-1], self.sources + self.sources[:1]):
            with self.subTest(inventory=[source.relative_path for source in sources]), self.assertRaises(FixtureIntegrityError):
                oracle.validate_sources(sources)
        changed = (replace(self.sources[0], text=self.sources[0].text.replace('val alias = callback', 'var alias = callback')), *self.sources[1:])
        with self.assertRaises(FixtureIntegrityError):
            oracle.validate_sources(changed)

    def test_additional_kotlin_script_rejects_exact_fixture_inventory(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary) / 'fixture'
            shutil.copytree(Path(__file__).parent / 'immutable-callback-fixture', root)
            extra = root / Path(oracle.SUPPLIERS).parent / 'Extra.kts'
            extra.write_text('println("unlisted")')
            with self.assertRaises(FixtureIntegrityError):
                oracle.load_sources(root)

    def test_edit_cannot_escape_or_claim_an_unchanged_preimage(self):
        for file, before, after in (('../outside.kt', 'x', 'y'), ('/outside.kt', 'x', 'y'), ('x.kt', '', 'y'), ('x.kt', 'x', 'x')):
            with self.subTest(file=file), self.assertRaises(FixtureIntegrityError):
                oracle.SourceEdit(file, before, after)

    def test_empty_complete_and_duplicate_targets_are_rejected(self):
        for targets in ((), ('alphaTarget', 'alphaTarget')):
            with self.subTest(targets=targets), self.assertRaises(FixtureIntegrityError):
                oracle.Complete(targets)


if __name__ == '__main__':
    unittest.main()
