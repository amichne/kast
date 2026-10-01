"""Pure comparison and scripted replay checks; no native-performance claims."""
import copy
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import tempfile
import sys
import unittest

import reproduce_semantic_queries as r


class SemanticComparisonTest(unittest.TestCase):
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
            limits=dict(work=100000), requests=r.comparison_requests(), warmups=0, repetitions=1,
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
