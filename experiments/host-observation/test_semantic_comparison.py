"""Pure comparison and scripted replay checks; no native-performance claims."""
import copy
import argparse
from dataclasses import asdict
from contextlib import contextmanager
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import sys
import subprocess
import unittest
from unittest.mock import patch

import reproduce_semantic_queries as r
import hosted_timing as timing
from test_hosted_timing import window as timing_window, READ


class SemanticComparisonTest(unittest.TestCase):
    def test_observation_capture_is_removed_once_from_subsequent_workload_wall_time(self):
        from dataclasses import replace
        first = replace(self.trial().calls[0], observationCaptureNanos=50)
        second = replace(first, observationCaptureNanos=70)
        self.assertEqual(200, r.workload_wall_nanos(200, []))
        self.assertEqual(430, r.workload_wall_nanos(480, [first]))
        self.assertEqual(610, r.workload_wall_nanos(730, [first, second]))
        # Raw process/stage clocks remain unchanged; observation duration is its own quantity.
        self.assertEqual(200, first.process['elapsedNanos'])
        self.assertEqual(120, first.diagnostics[0]['stages'][0]['durationNanos'])

    def with_timing(self, trial):
        # Preserve the independently authored semantic fixture, then supply bounded observation records.
        from dataclasses import replace
        call = trial.calls[0]
        diagnostic = {**call.diagnostics[0], 'readId': READ}
        observation = replace(timing_window(), diagnostics=(diagnostic,))
        changed = replace(call, diagnostics=[diagnostic], hostedObservations=observation)
        return r.finish_trial(trial.workload, trial.repetition, trial.warmup, [changed],
                              trial.measurements['totalNanos'], trial.measurements['firstUsableNanos'])

    def test_full_hosted_timing_is_compared_separately_from_work_and_round_trips_actual_receipts(self):
        from dataclasses import replace
        a, b = self.with_timing(self.trial()), self.with_timing(self.trial(work=20))
        call = b.calls[0]
        faster = tuple(replace(record, elapsedNanos=record.elapsedNanos // 2)
                       for record in call.hostedObservations.transport)
        changed = replace(call, hostedObservations=replace(call.hostedObservations, transport=faster))
        b = r.finish_trial('exact-source', 0, False, [changed], 240, 200)
        result = r.compare_trials(a, b, same_artifact=False)
        self.assertFalse(result.lessWork, 'Lower transport elapsed time does not prove less native work')
        self.assertIsInstance(result.hostedTimings, r.JoinedTimingComparison)
        self.assertEqual(130, result.hostedTimings.baseline[0].transport[5].elapsedNanos)
        self.assertEqual(65, result.hostedTimings.candidate[0].transport[5].elapsedNanos)
        self.assertEqual(a.calls[0].diagnostics[0], result.hostedTimings.baseline[0].reads[0].diagnostic)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trial.json'
            path.write_text(json.dumps(asdict(a)))
            self.assertEqual(a, r.load_trial(path))
            for version in (1, 2.0, True, 3):
                path.write_text(json.dumps({**asdict(a), 'schemaVersion': version}))
                with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'INVALID_TRIAL_VERSION'):
                    r.load_trial(path)
            for duration in (-1, True, 1.5):
                malformed = asdict(a)
                malformed['calls'][0]['observationCaptureNanos'] = duration
                path.write_text(json.dumps(malformed))
                with self.subTest(duration=duration), self.assertRaisesRegex(ValueError, 'INVALID_OBSERVATION_CAPTURE_DURATION'):
                    r.load_trial(path)

    def test_missing_or_forged_correlation_cannot_manufacture_hosted_timing(self):
        from dataclasses import replace
        a = self.with_timing(self.trial())
        call = a.calls[0]
        duplicate = {**call.diagnostics[0], 'readId': '44444444-4444-4444-8444-444444444444'}
        forged = replace(call, hostedObservations=replace(call.hostedObservations,
                         diagnostics=(*call.hostedObservations.diagnostics, duplicate)))
        self.assertIsInstance(r.call_timing(forged), timing.UnavailableTiming)
        self.assertIn(timing.TimingFailure.SEMANTIC_CORRELATION_UNAVAILABLE, r.call_timing(forged).failures)
        self.assertIsInstance(r.compare_trials(a, self.trial(), False).hostedTimings,
                              r.UnavailableTimingComparison)

    def trial(self, token='exact:v5:first', work=20):
        item = dict(type='exact-symbol', ref=token, kind='classlike', name='FixtureLogger',
                    signature=dict(type='class-like', qualifiedIdentity='repro.logging.FixtureLogger'),
                    location=dict(file='/fixture/Logger.kt', range=dict(startInclusive=0, endExclusive=10)),
                    connections=[], source=dict(text='class FixtureLogger', startLine=1, endLine=1))
        response = dict(status='complete', items=[item], failures=[], omissions=[],
                        coverage=dict(exhaustive=True), live=dict(root='/fixture', host='host', epoch=1,
                        contentView='SAVED_PSI_COMMITTED', version=1))
        diagnostic = dict(schemaVersion=5, readId='read', correlation=dict(type='bound', host='host', epoch=1),
                          counters=[dict(counter='COMPILER_REFINEMENTS', contributor='NONE', count=work), dict(counter='NATIVE_DISCOVERY_PAGES', contributor='NONE', count=1),
                                    dict(counter='NATIVE_RELATION_PAGES', contributor='NONE', count=0)],
                          nativePhaseDurations=[dict(phase='EXACT_REFINEMENT', durationNanos=100)],
                          stages=[dict(stage='SEMANTIC_READ', startedNanos=0, durationNanos=120)],
                          gauges=[], terminations=[], outcome=dict(type='evaluated', outcome='COMPLETE'))
        call = r.ReplayCall('RUN', {}, dict(outcome='completed', exitCode=0, elapsedNanos=200,
                            stdout=json.dumps(response), stderr=''), response, [diagnostic], [], 'MATCHED')
        return r.finish_trial('exact-source', 0, False, [call], 240, 200)

    def test_run_handles_are_normalized_but_source_and_identity_are_preserved(self):
        a, b = self.trial(), self.trial('exact:v5:second')
        self.assertEqual(a.semantic, b.semantic)
        b.semantic['items'][0]['source']['text'] = 'wrong source'
        self.assertNotEqual(a.semantic, b.semantic)

    def test_candidate_handle_reuse_requires_the_same_site_across_pages(self):
        prefix = copy.deepcopy(self.production_trial('dense-references').calls[0].response)
        prefix['items'] = prefix['items'][:1]
        prefix.update(status='qualified', coverage=dict(exhaustive=False))
        terminal = copy.deepcopy(prefix)
        terminal.update(status='complete', coverage=dict(exhaustive=True))
        accepted = r.stable_semantics([prefix, terminal])
        self.assertEqual(2, len(accepted['items']))
        self.assertEqual(accepted['items'][0], accepted['items'][1])
        for change in ('file', 'range'):
            moved = copy.deepcopy(terminal)
            site = moved['items'][0]['occurrence']['occurrence']
            if change == 'file': site['file'] = '/scripted/kast/other/QueryPlan.kt'
            else: site['range']['endExclusive'] += 1
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'HANDLE_IDENTITY_CHANGED'):
                r.stable_semantics([prefix, moved])

    def test_semantic_regression_blocks_smaller_response(self):
        a, b = self.trial(), self.trial(work=1)
        b.semantic['items'] = []
        result = r.compare_trials(a, b, same_artifact=False)
        self.assertEqual('SEMANTIC_REGRESSION', result.type)
        self.assertFalse(result.lessWork)

    def test_known_counter_difference_is_not_elapsed_time(self):
        result = r.compare_trials(self.trial(), self.trial(work=10), same_artifact=False)
        self.assertEqual('EQUIVALENT', result.type)
        self.assertEqual(-10, result.deltas['counters']['COMPILER_REFINEMENTS/NONE'])
        self.assertTrue(result.lessWork)

    def locator_trial(self, retained, rejected, model_work=45):
        """Authored completed-pipeline counts; no native execution or stored-locator claim."""
        template = self.production_trial('dense-references')
        original = template.calls[0]
        diagnostics = copy.deepcopy(original.diagnostics)
        counts = {'REVALIDATION_LOCATORS_RETAINED': retained,
                  'REVALIDATION_LOCATORS_REJECTED': rejected}
        for count in diagnostics[0]['counters']:
            if count['counter'] in counts: count['count'] = counts[count['counter']]
        diagnostics[0]['counters'].append(dict(counter='IMPORTED_PROJECTS', contributor='NONE', count=model_work))
        call = r.ReplayCall(original.action, original.request, original.process,
                            original.response, diagnostics, original.phases, original.correlation)
        return r.finish_trial('dense-references', 0, False, [call], 240, 200, r.WorkloadProfile.KAST_SOURCE)

    def test_retention_quality_change_preserves_raw_outcomes_and_can_accompany_less_model_work(self):
        baseline, candidate = self.locator_trial(2, 253), self.locator_trial(4, 251, model_work=28)
        result = r.compare_trials(baseline, candidate, same_artifact=False)
        self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
        self.assertEqual(2, result.deltas['counters']['REVALIDATION_LOCATORS_RETAINED/NONE'])
        self.assertEqual(-2, result.deltas['counters']['REVALIDATION_LOCATORS_REJECTED/NONE'])
        self.assertEqual(-17, result.deltas['counters']['IMPORTED_PROJECTS/NONE'])
        self.assertTrue(result.lessWork)
        self.assertEqual(dict(baseline=255, candidate=255, delta=0), result.deltas['locatorRetentionAttempts'])
        unchanged = r.compare_trials(self.locator_trial(0, 0), self.locator_trial(0, 0), False)
        self.assertTrue(r.suite_reduces_work([unchanged, result, unchanged]))

    def test_retention_outcome_quality_alone_cannot_claim_less_work(self):
        result = r.compare_trials(self.locator_trial(2, 253), self.locator_trial(4, 251), False)
        self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
        self.assertFalse(result.lessWork)
        self.assertEqual(dict(baseline=255, candidate=255, delta=0), result.deltas['locatorRetentionAttempts'])
        self.assertFalse(r.suite_reduces_work([result]))

    def test_fewer_completed_retention_attempts_can_establish_less_work(self):
        result = r.compare_trials(self.locator_trial(2, 253), self.locator_trial(4, 250), False)
        self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
        self.assertTrue(result.lessWork)
        self.assertEqual(dict(baseline=255, candidate=254, delta=-1), result.deltas['locatorRetentionAttempts'])
        self.assertTrue(r.suite_reduces_work([result]))

    def test_retention_attempt_growth_or_worse_outcomes_block_less_model_work(self):
        baseline = self.locator_trial(2, 253)
        for reason, retained, rejected in [('attempts-increased', 5, 251),
                                          ('rejections-increased', 4, 254),
                                          ('retained-decreased', 1, 251)]:
            with self.subTest(reason=reason):
                result = r.compare_trials(baseline, self.locator_trial(retained, rejected, model_work=28), False)
                self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
                self.assertEqual(-17, result.deltas['counters']['IMPORTED_PROJECTS/NONE'])
                self.assertFalse(result.lessWork)
                self.assertFalse(r.suite_reduces_work([result]))

    def test_partial_retention_counter_pair_is_missing_evidence_even_when_sets_match(self):
        for missing in ('REVALIDATION_LOCATORS_RETAINED/NONE', 'REVALIDATION_LOCATORS_REJECTED/NONE'):
            baseline, candidate = self.trial(), self.trial(work=1)
            for trial in (baseline, candidate):
                trial.measurements['counters'].update({'REVALIDATION_LOCATORS_RETAINED/NONE': 0,
                                                       'REVALIDATION_LOCATORS_REJECTED/NONE': 0})
                trial.measurements['counters'].pop(missing)
            with self.subTest(missing=missing):
                result = r.compare_trials(baseline, candidate, False)
                self.assertIs(r.ComparisonState.MISSING_EVIDENCE, result.type)
                self.assertNotIn(missing, result.deltas['counters'])
                self.assertIsNone(result.deltas.get('locatorRetentionAttempts'))
                self.assertFalse(result.lessWork)

    def test_suite_improvement_accepts_unchanged_exact_and_scoped_peers(self):
        comparisons = [r.compare_trials(self.production_trial(workload, work=20),
            self.production_trial(workload, work=1 if workload == 'dense-references' else 20),
            same_artifact=False) for workload in ('exact-source', 'dense-references', 'scoped-all')]
        self.assertEqual([False, True, False], [comparison.lessWork for comparison in comparisons])
        self.assertTrue(all(comparison.type == r.ComparisonState.EQUIVALENT for comparison in comparisons))
        self.assertTrue(r.suite_reduces_work(comparisons))

    def test_suite_improvement_rejects_counter_growth_regression_or_missing_evidence(self):
        improvement = r.TrialComparison(r.ComparisonState.EQUIVALENT, True,
                                       dict(counters={'COMPILER_REFINEMENTS/NONE': -10}), [])
        rejected_peers = [
            r.TrialComparison(r.ComparisonState.EQUIVALENT, False,
                              dict(counters={'COMPILER_REFINEMENTS/NONE': 1}), []),
            r.TrialComparison(r.ComparisonState.SEMANTIC_REGRESSION, True,
                              dict(counters={'COMPILER_REFINEMENTS/NONE': -20}), []),
            r.TrialComparison(r.ComparisonState.EQUIVALENT, True, dict(counters=None), []),
            r.TrialComparison(r.ComparisonState.EQUIVALENT, True, {}, []),
        ]
        for peer in rejected_peers:
            with self.subTest(type=peer.type, deltas=peer.deltas):
                self.assertFalse(r.suite_reduces_work([improvement, peer]))

    def test_empty_unchanged_and_self_comparison_suites_cannot_claim_improvement(self):
        self.assertFalse(r.suite_reduces_work([]))
        unchanged = [r.compare_trials(self.production_trial(workload), self.production_trial(workload),
            same_artifact=False) for workload in ('exact-source', 'dense-references', 'scoped-all')]
        self.assertFalse(r.suite_reduces_work(unchanged))
        repeats = [r.compare_trials(self.production_trial(workload, work=20),
            self.production_trial(workload, work=1), same_artifact=True)
            for workload in ('exact-source', 'dense-references', 'scoped-all')]
        self.assertTrue(all(comparison.type == r.ComparisonState.EQUIVALENT for comparison in repeats))
        self.assertTrue(all(not comparison.lessWork for comparison in repeats))
        self.assertFalse(r.suite_reduces_work(repeats))

    def test_missing_diagnostics_are_unavailable_not_zero(self):
        a = self.trial()
        a.measurements['counters'] = None
        result = r.compare_trials(a, self.trial(), same_artifact=False)
        self.assertEqual('MISSING_EVIDENCE', result.type)
        self.assertFalse(result.lessWork)
        self.assertIsNone(result.deltas['counters'])

    def test_self_comparison_is_repeatability_not_improvement(self):
        result = r.compare_trials(self.trial(), self.trial(work=1), same_artifact=True)
        self.assertEqual('EQUIVALENT', result.type)
        self.assertFalse(result.lessWork)

    def test_qualifications_omissions_failures_and_scope_remain_semantic(self):
        for field, value in [('coverage', dict(exhaustive=False)), ('failures', ['failure']),
                             ('omissions', ['omission']), ('qualification', dict(limitations=['unsupported']))]:
            a, b = self.trial(), self.trial()
            b.semantic['terminal'][field] = value
            self.assertEqual('SEMANTIC_REGRESSION', r.compare_trials(a, b, False).type)

    def test_incompatible_policy_and_fixture_are_rejected(self):
        a = dict(fixture={'A.kt':'hash'}, environment={'jdk':'25'}, limits={}, requests={},
                 warmups=1, repetitions=2, concurrency=1, maxCalls=256, timeoutSeconds=60)
        for key in a:
            b = copy.deepcopy(a)
            b[key] = 'different'
            self.assertIn(key, r.incompatible_runs(a, b))

    def test_canceled_and_incomplete_trials_are_retained(self):
        for outcome in ('harness-timeout', 'canceled'):
            call = r.ReplayCall('RUN', {}, dict(outcome=outcome, stdout='', stderr='', elapsedNanos=5),
                                None, [], [], 'UNAVAILABLE')
            trial = r.finish_trial('dense-references', 1, False, [call], 6, None)
            self.assertNotEqual('COMPLETE', trial.type)
            self.assertIsNone(trial.measurements['firstUsableNanos'])
            self.assertFalse(r.compare_trials(trial, trial, True).lessWork)

    def test_new_workloads_validate_against_current_public_schema(self):
        import jsonschema
        schema = json.loads((r.REPO / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.parameters.json').read_text())
        requests = r.comparison_requests()
        self.assertEqual({'exact-source', 'dense-references', 'scoped-all'}, set(requests))
        for request in requests.values():
            jsonschema.Draft202012Validator(schema).validate(request)

    def test_production_presets_preserve_exact_target_projection_and_scope(self):
        import jsonschema
        schema = json.loads((r.REPO / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.parameters.json').read_text())
        profile = r.WorkloadProfile.KAST_SOURCE
        requests = r.comparison_requests(profile)
        scope = dict(type='DIRECTORY', relativeDirectoryPath='query/contract/src/main/kotlin',
                     includeSubdirectories=True, sourceSetNames=['main'])
        function_scope = dict(type='DIRECTORY', relativeDirectoryPath='kernel/src/main/kotlin',
                              includeSubdirectories=True, sourceSetNames=['main'])
        budget = dict(maxElapsedMs=10000, maxWorkUnits=100000, maxResults=20, maxReturnedBytes=49152)
        fields = ['NAME', 'LOCATION', 'SIGNATURE']
        expected = {
            'exact-source': dict(source=dict(type='SEARCH_DECLARATIONS', declarationName='QueryPlanSyntax',
                nameMatch='EXACT', declarationKinds=['CLASS'], scope=scope), steps=[],
                output=dict(type='SYMBOLS', fields=fields + ['SOURCE'])),
            'dense-references': dict(source=dict(type='SEARCH_DECLARATIONS', declarationName='QueryStepSyntax',
                nameMatch='EXACT', declarationKinds=['CLASS'], scope=scope),
                steps=[dict(type='EXPAND_RELATION', relation='REFERENCES')], output=dict(type='OCCURRENCES')),
            'scoped-all': dict(source=dict(type='ALL_DECLARATIONS', declarationKinds=['FUNCTION'], scope=function_scope),
                steps=[dict(type='WHERE', predicate=dict(type='VISIBILITY', values=['PUBLIC']))],
                output=dict(type='SYMBOLS', fields=fields)),
        }
        self.assertEqual(set(expected), set(requests))
        for workload, semantic_request in expected.items():
            with self.subTest(workload=workload):
                jsonschema.Draft202012Validator(schema).validate(requests[workload])
                self.assertEqual(dict(verbose=True, request=dict(type='RUN', **semantic_request,
                                 executionBudget=budget)), requests[workload])
        for request in r.comparison_requests().values():
            self.assertEqual(dict(maxElapsedMs=2000, maxWorkUnits=100000, maxResults=20,
                                  maxReturnedBytes=49152), request['request']['executionBudget'])
        self.assertIs(profile, r.workload_profile(requests))
        self.assertEqual('io.github.amichne.kast.query.contract.QueryPlanSyntax', r.exact_identity(profile))
        self.assertEqual('query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt',
                         r.exact_source_file(profile))

    def test_unknown_profile_and_changed_scope_are_rejected(self):
        with self.assertRaises(ValueError): r.WorkloadProfile('UNKNOWN')
        for select in (r.comparison_requests, r.exact_identity, r.exact_source_file):
            with self.subTest(selector=select.__name__), self.assertRaises(ValueError):
                select('UNKNOWN')
        requests = r.comparison_requests(r.WorkloadProfile.KAST_SOURCE)
        requests['scoped-all']['request']['source']['scope']['relativeDirectoryPath'] = 'query'
        with self.assertRaisesRegex(ValueError, 'UNSUPPORTED_WORKLOAD_PROFILE'):
            r.workload_profile(requests)

    def production_trial(self, workload, token='exact:v5:scripted', work=20, response=None):
        """Authored SCRIPTED parser input; never evidence of native compiler execution."""
        template = self.trial(token, work)
        package = 'io.github.amichne.kast.query.contract'
        directory = '/scripted/kast/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/'
        if response is None:
            response = copy.deepcopy(template.calls[0].response)
            response['live']['root'] = '/scripted/kast'
            exact = response['items'][0]
            exact.update(name='QueryPlanSyntax', signature=dict(type='class-like',
                qualifiedIdentity=package + '.QueryPlanSyntax'), location=dict(file=directory + 'QueryPlan.kt',
                range=dict(startInclusive=100, endExclusive=200)),
                source=dict(text='data class QueryPlanSyntax(val steps: List<QueryStepSyntax>)', startLine=39, endLine=39))
            if workload == 'scoped-all':
                names = ['NamedRoot.Companion.parse', 'OperationId.Companion.parse',
                         'CapabilityId.Companion.parse']
                names += [f'ScriptedContract.operation{i:02d}' for i in range(18)]
                response['items'] = []
                for i, name in enumerate(names):
                    item = copy.deepcopy(exact)
                    item.update(ref=token + str(i), kind='function', name=name.split('.')[-1],
                        signature=dict(type='function', qualifiedIdentity='io.github.amichne.kast.kernel.' + name))
                    item.pop('source')
                    item['location']['file'] = '/scripted/kast/kernel/src/main/kotlin/io/github/amichne/kast/kernel/ScriptedKernel.kt'
                    item['location']['range'] = dict(startInclusive=100 + i * 10, endExclusive=109 + i * 10)
                    response['items'].append(item)
            elif workload == 'dense-references':
                target = dict(selector=token, qualifiedIdentity=package + '.QueryStepSyntax',
                    file=directory + 'QuerySteps.kt', range=dict(startInclusive=100, endExclusive=200),
                    compilerEvidence=dict(signature=dict(type='class-like',
                        qualifiedIdentity=package + '.QueryStepSyntax'), identity='scripted-compiler-digest'))
                response['items'] = [dict(type='reference-occurrence', ref=token, occurrence=dict(
                    target=copy.deepcopy(target), meaning='references', occurrence=dict(
                        candidateSelector=f'candidate:v5:scripted{i}',
                        file=directory + ('QueryPlan.kt' if i == 0 else 'QuerySteps.kt'),
                        range=dict(startInclusive=100 + i * 10, endExclusive=109 + i * 10)),
                    context='type', ownership=dict(type='file-scoped', context='type'),
                    coverage='exact-compiler-confirmed', provenance='k2-authored-source')) for i in range(21)]
        diagnostics = copy.deepcopy(template.calls[0].diagnostics)
        diagnostics[0]['counters'].extend([
            dict(counter='REVALIDATION_LOCATORS_RETAINED', contributor='NONE', count=0),
            dict(counter='REVALIDATION_LOCATORS_REJECTED', contributor='NONE', count=0)])
        call = r.ReplayCall('RUN', r.comparison_requests(r.WorkloadProfile.KAST_SOURCE)[workload],
            dict(outcome='completed', exitCode=0, elapsedNanos=200, stdout=json.dumps(response), stderr=''),
            response, diagnostics, [], 'MATCHED')
        return r.finish_trial(workload, 0, False, [call], 240, 200, r.WorkloadProfile.KAST_SOURCE)

    def test_production_requires_page_and_retention_labels_in_every_receipt(self):
        original = self.production_trial('exact-source').calls[0]
        for missing in ('REVALIDATION_LOCATORS_RETAINED', 'REVALIDATION_LOCATORS_REJECTED',
                        'NATIVE_DISCOVERY_PAGES', 'NATIVE_RELATION_PAGES'):
            for call_count in (1, 2):
                calls = [copy.deepcopy(original) for _ in range(call_count)]
                calls[0].diagnostics[0]['counters'] = [count for count in calls[0].diagnostics[0]['counters']
                    if count['counter'] != missing]
                if call_count == 2:
                    calls[0].response.update(status='qualified', items=[], coverage=dict(exhaustive=False))
                    calls[0].process['stdout'] = json.dumps(calls[0].response)
                observed = r.finish_trial('exact-source', 0, False, calls, 240, 200, r.WorkloadProfile.KAST_SOURCE)
                with self.subTest(missing=missing, calls=call_count):
                    self.assertIs(r.TrialState.COMPLETE, observed.type)
                    self.assertTrue(observed.unavailable)
                    self.assertNotIn(missing, [count['counter'] for count in calls[0].diagnostics[0]['counters']])
                    result = r.compare_trials(observed, observed, False)
                    self.assertIs(r.ComparisonState.MISSING_EVIDENCE, result.type)
                    self.assertFalse(result.lessWork)

    def test_scripted_production_trials_retain_full_raw_and_derive_receipts(self):
        with tempfile.TemporaryDirectory() as tmp:
            for workload in ('exact-source', 'dense-references', 'scoped-all'):
                trial = self.production_trial(workload)
                with self.subTest(workload=workload):
                    self.assertEqual([], trial.unavailable)
                    self.assertIs(r.TrialState.COMPLETE, trial.type)
                    self.assertEqual(trial.calls[0].response, json.loads(trial.calls[0].process['stdout']))
                    self.assertEqual(1 if workload == 'exact-source' else 21, len(trial.semantic['items']))
                    self.assertTrue(r.usable_result(workload, trial.calls[0].response, r.WorkloadProfile.KAST_SOURCE))
                    self.assertFalse(r.compare_trials(trial, trial, same_artifact=True).lessWork)
                    path = Path(tmp) / (workload + '.json')
                    path.write_text(json.dumps(asdict(trial)))
                    self.assertEqual(trial, r.load_trial(path, r.WorkloadProfile.KAST_SOURCE))

    def test_missing_production_target_source_and_sufficiency_witnesses_reject_work_claims(self):
        cases = [('exact-source', 'source'), ('exact-source', 'target'),
                 ('dense-references', 'density'), ('dense-references', 'witness'),
                 ('scoped-all', 'density'), ('scoped-all', 'witness')]
        for workload, missing in cases:
            response = copy.deepcopy(self.production_trial(workload).calls[0].response)
            if missing == 'source': response['items'][0]['source']['text'] = ''
            elif missing == 'target': response['items'][0]['signature']['qualifiedIdentity'] = 'scripted.WrongTarget'
            elif missing == 'density': response['items'] = response['items'][:20]
            elif workload == 'dense-references':
                response['items'][0]['occurrence']['occurrence']['file'] = '/scripted/Other.kt'
            else: response['items'][0]['signature']['qualifiedIdentity'] = 'scripted.MissingWitness'
            observed = self.production_trial(workload, response=response)
            with self.subTest(workload=workload, missing=missing):
                self.assertTrue(observed.unavailable)
                self.assertEqual('MISSING_EVIDENCE', r.compare_trials(observed, observed, False).type)
                self.assertFalse(r.compare_trials(observed, observed, False).lessWork)

    def test_every_production_reference_requires_exact_compiler_proof(self):
        mutations = ('coverage', 'provenance', 'target-identity', 'compiler-evidence',
                     'compiler-signature', 'compiler-digest', 'site-range')
        for mutation in mutations:
            response = copy.deepcopy(self.production_trial('dense-references').calls[0].response)
            row = response['items'][-1]['occurrence']
            if mutation == 'coverage': row['coverage'] = 'candidate'
            elif mutation == 'provenance': row['provenance'] = 'text-inference'
            elif mutation == 'target-identity': row['target']['qualifiedIdentity'] = 'scripted.OtherTarget'
            elif mutation == 'compiler-evidence': row['target'].pop('compilerEvidence')
            elif mutation == 'compiler-signature':
                row['target']['compilerEvidence']['signature']['qualifiedIdentity'] = 'scripted.OtherTarget'
            elif mutation == 'compiler-digest': row['target']['compilerEvidence']['identity'] = ''
            else: row['occurrence']['range'].pop('endExclusive')
            observed = self.production_trial('dense-references', response=response)
            with self.subTest(mutation=mutation):
                self.assertTrue(observed.unavailable)
                self.assertFalse(r.compare_trials(observed, observed, False).lessWork)

    def test_production_completion_requires_exhaustive_coverage_without_failures_or_omissions(self):
        for field, value in [('coverage', {}), ('coverage', dict(exhaustive=False)),
                             ('failures', [dict(type='scripted-failure')]),
                             ('omissions', [dict(type='scripted-omission')])]:
            response = copy.deepcopy(self.production_trial('scoped-all').calls[0].response)
            response[field] = value
            observed = self.production_trial('scoped-all', response=response)
            with self.subTest(field=field, value=value):
                self.assertFalse(r.compare_trials(observed, observed, False).lessWork)
                self.assertNotEqual('EQUIVALENT', r.compare_trials(observed, observed, False).type)

    def test_production_target_source_and_scope_regressions_block_smaller_work(self):
        for workload, change in [('dense-references', 'target'), ('exact-source', 'source'),
                                  ('scoped-all', 'scope')]:
            baseline = self.production_trial(workload)
            response = copy.deepcopy(baseline.calls[0].response)
            if change == 'target':
                for item in response['items']:
                    target = item['occurrence']['target']
                    target['qualifiedIdentity'] = 'scripted.DifferentQueryStepSyntax'
                    target['compilerEvidence']['signature']['qualifiedIdentity'] = 'scripted.DifferentQueryStepSyntax'
            elif change == 'source': response['items'][0]['source']['text'] += ' // changed'
            else: response['items'][-1]['location']['file'] = '/scripted/kast/other/QueryPlan.kt'
            candidate = self.production_trial(workload, work=1, response=response)
            comparison = r.compare_trials(baseline, candidate, same_artifact=False)
            with self.subTest(change=change):
                self.assertEqual('SEMANTIC_REGRESSION', comparison.type)
                self.assertEqual(-19, comparison.deltas['counters']['COMPILER_REFINEMENTS/NONE'])
                self.assertFalse(comparison.lessWork)

    def scripted_source_archive(self, root):
        """Case-owned archive metadata only; this is not a native source provenance claim."""
        fixture = root / 'source'
        fixture.mkdir()
        sources = {
            'settings.gradle.kts': 'rootProject.name = "scripted"',
            'query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt': 'class QueryPlanSyntax',
            'query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QuerySteps.kt': 'class QueryStepSyntax',
            'query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QuerySource.kt': 'class ScriptedSource',
        }
        commit = '1234567890abcdef1234567890abcdef12345678'
        archive_path = root / 'source.tar'
        with tarfile.open(archive_path, 'w', format=tarfile.PAX_FORMAT, pax_headers={'comment': commit}) as archive:
            for name, text in sources.items():
                path = fixture / name
                path.parent.mkdir(parents=True, exist_ok=True)
                data = text.encode('utf-8')
                path.write_bytes(data)
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return dict(root=str(fixture), hashes={name: hashlib.sha256(text.encode()).hexdigest()
                    for name, text in sources.items()}, type='REPRESENTATIVE', sourceArchive=dict(
                    path=str(archive_path), sha256=hashlib.sha256(archive_path.read_bytes()).hexdigest(),
                    commit=commit, repository='amichne/kast'))

    @contextmanager
    def scripted_git_archive(self, fixture, retained_commit, original_bytes, expected_calls=1):
        """Inject one external Git observation; production owns admission and digest checks."""
        calls = []
        def run(command, *, cwd, capture_output):
            calls.append(command)
            self.assertEqual(1, len(calls), 'Unexpected or excess Git archive observation')
            self.assertEqual(['git', 'archive', '--format=tar', fixture['sourceArchive']['commit']], command)
            self.assertEqual(r.REPO, cwd)
            self.assertIs(True, capture_output)
            available = command[-1] == retained_commit
            return subprocess.CompletedProcess(command, 0 if available else 128,
                original_bytes if available else b'', b'')
        with patch.object(r.subprocess, 'run', side_effect=run):
            try: yield
            finally: self.assertEqual(expected_calls, len(calls), 'Unconsumed Git archive observation')

    def test_source_archive_admission_requires_all_inventory_and_retained_git_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.scripted_source_archive(Path(tmp))
            commit = fixture['sourceArchive']['commit']
            original_bytes = Path(fixture['sourceArchive']['path']).read_bytes()
            with self.scripted_git_archive(fixture, commit, original_bytes):
                r.admit_kast_source_fixture(fixture)
            for mutation in ('missing-metadata', 'synthetic', 'archive-hash', 'omitted-source',
                             'retained-commit', 'live-source'):
                changed = copy.deepcopy(fixture)
                if mutation == 'missing-metadata': changed.pop('sourceArchive')
                elif mutation == 'synthetic': changed['type'] = 'SYNTHETIC'
                elif mutation == 'archive-hash': changed['sourceArchive']['sha256'] = '0' * 64
                elif mutation == 'omitted-source':
                    changed['hashes'].pop('query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QuerySource.kt')
                elif mutation == 'retained-commit': changed['sourceArchive']['commit'] = '0' * 40
                else: (Path(changed['root']) / 'settings.gradle.kts').write_text('changed')
                expected_calls = 0 if mutation in ('missing-metadata', 'synthetic') else 1
                with self.subTest(mutation=mutation), self.scripted_git_archive(
                        changed, commit, original_bytes, expected_calls), self.assertRaises(ValueError):
                    r.admit_kast_source_fixture(changed)

    def test_nonoriginal_archive_cannot_be_relabelled_representative(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.scripted_source_archive(Path(tmp))
            path = Path(fixture['sourceArchive']['path'])
            original_bytes = path.read_bytes()
            with tarfile.open(path, 'w', format=tarfile.PAX_FORMAT) as archive:
                for name in fixture['hashes']:
                    data = (Path(fixture['root']) / name).read_bytes()
                    entry = tarfile.TarInfo(name)
                    entry.size = len(data)
                    archive.addfile(entry, io.BytesIO(data))
            fixture['sourceArchive']['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.scripted_git_archive(fixture, fixture['sourceArchive']['commit'], original_bytes), \
                    self.assertRaises(ValueError):
                r.admit_kast_source_fixture(fixture)

    def test_relabelled_reliability_profile_cannot_claim_representative_less_work(self):
        baseline = dict(artifact={'executable': 'a' * 64}, evidenceLevel='NATIVE',
                        fixture=dict(type='REPRESENTATIVE'), requests=r.comparison_requests())
        candidate = copy.deepcopy(baseline)
        candidate['artifact']['executable'] = 'b' * 64
        self.assertTrue(r.observation_only(baseline, candidate))
        self.assertFalse(r.compare_trials(self.trial(), self.trial(work=1),
                         r.observation_only(baseline, candidate)).lessWork)

    def test_resume_reacquires_handle_and_drains_without_increasing_budget(self):
        requests = r.comparison_requests()
        base = requests['scoped-all']
        prefix = copy.deepcopy(self.trial().calls[0].response)
        prefix.update(status='qualified', coverage=dict(exhaustive=False), qualification=dict(
            limitations=['result-limit-reached'], progress=dict(type='resumable', checkpoint=dict(
                type='upstream', token='query:v1:new'), next_action='resume')))
        seen = []
        def invoke(request):
            seen.append(request)
            response = prefix if len(seen) == 1 else self.trial().calls[0].response
            return r.ReplayCall(request['request']['type'], request,
                dict(outcome='completed', elapsedNanos=10, stdout=json.dumps(response), stderr='', exitCode=0),
                response, [], [], 'UNAVAILABLE')
        r.drain_workload(base, invoke, 10)
        self.assertEqual('query:v1:new', seen[1]['request']['continuation'])
        self.assertEqual(base['request']['executionBudget'], seen[1]['request']['executionBudget'])

    def test_production_resume_keeps_the_predeclared_ten_second_grant(self):
        base = r.comparison_requests(r.WorkloadProfile.KAST_SOURCE)['scoped-all']
        terminal = self.production_trial('scoped-all').calls[0].response
        responses = []
        for token in ('query:v1:scripted-first', 'query:v1:scripted-second'):
            prefix = copy.deepcopy(terminal)
            prefix.update(status='qualified', coverage=dict(exhaustive=False), qualification=dict(
                limitations=['result-limit-reached'], progress=dict(type='resumable', checkpoint=dict(
                    type='upstream', token=token), next_action='resume')))
            responses.append(prefix)
        responses.append(terminal)
        seen = []
        def invoke(request):
            self.assertLess(len(seen), 3, 'Unexpected or excess scripted public query')
            seen.append(copy.deepcopy(request))
            response = responses[len(seen) - 1]
            return r.ReplayCall(request['request']['type'], request,
                dict(outcome='completed', elapsedNanos=10, stdout=json.dumps(response), stderr='', exitCode=0),
                response, [], [], 'UNAVAILABLE')
        calls = r.drain_workload(base, invoke, 3)
        self.assertEqual(3, len(calls), 'Unconsumed scripted public query')
        self.assertEqual(['RUN', 'RESUME', 'RESUME'], [request['request']['type'] for request in seen])
        self.assertEqual(['query:v1:scripted-first', 'query:v1:scripted-second'],
                         [request['request']['continuation'] for request in seen[1:]])
        grant = dict(maxElapsedMs=10000, maxWorkUnits=100000, maxResults=20, maxReturnedBytes=49152)
        self.assertTrue(all(request['request']['executionBudget'] == grant for request in seen))

    def test_delayed_diagnostic_capture_reobserves_only_the_original_log_boundary(self):
        path, before = object(), object()
        receipt = copy.deepcopy(self.trial().calls[0].diagnostics[0])
        phase = dict(phase='EXACT_REFINEMENT', outcome='COMPLETED', durationNanos=100)
        expected = [
            ('clock', (), 12.0),
            ('observe', (path, before), r.ObservationWindow(phases=(phase,))),
            ('clock', (), 12.01),
            ('pause', (0.01,), None),
            ('observe', (path, before), r.ObservationWindow(diagnostics=(receipt,), phases=(phase,))),
            ('clock', (), 12.25),
        ]
        def step(kind, *args):
            self.assertTrue(expected, 'Unexpected or excess observation capture callback')
            wanted, arguments, result = expected.pop(0)
            self.assertEqual((wanted, arguments), (kind, args))
            return result
        with patch.object(r, 'capture', side_effect=AssertionError('Observation capture reinvoked a query')) as query:
            observations = r.collect_observations(path, before,
                observe=lambda *args: step('observe', *args), clock=lambda: step('clock'),
                pause=lambda *args: step('pause', *args))
            query.assert_not_called()
        self.assertEqual([], expected, 'Unconsumed observation capture callback')
        self.assertEqual((receipt,), observations.diagnostics)
        self.assertEqual((phase,), observations.phases)
        self.assertIsInstance(r.join_timing(observations, receipt.get('readId')), r.UnavailableTiming)

    def test_diagnostic_capture_deadline_keeps_unavailable_measurements_and_phases(self):
        path, before = object(), object()
        phase = dict(phase='EXACT_REFINEMENT', outcome='COMPLETED', durationNanos=100)
        expected = [
            ('clock', (), 5.0),
            ('observe', (path, before), r.ObservationWindow(phases=(phase,))),
            ('clock', (), 5.01),
            ('pause', (0.01,), None),
            ('observe', (path, before), r.ObservationWindow(phases=(phase,))),
            ('clock', (), 5.25),
        ]
        def step(kind, *args):
            self.assertTrue(expected, 'Unexpected or excess observation capture callback')
            wanted, arguments, result = expected.pop(0)
            self.assertEqual((wanted, arguments), (kind, args))
            return result
        with patch.object(r, 'capture', side_effect=AssertionError('Observation capture reinvoked a query')) as query:
            observations = r.collect_observations(path, before,
                observe=lambda *args: step('observe', *args), clock=lambda: step('clock'),
                pause=lambda *args: step('pause', *args))
            query.assert_not_called()
        self.assertEqual([], expected, 'Unconsumed observation capture callback')
        self.assertEqual((), observations.diagnostics)
        self.assertEqual((phase,), observations.phases)
        template = self.trial()
        original = template.calls[0]
        call = r.ReplayCall(original.action, original.request, original.process, original.response,
                            list(observations.diagnostics), list(observations.phases), 'UNAVAILABLE', observations)
        observed = r.finish_trial('exact-source', 0, False, [call], 240, 200)
        self.assertIn('NATIVE_DIAGNOSTICS_UNAVAILABLE_OR_UNCORRELATED', observed.unavailable)
        for measurement in ('counters', 'nativePages', 'nativePhaseDurations', 'stageDurations'):
            self.assertIsNone(observed.measurements[measurement])
        self.assertEqual('MISSING_EVIDENCE', r.compare_trials(observed, template, False).type)
        self.assertFalse(r.compare_trials(observed, template, False).lessWork)

    def test_observation_capture_policy_is_explicit_and_comparison_compatible(self):
        policy = dict(maxWaitMillis=250, maxAppendedBytes=2 * 1024 * 1024, maxRotations=1)
        self.assertEqual(policy, r.OBSERVATION_POLICY)
        baseline = dict(fixture={'source.kt': 'hash'}, environment={'jdk': '25'},
            limits=dict(observationCapture=policy), requests=r.comparison_requests(), warmups=1,
            repetitions=2, concurrency=1, maxCalls=256, timeoutSeconds=60, cachePolicy='existing',
            evidenceLevel='SCRIPTED', transport='TOOL_RPC')
        self.assertEqual(policy, json.loads(json.dumps(baseline))['limits']['observationCapture'])
        self.assertEqual([], r.incompatible_runs(baseline, copy.deepcopy(baseline)))
        for field, value in [('maxWaitMillis', 251), ('maxAppendedBytes', 2 * 1024 * 1024 + 1), ('maxRotations', 2)]:
            candidate = copy.deepcopy(baseline)
            candidate['limits']['observationCapture'][field] = value
            with self.subTest(field=field):
                self.assertEqual(['limits'], r.incompatible_runs(baseline, candidate))

    def test_production_type_context_admission_preserves_the_raw_enum(self):
        lower = self.production_trial('dense-references')
        response = copy.deepcopy(lower.calls[0].response)
        response['items'][0]['occurrence']['context'] = 'TYPE'
        response['items'][0]['occurrence']['ownership']['context'] = 'TYPE'
        upper = self.production_trial('dense-references', response=response)
        self.assertEqual([], upper.unavailable)
        self.assertEqual('TYPE', upper.calls[0].response['items'][0]['occurrence']['context'])
        self.assertEqual('TYPE', upper.semantic['items'][0]['occurrence']['context'])
        self.assertEqual('SEMANTIC_REGRESSION', r.compare_trials(lower, upper, False).type)

    def test_increase_grant_and_nonadvancing_checkpoint_stop_as_incomplete(self):
        prefix = dict(status='qualified', items=[], failures=[], omissions=[], coverage=dict(exhaustive=False),
                      qualification=dict(progress=dict(type='resumable', checkpoint=dict(token='query:v1:x'),
                          next_action='increase_execution_budget')))
        def invoke(request):
            return r.ReplayCall('RUN', request, dict(outcome='completed', elapsedNanos=1,
                stdout=json.dumps(prefix), stderr='', exitCode=0), prefix, [], [], 'UNAVAILABLE')
        calls = r.drain_workload(r.comparison_requests()['scoped-all'], invoke, 10)
        self.assertEqual(1, len(calls))

    def workload_trial(self, workload, token):
        template = self.trial(token)
        response = copy.deepcopy(template.calls[0].response)
        if workload == 'scoped-all':
            names = [f'reliability.source.pageItem{i:02d}' for i in range(64)]
            names += [f'repro.logging.FixtureLogger.{n}' for n in ('trace', 'debug', 'info', 'warn', 'error')]
            names += [f'repro.logging.Identity{n}.sharedOperation' for n in ('One', 'Two', 'Three', 'Four', 'Five')]
            response['items'] = []
            for i, name in enumerate(names):
                item = copy.deepcopy(template.calls[0].response['items'][0])
                item.update(ref=token + str(i), kind='function', name=name.split('.')[-1])
                item['signature'] = dict(type='function', qualifiedIdentity=name)
                response['items'].append(item)
        elif workload == 'dense-references':
            expected = json.loads((r.FIXTURE / 'read-reliability/ReadDenseReferences.expected.json').read_text())
            target = dict(selector=token, qualifiedIdentity=expected['target'], file='/fixture/Target.kt',
                          range=dict(startInclusive=0, endExclusive=10), compilerEvidence=dict(
                              signature=dict(type='class-like', qualifiedIdentity=expected['target']), identity='digest'))
            response['items'] = [dict(type='reference-occurrence', ref=token, occurrence=dict(
                target=target, meaning='references', occurrence=dict(candidateSelector=f'candidate:v5:{token}{i}',
                    file='/fixture/' + o['file'], range=dict(startInclusive=o['start'], endExclusive=o['end'])),
                context=o['context'], ownership=dict(type='file-scoped', context=o['context']),
                coverage='exact-compiler-confirmed', provenance='k2-authored-source'))
                for i, o in enumerate(expected['referenceOccurrences'])]
        request = r.comparison_requests()[workload]
        call = r.ReplayCall('RUN', request, dict(outcome='completed', exitCode=0, elapsedNanos=200,
            stdout=json.dumps(response), stderr=''), response, template.calls[0].diagnostics, [], 'MATCHED')
        return r.finish_trial(workload, 0, False, [call], 240, 200)

    def write_run(self, root, token):
        root.mkdir()
        manifest = dict(type='SEMANTIC_REPLAY', schemaVersion=1, artifact=dict(executable='a'*64),
            fixture=dict(root='/fixture', hashes={'source.kt':'b'*64}), environment=dict(jdk='25', idea='262'),
            limits=dict(work=100000, observationCapture=dict(maxWaitMillis=250,
                        maxAppendedBytes=2 * 1024 * 1024)), requests=r.comparison_requests(), warmups=0, repetitions=1,
            concurrency=1, maxCalls=256, timeoutSeconds=60, cachePolicy='existing; no invalidation',
            evidenceLevel='SCRIPTED', trials=[], transport='TOOL_RPC')
        for workload in r.comparison_requests():
            trial = self.workload_trial(workload, token)
            self.assertEqual([], trial.unavailable)
            path = root / (workload + '.json')
            path.write_text(json.dumps(asdict(trial)))
            manifest['trials'].append(path.name)
        path = root / 'run.json'
        path.write_text(json.dumps(manifest))
        return path

    def test_native_run_requires_version_and_loaded_artifact_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = self.write_run(root / 'run', 'exact:v5:issued')
            fixture = root / 'fixture'
            fixture.mkdir()
            (fixture / 'source.kt').write_text('class Fixture')
            manifest = json.loads(path.read_text())
            manifest.update(evidenceLevel='NATIVE',
                fixture=dict(root=str(fixture), hashes=r.inventory(fixture)),
                environment=dict(ideaBuild='IU-262.10315.125', jbr='25.0.4', kotlinPlugin='262-IJ',
                                 os='case-owned', machine='arm64', javaHome='/fixture/jbr', cliJavaHome=None),
                artifact=dict(executable='a'*64, schema='b'*64, catalogExecutable='c'*64,
                              cliJars=['d'*64], pluginJars=['e'*64], loadedClasses={'ReadOwner':'f'*64}))
            path.write_text(json.dumps(manifest))
            r.load_run(path)  # This case proves local metadata admission, never native semantics.
            for omitted in ('ideaBuild', 'jbr', 'kotlinPlugin', 'javaHome'):
                incomplete = copy.deepcopy(manifest)
                del incomplete['environment'][omitted]
                path.write_text(json.dumps(incomplete))
                with self.subTest(omitted=omitted), self.assertRaises(ValueError):
                    r.load_run(path)
            for omitted, empty in (('cliJars', []), ('pluginJars', []), ('loadedClasses', {})):
                incomplete = copy.deepcopy(manifest)
                incomplete['artifact'][omitted] = empty
                path.write_text(json.dumps(incomplete))
                with self.subTest(omitted=omitted), self.assertRaises(ValueError):
                    r.load_run(path)

    def test_receipt_parser_retains_the_admitted_trial_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'trial.json'
            path.write_text(json.dumps(asdict(self.workload_trial('exact-source', 'exact:v5:issued'))))
            parsed = r.load_trial(path)
            self.assertIs(r.TrialState.COMPLETE, parsed.type)

    def test_three_workload_baseline_repeatability_and_machine_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a, b = self.write_run(root / 'baseline-a', 'exact:v5:first'), self.write_run(root / 'baseline-b', 'exact:v5:second')
            args = argparse.Namespace(baseline=a, candidate=b, output=root / 'comparison.json')
            self.assertEqual(0, r.compare_replays(args))
            report = json.loads(args.output.read_text())
            self.assertEqual('COMPARISON', report['type'])
            self.assertEqual('SCRIPTED', report['evidenceLevel'])
            self.assertEqual(3, len(report['trials']))
            self.assertTrue(report['repeatability'])
            self.assertFalse(report['lessWork'])
            self.assertTrue(all(t['type'] == 'EQUIVALENT' for t in report['trials']))
            manifest = json.loads(b.read_text())
            manifest['transport'] = 'MCP_SESSION'
            b.write_text(json.dumps(manifest))
            self.assertEqual(2, r.compare_replays(args))
            self.assertIn('transport', json.loads(args.output.read_text())['incompatible'])
            manifest['transport'] = 'TOOL_RPC'
            manifest['concurrency'] = 2
            b.write_text(json.dumps(manifest))
            self.assertEqual(2, r.compare_replays(args))
            self.assertEqual('INVALID_EVIDENCE', json.loads(args.output.read_text())['type'])

    def test_mcp_wire_admission_rejects_unknown_fields_and_unbound_identity(self):
        for payload, expected in [
            ({'jsonrpc':'2.0','id':True,'result':{}}, r.McpWireFailure.CORRELATION_REJECTED),
            ({'jsonrpc':'2.0','id':2,'result':{}}, r.McpWireFailure.CORRELATION_REJECTED),
            ({'jsonrpc':'2.0','id':1,'result':{},'extra':0}, r.McpWireFailure.INVALID_FRAME),
            ({'jsonrpc':'2.0','id':1,'result':{},'error':{}}, r.McpWireFailure.INVALID_FRAME),
            ({'jsonrpc':'2.0','id':1,'result':None}, r.McpWireFailure.INVALID_FRAME)]:
            self.assertEqual(r.McpWireRejected(expected), r.admit_mcp_wire(json.dumps(payload), 1))
        self.assertEqual(r.McpWireRejected(r.McpWireFailure.INVALID_FRAME), r.admit_mcp_wire('invalid', 1))
        for malformed in ('{"jsonrpc":"2.0","id":1,"id":1,"result":{}}',
                          '{"jsonrpc":"2.0","id":1,"result":{"value":NaN}}'):
            self.assertEqual(r.McpWireRejected(r.McpWireFailure.INVALID_FRAME), r.admit_mcp_wire(malformed, 1))
        self.assertIsInstance(r.admit_mcp_wire('{"jsonrpc":"2.0","id":1,"result":{}}', 1), r.McpWireAccepted)

    def test_persistent_mcp_uses_one_owned_process_and_preserves_wire_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            executable = root / 'mcp'
            executable.write_text('#!' + sys.executable + '\n' + """
import json, sys
for method in ('initialize', 'tools/list', 'tools/call'):
    request = json.loads(sys.stdin.readline())
    assert request['method'] == method
    result = {'tools': []} if method == 'tools/list' else {'structuredContent': {'status': 'complete'}}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
assert sys.stdin.readline() == ''
""")
            executable.chmod(0o700)
            session = r.McpReplaySession(executable, root, root, 1)
            self.assertEqual({'tools': []}, session.catalog())
            observed = session.call(r.comparison_requests()['exact-source'])
            session.close()
            self.assertEqual(0, session.process.returncode)
            self.assertEqual('completed', observed['outcome'])
            self.assertEqual(str(root), observed['cwd'])
            self.assertEqual('tools/call', json.loads(observed['stdin'])['method'])
            self.assertEqual({'status':'complete'}, json.loads(observed['stdout'])['result']['structuredContent'])
            self.assertTrue(observed['stdout'].endswith('\n'))
            self.assertTrue((root / 'mcp-initialize.json').is_file())
            self.assertTrue((root / 'mcp-tools-list.json').is_file())

    def test_mcp_mismatched_control_reply_is_rejected_and_child_drained(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            executable = root / 'mcp'
            executable.write_text('#!' + sys.executable + '\n' + """
import json, sys
request = json.loads(sys.stdin.readline())
assert request['method'] == 'initialize'
print(json.dumps({'jsonrpc':'2.0','id':request['id'] + 1,'result':{}}), flush=True)
assert sys.stdin.readline() == ''
""")
            executable.chmod(0o700)
            with self.assertRaisesRegex(ValueError, 'MCP_CONTROL_UNAVAILABLE'):
                r.McpReplaySession(executable, root, root, 1)
            observed = json.loads((root / 'mcp-initialize.json').read_text())
            self.assertEqual('exchange-rejected', observed['outcome'])
            self.assertEqual('MCP_CORRELATION_REJECTED', observed['failure'])
            self.assertTrue(observed['stdout'])
            self.assertNotEqual('completed', observed['outcome'])

    def test_failed_process_launch_retains_unavailable_trial(self):
        with tempfile.TemporaryDirectory() as tmp:
            process = r.capture([Path(tmp) / 'absent-rpc'], Path(tmp), '{}')
            self.assertEqual('PROCESS_LAUNCH_UNAVAILABLE', process['failure'])
            call = r.ReplayCall('RUN', {}, process, None, [], [], 'UNAVAILABLE')
            trial = r.finish_trial('exact-source', 0, False, [call], process['elapsedNanos'], None)
            self.assertEqual('UNAVAILABLE', trial.type)
            self.assertIsNone(trial.measurements['publicCalls'])
            self.assertIsNone(trial.measurements['encodedBytes'])

    def test_synthetic_native_runs_cannot_claim_less_work(self):
        a = dict(artifact={'executable':'a'*64}, evidenceLevel='NATIVE', fixture=dict(type='SYNTHETIC'))
        b = dict(artifact={'executable':'b'*64}, evidenceLevel='NATIVE', fixture=dict(type='SYNTHETIC'))
        self.assertTrue(r.observation_only(a, b))
        self.assertFalse(r.compare_trials(self.trial(), self.trial(work=1), r.observation_only(a, b)).lessWork)

    def test_missing_source_is_not_sufficient_even_in_self_comparison(self):
        a = self.trial()
        response = copy.deepcopy(a.calls[0].response)
        del response['items'][0]['source']
        call = r.ReplayCall('RUN', {}, a.calls[0].process, response, a.calls[0].diagnostics, [], 'MATCHED')
        trial = r.finish_trial('exact-source', 0, False, [call], 240, None)
        self.assertFalse(r.usable_result('exact-source', response))
        self.assertEqual('MISSING_EVIDENCE', r.compare_trials(trial, trial, True).type)

    def test_dense_oracle_and_filtered_declarations_are_independent(self):
        for workload in ('dense-references', 'scoped-all'):
            trial = self.workload_trial(workload, 'exact:v5:x')
            response = copy.deepcopy(trial.calls[0].response)
            response['items'].pop()
            call = r.ReplayCall('RUN', {}, trial.calls[0].process, response, trial.calls[0].diagnostics, [], 'MATCHED')
            observed = r.finish_trial(workload, 0, False, [call], 240, 200)
            self.assertTrue(any('ORACLE_MISMATCH' in reason for reason in observed.unavailable))
            self.assertFalse(r.compare_trials(observed, observed, True).lessWork)

    def test_nonadvancing_checkpoint_and_epoch_movement_stop(self):
        prefix = copy.deepcopy(self.trial().calls[0].response)
        prefix.update(status='qualified', coverage=dict(exhaustive=False), qualification=dict(progress=dict(
            type='resumable', checkpoint=dict(token='query:v1:repeated'), next_action='resume')))
        def invoke(request):
            return r.ReplayCall('RUN', request, dict(outcome='completed', exitCode=0, elapsedNanos=1,
                stdout=json.dumps(prefix), stderr=''), prefix, [], [], 'UNAVAILABLE')
        self.assertEqual(2, len(r.drain_workload(r.comparison_requests()['scoped-all'], invoke, 10)))
        seen = []
        def moving(request):
            response = copy.deepcopy(prefix)
            response['live']['epoch'] = len(seen)
            seen.append(request)
            return r.ReplayCall('RUN', request, dict(outcome='completed', exitCode=0, elapsedNanos=1,
                stdout=json.dumps(response), stderr=''), response, [], [], 'UNAVAILABLE')
        calls = r.drain_workload(r.comparison_requests()['scoped-all'], moving, 10)
        self.assertEqual(2, len(calls))
        self.assertIn('HOST_OR_EPOCH_MOVED', r.finish_trial('scoped-all', 0, False, calls, 3, None).unavailable)

    def test_receipt_parser_rejects_unknown_fields_and_negative_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'trial.json'
            value = asdict(self.trial())
            value['unexpected'] = True
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError): r.load_trial(path)
            del value['unexpected']
            value['measurements']['totalNanos'] = -1
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError): r.load_trial(path)


if __name__ == '__main__': unittest.main()
