"""Regression checks for proof failures and owned resource cleanup."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import enterprise_tls_acceptance as acceptance


class EnterpriseTlsAcceptanceTest(unittest.TestCase):
    def test_setup_failure_removes_generated_private_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            def fail_after_key(argv, cwd=None):
                key = Path(argv[argv.index('-keyout') + 1])
                key.write_text('test private key')
                raise RuntimeError('certificate setup failed')
            with patch.object(sys, 'argv', ['proof', '--kast', '/unused/kast', '--gradle', '/unused/gradle',
                                           '--jbr', '/unused/jbr', '--evidence', str(base)]), \
                    patch.object(acceptance, 'command', side_effect=fail_after_key):
                with self.assertRaisesRegex(RuntimeError, 'certificate setup failed'):
                    acceptance.main()
            self.assertEqual([], list(base.rglob('*.key')))

    def test_proof_failure_still_stops_owned_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            result = subprocess.CompletedProcess([], 0, '{"status":"complete","runtime":"stopped"}', '')
            with patch.object(acceptance.subprocess, 'run', return_value=result) as run:
                with self.assertRaisesRegex(RuntimeError, 'semantic timed out'):
                    with acceptance.owned_runtime(Path('/kast'), base, {}, base):
                        raise RuntimeError('semantic timed out')
            self.assertEqual(['/kast', 'stop'], run.call_args.args[0])
            self.assertTrue((base / 'stop.stdout.json').is_file())

    def test_unsuccessful_stop_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for result in (subprocess.CompletedProcess([], 1, '{}', ''),
                           subprocess.CompletedProcess([], 0, '{"status":"complete","runtime":"running"}', '')):
                with self.subTest(result=result), patch.object(acceptance.subprocess, 'run', return_value=result):
                    with self.assertRaisesRegex(RuntimeError, 'stop'):
                        with acceptance.owned_runtime(Path('/kast'), base, {}, base):
                            pass

    def test_repository_tls_failure_requires_dependency_and_import_evidence(self):
        endpoint = 'https://localhost:12345'
        relevant = ("Could not resolve enterprise:proof:1.0.\n"
                    f"Could not GET '{endpoint}/maven/enterprise/proof/1.0/proof-1.0.pom'.\n"
                    'PKIX path building failed')
        acceptance.require_repository_tls_failure(relevant, endpoint)
        for unrelated in ('PKIX path building failed',
                          'Could not resolve enterprise:proof:1.0.\nSSLHandshakeException',
                          relevant.replace(endpoint, 'https://another-host:12345')):
            with self.subTest(unrelated=unrelated), self.assertRaises(RuntimeError):
                acceptance.require_repository_tls_failure(unrelated, endpoint)

    def test_optimized_python_keeps_proof_gate(self):
        result = subprocess.run([sys.executable, '-O', '-c',
                                 'import enterprise_tls_acceptance as a; a.require(False, "proof rejected")'],
                                cwd=Path(__file__).parent, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('proof rejected', result.stderr)


if __name__ == '__main__':
    unittest.main()
