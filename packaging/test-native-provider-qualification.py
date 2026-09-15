#!/usr/bin/env python3
"""Executable pipe and report proof for closed native provider startup evidence."""
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from hosted_read_regression import run_read_regression
from hosted_authority_read_regression import AuthorityReport, AuthorityOutcome
from hosted_vfs_overflow_regression import OverflowReport, OverflowOutcome, OverflowFailure
from hosted_read_policy import NativeReadPolicy
from hosted_read_transport import HostedReadTransport, ReadTransportRejected
from native_provider_qualification import QualificationCause


class NativeProviderQualificationTest(unittest.TestCase):
    def run_report(self, event, emit=True, policy=NativeReadPolicy.DEFAULT):
        def spawn(_command, **options):
            options.pop('cwd')
            options.pop('env')
            return subprocess.Popen([sys.executable, '-c',
                'import sys; print(sys.argv[1], flush=True)' if emit else 'pass', json.dumps(event)], **options)
        isolation = SimpleNamespace(spawn=spawn)
        fixture = SimpleNamespace(workspace=Path('/synthetic'), environment={})
        read_fixture = SimpleNamespace(unchanged=lambda: True, evidence=lambda: {})
        def replay(_oracle, _fixture, _live, _transport, _surface, rows):
            return SimpleNamespace(run=lambda: rows.append({'passed': True}))
        with patch('hosted_read_transport.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout=b'{}')), \
             patch('hosted_read_transport._admit_cli_invocations', return_value={'source_read': ('source', 'read')}), \
             patch('hosted_read_regression._reproduction', return_value=None), \
             patch('hosted_read_regression._ReadReplay', side_effect=replay), \
             patch('hosted_read_regression.run_concurrent_read_regression', return_value={'outcome': 'passed'}), \
             patch('hosted_read_regression.run_authority_read_regression',
                   return_value=AuthorityReport(AuthorityOutcome.PASSED, sourceRestored=True)):
            return run_read_regression(isolation, fixture, Path('/product'), Path('/java'), Path('/harness'),
                                       Path('/repo'), read_fixture, {}, policy)

    def test_overflow_policy_requires_receipt_and_preserves_restored_epoch(self):
        event = {'outcome': 'admitted', 'stage': 'PROVIDER_QUALIFICATION'}
        for outcome in (OverflowOutcome.PASSED, OverflowOutcome.REJECTED):
            with self.subTest(outcome=outcome), patch('hosted_read_regression.run_vfs_overflow_regression',
                    return_value=(OverflowReport(outcome, filesRestored=True), {'epoch': 4})) as overflow:
                report = self.run_report(event, policy=NativeReadPolicy.OVERFLOW)
                self.assertEqual(outcome.value, report['outcome'])
                self.assertEqual(outcome.value, report['overflowReplay']['outcome'])
                overflow.assert_called_once()
        with patch('hosted_read_regression.run_vfs_overflow_regression',
                return_value=(OverflowReport(failure=OverflowFailure.RESTORATION), None)):
            report = self.run_report(event, policy=NativeReadPolicy.OVERFLOW)
            self.assertEqual('rejected', report['outcome'])
            self.assertEqual(0, report['caseCount'])
            self.assertEqual('OVERFLOW_RESTORATION_REJECTED', report['overflowReplay']['failure'])

    def test_successful_startup_enters_report_before_read_cases(self):
        event = {'outcome': 'admitted', 'stage': 'PROVIDER_QUALIFICATION'}
        report = self.run_report(event)
        self.assertEqual('passed', report['outcome'])
        self.assertEqual(event, report['providerQualification'])
        self.assertEqual(2, report['caseCount'])

    def test_every_rejected_startup_retains_cause_before_any_read(self):
        for cause in QualificationCause:
            with self.subTest(cause=cause):
                event = {'outcome': 'rejected', 'stage': 'PROVIDER_QUALIFICATION', 'cause': cause.value}
                report = self.run_report(event)
                self.assertEqual('rejected', report['outcome'])
                self.assertEqual('READ_TRANSPORT_REJECTED', report['failure'])
                self.assertEqual(event, report['providerQualification'])
                self.assertEqual({'reason': 'READ_PROVIDER_QUALIFICATION_REJECTED',
                                  'providerQualification': event}, report['failureDetails'])
                self.assertEqual(0, report['caseCount'])

    def test_unknown_stage_outcome_cause_and_extra_payload_fail_closed(self):
        events = [
            {'outcome': 'admitted', 'stage': 'UNPROVEN'},
            {'outcome': 'importing', 'stage': 'PROVIDER_QUALIFICATION'},
            {'outcome': 'rejected', 'stage': 'PROVIDER_QUALIFICATION', 'cause': 'UNPROVEN'},
            {'outcome': 'admitted', 'stage': 'PROVIDER_QUALIFICATION', 'payload': 'synthetic-private-text'},
        ]
        for event in events:
            with self.subTest(event=event):
                report = self.run_report(event)
                self.assertEqual('READ_PROVIDER_PROTOCOL_REJECTED', report['failureDetails']['reason'])
                self.assertIsNone(report['providerQualification'])
                self.assertNotIn('synthetic-private-text', json.dumps(report))

    def test_real_pipe_rejects_oversized_or_absent_handshake_before_read(self):
        oversized = self.run_report({'outcome': 'admitted', 'stage': 'PROVIDER_QUALIFICATION',
                                     'payload': 'synthetic-private-text' * 100})
        absent = self.run_report(None, emit=False)
        self.assertEqual('READ_PROVIDER_OUTPUT_BOUND', oversized['failureDetails']['reason'])
        self.assertEqual('READ_PROVIDER_DISCONNECTED', absent['failureDetails']['reason'])
        for report in (oversized, absent):
            self.assertEqual(0, report['caseCount'])
            self.assertIsNone(report['providerQualification'])
            self.assertNotIn('synthetic-private-text', json.dumps(report))

    def test_startup_frame_bound_and_disconnect_retain_transport_failure(self):
        transport = HostedReadTransport(None, None, None, None, None)
        for reason in ('READ_PROVIDER_OUTPUT_BOUND', 'READ_PROVIDER_DISCONNECTED'):
            with patch.object(transport, '_response', side_effect=ReadTransportRejected(reason)) as response:
                with self.assertRaises(ReadTransportRejected) as rejected:
                    transport._qualify()
                self.assertEqual(reason, rejected.exception.reason.value)
                response.assert_called_once_with(maximum_bytes=1024)


if __name__ == '__main__':
    unittest.main()
