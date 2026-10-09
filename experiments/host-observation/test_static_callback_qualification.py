"""Qualification admission/oracle tests; scripted receipts are not native semantic proof."""
import copy
import argparse
from dataclasses import asdict
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch
from zipfile import ZipFile

import jsonschema

import qualify_callback_tracing as q
import reproduce_semantic_queries as replay
import static_callback_oracle as oracle


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
LIVE = dict(root='/fixture', host='native-host', epoch=7, contentView='SAVED_PSI_COMMITTED', version=1)


def call(document, diagnostics=(), action='READ_RESULT'):
    return replay.ReplayCall(action, {}, dict(outcome='completed', exitCode=0), document,
                            list(diagnostics), [], 'UNAVAILABLE')


class StaticCallbackQualificationTest(unittest.TestCase):
    def setUp(self):
        self.suite = oracle.load_oracle(HERE / 'static-callback-fixture')

    def test_rooted_request_uses_fixed_policy_and_workspace_expansion(self):
        symbol = self.suite.complete_cases[0].supply.supplier
        request = q.static_request(symbol, q.Budget(maxResults=1), self.suite.root)
        encoded = json.loads(json.dumps(request))
        self.assertNotIn('completion', encoded['request'])
        self.assertEqual([{'type': 'EXPAND_RELATION', 'relation': 'CALLEES',
                           'expansionScope': {'type': 'WORKSPACE'}}], encoded['request']['steps'])
        self.assertEqual({'type': 'AT_LOCATION', 'file': 'suppliers/src/main/kotlin/fixture/staticcallbacks/suppliers/Suppliers.kt',
                          'offset': symbol.name_site.range.startInclusive}, encoded['request']['source'])
        schema = json.loads((ROOT / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.parameters.json').read_text())
        jsonschema.Draft202012Validator(schema).validate(request)

    def test_location_encoding_requires_symbol_inside_admitted_fixture_root(self):
        symbol = self.suite.complete_cases[0].supply.supplier
        with self.assertRaises(ValueError):
            q.static_location(symbol, '/another-fixture')

    def test_complete_route_grants_preserve_separate_small_presentation(self):
        grants = q.static_grants(65536)
        self.assertEqual([32, 128], [budget.maxResults for budget in grants.complete])
        self.assertEqual(1, grants.presentation.maxResults)
        self.assertEqual([65536, 65536], [budget.maxReturnedBytes for budget in grants.complete])
        self.assertEqual(65536, grants.presentation.maxReturnedBytes)

    def test_explicit_native_grants_apply_to_every_matched_execution(self):
        grants = q.static_grants(65536, max_elapsed_ms=30000, max_work_units=400000)
        for budget in (*grants.complete, grants.presentation):
            self.assertEqual(30000, budget.maxElapsedMs)
            self.assertEqual(400000, budget.maxWorkUnits)
        for elapsed, work in ((0, 1), (1, 0), (-1, 1), (1, -1)):
            with self.assertRaises(ValueError):
                q.static_grants(65536, elapsed, work)

    def test_qualified_binding_call_preserves_separate_named_callee_token(self):
        case = next(case for case in self.suite.complete_cases if case.name == 'immutable-alias-forwarding')
        site = q.named_call_site(case.supply.call, case.supply.formal.callable)
        self.assertEqual(case.supply.call.range.startInclusive + len('fixture.staticcallbacks.forwarding.'), site[1])
        self.assertEqual(site[1] + len('aliasWrapper'), site[2])

    def test_resource_control_retains_exact_capacity_cause_and_source_multiplicity(self):
        case = self.suite.resource_cases[0]
        document = self.rejected_document()
        document['rejection']['detail']['cause']['graphFailure']['cause'] = {
            'type': 'UNRESOLVED', 'obligations': ['RESULT_LIMIT_REACHED'], 'scan': 'INCOMPLETE'}
        detail = q.completion_rejection(document, case.rejection)
        self.assertEqual({'type': 'COMPLETE'}, detail['originalCoverage'])
        self.assertEqual(1, case.max_results)
        self.assertEqual(2, sum(len(supply.target_occurrences) for supply in case.supplies))
        self.assertEqual(self.suite.complete_cases[0].supplies, case.supplies)
        self.assertEqual(2, len({supply.body.range for supply in case.supplies}))
        self.assertEqual(2, len({supply.call.range for supply in case.supplies}))

    def test_resource_supply_rejection_retains_exact_sites_and_rejects_missing_evidence(self):
        case = self.suite.resource_cases[0]
        items = [{'occurrence': {'file': supply.call.file, 'range': asdict(supply.call.range)},
                  'lexical_owner': self.callable(supply.supplier),
                  'body': {'type': 'NAMED', 'callable': self.callable(supply.supplier)},
                  'target': {'type': 'UNAVAILABLE_SUPPLY', 'causes': ['RESULT_LIMIT_REACHED']}}
                 for supply in case.supplies]
        pages = [{'relation_observations': [{'callable_observations': items}]}]
        q.assert_unavailable_resource_supplies(pages, case)
        for changed in (items[:-1], items + items[:1], []):
            with self.assertRaises(AssertionError):
                q.assert_unavailable_resource_supplies([{'relation_observations': [{'callable_observations': changed}]}], case)
        items[0]['target']['causes'] = []
        with self.assertRaises(AssertionError): q.assert_unavailable_resource_supplies(pages, case)

    def test_retained_cursor_dto_preserves_zero_and_omits_absent_fields(self):
        absent = q.static_wire(q.StaticReadResultRequest('result:v1:opaque', q.Budget()))['request']
        self.assertNotIn('cursor', absent)
        self.assertNotIn('evidence_cursor', absent)
        selected = q.static_wire(q.StaticReadResultRequest('result:v1:opaque', q.Budget(), evidence_cursor=0))['request']
        self.assertEqual(0, selected['evidence_cursor'])
        self.assertEqual({'type': 'OCCURRENCES'}, selected['output'])

    def rejected_document(self):
        return {'status': 'rejected', 'rejection': {'type': 'COMPLETION_UNPROVEN', 'detail': {
            'model': 'COMPILER_RESOLVED_STATIC_V1', 'cause': {'type': 'CALLBACK_GRAPH_UNPROVEN', 'graphFailure': {
                'cause': {'type': 'UNRESOLVED', 'obligations': ['EXTERNAL_CALLABLE', 'NO_INVOCATION_PROVEN'],
                          'scan': 'INCOMPLETE'}, 'origin': 'RELATION', 'group': 0, 'observation': 0}},
            'policyProgress': {'type': 'EVIDENCE_ONLY'}, 'evidence': {'type': 'RETAINED', 'result': 'retained'},
            'originalCoverage': {'type': 'COMPLETE'}}}}

    def test_rejection_preserves_exact_typed_cause_and_retained_evidence(self):
        expected = next(case.rejection for case in self.suite.rejected_cases if case.name == 'external-escape')
        document = self.rejected_document()
        q.completion_rejection(document, expected)
        for mutation in ('obligations', 'type', 'evidence'):
            changed = copy.deepcopy(document)
            detail = changed['rejection']['detail']
            if mutation == 'obligations': detail['cause']['graphFailure']['cause']['obligations'] = ['PARAMETER_ESCAPES']
            if mutation == 'type': detail['cause']['graphFailure']['cause']['type'] = 'UNAVAILABLE'
            if mutation == 'evidence': detail['evidence'] = {'type': 'UNAVAILABLE', 'cause': 'CAPACITY_EXCEEDED'}
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.completion_rejection(changed, expected)

    def diagnostic(self):
        return {'schemaVersion': 6, 'limits': [{'parameter': 'DIAGNOSTIC_COUNT', 'value': 1000}],
                'correlation': {'type': 'bound', 'host': 'native-host', 'epoch': 7}, 'counters': [
            {'counter': name, 'contributor': 'NONE', 'count': 0}
            for name in q.STATIC_COUNTERS + q.POLICY_EVIDENCE_COUNTERS]}

    def test_explicit_native_zero_is_evidence_but_missing_or_wrong_basis_is_not(self):
        receipt = self.diagnostic()
        self.assertEqual({name: 0 for name in q.STATIC_COUNTERS}, q.native_callback_counters(call({}, [receipt]), LIVE))
        for mutation in ('missing', 'duplicate', 'negative', 'saturated', 'ceiling', 'version', 'basis', 'unavailable'):
            changed = copy.deepcopy(receipt)
            if mutation == 'missing': changed['counters'].pop(0)
            if mutation == 'duplicate': changed['counters'].append(changed['counters'][0])
            if mutation == 'negative': changed['counters'][0]['count'] = -1
            if mutation == 'saturated': changed['counters'][0]['count'] = 1000
            if mutation == 'ceiling': changed['limits'] = []
            if mutation == 'version': changed['schemaVersion'] = 5
            if mutation == 'basis': changed['correlation']['epoch'] = 8
            diagnostics = [] if mutation == 'unavailable' else [changed]
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.native_callback_counters(call({}, diagnostics), LIVE)

    def test_counter_contract_admits_only_declared_integer_versions(self):
        for version in (6, 7, 8, 9):
            receipt = self.diagnostic()
            receipt['schemaVersion'] = version
            with self.subTest(version=version):
                self.assertEqual(dict.fromkeys(q.STATIC_COUNTERS, 0),
                                 q.native_callback_counters(call({}, [receipt]), LIVE))
        for version in (5, 10, 6.0, '9', True, None):
            receipt = self.diagnostic()
            receipt['schemaVersion'] = version
            with self.subTest(version=version), self.assertRaises(AssertionError):
                q.native_callback_counters(call({}, [receipt]), LIVE)

    def test_semantic_fact_counters_require_explicit_native_observations(self):
        receipt = self.diagnostic()
        receipt['counters'] = [dict(counter=name, contributor='NONE', count=0) for name in q.SEMANTIC_FACT_COUNTERS]
        self.assertEqual(dict.fromkeys(q.SEMANTIC_FACT_COUNTERS, 0),
                         q.native_semantic_fact_counters(call({}, [receipt]), LIVE))
        for mutation in ('missing', 'duplicate', 'unavailable', 'wrong-basis', 'negative', 'saturated'):
            changed = copy.deepcopy(receipt)
            if mutation == 'missing': changed['counters'].pop()
            if mutation == 'duplicate': changed['counters'].append(changed['counters'][0])
            if mutation == 'wrong-basis': changed['correlation']['epoch'] += 1
            if mutation == 'negative': changed['counters'][0]['count'] = -1
            if mutation == 'saturated': changed['counters'][0]['count'] = 1000
            receipts = [] if mutation == 'unavailable' else [changed]
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.native_semantic_fact_counters(call({}, receipts), LIVE)

    def test_policy_rejected_run_requires_committed_retained_evidence(self):
        receipt = self.diagnostic()
        receipt['counters'][-3]['count'] = 1
        expected = {'QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED': 1,
                    'QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS': 0,
                    'QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED': 0}
        self.assertEqual(expected, q.assert_policy_evidence_publication(
            call(self.rejected_document(), [receipt], 'RUN'), LIVE))
        for mutation in ('uncommitted', 'commit-rejected', 'discarded'):
            changed = copy.deepcopy(receipt)
            if mutation == 'uncommitted': changed['counters'][-3]['count'] = 0
            if mutation == 'commit-rejected': changed['counters'][-2]['count'] = 1
            if mutation == 'discarded': changed['counters'][-1]['count'] = 1
            with self.subTest(mutation=mutation), self.assertRaisesRegex(AssertionError, 'public outcome'):
                q.assert_policy_evidence_publication(call(self.rejected_document(), [changed], 'RUN'), LIVE)

    def test_positive_run_and_policy_evidence_reads_require_explicit_publication_zeros(self):
        expected = {'QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED': 0,
                    'QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS': 0,
                    'QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED': 0}
        evidence_page = {'status': 'qualified', 'interpretation': {'type': 'POLICY_REJECTED_EVIDENCE'}}
        for action, document in (('RUN', {'status': 'complete'}), ('READ_RESULT', evidence_page)):
            with self.subTest(action=action):
                self.assertEqual(expected, q.assert_policy_evidence_publication(call(document, [self.diagnostic()], action), LIVE))
                changed = self.diagnostic()
                changed['counters'][-3]['count'] = 1
                with self.assertRaisesRegex(AssertionError, 'public outcome'):
                    q.assert_policy_evidence_publication(call(document, [changed], action), LIVE)

    def test_policy_publication_receipt_rejects_missing_ambiguous_or_unadmitted_counters(self):
        for mutation in ('missing', 'duplicate', 'contributor', 'negative', 'saturated', 'basis'):
            changed = self.diagnostic()
            if mutation == 'missing': changed['counters'].pop()
            if mutation == 'duplicate': changed['counters'].append(copy.deepcopy(changed['counters'][-1]))
            if mutation == 'contributor': changed['counters'][-1]['contributor'] = 'OTHER'
            if mutation == 'negative': changed['counters'][-1]['count'] = -1
            if mutation == 'saturated': changed['counters'][-1]['count'] = 1000
            if mutation == 'basis': changed['correlation']['epoch'] = 8
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.assert_policy_evidence_publication(call({'status': 'complete'}, [changed], 'RUN'), LIVE)

    def test_native_scan_outcomes_must_conserve_started_scans(self):
        receipt = self.diagnostic()
        receipt['counters'][0]['count'] = 1
        with self.assertRaisesRegex(AssertionError, 'CALLBACK_BODY_SCANS'):
            q.native_callback_counters(call({}, [receipt]), LIVE)

    def test_cold_eight_slot_selected_supply_rejection_is_exact_and_retained(self):
        cause = {'type': 'UNRESOLVED', 'obligations': ['RESULT_LIMIT_REACHED'], 'scan': 'INCOMPLETE'}
        document = {'status': 'rejected', 'rejection': {'type': 'COMPLETION_UNPROVEN', 'detail': {
            'model': 'COMPILER_RESOLVED_STATIC_V1', 'cause': {'type': 'CALLBACK_GRAPH_UNPROVEN',
                'graphFailure': {'cause': cause, 'origin': 'RELATION', 'group': 3, 'observation': 0}},
            'originalCoverage': {'type': 'COMPLETE'}, 'evidence': {'type': 'RETAINED'},
            'policyProgress': {'type': 'EVIDENCE_ONLY'}}}}
        q.assert_cold_selected_capacity(document)
        for status in ('complete', 'qualified'):
            with self.assertRaises(AssertionError): q.assert_cold_selected_capacity({**document, 'status': status})
        cause['obligations'] = []
        with self.assertRaises(AssertionError): q.assert_cold_selected_capacity(document)

    def test_each_alpha_callees_grant_requires_exact_formal_scan_and_reuse_counts(self):
        case = self.suite.complete_cases[0]
        counts = {'CALLBACK_BODY_SCANS': 3, 'CALLBACK_BODY_SCANS_COMPLETED': 3,
                  'CALLBACK_BODY_SCANS_INCOMPLETE': 0, 'CALLBACK_SUMMARY_HITS': 2,
                  'CALLBACK_SUMMARY_MISSES': 0, 'CALLBACK_SUMMARY_REJECTIONS': 0,
                  'CALLBACK_SUMMARIES_RETAINED': 0, 'CALLBACK_SUMMARY_RETENTION_REJECTIONS': 0,
                  'CALLBACK_FORWARDING_FORMALS': 3, 'CALLBACK_FORWARDING_EDGES': 2,
                  'CALLBACK_FIXED_POINTS_COMPLETED': 1, 'CALLBACK_FIXED_POINTS_REJECTED': 0}
        for grant in (32, 128):
            with self.subTest(grant=grant):
                q.assert_complete_scan_reuse(case, q.StaticRelation.CALLEES, counts)
        for counter in counts:
            changed = dict(counts)
            changed[counter] += 1
            with self.subTest(counter=counter), self.assertRaisesRegex(AssertionError, 'scan/reuse'):
                q.assert_complete_scan_reuse(case, q.StaticRelation.CALLEES, changed)

    def test_project_reuse_requires_one_summary_and_no_repeated_body_or_graph_work(self):
        case = self.suite.complete_cases[0]
        facts = dict.fromkeys(q.SEMANTIC_FACT_COUNTERS, 0)
        facts['SEMANTIC_FACT_PARTITIONS_REUSED'] = 2
        counts = dict.fromkeys(q.STATIC_COUNTERS, 0)
        counts['CALLBACK_SUMMARY_HITS'] = 2
        q.assert_complete_scan_reuse(case, q.StaticRelation.CALLEES, counts, facts)
        for mutation in ('extra-reuse', 'extracted', 'scan', 'formal', 'edge', 'supplier-hit'):
            changed_facts, changed_counts = dict(facts), dict(counts)
            if mutation == 'extra-reuse': changed_facts['SEMANTIC_FACT_PARTITIONS_REUSED'] = 3
            if mutation == 'extracted': changed_facts['SEMANTIC_FACT_PARTITIONS_EXTRACTED'] = 1
            if mutation == 'scan': changed_counts['CALLBACK_BODY_SCANS'] = 1
            if mutation == 'formal': changed_counts['CALLBACK_FORWARDING_FORMALS'] = 1
            if mutation == 'edge': changed_counts['CALLBACK_FORWARDING_EDGES'] = 1
            if mutation == 'supplier-hit': changed_counts['CALLBACK_SUMMARY_HITS'] = 1
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.assert_complete_scan_reuse(case, q.StaticRelation.CALLEES, changed_counts, changed_facts)

    def test_recursive_native_cases_require_each_formal_scanned_once_and_exact_graph_work(self):
        expected_sizes = {'recursive': (1, 1, 1), 'self-recursive-invocation': (1, 1, 2),
                          'mutual-recursive-invocation': (3, 3, 1)}
        cases = {case.name: case for case in self.suite.complete_cases}
        for name, (formals, edges, hits) in expected_sizes.items():
            counts = {'CALLBACK_BODY_SCANS': formals, 'CALLBACK_BODY_SCANS_COMPLETED': formals,
                'CALLBACK_BODY_SCANS_INCOMPLETE': 0, 'CALLBACK_SUMMARY_HITS': hits,
                'CALLBACK_SUMMARY_MISSES': 0, 'CALLBACK_SUMMARY_REJECTIONS': 0,
                'CALLBACK_SUMMARIES_RETAINED': 0, 'CALLBACK_SUMMARY_RETENTION_REJECTIONS': 0,
                'CALLBACK_FORWARDING_FORMALS': formals, 'CALLBACK_FORWARDING_EDGES': edges,
                'CALLBACK_FIXED_POINTS_COMPLETED': 1, 'CALLBACK_FIXED_POINTS_REJECTED': 0}
            with self.subTest(case=name):
                q.assert_complete_scan_reuse(cases[name], q.StaticRelation.CALLEES, counts)
            for counter in counts:
                changed = dict(counts)
                changed[counter] += 1
                with self.subTest(case=name, counter=counter), self.assertRaisesRegex(AssertionError, 'scan/reuse'):
                    q.assert_complete_scan_reuse(cases[name], q.StaticRelation.CALLEES, changed)

    def test_retained_group_direction_requires_exact_canonical_wire_spelling(self):
        q.assert_relation_direction([{'relation': 'callees'}], q.StaticRelation.CALLEES)
        q.assert_relation_direction([{'relation': 'callers'}], q.StaticRelation.CALLERS)
        for direction, invalid in ((q.StaticRelation.CALLEES, 'callers'),
                                   (q.StaticRelation.CALLERS, 'callees'),
                                   (q.StaticRelation.CALLEES, 'CALLEES'),
                                   (q.StaticRelation.CALLERS, 'unknown')):
            with self.subTest(direction=direction, invalid=invalid), self.assertRaisesRegex(AssertionError, 'direction changed'):
                q.assert_relation_direction([{'relation': invalid}], direction)

    def test_typed_rejection_pointer_must_identify_its_retained_observation(self):
        case = next(case for case in self.suite.rejected_cases if case.name == 'external-escape')
        detail = self.rejected_document()['rejection']['detail']
        groups = [{'callback_observations': [{'occurrence': self.span(case.supply.target_occurrences[0])}]}]
        q.assert_rejection_pointer(detail, groups, case)
        for field, value in (('origin', 'WALK'), ('group', 999), ('observation', 999)):
            changed = copy.deepcopy(detail)
            changed['cause']['graphFailure'][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                q.assert_rejection_pointer(changed, groups, case)

    def test_retained_rows_and_evidence_drain_independently(self):
        requests = []
        documents = [
            dict(status='qualified', live=LIVE, items=[dict(row_id='row-a')], next_cursor='next-row'),
            dict(status='complete', live=LIVE, items=[dict(row_id='row-b')]),
            dict(status='qualified', live=LIVE, items=[], relation_observations=[dict(group='first')],
                 evidence_window=dict(type='MORE', start=0, end=1, total=2)),
            dict(status='complete', live=LIVE, items=[], relation_observations=[dict(group='second')],
                 evidence_window=dict(type='FINAL', start=1, end=2, total=2)),
        ]
        def invoke(request):
            requests.append(request)
            return call(documents[len(requests) - 1])
        rows, row_calls, evidence_calls, live = q.retained_static_result('retained', q.Budget(), invoke)
        self.assertEqual(['row-a', 'row-b'], [item['row_id'] for item in rows])
        self.assertEqual(2, len(row_calls))
        self.assertEqual(2, len(evidence_calls))
        self.assertEqual(LIVE, live)
        self.assertEqual('next-row', requests[1]['request']['cursor'])
        self.assertNotIn('cursor', requests[2]['request'])
        self.assertEqual([0, 1], [request['request']['evidence_cursor'] for request in requests[2:]])

    def test_retained_evidence_must_advance_and_match_its_emitted_records(self):
        for window, records in ((dict(type='MORE', start=0, end=0, total=1), []),
                                (dict(type='FINAL', start=0, end=1, total=1), [])):
            responses = iter([dict(status='complete', live=LIVE, items=[]),
                dict(status='complete', live=LIVE, items=[], relation_observations=records, evidence_window=window)])
            with self.subTest(window=window), self.assertRaises(AssertionError):
                q.retained_static_result('retained', q.Budget(), lambda request: call(next(responses)))

    def span(self, expected):
        return dict(candidateSelector='candidate:' + str(expected.range.startInclusive), file=expected.file,
                    range=asdict(expected.range))

    def callable(self, expected):
        declaration = self.span(expected.declaration)
        return dict(declaration=declaration, compiler_target=dict(file=expected.declaration.file,
            range=asdict(expected.declaration.range), name=expected.name,
            compiler_evidence=dict(identity="canonical-signature-sha256-v1|" + "a" * 64, signature=dict(qualifiedIdentity=expected.fqn))))

    def formal(self, expected):
        return dict(callable=self.callable(expected.callable), position=expected.position,
                    parameter=self.span(expected.parameter))

    def observation(self, case, supply, occurrence):
        binding = self.formal(supply.formal)
        binding.update(type='BOUND', invocation_occurrence=self.span(supply.call),
            invocation_owner=dict(type='NAMED', callable=self.callable(supply.supplier)),
            invocation=dict(range=dict(start=supply.call.range.startInclusive, end=supply.call.range.endExclusive)))
        def forwarding(forward):
            target = self.formal(forward.target)
            target.update(type='BOUND', invocation_occurrence=self.span(forward.call),
                invocation_owner=dict(type='NAMED', callable=self.callable(forward.source.callable)))
            owner = forward.source.callable.declaration
            def site(span, role):
                return dict(enclosing=dict(file=owner.file,
                    range=dict(start=owner.range.startInclusive, end=owner.range.endExclusive),
                    compilerIdentity='canonical-signature-sha256-v1|' + 'a' * 64),
                    range=dict(start=span.range.startInclusive, end=span.range.endExclusive), role=dict(type=role))
            return dict(source=self.formal(forward.source), argument=self.span(forward.argument), target=target,
                callable_transfers=[dict(source=site(transfer.source, transfer.source_role),
                    target=site(transfer.target, transfer.target_role), kind=transfer.kind) for transfer in forward.transfers])
        invocations = []
        if isinstance(case, oracle.CompleteCase):
            invocations.append(dict(occurrence=self.span(case.invocation.occurrence), owner=dict(type='NAMED',
                callable=self.callable(case.invocation.owner)), forwardings=[forwarding(step) for step in case.forwardings]))
        return dict(lexical_owner=self.callable(supply.supplier), target=self.callable(supply.target),
            occurrence=self.span(occurrence), callback_body=self.span(supply.body),
            named_policy=dict(type='EXCLUDED', reason='NON_INLINE_ARGUMENT', excluded_boundary=self.span(supply.body)),
            flow=dict(type='OBSERVED', body=dict(occurrence=self.span(supply.body), compiler_evidence=dict(
                identity='canonical-signature-sha256-v1|' + 'a' * 64,
                signature=dict(qualifiedIdentity='anonymous@' + supply.body.file + '#' +
                    str(supply.body.range.startInclusive) + ':' + str(supply.body.range.endExclusive)))), binding=binding,
                owner_bindings=[], scan='EXHAUSTIVE', obligations=[], invocations=invocations,
                forwarding=dict(type='EXHAUSTED_GRAPH', root=self.formal(case.graph.root),
                    formals=[self.formal(formal) for formal in case.graph.formals],
                    forwardings=[forwarding(step) for step in case.graph.forwardings])))

    def test_additional_selected_supply_preserves_exact_original_binding_body_and_graph(self):
        case = self.suite.complete_cases[0]
        supply = case.supply
        original = self.observation(case, supply, supply.target_occurrences[0])
        flow = original['flow']
        owner = dict(file=supply.supplier.declaration.file,
                     range=dict(start=supply.supplier.declaration.range.startInclusive, end=supply.supplier.declaration.range.endExclusive),
                     compilerIdentity='canonical-signature-sha256-v1|' + 'a' * 64)
        start = dict(enclosing=owner, range=dict(start=supply.body.range.startInclusive, end=supply.body.range.endExclusive), role=dict(type='EXPRESSION_RESULT'))
        argument = dict(enclosing=owner, range=start['range'], role=dict(type='ARGUMENT', invocation=flow['binding']['invocation'], index=supply.formal.position))
        value = dict(origin=dict(type='ANONYMOUS', body=flow['body']), source=start, destination=argument,
                     transfers=[dict(source=start, target=argument, kind='ARGUMENT')], factories=[], invoked_callables=[self.callable(supply.formal.callable)])
        use = dict(supplier=dict(binding=flow['binding'], selection=dict(type='EXPLICIT', argument=argument), value=value),
                   forwarding=flow['forwarding'], invocations=flow['invocations'])
        observed = dict(occurrence=self.span(supply.call), lexical_owner=self.callable(supply.supplier),
                        body=dict(type='NAMED', callable=self.callable(supply.supplier)),
                        target=dict(type='CALLBACK_SUPPLIES', formals=[self.formal(supply.formal)], supplies=[use]))
        pages = [dict(relation_observations=[dict(callable_observations=[observed])])]
        self.assertEqual([], q.selected_supply_extras(pages, case))
        for mutation in ('body', 'formal', 'graph', 'owner', 'transfer'):
            broken = copy.deepcopy(pages)
            observation = broken[0]['relation_observations'][0]['callable_observations'][0]
            supplied = observation['target']['supplies'][0]
            if mutation == 'body': supplied['supplier']['value']['origin']['body']['occurrence']['range']['startInclusive'] += 1
            if mutation == 'formal': observation['target']['formals'][0]['position'] += 1
            if mutation == 'graph': supplied['forwarding']['formals'].clear()
            if mutation == 'owner': supplied['supplier']['value']['source']['enclosing']['file'] = '/foreign'
            if mutation == 'transfer': supplied['supplier']['value']['transfers'].clear()
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): q.selected_supply_extras(broken, case)

    def test_complete_native_evidence_requires_exact_exhaustive_graph_and_cycle_edges(self):
        for case in self.suite.complete_cases:
            page = dict(relation_observations=[dict(callback_observations=[
                self.observation(case, supply, occurrence)
                for supply in case.supplies for occurrence in supply.target_occurrences])])
            q.assert_complete_callback([page], case)
            for mutation in ('graph-missing', 'graph-routes-only', 'graph-wrong-root', 'graph-missing-formal',
                             'graph-duplicate-formal', 'graph-missing-edge', 'graph-duplicate-edge'):
                changed = copy.deepcopy(page)
                flow = changed['relation_observations'][0]['callback_observations'][0]['flow']
                graph = flow['forwarding']
                if mutation == 'graph-missing': del flow['forwarding']
                if mutation == 'graph-routes-only': flow['forwarding'] = dict(type='INVOCATION_ROUTES')
                if mutation == 'graph-wrong-root': graph['root']['position'] = 1
                if mutation == 'graph-missing-formal': graph['formals'].pop()
                if mutation == 'graph-duplicate-formal': graph['formals'].append(copy.deepcopy(graph['formals'][0]))
                if mutation == 'graph-missing-edge': graph['forwardings'].pop()
                if mutation == 'graph-duplicate-edge': graph['forwardings'].append(copy.deepcopy(graph['forwardings'][0]))
                with self.subTest(case=case.name, mutation=mutation), self.assertRaises((AssertionError, KeyError)):
                    q.assert_complete_callback([changed], case)

    def test_named_reference_requires_exact_supply_graph_and_terminal(self):
        case = self.suite.excluded_cases[0]
        # These response fragments are opaque public documents consumed by the
        # validator. Their expected identities come from the authored source oracle.
        source_case = self.suite.complete_cases[0]
        source = self.observation(source_case, source_case.supply, source_case.supply.target_occurrences[0])
        binding = copy.deepcopy(source['flow']['binding'])
        binding['invocation_occurrence'] = self.span(case.call)
        binding['invocation_owner']['callable'] = self.callable(case.supplier)
        invocations = copy.deepcopy(source['flow']['invocations'])
        invocations[0]['callable_transfers'] = []
        reference = dict(target=self.callable(case.target), dispatch_receiver=dict(type='ABSENT'),
            extension_receiver=dict(type='ABSENT'), flow=dict(type='SUPPLIED', binding=binding,
                invocations=invocations, forwarding=source['flow']['forwarding']))
        observed = dict(occurrence=self.span(case.reference), lexical_owner=self.callable(case.supplier),
            body=dict(type='NAMED', callable=self.callable(case.supplier)),
            target=dict(type='NAMED_REFERENCE', reference=reference))
        page = dict(relation_observations=[dict(callback_observations=[], callable_observations=[observed])])
        q.assert_named_reference([page], case)
        for mutation in ('receiver', 'target', 'supplier', 'occurrence', 'invocation', 'forwarding', 'multiplicity'):
            changed = copy.deepcopy(page)
            observation = changed['relation_observations'][0]['callable_observations'][0]
            value = observation['target']['reference']
            if mutation == 'receiver': value['dispatch_receiver'] = dict(type='UNBOUND')
            if mutation == 'target': value['target'] = self.callable(source_case.supply.target)
            if mutation == 'supplier': value['flow']['binding']['invocation_owner']['callable'] = self.callable(source_case.supply.supplier)
            if mutation == 'occurrence': observation['occurrence']['range']['endExclusive'] += 1
            if mutation == 'invocation': value['flow']['invocations'].clear()
            if mutation == 'forwarding': value['flow']['forwarding']['forwardings'].pop()
            if mutation == 'multiplicity': changed['relation_observations'][0]['callable_observations'].append(observation)
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.assert_named_reference([changed], case)

    def test_alias_proof_requires_each_exact_transfer_and_owner(self):
        case = next(case for case in self.suite.complete_cases if case.name == 'immutable-alias-forwarding')
        observed = self.observation(case, case.supply, case.supply.target_occurrences[0])
        edge = observed['flow']['forwarding']['forwardings'][0]
        q.assert_forwarding(edge, case.forwardings[0])
        for mutation in ('missing', 'truncated', 'kind', 'source', 'target', 'owner', 'identity', 'role'):
            changed = copy.deepcopy(edge)
            transfers = changed['callable_transfers']
            if mutation == 'missing': del changed['callable_transfers']
            if mutation == 'truncated': transfers.pop()
            if mutation == 'kind': transfers[0]['kind'] = 'ARGUMENT'
            if mutation == 'source': transfers[0]['source']['range']['start'] += 1
            if mutation == 'target': transfers[0]['target']['range']['end'] += 1
            if mutation == 'owner': transfers[0]['source']['enclosing']['file'] += '-other'
            if mutation == 'identity': transfers[0]['source']['enclosing']['compilerIdentity'] += 'wrong'
            if mutation == 'role': transfers[0]['source']['role'] = dict(type='LOCAL_READ')
            with self.subTest(mutation=mutation), self.assertRaises((AssertionError, KeyError)):
                q.assert_forwarding(changed, case.forwardings[0])

    def test_source_oracle_checks_wire_policy_supplier_and_each_exact_forwarding(self):
        case = self.suite.complete_cases[0]
        observations = [self.observation(case, supply, occurrence)
                        for supply in case.supplies for occurrence in supply.target_occurrences]
        page = dict(relation_observations=[dict(callback_observations=observations)])
        q.assert_complete_callback([page], case)
        reversed_page = copy.deepcopy(page)
        reversed_page['relation_observations'][0]['callback_observations'].reverse()
        q.assert_complete_callback([reversed_page], case)
        for mutation in ('supplier', 'formal', 'terminal', 'multiplicity', 'body-rebind', 'call-rebind', 'anonymous-identity'):
            changed = copy.deepcopy(page)
            observed = changed['relation_observations'][0]['callback_observations']
            if mutation == 'supplier': observed[0]['flow']['binding']['invocation_owner']['callable'] = self.callable(self.suite.complete_cases[1].supply.supplier)
            if mutation == 'formal': observed[0]['flow']['invocations'][0]['forwardings'][0]['target']['position'] = 1
            if mutation == 'terminal': observed[0]['flow']['invocations'][0]['occurrence']['range']['endExclusive'] += 1
            if mutation == 'multiplicity': observed.pop()
            if mutation == 'body-rebind':
                observed[1]['callback_body'] = observed[0]['callback_body']
                observed[1]['flow']['body'] = observed[0]['flow']['body']
            if mutation == 'call-rebind':
                observed[1]['flow']['binding']['invocation_occurrence'] = observed[0]['flow']['binding']['invocation_occurrence']
            if mutation == 'anonymous-identity':
                observed[1]['flow']['body']['compiler_evidence']['signature']['qualifiedIdentity'] = 'anonymous@other#0:1'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                q.assert_complete_callback([changed], case)

    def test_failed_acceptance_records_failure_without_issuing_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            args = argparse.Namespace(static_cross_module=True, output=output)
            with patch.object(q, 'run_admitted', side_effect=AssertionError('end pin changed')):
                with self.assertRaisesRegex(AssertionError, 'end pin changed'):
                    q.run(args)
            self.assertEqual({'type': 'NATIVE_STATIC_CALLBACK_FAILED',
                'cause': 'REQUIRED_EVIDENCE_REJECTED', 'stage': 'NATIVE_STATIC_CALLBACK_ACCEPTANCE'},
                json.loads((output / 'failure.json').read_text()))
            self.assertFalse((output / 'qualification.json').exists())

    def test_build_receipt_rejects_changed_source_or_failed_effect(self):
        fingerprint = q.SourceFingerprint('a' * 40, 'b' * 64, {})
        process = {'outcome': 'completed', 'exitCode': 0, 'cwd': str(ROOT),
                   'command': ['./gradlew', ':runtime:hosted:hostedPlugin', ':cli:installDist']}
        with patch.object(q, 'source_fingerprint', return_value=q.SourceFingerprint('c' * 40, 'b' * 64, {})):
            with self.assertRaisesRegex(AssertionError, 'SOURCE_CHANGED_DURING_CANDIDATE_BUILD'):
                q.candidate_build_receipt(ROOT, fingerprint, process, Path('/absent-cli'), Path('/absent-plugin'))
        with patch.object(q, 'source_fingerprint', return_value=fingerprint):
            process['exitCode'] = 1
            with self.assertRaisesRegex(AssertionError, 'CANDIDATE_BUILD_FAILED'):
                q.candidate_build_receipt(ROOT, fingerprint, process, Path('/absent-cli'), Path('/absent-plugin'))

    def test_candidate_composition_admits_only_staged_canonical_marker_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, cli = root / 'source', root / 'cli'
            origin = source / 'distribution/cli/one-shot-observation-v1'
            destination = cli / 'share/kast/one-shot-observation-v1'
            origin.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            origin.write_bytes(b'1\n')
            with self.assertRaisesRegex(AssertionError, 'CANDIDATE_MARKER_UNAVAILABLE'):
                q.candidate_composition_receipt(source, cli)
            destination.write_bytes(b'1\n')
            receipt = q.candidate_composition_receipt(source, cli)
            self.assertEqual('STATIC_CALLBACK_CANDIDATE_COMPOSITION', receipt.type)
            self.assertEqual(str(origin.resolve()), receipt.markerSource)
            self.assertEqual(str(destination.resolve()), receipt.markerDestination)
            self.assertEqual(replay.digest(origin), receipt.markerSourceSha256)
            self.assertEqual(receipt.markerSourceSha256, receipt.markerDestinationSha256)
            destination.write_bytes(b'foreign')
            with self.assertRaisesRegex(AssertionError, 'CANDIDATE_MARKER_MISMATCH'):
                q.candidate_composition_receipt(source, cli)
            destination.unlink()
            destination.symlink_to(origin)
            with self.assertRaisesRegex(AssertionError, 'CANDIDATE_MARKER_UNAVAILABLE'):
                q.candidate_composition_receipt(source, cli)

    def test_candidate_admission_returns_refined_build_and_composition_types(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source, cli, fixture = root / 'source', root / 'cli', root / 'fixture'
            origin = source / 'distribution/cli/one-shot-observation-v1'
            marker = cli / 'share/kast/one-shot-observation-v1'
            rpc, cli_jar = cli / 'bin/kast-mcp', cli / 'lib/candidate.jar'
            for target in (origin, marker, rpc, cli_jar): target.parent.mkdir(parents=True, exist_ok=True)
            fixture.mkdir()
            origin.write_bytes(b'1\n')
            marker.write_bytes(b'1\n')
            rpc.write_bytes(b'candidate launcher')
            cli_jar.write_bytes(b'candidate CLI jar')
            class_bytes = b'admitted class resource'
            nested = BytesIO()
            with ZipFile(nested, 'w') as jar: jar.writestr('fixture/Owner.class', class_bytes)
            jar_bytes = nested.getvalue()
            plugin = root / 'plugin.zip'
            with ZipFile(plugin, 'w') as archive: archive.writestr('plugin/lib/candidate.jar', jar_bytes)
            fingerprint = q.SourceFingerprint('a' * 40, 'b' * 64, {})
            process = {'outcome': 'completed', 'exitCode': 0, 'cwd': str(source),
                       'command': ['./gradlew', ':runtime:hosted:hostedPlugin', ':cli:installDist']}
            receipt_path = root / 'build.json'
            args = argparse.Namespace(build_receipt=receipt_path, candidate_plugin=plugin,
                                      candidate_cli=cli, rpc=rpc, root=fixture)
            pinned = {'source': asdict(fingerprint), 'cli': {'sha256': replay.digest(rpc),
                'jars': {str(cli_jar): replay.digest(cli_jar)}, 'transport': 'MCP_SESSION'},
                'plugin': {'jars': {'candidate.jar': sha256(jar_bytes).hexdigest()}, 'native': {
                    'classResources': {'fixture.Owner': {'sha256': sha256(class_bytes).hexdigest()}}}},
                'fixture': {'root': str(fixture), 'hashes': {}}}
            with patch.object(q, 'source_fingerprint', return_value=fingerprint):
                receipt = q.candidate_build_receipt(source, fingerprint, process, cli, plugin)
                replay.write(receipt_path, asdict(receipt))
                admitted = q.admit_candidate(args, pinned)
                self.assertIsInstance(admitted, q.CandidateBuildReceipt)
                self.assertIsInstance(admitted.sourceBefore, q.SourceFingerprint)
                self.assertIsInstance(admitted.composition, q.CandidateCompositionReceipt)
                self.assertEqual(receipt, admitted)
                marker.write_bytes(b'foreign')
                with self.assertRaisesRegex(AssertionError, 'CANDIDATE_CLI_CHANGED'):
                    q.admit_candidate(args, pinned)


if __name__ == '__main__':
    unittest.main()
