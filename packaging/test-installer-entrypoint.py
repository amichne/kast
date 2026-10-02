from dataclasses import asdict, dataclass
#!/usr/bin/env python3
"""Public installer invocation contract; no network or machine state changes."""

from pathlib import Path
import hashlib
import io
import json
import os
import shlex
import subprocess
import shutil
import sys
import tarfile
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parent.parent
INSTALLER = ROOT / "install.sh"
DOCUMENTS = (
    ROOT / "README.md",
    ROOT / "docs/public/start.mdx",
    ROOT / "docs/public/reference/compatibility.mdx",
)
CANONICAL_PREFIX = '/bin/bash -c "$(curl -fsSL '
# Admit the interpreter once; the collision child's PATH has no host utility fallback.
BASH = Path(shutil.which('bash', path=os.defpath)).resolve(strict=True)
TOOL_PATH = str(Path(sys.executable).resolve().parent) + os.pathsep + os.defpath


@dataclass(frozen=True)
class ContractFixture:
    type: str = 'HOSTED_CONTRACT'
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v2'
    operationRegistryDigest: str = 'sha256:' + 'a' * 64
    wireSchemaDigest: str = 'sha256:' + 'b' * 64
    capabilities: tuple[str, ...] = ('query.run',)


@dataclass(frozen=True)
class ControlMetadataFixture:
    productVersion: str
    schemaVersion: int = 2
    execution: str = 'existing_ide'
    ideaBuild: str = '262.1234'
    kotlinPluginBuild: str = '262.1234-IJ'
    requiredHostedContract: ContractFixture = ContractFixture()


@dataclass(frozen=True)
class ArtifactFixture:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class HostReleaseFixture:
    hostedPluginVersion: str
    artifact: ArtifactFixture
    supportedIntellijReleaseLine: str
    type: str = 'HOST_RELEASE'
    providedHostedContract: ContractFixture = ContractFixture()
    sourceRevision: str = '1' * 40


class InstallerEntrypointTest(unittest.TestCase):
    def installer_fixture(self, directory: str, *, plugin_line: str = "262", version: str = "1.2.3", reset_capability: bool = False):
        root = Path(directory)
        home = root / "home"
        idea = root / "IntelliJ IDEA.app/Contents"
        assets = root / "assets"
        bin_directory = root / "bin"
        for path in (home, assets, bin_directory, idea / "plugins/Kotlin", idea / "jbr/Contents/Home/bin"):
            path.mkdir(parents=True, exist_ok=True)
        # The public installer owns the OS policy; this fixture supplies its one OS observation.
        uname = bin_directory / 'uname'
        uname.write_text('#!/bin/sh\ncase "$1" in -s) echo Darwin ;; -m) echo arm64 ;; *) exit 1 ;; esac\n')
        uname.chmod(0o755)
        (idea / "Resources").mkdir()
        (idea / "Resources/build.txt").write_text("IU-262.1234\n")
        (idea / "Resources/product-info.json").write_text(json.dumps({
            "buildNumber": "262.1234", "dataDirectoryName": "IntelliJIdea2026.2", "version": "2026.2.1",
        }))
        (idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25.0.1"\nOS_ARCH="aarch64"\n')
        (idea / "jbr/Contents/Home/bin/java").write_text("")
        (idea / "jbr/Contents/Home/bin/java").chmod(0o755)

        control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
        control = assets / control_name
        executable = b"#!/bin/sh\nprintf 'profile=%s mode=%s force=%s idea=%s\\n' \"$KAST_INSTALL_PROFILE\" \"$KAST_INSTALL_MODE\" \"$KAST_INSTALL_FORCE\" \"$KAST_INSTALL_IDEA_HOME\" >&2\n"
        if reset_capability:
            executable += b"printf 'options=%s\\n' \"$*\" >&2\n"
        info = tarfile.TarInfo("share/kast/libexec/kast-service")
        info.mode = 0o755
        info.size = len(executable)
        with tarfile.open(control, "w:gz") as archive:
            archive.addfile(info, io.BytesIO(executable))
            if reset_capability:
                capability = tarfile.TarInfo("share/kast/reset-fence-v1")
                capability.mode, capability.size = 0o644, 2
                archive.addfile(capability, io.BytesIO(b"1\n"))
            management = b'''#!/bin/sh
set -eu
[ "$1" = --internal-install ] || exit 92
case "$2" in
  preflight|commit) printf '%s\\n' "$HOME/.local/bin/kast" ;;
  *) exit 93 ;;
esac
'''
            item = tarfile.TarInfo('share/kast/libexec/kast-management')
            item.mode, item.size = 0o755, len(management)
            archive.addfile(item, io.BytesIO(management))
            helper = (ROOT / 'packaging/host-installation.py').read_bytes()
            item = tarfile.TarInfo('share/kast/host-installation.py')
            item.mode, item.size = 0o644, len(helper)
            archive.addfile(item, io.BytesIO(helper))
            metadata = json.dumps(asdict(ControlMetadataFixture(version))).encode()
            item = tarfile.TarInfo('share/kast/ide-host.json')
            item.mode, item.size = 0o644, len(metadata)
            archive.addfile(item, io.BytesIO(metadata))
        plugin_name = f"kast-ide-hosted-v{version}-idea-{plugin_line}.zip"
        plugin = assets / plugin_name
        descriptor = (f'<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version>'
                      f'<idea-version since-build="{plugin_line}" until-build="{plugin_line}.*"/></idea-plugin>').encode()
        jar_buffer = io.BytesIO()
        with zipfile.ZipFile(jar_buffer, "w") as jar:
            jar.writestr("META-INF/plugin.xml", descriptor)
        with zipfile.ZipFile(plugin, "w") as archive:
            archive.writestr("kast-ide-hosted/lib/plugin.jar", jar_buffer.getvalue())
        host_record = assets / f'kast-host-release-v{version}.json'
        host_record.write_text(json.dumps(asdict(HostReleaseFixture(version,
            ArtifactFixture(plugin_name, 'sha256:' + hashlib.sha256(plugin.read_bytes()).hexdigest(), plugin.stat().st_size), plugin_line))))
        host_helper = assets / 'host-installation.py'
        shutil.copyfile(ROOT / 'packaging/host-installation.py', host_helper)
        for asset in (control, plugin, host_helper, host_record):
            digest = hashlib.sha256(asset.read_bytes()).hexdigest()
            asset.with_name(asset.name + ".sha256").write_text(f"{digest}  {asset.name}\n")
        environment = {
            "HOME": str(home), "PATH": str(bin_directory) + os.pathsep + TOOL_PATH, "NO_COLOR": "1",
            "KAST_VERSION": version, "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
            "KAST_INSTALL_ROOT": str(root / "install"), "KAST_BIN_DIR": str(bin_directory),
        }
        return idea, assets, environment

    def test_cold_stage_rejects_incompatible_release_before_private_installer(self):
        with tempfile.TemporaryDirectory(prefix='kast-stage-old-') as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only', '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn('this release cannot stage a fenced reset', result.stderr)
            self.assertNotIn('profile=', result.stderr)
            self.assertFalse((Path(directory) / 'install').exists())

    def test_cold_stage_passes_explicit_option_to_compatible_private_installer(self):
        with tempfile.TemporaryDirectory(prefix='kast-stage-compatible-') as directory:
            idea, _, environment = self.installer_fixture(directory, reset_capability=True)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only', '--skip-codex-mcp', '--verbose', '--dry-run'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('options=install --stage-only', result.stderr)

    def developer_curl(self, directory: str, assets: Path, pointer: str):
        binary = Path(directory) / "bin/curl"
        binary.write_text(f"""#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

arguments = sys.argv[1:]
url = arguments[-1]
if url == 'https://raw.githubusercontent.com/amichne/kast/developer-latest/latest.txt':
    print({pointer!r})
elif '/developer-v0.0.123/' in url and '--output' in arguments:
    name = url.rsplit('/', 1)[-1]
    shutil.copyfile(Path({str(assets)!r}) / name, arguments[arguments.index('--output') + 1])
else:
    raise SystemExit('unexpected curl request: ' + url)
""")
        binary.chmod(0o755)
        return {"PATH": str(binary.parent) + os.pathsep + TOOL_PATH}

    def upgrade_fixture(self, directory, *, cold_staging=False):
        idea, assets, environment = self.installer_fixture(directory, version='1.2.4')
        root = Path(directory)
        install = root / 'install'
        prior = install / 'installation'
        prior.mkdir(parents=True)
        log = root / 'upgrade.log'
        environment.update(TEST_LOG=str(log), TMPDIR=str(root / 'tmp'))
        (root / 'tmp').mkdir()
        scripts = {
            'share/kast/libexec/kast-management': b'''#!/bin/sh
set -eu
if [ "$1" = --internal-install ]; then
  case "$2" in
    preflight) ;;
    commit) mkdir -p "$HOME/.local/bin"; cp "$0" "$HOME/.local/bin/kast" ;;
    *) exit 93 ;;
  esac
  printf '%s\\n' "$HOME/.local/bin/kast"
elif [ "$1" = connect ]; then
  printf 'connect' >> "$TEST_LOG"
  shift
  for argument in "$@"; do printf ':%s' "$argument" >> "$TEST_LOG"; done
  printf '\\n' >> "$TEST_LOG"
else
  exit 92
fi
''',
            'share/kast/libexec/kast-service': b'''#!/bin/sh
set -eu
[ "$1" = install ] || exit 91
if [ "${FAIL_SERVICE:-0}" != 0 ]; then
  printf '%s\\n' '{"event":"kast_installation_retirement","stage":"OBSERVE_AFTER","outcome":{"type":"DEADLINE_EXCEEDED"}}' >&2
  printf '%s\\n' '{"operation":"installation.install","status":"rejected","reason":"replacement-exit-rejected"}' >&2
  exit "$FAIL_SERVICE"
fi
mkdir -p "$KAST_INSTALL_ROOT/installation/bin" "$KAST_INSTALL_ROOT/installation/share/kast"
printf '#!/bin/sh\\nexit 0\\n' > "$KAST_INSTALL_ROOT/installation/bin/kast-mcp-complete"
chmod +x "$KAST_INSTALL_ROOT/installation/bin/kast-mcp-complete"
cp "$KAST_INSTALL_CONTROL_ROOT/share/kast/codex-mcp-registration.py" "$KAST_INSTALL_ROOT/installation/share/kast/codex-mcp-registration.py"
printf 'service\\n' >> "$TEST_LOG"
printf '%s\\n' '{"event":"kast_installation","stage":"APP_SERVER_ENABLE","outcome":"COMPLETED"}' >&2
if [ "${PENDING_ACTIVATION:-0}" = 1 ]; then
  printf '%s\\n' '{"operation":"installation.install","status":"installed-activation-pending","activation":{"type":"pending","reason":"EXIT_REJECTED","resume":"kast codex"},"semanticVersion":"1.2.4"}'
else
  printf '%s\\n' '{"operation":"installation.install","status":"installed","activation":{"type":"ready"},"semanticVersion":"1.2.4"}'
fi
''',
            'share/kast/host-installation.py': b'''import os,sys
assert sys.argv[1] == '--archive'
assert sys.argv[3] == '--sha256'
assert sys.argv[5] == '--version'
assert sys.argv[7] == '--idea-build'
assert sys.argv[9] == '--plugin-root'
assert len(sys.argv) in (15, 16)
assert sys.argv[11] == '--release-record'
assert sys.argv[13] == '--required-control'
if len(sys.argv) == 16:
    assert sys.argv[15] == '--dry-run'
    sys.exit(0)
with open(os.environ['TEST_LOG'], 'a') as log: log.write('plugin\\n')
code = int(os.environ.get('FAIL_PLUGIN', '0'))
print('{"type":"REJECTED","failure":"OWNERSHIP_UNPROVEN"}' if code else '{"type":"ACTIVATED","restartRequired":true}')
sys.exit(code)
''',
            'share/kast/installation-recovery.py': b'''import os,sys
operation = sys.argv[1]
assert operation in ('activate-plugin', 'seal-upgrade')
with open(os.environ['TEST_LOG'], 'a') as log: log.write(('plugin' if operation == 'activate-plugin' else 'seal') + '\\n')
code = int(os.environ.get('FAIL_PLUGIN' if operation == 'activate-plugin' else 'FAIL_SEAL', '0'))
print('{"status":"RecoveryBlocked","unresolved":["PLUGIN_OWNERSHIP_UNPROVEN"],"recoveryExecutable":""}' if code else
      '{"status":"Active","unresolved":[],"recoveryExecutable":""}' if operation == 'activate-plugin' else
      '{"status":"UpgradeFinalized","unresolved":[],"recoveryExecutable":""}')
sys.exit(code)
''',
            'share/kast/prune-prior-installations.py': b'''import os,sys
assert sys.argv[1:3] == ['--installation', os.path.realpath(os.environ['KAST_INSTALL_ROOT'] + '/installation')]
assert sys.argv[3:] == []
with open(os.environ['TEST_LOG'], 'a') as log: log.write('review\\n')
print('{"status":"retained","removed":[],"retained":["prior"]}')
sys.exit(int(os.environ.get('FAIL_REVIEW', '0')))
''',
            'share/kast/codex-mcp-registration.py': b'''import os,sys
assert sys.argv[1] in ('check', 'install')
with open(os.environ['TEST_LOG'], 'a') as log: log.write('registration:' + sys.argv[1] + '\\n')
''',
        }
        if cold_staging: scripts['share/kast/reset-fence-v1'] = b'1\n'
        control = assets / 'kast-control-v1.2.4-macos-aarch64.tar.gz'
        with tarfile.open(control, 'w:gz') as archive:
            for path, content in scripts.items():
                item = tarfile.TarInfo(path)
                item.mode, item.size = 0o755, len(content)
                archive.addfile(item, io.BytesIO(content))
        control.with_name(control.name + '.sha256').write_text(
            f'{hashlib.sha256(control.read_bytes()).hexdigest()}  {control.name}\n'
        )
        return idea, environment, prior, log

    def test_explicit_mcp_registration_dispatches_to_native_transport_command(self):
        with tempfile.TemporaryDirectory(prefix='kast-installer-codex-mode-') as directory:
            idea, environment, _, log = self.upgrade_fixture(directory)
            codex = Path(directory) / 'bin/codex'
            codex.write_text('#!/bin/sh\nexit 0\n')
            codex.chmod(0o755)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--register-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('connect:codex:mcp', log.read_text().splitlines())

    def test_upgrade_finalizes_only_after_plugin_activation(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['service', 'plugin', 'seal', 'review'], log.read_text().splitlines())
            self.assertTrue(prior.exists())  # Stable payload retirement is proved separately by lifecycle tests.
            self.assertEqual('', result.stdout)
            self.assertNotIn('{', result.stderr)
            self.assertIn('1 prior Kast entries retained', result.stderr)

    def test_verbose_preserves_structured_stage_and_recovery_output(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-verbose-') as directory:
            idea, environment, _, _ = self.upgrade_fixture(directory)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp', '--verbose'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('"activation":{"type":"ready"}', result.stdout)
            self.assertIn('"type":"ACTIVATED"', result.stdout)
            self.assertIn('"status":"UpgradeFinalized"', result.stdout)
            self.assertIn('"status":"retained"', result.stdout)
            self.assertIn('"stage":"APP_SERVER_ENABLE"', result.stderr)

    def test_default_failure_reports_reason_and_child_outcome_without_json(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-rejected-') as directory:
            idea, environment, prior, _ = self.upgrade_fixture(directory)
            environment['FAIL_SERVICE'] = '42'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(42, result.returncode)
            self.assertEqual('', result.stdout)
            self.assertNotIn('{', result.stderr)
            self.assertIn('replacement-exit-rejected', result.stderr)
            self.assertIn('OBSERVE_AFTER', result.stderr)
            self.assertIn('DEADLINE_EXCEEDED', result.stderr)
            self.assertIn('exit 42', result.stderr)
            self.assertTrue(prior.exists())
            self.assertNotIn('installed Kast 1.2.4', result.stderr)

    def test_default_output_preserves_pending_activation_qualification(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-pending-') as directory:
            idea, environment, _, _ = self.upgrade_fixture(directory)
            environment['PENDING_ACTIVATION'] = '1'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn('{', result.stdout + result.stderr)
            self.assertIn('App server activation is pending: EXIT_REJECTED; resume with kast codex', result.stderr)

    def test_verbose_failure_preserves_structured_rejection(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-rejected-verbose-') as directory:
            idea, environment, _, _ = self.upgrade_fixture(directory)
            environment['FAIL_SERVICE'] = '42'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp', '--verbose'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(42, result.returncode)
            self.assertIn('"reason":"replacement-exit-rejected"', result.stderr)
            self.assertIn('"outcome":{"type":"DEADLINE_EXCEEDED"}', result.stderr)

    def test_upgrade_reports_private_installation_activation_to_management_cli(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-report-') as directory:
            idea, environment, _, _ = self.upgrade_fixture(directory)
            report = Path(directory) / 'installation-report.json'
            environment['KAST_MANAGEMENT_REPORT_PATH'] = str(report)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                '{"operation":"installation.install","status":"installed","activation":{"type":"ready"},"semanticVersion":"1.2.4"}\n',
                report.read_text(),
            )
            self.assertEqual('', result.stdout)
            self.assertNotIn('{', result.stderr)

    def test_failed_plugin_activation_preserves_prior_without_pruning(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            environment['FAIL_PLUGIN'] = '17'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['service', 'plugin'], log.read_text().splitlines())
            self.assertTrue(prior.exists())
            self.assertNotIn('{', result.stdout + result.stderr)
            self.assertIn('OWNERSHIP_UNPROVEN', result.stderr)
            self.assertIn('exit 17', result.stderr)

    def test_failed_review_rejects_upgrade_without_claiming_success(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            environment['FAIL_REVIEW'] = '19'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['service', 'plugin', 'seal', 'review'], log.read_text().splitlines())
            self.assertTrue(prior.exists())
            self.assertNotIn('installed Kast 1.2.4', result.stderr)

    def test_failed_recovery_seal_preserves_prior_without_pruning(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            environment['FAIL_SEAL'] = '18'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['service', 'plugin', 'seal'], log.read_text().splitlines())
            self.assertTrue(prior.exists())
            self.assertNotIn('installed Kast 1.2.4', result.stderr)

    def test_cold_control_staging_requires_no_host_release_or_artifact(self):
        with tempfile.TemporaryDirectory(prefix='kast-control-stage-') as directory:
            idea, assets, environment = self.installer_fixture(directory, reset_capability=True)
            for pattern in ('kast-ide-hosted*', 'kast-host-release*', 'host-installation.py*'):
                for asset in assets.glob(pattern): asset.unlink()
            host = Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted'
            host.mkdir(parents=True)
            marker = host / 'host.jar'
            marker.write_bytes(b'P1')
            before = (host.stat().st_dev, host.stat().st_ino, marker.read_bytes())
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only',
                '--force', '--skip-codex-mcp', '--dry-run', '--verbose'], cwd=ROOT, env=environment,
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('options=install --force --stage-only', result.stderr)
            self.assertNotIn('downloading Kast Host', result.stderr)
            self.assertNotIn('IDEA host payload', result.stderr)
            self.assertEqual(before, (host.stat().st_dev, host.stat().st_ino, marker.read_bytes()))

    def test_control_staging_apply_preserves_host_and_skips_legacy_pruning(self):
        with tempfile.TemporaryDirectory(prefix='kast-control-stage-apply-') as directory:
            idea, environment, _, log = self.upgrade_fixture(directory, cold_staging=True)
            assets = Path(environment['KAST_INSTALL_ASSETS_DIRECTORY'])
            for pattern in ('kast-ide-hosted*', 'kast-host-release*', 'host-installation.py*'):
                for asset in assets.glob(pattern): asset.unlink()
            host = Path(environment['HOME']) / 'idea-profile/plugins/kast-ide-hosted'
            host.mkdir(parents=True)
            marker = host / 'host.jar'
            marker.write_bytes(b'P1')
            before = (host.stat().st_dev, host.stat().st_ino, marker.read_bytes())
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only',
                '--force', '--skip-codex-mcp'], cwd=ROOT, env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['service', 'seal'], log.read_text().splitlines())
            self.assertIn('staged Kast Control 1.2.4', result.stderr)
            self.assertNotIn('admitted running IntelliJ', result.stderr)
            self.assertEqual(before, (host.stat().st_dev, host.stat().st_ino, marker.read_bytes()))

    def test_cold_staging_rejects_component_upgrade_combinations_before_effects(self):
        with tempfile.TemporaryDirectory(prefix='kast-control-stage-conflict-') as directory:
            idea, assets, environment = self.installer_fixture(directory, reset_capability=True)
            for asset in assets.iterdir(): asset.unlink()
            for component in ('--control-only', '--host-only'):
                result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only', component],
                    cwd=ROOT, env=environment, capture_output=True, text=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('--stage-only cannot be combined with a component upgrade', result.stderr)
                self.assertFalse(Path(environment['KAST_INSTALL_ROOT']).exists())
                self.assertNotIn('downloading', result.stderr)

    def test_control_only_upgrade_never_requests_host_payload_or_host_effect(self):
        with tempfile.TemporaryDirectory(prefix="kast-control-only-shell-") as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            assets = Path(environment['KAST_INSTALL_ASSETS_DIRECTORY'])
            for plugin in assets.glob('kast-ide-hosted*'): plugin.unlink()
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--control-only'],
                                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['service', 'seal'], log.read_text().splitlines())
            self.assertNotIn('Restart IntelliJ IDEA', result.stderr)

    def test_host_only_install_never_requests_or_installs_control(self):
        with tempfile.TemporaryDirectory(prefix="kast-host-only-shell-") as directory:
            idea, assets, environment = self.installer_fixture(directory)
            for control in assets.glob('kast-control*'): control.unlink()
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--host-only'],
                                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(Path(environment['KAST_INSTALL_ROOT']).exists())
            self.assertTrue((Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted/lib/plugin.jar').exists())

    def test_documented_remote_invocations_use_bash_c(self):
        for document in DOCUMENTS:
            text = document.read_text()
            with self.subTest(document=document.relative_to(ROOT)):
                self.assertNotIn("| bash", text)
                self.assertNotIn("| \\\n  bash", text)
                for line in text.splitlines():
                    if "raw.githubusercontent.com/amichne/kast" in line:
                        command = line.lstrip(" \t")
                        self.assertTrue(command.startswith(CANONICAL_PREFIX), line)

    def test_removed_options_reject_before_installation(self):
        for option in ("--local", "--bin-dir", "--clean-collisions", "--no-interactive"):
            with tempfile.TemporaryDirectory(prefix="kast-retired-option-") as directory:
                result = subprocess.run([str(BASH), str(INSTALLER), option],
                    env={"HOME": directory, "PATH": TOOL_PATH}, text=True, capture_output=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("unknown argument: " + option, result.stderr)
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_retired_environment_rejects_with_removal_instruction(self):
        for key in ("KAST_ENABLE_APP_SERVER", "KAST_APP_SERVER_TOOLS", "KAST_ENABLE_LAUNCHD", "KAST_INSTALL_REFRESH_APP_SERVER"):
            with tempfile.TemporaryDirectory(prefix="kast-retired-setting-") as directory:
                result = subprocess.run([str(BASH), str(INSTALLER)],
                    env={"HOME": directory, "PATH": TOOL_PATH, key: "0"}, text=True, capture_output=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(key + " is retired; remove it", result.stderr)
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_double_dash_delivers_help_to_downloaded_script(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            environment = {
                "HOME": directory,
                "PATH": TOOL_PATH,
                "NO_COLOR": "1",
            }
            result = subprocess.run(
                [str(BASH), "-c", INSTALLER.read_text(), "--", "--help"],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Usage:", result.stdout)
        self.assertNotIn("--clean-collisions", result.stdout)

    def test_unrelated_command_does_not_block_installation_plan(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-foreign-command-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            foreign = Path(directory) / "bin/kast"
            foreign.write_bytes(b"foreign command\n")
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"foreign command\n", foreign.read_bytes())

    def test_explicit_install_root_selects_the_plan_without_changing_the_old_root(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-root-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            selected = Path(directory) / 'selected-kast'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--install-root', str(selected), '--dry-run'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(str(selected), result.stderr)
            self.assertFalse(selected.exists())
            self.assertFalse((Path(directory) / 'install').exists())

    def test_relative_explicit_install_root_is_rejected_before_effects(self):
        result = subprocess.run([str(BASH), str(INSTALLER), '--install-root', 'relative'],
                                env={"HOME": "/tmp", "PATH": TOOL_PATH}, text=True, capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('install root must be an absolute path', result.stderr)

    def test_unsupported_os_is_rejected_before_installation(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-os-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            (Path(directory) / 'bin/uname').write_text(
                '#!/bin/sh\ncase "$1" in -s) echo Linux ;; -m) echo aarch64 ;; *) exit 1 ;; esac\n'
            )
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--dry-run'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn('only macOS is supported', result.stderr)
            self.assertFalse((Path(directory) / 'install').exists())

    def test_install_enables_the_complete_suite_without_prompting(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, input="y\n", text=True, capture_output=True, timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("██╗  ██╗", result.stderr)
        self.assertIn("IntelliJ IDEA 2026.2.1 (build 262.1234)", result.stderr)
        self.assertIn("app server", result.stderr.lower())
        self.assertIn("login", result.stderr)
        self.assertNotIn("Register a user-level Kast MCP server", result.stderr)
        self.assertIn("profile=persistent mode=plan", result.stderr)

    def test_discovers_idea_in_user_applications_without_explicit_home(self):
        with tempfile.TemporaryDirectory(prefix="kast-user-applications-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            applications = Path(directory) / "home/Applications"
            applications.mkdir()
            discovered = applications / "IntelliJ IDEA.app"
            idea.parent.rename(discovered)
            environment["KAST_INSTALL_IDEA_SEARCH_ROOT"] = str(applications)
            result = subprocess.run(
                ["bash", str(INSTALLER), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("IntelliJ IDEA 2026.2.1 (build 262.1234)", result.stderr)
        self.assertIn("idea=" + str((discovered / "Contents").resolve()), result.stderr)

    def test_force_dry_run_enables_suite_and_preserves_state(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea), "--dry-run", "--force"],
                cwd=ROOT, env=environment, stdin=subprocess.DEVNULL, text=True, capture_output=True, timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("Register a user-level Kast MCP server", result.stderr)
        self.assertIn("profile=persistent mode=plan force=1", result.stderr)

    def test_developer_control_only_rejects_before_artifact_or_service_effects(self):
        with tempfile.TemporaryDirectory(prefix='kast-developer-control-rejection-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            assets = Path(environment['KAST_INSTALL_ASSETS_DIRECTORY'])
            for asset in assets.iterdir(): asset.unlink()
            before = (prior.stat().st_dev, prior.stat().st_ino)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--developer-latest', '--control-only', '--idea-home', str(idea)],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn('developer builds require an explicit tested pair', result.stderr)
            self.assertNotIn('downloading', result.stderr)
            self.assertFalse(log.exists())
            self.assertEqual(before, (prior.stat().st_dev, prior.stat().st_ino))

    def test_developer_latest_installs_pinned_public_candidate(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-install-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.123")
            for key in ("KAST_VERSION", "KAST_INSTALL_ASSETS_DIRECTORY", "KAST_INSTALL_ROOT", "KAST_BIN_DIR"):
                environment.pop(key)
            source = "a" * 40
            environment.update(self.developer_curl(directory, assets, f"developer-v0.0.123 0.0.123 {source}"))
            result = subprocess.run(
                ["bash", str(INSTALLER), "--developer-latest", "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"selected developer build 0.0.123 from {source}", result.stderr)
        self.assertIn("profile=persistent mode=plan", result.stderr)

    def test_developer_latest_rejects_ambiguous_pointer(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-install-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.123")
            for key in ("KAST_VERSION", "KAST_INSTALL_ASSETS_DIRECTORY", "KAST_INSTALL_ROOT", "KAST_BIN_DIR"):
                environment.pop(key)
            environment.update(self.developer_curl(directory, assets, "developer-v0.0.122 0.0.123 " + "a" * 40))
            result = subprocess.run(
                ["bash", str(INSTALLER), "--developer-latest", "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("developer-latest pointer is invalid", result.stderr)

    def test_codex_mcp_flags_are_mutually_exclusive_before_installation(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-mcp-choice-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea),
                 "--register-codex-mcp", "--skip-codex-mcp"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("choose only one Codex MCP registration option", result.stderr)
            self.assertFalse((Path(directory) / "install").exists())

    def test_missing_matching_idea_plugin_explains_why_nothing_is_installed(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            idea, assets, environment = self.installer_fixture(directory)
            for asset in assets.glob("*idea-262.zip*"):
                asset.unlink()
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea), "--dry-run", "--force"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not installing Kast", result.stderr)
        self.assertIn("IntelliJ IDEA 2026.2.1 (build 262.1234)", result.stderr)
        self.assertIn("matching IDEA 262 plugin", result.stderr)
        self.assertNotIn("launchd=", result.stderr)

    def test_uninstall_uses_selected_private_lifecycle_control(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-uninstall-") as directory:
            root = Path(directory).resolve()
            install = root / "data/kast"
            selected = install / "installation"
            control = selected / "share/kast/installation-lifecycle.py"
            control.parent.mkdir(parents=True)
            control.write_text("import json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            environment = {
                "HOME": str(root), "PATH": TOOL_PATH, "NO_COLOR": "1",
                "XDG_DATA_HOME": str(root / "data"),
            }
            result = subprocess.run(
                ["bash", str(INSTALLER), "uninstall", "--dry-run", "--verbose"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["--installation", str(selected.resolve()), "remove", "--control-only", "--dry-run", "--json"],
                json.loads(result.stdout),
            )


if __name__ == "__main__":
    unittest.main()
