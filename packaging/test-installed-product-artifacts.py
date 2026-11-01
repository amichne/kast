"""Exercise assembled Host archive admission without Java, installation, or IDE effects."""
from dataclasses import asdict, dataclass
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location('installed_product', Path(__file__).with_name('run-installed-product.py'))
product = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = product
spec.loader.exec_module(product)


@dataclass(frozen=True)
class ContractFixture:
    type: str = 'HOSTED_CONTRACT'
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v1'
    operationRegistryDigest: str = 'sha256:' + 'a' * 64
    wireSchemaDigest: str = 'sha256:' + 'b' * 64
    capabilities: tuple[str, ...] = ('query.run',)


class InstalledProductArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / 'host.zip'
        self.contract = json.loads(json.dumps(asdict(ContractFixture())))

    def write_archive(self, main='kast-ide-hosted.jar', duplicate=False, version='1.2.3', contract=None):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as nested:
            nested.writestr('META-INF/plugin.xml', f'<idea-plugin><version>{version}</version></idea-plugin>')
            nested.writestr('hosted-contract.json', json.dumps(self.contract if contract is None else contract))
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('kast-ide-hosted/lib/' + main, jar.getvalue())
            archive.writestr('kast-ide-hosted/lib/sqlite-jdbc-1.2.3.jar', b'dependency bytes')
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr('kast-ide-hosted/lib/' + main, jar.getvalue())

    def test_stable_main_jar_admits_without_changing_versioned_dependency_bytes(self):
        self.write_archive()
        before = self.archive.read_bytes()
        product.verify_plugin_archive(self.archive, '1.2.3', self.contract)
        self.assertEqual(before, self.archive.read_bytes())
        with zipfile.ZipFile(self.archive) as archive:
            self.assertEqual(b'dependency bytes', archive.read('kast-ide-hosted/lib/sqlite-jdbc-1.2.3.jar'))

    def test_legacy_main_jar_and_duplicate_stable_path_reject(self):
        for name, duplicate in (('kast-ide-hosted-1.2.3.jar', False), ('kast-ide-hosted.jar', True)):
            self.write_archive(main=name, duplicate=duplicate)
            with self.assertRaises(AssertionError):
                product.verify_plugin_archive(self.archive, '1.2.3', self.contract)

    def test_embedded_version_and_contract_still_need_exact_selected_identity(self):
        self.write_archive(version='1.2.4')
        with self.assertRaises(AssertionError):
            product.verify_plugin_archive(self.archive, '1.2.3', self.contract)
        self.write_archive(contract=json.loads(json.dumps(asdict(ContractFixture(runtimeProtocolIdentity='other.v1')))))
        with self.assertRaises(AssertionError):
            product.verify_plugin_archive(self.archive, '1.2.3', self.contract)


if __name__ == '__main__':
    unittest.main()
