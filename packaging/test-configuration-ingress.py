import unittest
import json
from dataclasses import asdict, dataclass
from pathlib import Path
import subprocess
import sys
import tempfile
from configuration_ingress import Failure, violations

@dataclass(frozen=True)
class EmptyCatalogue:
    parameters: tuple[str, ...] = ()

@dataclass(frozen=True)
class EmptyIngressPolicy:
    ingressOwners: tuple[str, ...] = ()

class ConfigurationIngressTest(unittest.TestCase):
    def test_operational_projection_drift_is_rejected_by_snapshot_check(self):
        for altered in (None, 'INSTALL_DOWNLOAD_RETRIES', 'RETIREMENT_CHILD_TIMEOUT_MILLIS'):
            with self.subTest(altered=altered), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / 'packaging').mkdir()
                shell = {'INSTALL_DOWNLOAD_RETRIES': 5, 'INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS': 2000}
                python = {'RETIREMENT_CHILD_TIMEOUT_MILLIS': 60000, 'STATE_MAXIMUM_ENTRIES': 100000}
                keys = ['installation.download.retries', 'installation.download.retry_delay',
                        'installation.retirement.child_timeout', 'installation.state.maximum_entries']
                schema = {'parameters': [], 'operationalLimits': [dict(key=key, value=value)
                    for key, value in zip(keys, [*shell.values(), *python.values()])]}
                (root / 'install.sh').write_text('\n'.join(f'readonly {key}={value + (key == altered)}' for key, value in shell.items()))
                (root / 'packaging/installation-lifecycle.py').write_text('\n'.join(f'{key} = {value + (key == altered)}' for key, value in python.items()))
                for name in ('generated.json', 'snapshot.json'):
                    (root / name).write_text(json.dumps(schema))
                (root / 'policy.json').write_text('{"ingressOwners":[]}')
                result = subprocess.run([sys.executable, str(Path(__file__).with_name('configuration_ingress.py')),
                    '--root', str(root), '--schema', str(root / 'generated.json'), '--snapshot', str(root / 'snapshot.json'),
                    '--policy', str(root / 'policy.json')], capture_output=True, text=True, timeout=10)
                if altered is None:
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                else:
                    self.assertNotEqual(0, result.returncode, 'altered operational projection was accepted')
                    self.assertEqual('OPERATIONAL_PROJECTION_MISMATCH', json.loads(result.stdout)['findings'][0]['condition'])

    def test_stale_checked_in_snapshot_rejects_even_equivalent_json(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            generated = root / "generated.json"
            snapshot = root / "snapshot.json"
            policy = root / "policy.json"
            generated.write_bytes(b'{"parameters":[]}\n')
            snapshot.write_bytes(b'{ "parameters": [] }\n')
            policy.write_text('{"ingressOwners":[]}')
            command = [sys.executable, str(Path(__file__).with_name("configuration_ingress.py")),
                       "--root", str(root), "--schema", str(generated), "--policy", str(policy), "--snapshot", str(snapshot)]
            stale = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, stale.returncode, "stale generated snapshot was accepted")
            self.assertEqual("GENERATED_SNAPSHOT_MISMATCH", json.loads(stale.stdout)["findings"][0]["condition"])
            snapshot.write_bytes(generated.read_bytes())
            current = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, current.returncode, current.stdout + current.stderr)

    def test_undeclared_kast_input_is_rejected_at_named_ingress(self):
        result = violations('cli/Ingress.kt', 'System.getenv("KAST_UNDECLARED")', {'KAST_INDEXER_MAX_HEAP'}, {'cli/Ingress.kt'})
        self.assertEqual([(Failure.UNDECLARED_KEY, 'KAST_UNDECLARED')], result)

    def test_test_only_inputs_are_excluded_but_production_inputs_still_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            packaging = root / 'packaging'
            packaging.mkdir()
            for name in ('test-fixture.py', 'test_fixture.py'):
                (packaging / name).write_text('os.environ.get("KAST_TEST_ONLY")')
            for name in ('generated.json', 'snapshot.json'):
                (root / name).write_text(json.dumps(asdict(EmptyCatalogue())))
            (root / 'policy.json').write_text(json.dumps(asdict(EmptyIngressPolicy())))
            command = [sys.executable, str(Path(__file__).with_name('configuration_ingress.py')),
                       '--root', str(root), '--schema', str(root / 'generated.json'),
                       '--snapshot', str(root / 'snapshot.json'), '--policy', str(root / 'policy.json')]
            tests_only = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, tests_only.returncode, tests_only.stdout + tests_only.stderr)
            (packaging / 'fixture.py').write_text('os.environ.get("KAST_UNDECLARED")')
            production = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, production.returncode)
            findings = json.loads(production.stdout)['findings']
            self.assertEqual({'UNDECLARED_KEY', 'UNOWNED_INGRESS'}, {item['condition'] for item in findings})
            self.assertEqual({'packaging/fixture.py'}, {item['file'] for item in findings})

    def test_declared_input_cannot_add_an_ambient_reader_outside_named_ingress(self):
        result = violations('core/Pure.kt', 'System.getenv("KAST_INDEXER_MAX_HEAP")', {'KAST_INDEXER_MAX_HEAP'}, {'cli/Ingress.kt'})
        self.assertEqual([(Failure.UNOWNED_INGRESS, 'core/Pure.kt')], result)

    def test_unknown_script_inputs_and_jvm_properties_are_covered(self):
        self.assertEqual([(Failure.UNDECLARED_KEY, 'KAST_NEW')], violations('install.sh', '${KAST_NEW:-}', set(), {'install.sh'}))
        self.assertEqual([(Failure.UNDECLARED_KEY, 'kast.new.proof')], violations('cli/Ingress.kt', 'System.getProperty("kast.new.proof")', set(), {'cli/Ingress.kt'}))

    def test_symbolic_property_reader_still_requires_declared_key_and_owner(self):
        source = 'private const val PROPERTY = "kast.new.proof"; System.getProperty(PROPERTY)'
        self.assertEqual([(Failure.UNDECLARED_KEY, 'kast.new.proof')], violations('cli/Ingress.kt', source, set(), {'cli/Ingress.kt'}))
        self.assertEqual([], violations('core/Receipt.kt', 'values.getProperty("kast.payload.digest")', set(), set()))

    def test_named_reads_and_third_party_values_are_not_blanket_rejected(self):
        source = 'System.getenv("KAST_INDEXER_MAX_HEAP"); System.getProperty("java.home"); System.getenv("GITHUB_TOKEN")'
        self.assertEqual([], violations('cli/Ingress.kt', source, {'KAST_INDEXER_MAX_HEAP'}, {'cli/Ingress.kt'}))
        self.assertEqual([], violations('core/Pure.kt', 'val KAST_OPERATION = "query.run"', set(), set()))

if __name__ == '__main__':
    unittest.main()
