"""Proof-gate tests; these do not launch Codex or send a cloud request."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import codex_lifecycle_acceptance as acceptance


def event(tool='symbol_lookup', call='call1', kind='tool-call-started', completion='completed'):
    record = {'component': 'kast-broker', 'event': kind, 'namespace': 'kast',
              'threadId': 'thread', 'turnId': 'turn', 'callId': call, 'tool': tool}
    if kind == 'tool-call-finished':
        record['completion'] = completion
    return record


def span(name, outcome='complete'):
    return {'name': name, 'attributes': [{'key': 'io.github.amichne.kast.outcome',
                                          'value': {'stringValue': outcome}}]}


class CodexLifecycleAcceptanceTest(unittest.TestCase):
    def test_prompt_supplies_installed_schema_arguments(self):
        self.assertEqual({'mode': 'name', 'query': 'genericRead', 'kind': 'symbol',
                          'match': 'exact-name', 'limit': 10}, acceptance.LOOKUP_ARGUMENTS)
        self.assertEqual({'selector', 'relation', 'maximumDepth', 'maximumResults'},
                         set(acceptance.IMPACT_ARGUMENTS))
        self.assertEqual(2, acceptance.IMPACT_ARGUMENTS['maximumDepth'])
        self.assertEqual(20, acceptance.IMPACT_ARGUMENTS['maximumResults'])
        self.assertIn(json.dumps(acceptance.LOOKUP_ARGUMENTS), acceptance.PROMPT)
        self.assertIn(json.dumps(acceptance.IMPACT_ARGUMENTS), acceptance.PROMPT)
        self.assertIn('candidateSelector', acceptance.PROMPT)
        self.assertIn('emit the entire returned value with text(result)', acceptance.PROMPT)
        self.assertIn('Kast may return a direct structured object', acceptance.PROMPT)
        self.assertIn('provided tool metadata', acceptance.PROMPT)
        self.assertIn('until a yielded call completes', acceptance.PROMPT)

    def test_ansi_fragmented_broker_activity_requires_all_four_semantic_completions(self):
        stream = acceptance.Activities()
        for index, tool in enumerate(acceptance.EXPECTED_TOOLS):
            for kind in ('tool-call-started', 'tool-call-finished'):
                raw = b'\x1b[2Kterminal text ' + json.dumps(event(tool, str(index), kind)).encode() + b'\r\n'
                for offset in range(0, len(raw), 7):
                    stream.feed(raw[offset:offset + 7])
            self.assertEqual(index == 3, stream.complete())
        self.assertEqual(('thread', 'turn'), stream.context)

    def test_unknown_lifecycle_malformed_and_unmatched_records_fail_closed(self):
        for record in (event('index_sync'), event('start'), event(kind='tool-call-finished'),
                       dict(event(), event='unknown'), dict(event(), namespace='shell')):
            with self.subTest(record=record), self.assertRaises(acceptance.EvidenceError):
                acceptance.Activities().accept(record)
        with self.assertRaisesRegex(acceptance.EvidenceError, 'malformed'):
            acceptance.Activities().feed(b'{"component":"kast-broker", not json}\n')

    def test_rejected_duplicate_and_cross_turn_completions_fail(self):
        for finished in (event(kind='tool-call-finished', completion='rejected'),
                         dict(event(kind='tool-call-finished'), turnId='other')):
            stream = acceptance.Activities()
            stream.accept(event())
            with self.assertRaises(acceptance.EvidenceError):
                stream.accept(finished)
        stream = acceptance.Activities()
        stream.accept(event())
        stream.accept(event(kind='tool-call-finished'))
        with self.assertRaisesRegex(acceptance.EvidenceError, 'duplicate'):
            stream.accept(event(kind='tool-call-finished'))

    def test_existing_integration_socket_path_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            socket = home / 'kast-integration/upstream.sock'
            socket.parent.mkdir()
            socket.symlink_to(home / 'missing')
            with self.assertRaisesRegex(acceptance.EvidenceError, 'existing Codex integration socket'):
                acceptance.require_no_host(home)
            self.assertTrue(socket.is_symlink())

    def test_reuse_requires_successful_spans_and_one_topology_publication(self):
        spans = [span('kast.symbol.discovery'), span('kast.topology.build'), span('kast.topology.build'),
                 span('kast.traversal.run'), span('kast.traversal.run'), span('kast.topology.extraction'),
                 span('kast.topology.publication')]
        rows = [{'generation': 1, 'file_count': 14}]
        acceptance.verify_semantic_evidence(spans, rows)
        for changed, changed_rows in ((spans + [span('kast.topology.extraction')], rows),
                                      (spans + [span('kast.workspace.refresh')], rows),
                                      (spans, rows + rows),
                                      (spans[:-1] + [span('kast.topology.publication', 'rejected')], rows)):
            with self.subTest(changed=changed), self.assertRaises(acceptance.EvidenceError):
                acceptance.verify_semantic_evidence(changed, changed_rows)

    def test_failed_stop_cannot_report_success(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            result = subprocess.CompletedProcess([], 0, b'{"status":"complete","runtime":"running"}', b'')
            with patch.object(acceptance.subprocess, 'run', return_value=result) as run:
                with self.assertRaisesRegex(acceptance.EvidenceError, 'did not stop'):
                    acceptance.stop_owned(Path('/kast'), base, {'KAST_RUNTIME_DIRECTORY': str(base)}, base)
            self.assertEqual(['/kast', 'stop'], run.call_args.args[0])
            self.assertEqual(base, run.call_args.kwargs['cwd'])

    def test_proof_failure_still_stops_the_owned_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            fixture = base / 'fixture'
            fixture.mkdir()
            (fixture / 'settings.gradle.kts').write_text('rootProject.name = "proof"')
            kast = base / 'kast'
            kast.touch()
            host = base / 'kast-codex'
            host.touch()
            args = acceptance.argparse.Namespace(kast=kast, kast_codex=host, fixture=fixture,
                                                 evidence=base / 'evidence', timeout=10)
            with patch.object(acceptance, 'require_no_host', return_value=[]), \
                    patch.object(acceptance, 'command', return_value={'status': 'complete', 'runtime': 'stopped',
                                                                     'cache': {'state': 'absent'}}), \
                    patch.object(acceptance, 'run_client', side_effect=acceptance.EvidenceError('proof failed')), \
                    patch.object(acceptance, 'stop_owned') as stop:
                summary = acceptance.execute(args)
            self.assertEqual('failed', summary['outcome'])
            self.assertTrue(summary['ownedRuntimeStopped'])
            self.assertEqual((base / 'evidence/workspace').resolve(), stop.call_args.args[1])

    def test_owned_pty_collects_activity_and_exits_a_fake_host(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            sockets = [base / 'public.sock', base / 'private.sock']
            host = base / 'fake-host'
            records = [event(tool, str(index), kind)
                       for index, tool in enumerate(acceptance.EXPECTED_TOOLS)
                       for kind in ('tool-call-started', 'tool-call-finished')]
            host.write_text('#!' + sys.executable + '\n' +
                            'import json, os, socket\n' +
                            'paths = json.loads(os.environ["PROOF_SOCKETS"])\n' +
                            'sockets = []\n' +
                            'for path in paths:\n' +
                            ' s = socket.socket(socket.AF_UNIX); s.bind(path); sockets.append(s)\n' +
                            'for record in ' + repr(records) + ': print(json.dumps(record), flush=True)\n' +
                            'line = input()\n' +
                            'for s, path in zip(sockets, paths): s.close(); os.unlink(path)\n' +
                            'raise SystemExit(0 if line == "/quit" else 2)\n')
            host.chmod(0o700)
            activities = acceptance.run_client(host, base, dict(os.environ, PROOF_SOCKETS=json.dumps(
                [str(path) for path in sockets])), base, 30, sockets)
            self.assertTrue(activities.complete())
            self.assertTrue((base / 'client.raw').stat().st_size > 0)
            self.assertFalse(any(path.exists() for path in sockets))

    def test_rejected_call_retains_activity_and_drains_graceful_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            host = base / 'fake-rejecting-host'
            records = [event(), event(kind='tool-call-finished', completion='rejected')]
            host.write_text('#!' + sys.executable + '\n' +
                            'import json\n' +
                            'for record in ' + repr(records) + ': print(json.dumps(record), flush=True)\n' +
                            'line = input()\n' +
                            'print("owned cleanup drained", flush=True)\n' +
                            'raise SystemExit(0 if line == "/quit" else 2)\n')
            host.chmod(0o700)
            with self.assertRaisesRegex(acceptance.EvidenceError, 'broker invocation rejected'):
                acceptance.run_client(host, base, os.environ, base, 10, [])
            recorded = [json.loads(line) for line in (base / 'broker-activity.jsonl').read_text().splitlines()]
            self.assertEqual(records, recorded)
            observation = json.loads((base / 'client-observation.json').read_text())
            self.assertEqual('stopped', observation['cleanup']['outcome'])
            self.assertEqual(0, observation['hostExit'])
            self.assertIn('broker invocation rejected', observation['primaryFailure'])
            self.assertIn(b'owned cleanup drained', (base / 'client.raw').read_bytes())

    def test_main_graceful_window_retries_a_quit_ignored_by_the_host(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            host = base / 'fake-delayed-host'
            records = [event(tool, str(index), kind)
                       for index, tool in enumerate(acceptance.EXPECTED_TOOLS)
                       for kind in ('tool-call-started', 'tool-call-finished')]
            host.write_text('#!' + sys.executable + '\n' +
                            'import json\n' +
                            'for record in ' + repr(records) + ': print(json.dumps(record), flush=True)\n' +
                            'first = input()\nsecond = input()\n' +
                            'raise SystemExit(0 if first == second == "/quit" else 2)\n')
            host.chmod(0o700)
            steps = ((0.1, 'quit-text', b'/quit'), (0.4, 'quit-submit', b'\r'),
                     (0.7, 'quit-retry-text', b'\x15/quit'), (1.0, 'quit-retry-submit', b'\r'))
            with patch.object(acceptance, 'GRACEFUL_EXIT_STEPS', steps):
                activities = acceptance.run_client(host, base, os.environ, base, 10, [])
            self.assertTrue(activities.complete())
            observation = json.loads((base / 'client-observation.json').read_text())
            self.assertEqual(0, observation['hostExit'])
            self.assertIsNone(observation['primaryFailure'])
            self.assertEqual([step[1] for step in steps],
                             [step['stage'] for step in observation['gracefulExitSteps']])
            self.assertEqual([], observation['cleanup']['stages'])

    def test_forced_cleanup_after_four_calls_cannot_manufacture_success(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            host = base / 'fake-stuck-host'
            records = [event(tool, str(index), kind)
                       for index, tool in enumerate(acceptance.EXPECTED_TOOLS)
                       for kind in ('tool-call-started', 'tool-call-finished')]
            host.write_text('#!' + sys.executable + '\n' +
                            'import json, time\n' +
                            'for record in ' + repr(records) + ': print(json.dumps(record), flush=True)\n' +
                            'time.sleep(60)\n')
            host.chmod(0o700)
            cleanup = acceptance.terminate_owned
            with patch.object(acceptance, 'GRACEFUL_EXIT_STEPS', ()), \
                    patch.object(acceptance, 'GRACEFUL_EXIT_TIMEOUT', 0.1), \
                    patch.object(acceptance, 'terminate_owned', side_effect=lambda process, master, drain:
                                 cleanup(process, master, drain, grace_seconds=0, signal_seconds=0.5)):
                with self.assertRaisesRegex(acceptance.EvidenceError, 'bounded graceful shutdown'):
                    acceptance.run_client(host, base, os.environ, base, 10, [])
            observation = json.loads((base / 'client-observation.json').read_text())
            self.assertEqual('stopped', observation['cleanup']['outcome'])
            self.assertIn('bounded graceful shutdown', observation['primaryFailure'])
            self.assertNotEqual(0, observation['hostExit'])

    def test_unexpected_cleanup_failure_cannot_mask_primary_failure_or_skip_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            process = Mock(pid=1234, returncode=1)
            process.poll.return_value = 1
            with patch.object(acceptance.subprocess, 'Popen', return_value=process), \
                    patch.object(acceptance, 'terminate_owned',
                                 side_effect=subprocess.TimeoutExpired('owned host', 3)):
                with self.assertRaisesRegex(acceptance.EvidenceError, 'before all four'):
                    acceptance.run_client(base / 'host', base, {}, base, 10, [])
            self.assertTrue((base / 'broker-activity.jsonl').exists())
            observation = json.loads((base / 'client-observation.json').read_text())
            self.assertEqual('cleanup-error', observation['cleanup']['outcome'])
            self.assertEqual('TimeoutExpired', observation['cleanup']['failure'])
            self.assertIn('before all four', observation['primaryFailure'])

    def test_cleanup_signals_only_owned_group_and_has_exact_child_fallback(self):
        for group in (1234, 9876):
            with self.subTest(group=group):
                process = Mock(pid=1234)
                process.poll.return_value = None
                with patch.object(acceptance.os, 'getpgid', return_value=group), \
                        patch.object(acceptance.os, 'killpg') as kill_group:
                    result = acceptance.terminate_owned(process, grace_seconds=0, signal_seconds=0)
                self.assertEqual('not-reaped', result['outcome'])
                process.kill.assert_called_once_with()
                if group == process.pid:
                    self.assertEqual(2, kill_group.call_count)
                    self.assertTrue(all(call.args[0] == process.pid for call in kill_group.call_args_list))
                    process.send_signal.assert_not_called()
                else:
                    kill_group.assert_not_called()
                    self.assertEqual(2, process.send_signal.call_count)

    def test_optimized_python_keeps_proof_gate(self):
        result = subprocess.run([sys.executable, '-O', '-c',
                                 'import codex_lifecycle_acceptance as a; a.require(False, "proof rejected")'],
                                cwd=Path(__file__).parent, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('proof rejected', result.stderr)


if __name__ == '__main__':
    unittest.main()
