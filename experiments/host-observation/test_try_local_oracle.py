import unittest

import try_local_oracle as oracle
import verify_try_local_receipts as qualification
import reproduce_semantic_queries as native


class TryLocalOracleTest(unittest.TestCase):
    def test_preserves_authored_cases_and_shadowing_exclusions(self):
        intent = oracle.load_oracle()
        self.assertEqual(11, len(intent.flows))
        self.assertEqual(9, len(intent.locals))
        outer, inner = intent.locals[:2]
        self.assertEqual(outer.forbiddenReferences, inner.references)
        self.assertEqual(inner.forbiddenReferences, outer.references)
        self.assertNotEqual(outer.declaration, inner.declaration)
        self.assertEqual(2, next(case.normalBranchResults for case in intent.flows if case.name == 'nested'))
        self.assertEqual(oracle.CompletionExpectation.FINALLY_UNSUPPORTED,
                         next(case.expectation for case in intent.flows if case.name == 'finally-return'))

    def test_reference_positions_have_independent_source_text(self):
        text = oracle.TEMPLATE.read_text().encode('utf-16-le')
        other = oracle.OTHER_TEMPLATE.read_text().encode('utf-16-le')
        intent = oracle.load_oracle()
        for local in intent.locals:
            for site in (local.declaration, local.nameSite, *local.references, *local.forbiddenReferences):
                source = text if site.file == oracle.SOURCE else other
                self.assertEqual(site.text,
                                 source[site.startInclusive * 2:site.endExclusive * 2].decode('utf-16-le'))

    def test_source_reordering_changes_locations_without_collapsing_binding(self):
        baseline = oracle.load_oracle()
        moved = oracle.materialize('// Unicode control: \U0001f642\n' + oracle.TEMPLATE.read_text())
        delta = len('// Unicode control: \U0001f642\n'.encode('utf-16-le')) // 2
        self.assertNotEqual(baseline.sourceSha256, moved.sourceSha256)
        for left, right in zip(baseline.locals, moved.locals):
            self.assertEqual(left.declaration.startInclusive + delta, right.declaration.startInclusive)
            self.assertEqual(left.declaration.text, right.declaration.text)

    def test_changed_or_missing_producer_is_fixture_failure(self):
        text = oracle.TEMPLATE.read_text()
        changed = text.replace('fun tryResult(', 'fun missingResult(')
        with self.assertRaises(oracle.FixtureIntegrityError):
            oracle.materialize(changed)

    def test_no_captured_calls_cannot_establish_qualification(self):
        with self.assertRaises(qualification.QualificationFailure):
            qualification.verify(oracle.load_oracle(), [], oracle.TEMPLATE.parent, 'TRY_LOCAL_IDENTITIES')

    def test_rejected_capture_is_not_expected_product_success(self):
        with self.assertRaises(qualification.QualificationFailure):
            qualification.document({'tool': 'query_symbols', 'reply': {'type': 'rejected_document'}})

    def test_stale_observation_requires_closed_semantic_reference_reason(self):
        def rejected(reason):
            return {'tool': 'query_symbols', 'isError': False,
                    'reply': {'type': 'rejected_document', 'document': {'status': 'rejected',
                    'rejection': {'type': 'reference-rejected', 'reason': reason}}}}
        for reason in ('stale-authority', 'stale-generation', 'revalidation-content-changed'):
            self.assertEqual(reason, qualification.stale_document(rejected(reason))['rejection']['reason'])
        for reason in ('malformed', 'unknown', 'revalidation-compiler-unavailable'):
            with self.assertRaises(qualification.QualificationFailure):
                qualification.stale_document(rejected(reason))

    def test_replay_checker_preserves_evidence_and_cursor(self):
        def observation(evidence):
            return {'tool': 'query_symbols', 'arguments': {'request': {'type': 'READ_RESULT',
                    'result': 'retained-fixture-input', 'output': {'type': 'VALUE_PATHS'}}},
                    'reply': {'type': 'complete', 'document': {'items': [{'evidence': evidence}], 'next_cursor': None}}}
        left = observation('DIRECT')
        self.assertEqual(1, qualification.check_replays([left, observation('DIRECT')]).observedPaths)
        with self.assertRaises(qualification.QualificationFailure):
            qualification.check_replays([left, observation('ERASED')])
        with self.assertRaises(qualification.QualificationFailure):
            qualification.check_replays([left])

    def test_local_owner_chain_has_exact_independent_source_anchors(self):
        intent = oracle.load_oracle()
        outer, inner = intent.locals[:2]
        self.assertEqual(outer.ownerRange, inner.ownerRange)
        self.assertEqual(outer.lexicalOwners, inner.lexicalOwners[:1])
        self.assertEqual(2, len(inner.lexicalOwners))
        for local in intent.locals:
            text = oracle.TEMPLATE.read_text() if local.declaration.file == oracle.SOURCE else oracle.OTHER_TEMPLATE.read_text()
            encoded = text.encode('utf-16-le')
            for site in (local.ownerRange, *local.lexicalOwners):
                self.assertEqual(site.text, encoded[site.startInclusive * 2:site.endExclusive * 2].decode('utf-16-le'))

    def test_try_branch_owner_profile_requires_exact_try_classes_without_local_classes(self):
        owners = native.NATIVE_COMMON_OWNERS | native.TRY_BRANCH_NATIVE_OWNERS
        self.assertEqual(native.QualificationSlice.TRY_BRANCH_RESULTS,
                         native.admit_native_owner_profile('TRY_BRANCH_RESULTS', owners))
        enum_owner = 'io.github.amichne.kast.relation.intellij.NativeCompilerTypeProof'
        self.assertIn(enum_owner, owners)
        with self.assertRaisesRegex(ValueError, '^NATIVE_OWNER_SLICE_MISMATCH$'):
            native.admit_native_owner_profile('TRY_BRANCH_RESULTS', owners - {enum_owner})
        with self.assertRaisesRegex(ValueError, '^NATIVE_OWNER_SLICE_MISMATCH$'):
            native.admit_native_owner_profile('TRY_BRANCH_RESULTS', owners | native.LOCAL_IDENTITY_NATIVE_OWNERS)

    def test_combined_owner_profile_rejects_missing_local_or_source_projection(self):
        owners = native.NATIVE_COMMON_OWNERS | native.TRY_LOCAL_NATIVE_OWNERS
        self.assertEqual(native.QualificationSlice.TRY_LOCAL_IDENTITIES,
                         native.admit_native_owner_profile('TRY_LOCAL_IDENTITIES', owners))
        for owner in ('io.github.amichne.kast.symbol.contract.LocalDeclarationAddress',
                      'io.github.amichne.kast.source.intellij.LocalCompilerTypeProof',
                      'io.github.amichne.kast.workspace.intellij.read.IntellijLocalIdentityObservationKt'):
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, '^NATIVE_OWNER_SLICE_MISMATCH$'):
                native.admit_native_owner_profile('TRY_LOCAL_IDENTITIES', owners - {owner})

    def test_slice_rejects_unknown_profile_or_incompatible_public_contract(self):
        for value in (None, '', 'SKIP_LOCALS', 'TRY_BRANCH_RESULTS_AND_OPTIONAL_LOCALS'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, '^INVALID_QUALIFICATION_SLICE$'):
                native.QualificationSlice.admit(value)
        for slice_, expected, incompatible in (('TRY_BRANCH_RESULTS', 6, 7), ('TRY_LOCAL_IDENTITIES', 7, 6)):
            self.assertEqual(slice_, native.admit_public_contract_slice(slice_, expected).value)
            for observed in (None, incompatible, float(expected), True):
                with self.subTest(slice=slice_, version=observed), self.assertRaisesRegex(ValueError, '^PUBLIC_CONTRACT_SLICE_MISMATCH$'):
                    native.admit_public_contract_slice(slice_, observed)

    def test_receipt_slice_rejects_cross_candidate_or_local_stale_case_in_try_capture(self):
        intent = oracle.load_oracle()
        with self.assertRaisesRegex(qualification.QualificationFailure, 'another or missing qualification slice'):
            qualification.verify(intent, [{'qualificationSlice': 'TRY_LOCAL_IDENTITIES'}], oracle.TEMPLATE.parent, 'TRY_BRANCH_RESULTS')
        with self.assertRaisesRegex(qualification.QualificationFailure, 'local stale/reacquisition stage'):
            qualification.verify(intent, [{'qualificationSlice': 'TRY_BRANCH_RESULTS', 'phase': 'STALE_REFERENCE'}],
                                 oracle.TEMPLATE.parent, 'TRY_BRANCH_RESULTS')
        local = intent.locals[0]
        call = {'qualificationSlice': 'TRY_BRANCH_RESULTS', 'arguments': {'request': {'source': {'type': 'AT_LOCATION',
                'file': local.nameSite.file, 'offset': local.nameSite.startInclusive}}}}
        with self.assertRaisesRegex(qualification.QualificationFailure, 'local declaration observation'):
            qualification.verify(intent, [call], oracle.TEMPLATE.parent, 'TRY_BRANCH_RESULTS')


if __name__ == '__main__':
    unittest.main()
