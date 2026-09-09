import unittest
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from configuration_ingress import Failure, violations

class ConfigurationIngressTest(unittest.TestCase):
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
        self.assertEqual([], violations('core/Pure.kt', 'val KAST_OPERATION = "symbol.inspect"', set(), set()))

if __name__ == '__main__':
    unittest.main()
