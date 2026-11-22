"""Scripted MCP session and workload drain checks; no native-performance claims."""
import copy
from dataclasses import replace
import json
from pathlib import Path
import sys
import tempfile
import unittest

import hosted_timing as timing
import mcp_replay as r
from test_hosted_timing import window as timing_window, READ


# Opaque public request; the drainer reads only its type, grant and terminal output.
REQUEST = {'verbose': True, 'request': {
    'type': 'RUN',
    'source': {'type': 'ALL_DECLARATIONS', 'declarationKinds': ['FUNCTION'], 'scope': {
        'type': 'DIRECTORY', 'relativeDirectoryPath': 'logging/src/main/kotlin',
        'sourceSetNames': ['main'], 'includeSubdirectories': True}},
    'steps': [],
    'terminalStep': {'type': 'EMIT', 'output': {'type': 'SYMBOLS', 'fields': ['NAME', 'LOCATION', 'SIGNATURE']}},
    'executionBudget': {'maxElapsedMs': 2000, 'maxWorkUnits': 100000, 'maxResults': 20,
                        'maxReturnedBytes': 49152},
    'executionMode': 'BOUNDED'}}


def complete_response():
    return dict(status='complete', items=[], failures=[], omissions=[], coverage=dict(exhaustive=True),
                live=dict(root='/fixture', host='host', epoch=1, contentView='SAVED_PSI_COMMITTED', version=1))


def resumable(token, next_action='resume'):
    response = complete_response()
    response.update(status='qualified', coverage=dict(exhaustive=False), qualification=dict(
        progress=dict(type='resumable', checkpoint=dict(type='upstream', token=token), next_action=next_action)))
    return response


def completed_call(request, response, diagnostics=(), correlation='UNAVAILABLE'):
    return r.ReplayCall(request['request']['type'], request, dict(outcome='completed', exitCode=0,
                        elapsedNanos=1, stdout=json.dumps(response), stderr=''),
                        response, list(diagnostics), [], correlation)


class McpReplayTest(unittest.TestCase):
    def test_resume_reacquires_handle_and_drains_without_increasing_budget(self):
        seen = []
        def invoke(request):
            seen.append(request)
            return completed_call(request, resumable('query:v1:new') if len(seen) == 1 else complete_response())
        calls = r.drain_workload(REQUEST, invoke, 10)
        self.assertEqual(2, len(calls))
        self.assertEqual('RESUME', seen[1]['request']['type'])
        self.assertEqual('query:v1:new', seen[1]['request']['continuation'])
        self.assertEqual(REQUEST['request']['executionBudget'], seen[1]['request']['executionBudget'])

    def test_increase_grant_checkpoint_stops_after_one_call(self):
        prefix = resumable('query:v1:x', next_action='increase_execution_budget')
        calls = r.drain_workload(REQUEST, lambda request: completed_call(request, prefix), 10)
        self.assertEqual(1, len(calls))

    def test_nonadvancing_checkpoint_and_epoch_movement_stop(self):
        prefix = resumable('query:v1:repeated')
        self.assertEqual(2, len(r.drain_workload(REQUEST, lambda request: completed_call(request, prefix), 10)))
        seen = []
        def moving(request):
            response = copy.deepcopy(prefix)
            response['live']['epoch'] = len(seen)
            response['qualification']['progress']['checkpoint']['token'] = f'query:v1:{len(seen)}'
            seen.append(request)
            return completed_call(request, response)
        self.assertEqual(2, len(r.drain_workload(REQUEST, moving, 10)))

    def test_drain_stops_at_call_capacity(self):
        tokens = iter(f'query:v1:{index}' for index in range(10))
        calls = r.drain_workload(REQUEST, lambda request: completed_call(request, resumable(next(tokens))), 3)
        self.assertEqual(3, len(calls))

    def test_missing_or_forged_correlation_cannot_manufacture_hosted_timing(self):
        response = complete_response()
        diagnostic = dict(readId=READ, correlation=dict(type='bound', host='host', epoch=1))
        call = replace(completed_call(REQUEST, response, [diagnostic], 'MATCHED'),
                       hostedObservations=replace(timing_window(), diagnostics=(diagnostic,)))
        self.assertIsInstance(r.call_timing(call), timing.JoinedTiming)
        duplicate = {**diagnostic, 'readId': '44444444-4444-4444-8444-444444444444'}
        forged = replace(call, hostedObservations=replace(call.hostedObservations,
                         diagnostics=(diagnostic, duplicate)))
        self.assertIsInstance(r.call_timing(forged), timing.UnavailableTiming)
        self.assertIn(timing.TimingFailure.SEMANTIC_CORRELATION_UNAVAILABLE, r.call_timing(forged).failures)
        uncaptured = replace(call, hostedObservations=timing.ObservationWindow(
            failures=(timing.TimingFailure.NOT_CAPTURED,)))
        self.assertIn(timing.TimingFailure.NOT_CAPTURED, r.call_timing(uncaptured).failures)

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
            observed = session.call(REQUEST)
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


if __name__ == '__main__':
    unittest.main()
