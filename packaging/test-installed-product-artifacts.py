"""Exercise assembled Host archive admission without Java, installation, or IDE effects."""
from dataclasses import asdict, dataclass
import importlib.util
import gzip
import io
import json
from pathlib import Path
import sys
import stat
import tarfile
import tempfile
import unittest
import warnings
import zipfile

import managed_release as release

spec = importlib.util.spec_from_file_location('installed_product', Path(__file__).with_name('run-installed-product.py'))
product = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = product
spec.loader.exec_module(product)


@dataclass(frozen=True)
class ContractFixture:
    type: str = 'HOSTED_CONTRACT'
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v3'
    operationRegistryDigest: str = 'sha256:' + 'a' * 64
    wireSchemaDigest: str = 'sha256:' + 'b' * 64
    capabilities: tuple[str, ...] = ('workspace.lifecycle', 'query.run', 'source.read', 'diagnostic.check', 'change.plan', 'change.apply', 'change.recover')


@dataclass(frozen=True)
class ControlMetadataFixture:
    productVersion: str = '1.2.3'
    schemaVersion: int = 2
    execution: str = 'existing_ide'
    ideaBuild: str = '262.1'
    kotlinPluginBuild: str = '262.1-IJ'
    requiredHostedContract: ContractFixture = ContractFixture()


@dataclass(frozen=True)
class BuildFixture:
    version: str = '1.2.3'
    sourceRevision: str = 'a' * 40
    sourceTree: str = 'b' * 40


@dataclass(frozen=True)
class TargetProfileFixture:
    targets: tuple[release.SdkTarget, release.SdkTarget]
    schemaVersion: int = 1
    defaultTarget: str = '262'


@dataclass(frozen=True)
class HostArtifactFixture:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class HostRecordFixture:
    artifact: HostArtifactFixture
    type: str = 'HOST_RELEASE'
    hostedPluginVersion: str = '1.2.3'
    sourceRevision: str = 'a' * 40
    supportedIntellijReleaseLine: str = '263'
    providedHostedContract: ContractFixture = ContractFixture()


class InstalledProductArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / 'host.zip'
        self.contract = json.loads(json.dumps(asdict(ContractFixture())))

    def write_archive(self, main='kast-ide-hosted.jar', duplicate=False, version='1.2.3', contract=None,
                      line='262', sdk='262.1', kotlin='262.1-IJ', source='a' * 40, tree='b' * 40):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as nested:
            nested.writestr('META-INF/plugin.xml', f'<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version><idea-version since-build="{line}" until-build="{line}.*"/></idea-plugin>')
            nested.writestr('hosted-contract.json', json.dumps(self.contract if contract is None else contract))
            nested.writestr('kast-hosted-query.properties', f'version={version}\nsourceRevision={source}\nsourceTree={tree}\nideBuild={sdk}\nkotlinBuild={kotlin}\n')
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

    def test_explicit263_archive_keeps_distinct_sdk_and_source_proof(self):
        target = release.SdkTarget('263', '263.7', '263.7-IJ')
        identity = release.ArchiveBuildIdentity('1.2.3', 'a' * 40, 'b' * 40)
        self.write_archive(line='263', sdk='263.7', kotlin='263.7-IJ')
        product.verify_plugin_archive(self.archive, '1.2.3', self.contract, target, identity)

    def test_target_substitution_rejects_without_running_any_ide(self):
        target = release.SdkTarget('263', '263.7', '263.7-IJ')
        identity = release.ArchiveBuildIdentity('1.2.3', 'a' * 40, 'b' * 40)
        for kwargs in ({'line': '262'}, {'sdk': '263.8'}, {'kotlin': '263.8-IJ'}, {'source': 'c' * 40}, {'tree': 'c' * 40}):
            with self.subTest(changed=kwargs):
                inputs = {'line': '263', 'sdk': '263.7', 'kotlin': '263.7-IJ'}
                inputs.update(kwargs)
                self.write_archive(**inputs)
                with self.assertRaises(AssertionError):
                    product.verify_plugin_archive(self.archive, '1.2.3', self.contract, target, identity)

    def test_metadata_fixture_uses_the_explicit_target_not_the_control_default(self):
        target = release.SdkTarget('263', '263.7', '263.7-IJ')
        info = product.fixture_idea_info(target)
        self.assertEqual('263.7', info.buildNumber)
        self.assertEqual('IntelliJIdea2026.3', info.dataDirectoryName)

    def test_report_codec_matches_the_frozen_contract_fixture_scope(self):
        artifact = product.ReportArtifact('case-owned-artifact', 'd' * 64, 1)
        report = product.InstalledProductReport(
            '263', '263.7', '263.7-IJ', 'c' * 64, '1.2.3', '1.2.3',
            'a' * 40, 'b' * 40, artifact, artifact, artifact,
        )
        actual = json.loads(json.dumps(asdict(report)))
        self.assertEqual({
            'schemaVersion', 'scope', 'status', 'intellijReleaseLine', 'ideBuild',
            'kotlinPluginBuild', 'targetProfileSha256', 'controlVersion', 'hostedPluginVersion',
            'sourceRevision', 'sourceTree', 'controlArtifact', 'hostArtifact',
            'hostReleaseRecord', 'runtimeIdeLaunched', 'completedChecks',
        }, set(actual))
        self.assertEqual('INSTALLATION_CONTRACT_METADATA_FIXTURE', actual['scope'])
        self.assertEqual('PASSED', actual['status'])
        self.assertIs(False, actual['runtimeIdeLaunched'])
        self.assertIs(int, type(actual['schemaVersion']))
        self.assertEqual(1, actual['schemaVersion'])
        self.assertEqual([
            'ARTIFACT_IDENTITY', 'NATIVE_LAUNCHER', 'CONTROL_STAGING',
            'INSTALLED_QUERY_DISPATCH', 'NATIVE_HOST_AND_STATE_CLEANUP',
        ], actual['completedChecks'])
        self.assertEqual({'fileName', 'sha256', 'bytes'}, set(actual['hostArtifact']))
        self.assertEqual('d' * 64, actual['hostArtifact']['sha256'])

    def test_report_destination_rejects_existing_files_and_symlink_parents(self):
        root = self.archive.parent
        prior = root / 'installed-product.json'
        prior.write_bytes(b'prior report')
        with self.assertRaises(product.FixtureRejected):
            product.admitted_report_path(str(prior))
        self.assertEqual(b'prior report', prior.read_bytes())
        physical = root / 'physical'
        physical.mkdir()
        alias = root / 'alias'
        alias.symlink_to(physical, target_is_directory=True)
        with self.assertRaises(product.FixtureRejected):
            product.admitted_report_path(str(alias / 'new.json'))
        self.assertTrue(alias.is_symlink())
        self.assertEqual([], list(physical.iterdir()))

    def staged_control_fixture(self):
        root = self.archive.parent.resolve()
        staged = root / 'product'
        profile = TargetProfileFixture((
            release.SdkTarget('262', '262.1', '262.1-IJ'),
            release.SdkTarget('263', '263.7', '263.7-IJ'),
        ))
        resources = {
            'share/kast/ide-host.json': json.dumps(asdict(ControlMetadataFixture())).encode(),
            'share/kast/build-identity.json': json.dumps(asdict(BuildFixture())).encode(),
            'share/kast/intellij-targets.json': json.dumps(asdict(profile)).encode(),
            'share/kast/operation-registry.json': b'case-owned content, not executed',
            'share/kast/wire-schema.json': b'case-owned content, not executed',
            'share/kast/knowledge/manifest.json': b'case-owned content, not executed',
            'share/kast/host-installation.py': b'case-owned content, not executed',
            'share/kast/reset-fence-v1': b'1\n',
            'share/kast/public-reset-v1': b'1\n',
        }
        for name in ('bin/kast', 'bin/kast-codex', 'share/kast/libexec/kast-daemon',
                     'share/kast/libexec/kast-service', 'share/kast/libexec/kast-management'):
            resources[name] = b'case-owned executable bytes; never executed'
        for name, raw in resources.items():
            path = staged / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            path.chmod(0o755 if name.startswith('bin/') or '/libexec/' in name else 0o644)
        control = root / 'kast-control-v1.2.3-macos-aarch64.tar.gz'
        with tarfile.open(control, 'w:gz') as archive:
            archive.add(staged / 'bin', arcname='bin')
            archive.add(staged / 'share', arcname='share')
        self.archive = root / 'kast-ide-hosted-v1.2.3-idea-263.zip'
        self.write_archive(line='263', sdk='263.7', kotlin='263.7-IJ')
        record = root / 'kast-host-release-v1.2.3-idea-263.json'
        artifact = HostArtifactFixture(self.archive.name, 'sha256:' + release.file_digest(self.archive), self.archive.stat().st_size)
        record.write_text(json.dumps(asdict(HostRecordFixture(artifact))))
        return staged, control, record

    def test_report_control_archive_must_be_the_payload_that_was_exercised(self):
        staged, control, record = self.staged_control_fixture()
        pair = product.verify_artifacts(staged, control, self.archive, record, '263')
        self.assertEqual(control, pair.control)
        executable = staged / 'share/kast/libexec/kast-management'
        executable.write_bytes(b'other same-version native executable')
        with self.assertRaises(AssertionError):
            product.verify_artifacts(staged, control, self.archive, record, '263')

    def test_control_payload_modes_and_extra_executable_bytes_are_not_unreported(self):
        staged, control, record = self.staged_control_fixture()
        executable = staged / 'share/kast/libexec/kast-management'
        executable.chmod(0o700)
        with self.assertRaises(AssertionError):
            product.verify_artifacts(staged, control, self.archive, record, '263')
        executable.chmod(0o755)
        (staged / 'share/kast/unreported.py').write_bytes(b'unreported code')
        with self.assertRaises(AssertionError):
            product.verify_artifacts(staged, control, self.archive, record, '263')

    def raw_mode_archive_fixture(self, directory_mode, file_mode):
        """Encode the actual mode field: tarfile's writer otherwise masks POSIX type bits."""
        root = self.archive.parent.resolve()
        staged = root / 'raw-mode-product'
        (staged / 'bin').mkdir(parents=True, mode=0o755, exist_ok=True)
        (staged / 'bin').chmod(0o755)
        content = b'owned fixture bytes, not executed\n'
        path = staged / 'bin/kast'
        path.write_bytes(content)
        path.chmod(0o755)

        def member(name, mode, kind, raw=b''):
            item = tarfile.TarInfo(name)
            item.type, item.size = kind, len(raw)
            header = bytearray(item.tobuf(format=tarfile.USTAR_FORMAT))
            header[100:108] = f'{mode:07o}\0'.encode('ascii')
            header[148:156] = b' ' * 8
            header[148:156] = f'{sum(header):06o}\0 '.encode('ascii')
            return bytes(header) + raw + b'\0' * (-len(raw) % 512)

        encoded = member('bin', directory_mode, tarfile.DIRTYPE) + member('bin/kast', file_mode, tarfile.REGTYPE, content) + b'\0' * 1024
        archive = root / 'raw-mode-control.tar.gz'
        archive.write_bytes(gzip.compress(encoded))
        with tarfile.open(archive, 'r:gz') as observed:
            self.assertEqual(directory_mode, observed.getmember('bin').mode)
            self.assertEqual(file_mode, observed.getmember('bin/kast').mode)
        return staged, archive

    def test_gradle_raw_regular_and_directory_modes_bind_exact_permissions(self):
        staged, archive = self.raw_mode_archive_fixture(0o40755, 0o100755)
        before = archive.read_bytes()
        product.verify_control_archive_matches_product(staged, archive)
        self.assertEqual(before, archive.read_bytes())

    def test_permission_only_tar_modes_remain_admitted(self):
        staged, archive = self.raw_mode_archive_fixture(0o755, 0o755)
        product.verify_control_archive_matches_product(staged, archive)

    def test_raw_tar_mode_rejects_wrong_file_kinds_and_special_permissions(self):
        cases = (
            (0o100755, 0o100755),  # directory header falsely says regular
            (0o40755, 0o40755),  # regular header falsely says directory
            (0o40755, 0o120755),  # regular header with symlink kind
            (0o40755, 0o10755),  # regular header with FIFO kind
            (0o40755, 0o140755),  # regular header with socket kind
            (0o40755, 0o4000755),  # undeclared high mode bits
            (0o40755, -1),  # a signed mode field must not reach platform stat conversion
        ) + tuple(
            modes
            for special in (stat.S_ISUID, stat.S_ISGID, stat.S_ISVTX)
            for modes in ((0o40755 | special, 0o100755), (0o40755, 0o100755 | special))
        )
        for directory_mode, file_mode in cases:
            with self.subTest(directory_mode=oct(directory_mode), file_mode=oct(file_mode)):
                staged, archive = self.raw_mode_archive_fixture(directory_mode, file_mode)
                before = archive.read_bytes()
                with self.assertRaisesRegex(AssertionError, 'Control archive mode'):
                    product.verify_control_archive_matches_product(staged, archive)
                self.assertEqual(before, archive.read_bytes())

    def test_gradle_raw_modes_cannot_mask_changed_permissions_bytes_or_inventory(self):
        for changed in ('directory-permissions', 'file-permissions', 'bytes', 'inventory'):
            with self.subTest(changed=changed):
                staged, archive = self.raw_mode_archive_fixture(0o40755, 0o100755)
                if changed == 'directory-permissions':
                    (staged / 'bin').chmod(0o700)
                elif changed == 'file-permissions':
                    (staged / 'bin/kast').chmod(0o700)
                elif changed == 'bytes':
                    content = (staged / 'bin/kast').read_bytes()
                    (staged / 'bin/kast').write_bytes(b'X' * len(content))
                else:
                    (staged / 'bin/unreported').write_bytes(b'owned unreported bytes')
                reason = 'Control product mode' if 'permissions' in changed else 'Control product bytes' if changed == 'bytes' else 'Control product inventory'
                with self.assertRaisesRegex(AssertionError, reason):
                    product.verify_control_archive_matches_product(staged, archive)
                if changed == 'inventory':
                    (staged / 'bin/unreported').unlink()


if __name__ == '__main__':
    unittest.main()
