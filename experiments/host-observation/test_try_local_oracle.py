import unittest
import re
from pathlib import Path

import try_local_oracle as oracle
import verify_try_local_receipts as qualification
import reproduce_semantic_queries as native
import supplemental_trust_oracle as supplemental


class TryLocalOracleTest(unittest.TestCase):
    def test_reference_row_projection_requires_the_canonical_closed_variant(self):
        occurrence = {'target': {'range': {'startInclusive': 2, 'endExclusive': 5}}}
        self.assertEqual([occurrence], qualification.reference_rows(
            {'items': [{'type': 'reference-occurrence', 'occurrence': occurrence}]}))
        for item in ({'type': 'occurrence', 'relation': occurrence},
                     {'type': 'unknown', 'occurrence': occurrence},
                     {'type': 'reference-occurrence', 'occurrence': None},
                     {'type': 'reference-occurrence'}):
            with self.assertRaises(qualification.QualificationFailure):
                qualification.reference_rows({'items': [item]})

    def test_local_file_address_requires_exact_absolute_admitted_workspace_path(self):
        case = oracle.load_oracle().locals[0]
        root = Path('/qualified-fixture')
        expected = str(root / case.declaration.file)
        qualification.check_local_file_address(case, {'type': 'WORKSPACE', 'path': expected}, root)
        for address in ({'type': 'WORKSPACE', 'path': case.declaration.file},
                        {'type': 'WORKSPACE', 'path': '/other-fixture/' + case.declaration.file},
                        {'type': 'WORKSPACE', 'path': expected + '/..'},
                        {'type': 'EXTERNAL', 'path': expected}):
            with self.assertRaises(qualification.QualificationFailure):
                qualification.check_local_file_address(case, address, root)

    def test_native_pin_template_consumes_one_encoded_owner_profile(self):
        text = (oracle.TEMPLATE.parent.parent.parent / 'semantic-reproduction-pin.kts.template').read_text()
        self.assertIn('val classes = input.requiredOwners.associateWith', text)
        self.assertNotIn('val branchClasses', text)
        for profile in native.QualificationSlice:
            request = native.NativePinRequest.create('/qualified-fixture', 123, profile)
            self.assertEqual(profile, request.qualificationSlice)
            self.assertEqual(tuple(sorted(native.NATIVE_COMMON_OWNERS | profile.changed_owners)), request.requiredOwners)
            self.assertEqual(profile, native.admit_native_owner_profile(profile, request.requiredOwners))
        checkpoint = native.NativePinRequest.create('/qualified-fixture', 123, 'QUERY_CHECKPOINT_STORAGE')
        self.assertEqual(47, len(checkpoint.requiredOwners))
        self.assertIn('io.github.amichne.kast.query.service.QueryService$Execution', checkpoint.requiredOwners)
        unrelated = native.TRY_BRANCH_NATIVE_OWNERS - native.CHECKPOINT_STORAGE_NATIVE_OWNERS
        self.assertEqual(6, len(unrelated))
        self.assertFalse(unrelated & set(checkpoint.requiredOwners))
        with self.assertRaisesRegex(ValueError, '^INVALID_QUALIFICATION_SLICE$'):
            native.NativePinRequest.create('/qualified-fixture', 123, None)

    def test_relation_work_profile_requires_current_contract_and_every_work_owner(self):
        profile = native.QualificationSlice.RELATION_WORK_REDUCTION
        source = (native.REPO / 'protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt').read_text()
        current = int(re.search(r'PUBLIC_TOOL_CONTRACT_VERSION = (\d+)', source)[1])
        self.assertEqual(current, profile.public_contract_version)
        native.admit_public_contract_slice(profile, current)
        owners = native.NATIVE_COMMON_OWNERS | native.TRY_BRANCH_NATIVE_OWNERS | native.RELATION_WORK_NATIVE_OWNERS
        self.assertEqual(profile, native.admit_native_owner_profile(profile, owners))
        for owner in native.RELATION_WORK_NATIVE_OWNERS:
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, '^NATIVE_OWNER_SLICE_MISMATCH$'):
                native.admit_native_owner_profile(profile, owners - {owner})
        for version in (6, 7, current - 1, current + 1, True, None):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, '^PUBLIC_CONTRACT_SLICE_MISMATCH$'):
                native.admit_public_contract_slice(profile, version)

    def test_checkpoint_profile_requires_actual_execution_accounting_and_observation_owners(self):
        profile = native.QualificationSlice.QUERY_CHECKPOINT_STORAGE
        owners = native.NATIVE_COMMON_OWNERS | native.CHECKPOINT_STORAGE_NATIVE_OWNERS
        self.assertEqual(profile, native.admit_native_owner_profile(profile, owners))
        native.admit_public_contract_slice(profile, 14)
        self.assertIn('io.github.amichne.kast.query.service.QueryService$Execution', profile.changed_owners)
        self.assertIn('io.github.amichne.kast.query.service.PipelineCheckpointKt', profile.changed_owners)
        self.assertIn('io.github.amichne.kast.query.service.PipelineSeed$Accounted$Companion', profile.changed_owners)
        self.assertIn('io.github.amichne.kast.query.contract.QueryExactReferences$Companion', profile.changed_owners)
        for owner in native.CHECKPOINT_STORAGE_NATIVE_OWNERS:
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, '^NATIVE_OWNER_SLICE_MISMATCH$'):
                native.admit_native_owner_profile(profile, owners - {owner})

    def test_preserves_authored_cases_and_shadowing_exclusions(self):
        intent = oracle.load_oracle()
        self.assertEqual(14, len(intent.flows))
        self.assertEqual(13, len(intent.locals))
        outer, inner = intent.locals[:2]
        self.assertEqual(outer.forbiddenReferences, inner.references)
        self.assertEqual(inner.forbiddenReferences, outer.references)
        self.assertNotEqual(outer.declaration, inner.declaration)
        self.assertEqual(2, next(case.normalBranchResults for case in intent.flows if case.name == 'nested'))
        self.assertEqual(oracle.CompletionExpectation.FINALLY_UNSUPPORTED,
                         next(case.expectation for case in intent.flows if case.name == 'finally-return'))

    def test_reference_positions_have_independent_source_text(self):
        sources = {oracle.SOURCE: oracle.TEMPLATE, oracle.OTHER_SOURCE: oracle.OTHER_TEMPLATE,
                   supplemental.SOURCE: supplemental.TEMPLATE}
        intent = oracle.load_oracle()
        for local in intent.locals:
            for site in (local.declaration, local.nameSite, *local.references, *local.forbiddenReferences):
                source = sources[site.file].read_text().encode('utf-16-le')
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
            sources = {oracle.SOURCE: oracle.TEMPLATE, oracle.OTHER_SOURCE: oracle.OTHER_TEMPLATE,
                       supplemental.SOURCE: supplemental.TEMPLATE}
            text = sources[local.declaration.file].read_text()
            encoded = text.encode('utf-16-le')
            for site in (local.ownerRange, *local.lexicalOwners):
                self.assertEqual(site.text, encoded[site.startInclusive * 2:site.endExclusive * 2].decode('utf-16-le'))

    def test_supplemental_results_preserve_independent_type_and_branch_expectations(self):
        intent = supplemental.load_oracle()
        self.assertEqual('supplemental', intent.scenario)
        self.assertEqual([(225, 239), (338, 354), (470, 494)],
                         [qualification.expected_bounds(case.producer) for case in intent.flows])
        self.assertEqual([oracle.CompletionExpectation.UNIT_UNSUPPORTED,
                          oracle.CompletionExpectation.ABRUPT_COMPLETION,
                          oracle.CompletionExpectation.NORMAL_RETURN], [case.expectation for case in intent.flows])
        self.assertEqual([0, 0, 1], [case.normalBranchResults for case in intent.flows])
        self.assertEqual([(599, 783), (599, 783), (812, 996), (812, 996)],
                         [qualification.expected_bounds(case.ownerRange) for case in intent.locals])
        self.assertEqual([(657, 676), (689, 739), (870, 889), (902, 952)],
                         [qualification.expected_bounds(case.declaration) for case in intent.locals])
        for first, second in zip(intent.locals[:2], intent.locals[2:]):
            self.assertEqual(first.forbiddenReferences, second.references)
            self.assertNotEqual(first.ownerRange, second.ownerRange)

    def test_changed_supplemental_type_is_fixture_failure_and_empty_observations_are_not_proof(self):
        changed = supplemental.TEMPLATE.read_text().replace('fun bottomProducer(): Nothing', 'fun bottomProducer(): Unit')
        with self.assertRaises(oracle.FixtureIntegrityError):
            supplemental.materialize(changed)
        with self.assertRaises(qualification.QualificationFailure):
            qualification.verify(supplemental.load_oracle(), [], supplemental.TEMPLATE.parent, 'TRY_BRANCH_RESULTS')

    def test_owner_identity_checker_rejects_collapsed_or_inconsistent_observations(self):
        locals_ = supplemental.load_oracle().locals
        def observations(identities):
            return [{'tool': 'query_symbols', 'arguments': {'request': {'source': {
                     'type': 'AT_LOCATION', 'file': case.nameSite.file, 'offset': case.nameSite.startInclusive}}},
                     'reply': {'type': 'complete', 'document': {'items': [
                         {'signature': {'address': {'ownerIdentity': identity}}}]}}}
                    for case, identity in zip(locals_, identities)]
        # These scripted receipts test rejection policy only; they do not establish compiler identities.
        self.assertEqual(2, qualification.check_owner_identities(
            locals_, observations(('first', 'first', 'second', 'second'))).observedPaths)
        for identities in (('same',) * 4, ('first', 'other', 'second', 'second')):
            with self.assertRaises(qualification.QualificationFailure):
                qualification.check_owner_identities(locals_, observations(identities))

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
                      'io.github.amichne.kast.workspace.intellij.read.IntellijLocalIdentityObservationKt',
                      'io.github.amichne.kast.source.intellij.SourceLocalOwnerCallableIdentityKt',
                      'io.github.amichne.kast.relation.intellij.IntellijLocalRelationOwnerSignatureKt'):
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
