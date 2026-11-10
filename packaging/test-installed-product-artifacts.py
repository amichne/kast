"""Exercise assembled Host archive admission without Java, installation, or IDE effects."""
from dataclasses import asdict, dataclass
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
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
    def test_extracted_task_tools_run_without_checkout_imports(self):
        from installer_fixture import InstallerFixture, admitted_tools

        source = Path(__file__).resolve().parent.parent
        inputs = (
            'bin/kast-task-vm', 'bin/kast-task-vm.lock', 'bin/setup-kast', 'bin/setup-kast.lock',
            'packaging/task_vm.py', 'packaging/task_vm_guest.py', 'packaging/composed_bundle.py',
            'packaging/composition_platform.py', 'packaging/managed_headless.py',
        )
        with InstallerFixture(admitted_tools(), parent=Path('/tmp').resolve()) as fixture:
            control = fixture.root / 'control.tar'
            with tarfile.open(control, 'w') as archive:
                for entry in inputs:
                    archive.add(source / entry, arcname='share/kast/task-vm/' + entry)
            product.verify_task_tools(fixture, control)
            fixture.mark_passed()

    def test_retired_container_payload_rejects_before_extraction(self):
        from installer_fixture import InstallerFixture, admitted_tools

        with InstallerFixture(admitted_tools(), parent=Path('/tmp').resolve()) as fixture:
            control = fixture.root / 'control.tar'
            with tarfile.open(control, 'w') as archive:
                entry = tarfile.TarInfo('share/kast/container/packaging/dev_container.py')
                archive.addfile(entry, io.BytesIO())
            with self.assertRaisesRegex(AssertionError, 'retired Python container payload'):
                product.verify_task_tools(fixture, control)
            self.assertFalse((fixture.root / 'extracted-task-tools').exists())
            fixture.mark_passed()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / 'host.zip'
        self.contract = json.loads(json.dumps(asdict(ContractFixture())))

    def write_archive(self, main='kast-ide-hosted.jar', duplicate=False, version='1.2.3', contract=None, line='262'):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as nested:
            nested.writestr('META-INF/plugin.xml', f'<idea-plugin><version>{version}</version>'
                            f'<idea-version since-build="{line}" until-build="{line}.*"/></idea-plugin>')
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

    def test_selected_263_descriptor_admits_and_another_line_rejects(self):
        self.write_archive(line='263')
        before = self.archive.read_bytes()
        product.verify_plugin_archive(self.archive, '1.2.3', self.contract, '263')
        self.assertEqual(before, self.archive.read_bytes())
        with self.assertRaises(AssertionError):
            product.verify_plugin_archive(self.archive, '1.2.3', self.contract, '262')

    def test_embedded_version_and_contract_still_need_exact_selected_identity(self):
        self.write_archive(version='1.2.4')
        with self.assertRaises(AssertionError):
            product.verify_plugin_archive(self.archive, '1.2.3', self.contract)
        self.write_archive(contract=json.loads(json.dumps(asdict(ContractFixture(runtimeProtocolIdentity='other.v1')))))
        with self.assertRaises(AssertionError):
            product.verify_plugin_archive(self.archive, '1.2.3', self.contract)


if __name__ == '__main__':
    unittest.main()
