from dataclasses import asdict, dataclass
#!/usr/bin/env python3
"""Public installer invocation contract; no network or machine state changes."""

from pathlib import Path
import hashlib
import errno
import io
import json
import os
import shlex
import subprocess
import shutil
import sys
import tarfile
import tempfile
import threading
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
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v3'
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


@dataclass(frozen=True)
class CompletionRejectedFixture:
    type: str = 'REJECTED'
    operation: str = 'REINSTALL'
    stage: str = 'ACTIVATION'
    failure: str = 'FENCE_REJECTED'
    component: str = 'kast-lifecycle'


class InstallerEntrypointTest(unittest.TestCase):
    def installer_fixture(self, directory: str, *, plugin_line: str = "262", version: str = "1.2.3", reset_capability: bool = False,
                          completion_capability: bytes | None = b"1\n"):
        root = Path(directory).resolve()
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
        executable += b"printf 'plugin-root=%s\\n' \"$KAST_INSTALL_IDEA_PLUGIN_ROOT\" >&2\n"
        if reset_capability:
            executable += b"printf 'options=%s\\n' \"$*\" >&2\n"
        info = tarfile.TarInfo("share/kast/libexec/kast-service")
        info.mode = 0o755
        info.size = len(executable)
        with tarfile.open(control, "w:gz") as archive:
            archive.addfile(info, io.BytesIO(executable))
            if completion_capability is not None:
                capability = tarfile.TarInfo("share/kast/install-completion-v1")
                capability.mode, capability.size = 0o644, len(completion_capability)
                archive.addfile(capability, io.BytesIO(completion_capability))
            if reset_capability:
                capability = tarfile.TarInfo("share/kast/reset-fence-v1")
                capability.mode, capability.size = 0o644, 2
                archive.addfile(capability, io.BytesIO(b"1\n"))
            management = b'''#!/bin/sh
set -eu
[ "$1" = --internal-install ] || exit 92
case "$2" in
  preflight) printf '%s\\n' "$HOME/.local/bin/kast" ;;
  commit|commit-active)
    printf 'completion=%s\\n' "$2" >&2
    printf '%s\\n' "$HOME/.local/bin/kast" ;;
  *) exit 93 ;;
esac
'''
            if completion_capability is None:
                management = management.replace(b"commit|commit-active)", b"commit)")
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
            "KAST_INSTALL_ROOT": str(home / ".local/share/kast"), "KAST_BIN_DIR": str(home / ".local/bin"),
        }
        return idea, assets, environment

    def test_control_stage_receives_the_exact_host_install_target(self):
        with tempfile.TemporaryDirectory(prefix='kast-host-target-relay-') as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--dry-run', '--verbose'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            expected = Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins'
            self.assertIn('plugin-root=' + str(expected), result.stderr)

    def test_legacy_control_completion_uses_only_its_supported_commit_protocol(self):
        with tempfile.TemporaryDirectory(prefix='kast-completion-legacy-') as directory:
            idea, environment, _, log = self.upgrade_fixture(directory, completion_capability=False)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('completion=commit\n', result.stderr)
            self.assertNotIn('completion=commit-active', result.stderr)
            self.assertEqual(['service', 'plugin', 'seal', 'review'], log.read_text().splitlines())

    def test_invalid_completion_capability_rejects_before_private_installation(self):
        for marker in (b'2\n', b'1', b'1\n\n', b'x' * 4097):
            with self.subTest(marker=marker[:8]), tempfile.TemporaryDirectory(prefix='kast-completion-invalid-') as directory:
                idea, _, environment = self.installer_fixture(directory, completion_capability=marker)
                result = subprocess.run(
                    [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn('installation completion capability is incompatible', result.stderr)
                self.assertNotIn('profile=', result.stderr)
                self.assertNotIn('completion=', result.stderr)
                self.assertFalse((Path(environment['KAST_INSTALL_ROOT']) / 'installation').exists())
                self.assertFalse((Path(directory) / 'home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted').exists())

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

    def developer_curl(self, directory: str, assets: Path, pointer: str | None, *, version: str = '0.0.123'):
        binary = Path(directory) / "bin/curl"
        binary.write_text(f"""#!/usr/bin/env python3
from pathlib import Path
import shutil
import shlex
import sys

arguments = sys.argv[1:]
with Path({str(Path(directory) / 'curl.calls')!r}).open('a') as log:
    log.write(shlex.join(arguments) + '\\n')
url = arguments[-1]
if url == 'https://raw.githubusercontent.com/amichne/kast/main/install.sh':
    sys.stdout.write(Path({str(INSTALLER)!r}).read_text())
elif url == 'https://api.github.com/repos/amichne/kast/contents/latest.txt?ref=developer-latest':
    if 'Accept: application/vnd.github.raw+json' not in arguments or 'Cache-Control: no-cache' not in arguments:
        raise SystemExit('pointer request must select raw content and revalidate caches')
    if {pointer is None!r}:
        raise SystemExit(22)
    print({pointer!r})
elif {f'/developer-v{version}/'!r} in url and '--output' in arguments:
    name = url.rsplit('/', 1)[-1]
    shutil.copyfile(Path({str(assets)!r}) / name, arguments[arguments.index('--output') + 1])
else:
    raise SystemExit('unexpected curl request: ' + url)
""")
        binary.chmod(0o755)
        return {"PATH": str(binary.parent) + os.pathsep + TOOL_PATH}

    def upgrade_fixture(self, directory, *, cold_staging=False, version='1.2.4', completion_capability=True):
        idea, assets, environment = self.installer_fixture(
            directory, version=version, completion_capability=b'1\n' if completion_capability else None,
        )
        root = Path(directory).resolve()
        install = Path(environment['KAST_INSTALL_ROOT'])
        prior = install / 'installation'
        prior.mkdir(parents=True)
        log = root / 'upgrade.log'
        environment.update(TEST_LOG=str(log), TMPDIR=str(root / 'tmp'))
        (root / 'tmp').mkdir()
        scripts = {
            'share/kast/ide-host.json': json.dumps(asdict(ControlMetadataFixture(version))).encode(),
            'share/kast/libexec/kast-management': b'''#!/bin/sh
set -eu
if [ "$1" = --internal-install ]; then
  case "$2" in
    preflight) ;;
    commit|commit-active)
      printf 'completion=%s\\n' "$2" >&2
      mkdir -p "$HOME/.local/bin"; cp "$0" "$HOME/.local/bin/kast"
      if [ "${FAIL_COMPLETION:-0}" != 0 ]; then
        printf '%s\\n' '__COMPLETION_REJECTED__' >&2
        printf '%s\\n' "$HOME/.local/bin/kast"
        exit "$FAIL_COMPLETION"
      fi ;;
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
        scripts['share/kast/libexec/kast-management'] = scripts['share/kast/libexec/kast-management'].replace(
            b'__COMPLETION_REJECTED__', json.dumps(asdict(CompletionRejectedFixture())).encode(),
        )
        if completion_capability:
            scripts['share/kast/install-completion-v1'] = b'1\n'
        else:
            scripts['share/kast/libexec/kast-management'] = scripts['share/kast/libexec/kast-management'].replace(
                b'commit|commit-active)', b'commit)',
            )
        if cold_staging: scripts['share/kast/reset-fence-v1'] = b'1\n'
        control = assets / f'kast-control-v{version}-macos-aarch64.tar.gz'
        with tarfile.open(control, 'w:gz') as archive:
            for path, content in scripts.items():
                if path == 'share/kast/libexec/kast-service': content = content.replace(b'1.2.4', version.encode())
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
            self.assertIn('completion=commit-active', result.stderr)
            self.assertTrue(prior.exists())  # Stable payload retirement is proved separately by lifecycle tests.
            self.assertEqual('', result.stdout)
            self.assertNotIn('{', result.stderr)
            self.assertIn('1 prior Kast entries retained', result.stderr)

    def test_completion_rejection_preserves_native_path_and_reports_finite_activation_failure(self):
        with tempfile.TemporaryDirectory(prefix='kast-completion-rejected-') as directory:
            idea, environment, _, log = self.upgrade_fixture(directory)
            environment['FAIL_COMPLETION'] = '42'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('', result.stdout)
            self.assertNotIn('{', result.stderr)
            self.assertIn('Native installation completion failed (exit 42)', result.stderr)
            self.assertIn('ACTIVATION', result.stderr)
            self.assertIn('FENCE_REJECTED', result.stderr)
            self.assertTrue((Path(environment['HOME']) / '.local/bin/kast').is_file())
            self.assertEqual(['service', 'plugin'], log.read_text().splitlines())

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
            registration = Path(environment['HOME']) / '.codex/config.toml'
            registration.parent.mkdir()
            registration.write_text('protected registration\n')
            registration_before = (registration.stat().st_ino, registration.read_bytes())
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), '--stage-only',
                '--force'], cwd=ROOT, env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['service', 'seal'], log.read_text().splitlines())
            self.assertIn('completion=commit\n', result.stderr)
            self.assertNotIn('completion=commit-active', result.stderr)
            self.assertIn('staged Kast Control 1.2.4', result.stderr)
            self.assertNotIn('admitted running IntelliJ', result.stderr)
            self.assertNotIn('Register a user-level Kast MCP server', result.stderr)
            self.assertEqual(before, (host.stat().st_dev, host.stat().st_ino, marker.read_bytes()))
            self.assertEqual(registration_before, (registration.stat().st_ino, registration.read_bytes()))

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

    def test_component_skip_registration_is_independent_of_argument_order(self):
        for component in ('--control-only', '--host-only'):
            for options in ((component, '--skip-codex-mcp'), ('--skip-codex-mcp', component)):
                with self.subTest(options=options), tempfile.TemporaryDirectory(prefix='kast-component-skip-') as directory:
                    if component == '--control-only':
                        idea, environment, _, log = self.upgrade_fixture(directory)
                        assets = Path(environment['KAST_INSTALL_ASSETS_DIRECTORY'])
                        for asset in assets.iterdir():
                            if not asset.name.startswith('kast-control-'): asset.unlink()
                    else:
                        idea, assets, environment = self.installer_fixture(directory)
                        for asset in assets.glob('kast-control*'): asset.unlink()
                    registration = Path(environment['HOME']) / '.codex/config.toml'
                    registration.parent.mkdir()
                    registration.write_text('protected registration\n')
                    before = (registration.stat().st_ino, registration.read_bytes())
                    result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), *options],
                        cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(before, (registration.stat().st_ino, registration.read_bytes()))
                    if component == '--control-only':
                        self.assertEqual(['service', 'seal'], log.read_text().splitlines())
                        self.assertNotIn('Restart IntelliJ IDEA', result.stderr)
                    else:
                        self.assertFalse(Path(environment['KAST_INSTALL_ROOT']).exists())
                        self.assertTrue((Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted/lib/plugin.jar').exists())

    def test_checkout_selects_only_its_component_and_reaches_real_public_installer(self):
        version = '0.20261002.30000'
        for component in ('control', 'host', 'pair'):
            with self.subTest(component=component), tempfile.TemporaryDirectory(prefix='kast-checkout-component-') as directory:
                idea, environment, _, effect_log = self.upgrade_fixture(directory, version=version)
                root, assets = Path(directory).resolve(), Path(environment['KAST_INSTALL_ASSETS_DIRECTORY'])
                checkout = root / 'checkout'
                (checkout / 'packaging').mkdir(parents=True)
                (checkout / 'build.gradle.kts').touch()
                (checkout / 'packaging/install-local.sh').touch()
                adapter = checkout / 'packaging/install-checkout.sh'
                shutil.copyfile(ROOT / 'packaging/install-checkout.sh', adapter)
                shutil.copyfile(ROOT / 'packaging/host-installation.py', checkout / 'packaging/host-installation.py')
                build_log, forwarding = root / 'build.log', root / 'forwarding.log'
                control_name = f'kast-control-v{version}-macos-aarch64.tar.gz'
                host_name = f'kast-ide-hosted-v{version}-idea-262.zip'
                record_name = f'kast-host-release-v{version}.json'
                expected_build = ['--console=plain', f'-PhostedIdeaHome={idea}']
                copies = []
                if component != 'host':
                    expected_build.append(f'-PcontrolVersion={version}')
                    copies.append((control_name, f'build/distributions/{control_name}'))
                if component != 'control':
                    expected_build.append(f'-PhostedPluginVersion={version}')
                    copies.extend(((host_name, f'runtime/hosted/build/distributions/{host_name}'),
                                   (record_name, f'build/generated/host-release/{record_name}'),
                                   (record_name + '.sha256', f'build/generated/host-release/{record_name}.sha256')))
                if component != 'host': expected_build.append('assembleKastControlDist')
                if component != 'control': expected_build.append('generateHostReleaseRecord')
                gradle = checkout / 'gradlew'
                gradle.write_text(f'''#!{sys.executable}
import json, shutil, sys
from pathlib import Path
log = Path({str(build_log)!r})
assert not log.exists(), 'excess build call'
assert sys.argv[1:] == {expected_build!r}, 'unexpected build arguments'
log.write_text(json.dumps(sys.argv[1:]))
for source, destination in {copies!r}:
    target = Path(destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path({str(assets)!r}) / source, target)
''')
                gradle.chmod(0o755)
                date = root / 'bin/date'
                date.write_text('#!/bin/sh\ncase "$*" in "-u +%Y%m%d") echo 20261002 ;; "-u +%H%M%S") echo 030000 ;; *) exit 97 ;; esac\n')
                date.chmod(0o755)
                public_entry = checkout / 'install.sh'
                public_entry.write_text(f'''#!/bin/sh
test ! -e "$TEST_FORWARDING" || exit 98
printf 'host-selector:%s:%s\\n' "${{KAST_HOST_VERSION+x}}" "${{KAST_HOST_VERSION-}}" > "$TEST_FORWARDING"
printf 'argument:%s\\n' "$@" >> "$TEST_FORWARDING"
for asset in "$KAST_INSTALL_ASSETS_DIRECTORY"/*; do printf 'asset:%s\\n' "${{asset##*/}}" >> "$TEST_FORWARDING"; done
exec {shlex.quote(str(BASH))} {shlex.quote(str(INSTALLER))} "$@"
''')
                public_entry.chmod(0o755)
                options = [] if component == 'pair' else [f'--{component}-only']
                options += ['--skip-codex-mcp', '--idea-home', str(idea)]
                environment.update(KAST_HOST_VERSION='99.0.0', TEST_FORWARDING=str(forwarding),
                                   KAST_MANAGEMENT_REPORT_PATH=str(root / 'receipt.json'))
                protected_host = Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted'
                if component == 'control':
                    protected_host.mkdir(parents=True)
                    marker = protected_host / 'host.jar'
                    marker.write_bytes(b'P1')
                    host_before = (protected_host.stat().st_ino, marker.read_bytes())
                result = subprocess.run([str(BASH), str(adapter), 'persistent', *options],
                    cwd=checkout, env=environment, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected_build, json.loads(build_log.read_text()))
                if component == 'control':
                    self.assertFalse((checkout / 'runtime/hosted/build/distributions').exists())
                    self.assertFalse((checkout / 'build/generated/host-release').exists())
                forwarded = forwarding.read_text().splitlines()
                self.assertEqual('host-selector:x:' + ('' if component == 'control' else version), forwarded[0])
                self.assertEqual(['argument:' + option for option in options], [line for line in forwarded if line.startswith('argument:')])
                primary = ([control_name] if component != 'host' else [])
                if component != 'control': primary += [host_name, record_name, 'host-installation.py']
                expected_assets = primary + [name + '.sha256' for name in primary]
                self.assertEqual(sorted(expected_assets), sorted(line.removeprefix('asset:') for line in forwarded if line.startswith('asset:')))
                if component == 'host':
                    self.assertFalse(effect_log.exists())
                    self.assertTrue((Path(environment['HOME']) / 'Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted/lib/plugin.jar').exists())
                else:
                    self.assertEqual(['service', 'seal'] if component == 'control' else ['service', 'plugin', 'seal', 'review'], effect_log.read_text().splitlines())
                    self.assertEqual(version, json.loads((root / 'receipt.json').read_text())['semanticVersion'])
                    with tarfile.open(assets / control_name) as archive:
                        self.assertEqual(version, json.load(archive.extractfile('share/kast/ide-host.json'))['productVersion'])
                    if component == 'control':
                        self.assertEqual(host_before, (protected_host.stat().st_ino, marker.read_bytes()))

    def test_component_explicit_registration_rejects_in_either_order_before_effects(self):
        for component in ('--control-only', '--host-only', '--stage-only'):
            for options in ((component, '--register-codex-mcp'), ('--register-codex-mcp', component)):
                with self.subTest(options=options), tempfile.TemporaryDirectory(prefix='kast-component-register-') as directory:
                    idea, assets, environment = self.installer_fixture(directory)
                    for asset in assets.iterdir(): asset.unlink()
                    effect_log = Path(directory) / 'registration.log'
                    codex = Path(directory) / 'bin/codex'
                    codex.write_text('#!/bin/sh\nprintf "registration\\n" >> "$TEST_LOG"\n')
                    codex.chmod(0o755)
                    environment['TEST_LOG'] = str(effect_log)
                    result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea), *options],
                        cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('--register-codex-mcp requires a paired installation', result.stderr)
                    self.assertNotIn('downloading', result.stderr)
                    self.assertFalse(effect_log.exists())
                    self.assertFalse(Path(environment['KAST_INSTALL_ROOT']).exists())
                    self.assertFalse((Path(environment['HOME']) / 'Library').exists())

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
        self.assertIn('--register-codex-mcp requires a paired installation', result.stdout)
        self.assertIn('and --stage-only skip registration without prompting', result.stdout)
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

    def test_alternate_install_root_is_rejected_before_effects(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-root-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            selected = Path(directory) / 'selected-kast'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--install-root', str(selected), '--dry-run'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("sole per-user installation", result.stderr)
            self.assertNotIn("downloading", result.stderr)
            self.assertFalse(selected.exists())
            self.assertFalse((Path(directory) / 'install').exists())

    def test_relative_explicit_install_root_is_rejected_before_effects(self):
        result = subprocess.run([str(BASH), str(INSTALLER), '--install-root', 'relative'],
                                env={"HOME": "/tmp", "PATH": TOOL_PATH}, text=True, capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('sole per-user installation', result.stderr)

    def test_alternate_environment_roots_bins_and_session_reject_before_effects(self):
        with tempfile.TemporaryDirectory(prefix='kast-sole-install-') as directory:
            idea, assets, environment = self.installer_fixture(directory)
            for asset in assets.iterdir(): asset.unlink()
            for key, value in (('KAST_INSTALL_ROOT', str(Path(directory) / 'alternate')),
                               ('KAST_BIN_DIR', str(Path(directory) / 'alternate-bin')),
                               ('KAST_INSTALL_PROFILE', 'session')):
                with self.subTest(key=key):
                    result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea)],
                        cwd=ROOT, env=dict(environment, **{key: value}), text=True, capture_output=True, timeout=10)
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn('downloading', result.stderr)
                    self.assertFalse(Path(environment['KAST_INSTALL_ROOT']).exists())

    def test_xdg_data_home_cannot_select_another_installation(self):
        with tempfile.TemporaryDirectory(prefix='kast-sole-xdg-') as directory:
            idea, _, environment = self.installer_fixture(directory)
            selected = Path(environment['KAST_INSTALL_ROOT'])
            ignored = Path(directory) / 'alternate-data'
            environment['XDG_DATA_HOME'] = str(ignored)
            result = subprocess.run([str(BASH), str(INSTALLER), '--idea-home', str(idea),
                '--install-root', str(selected), '--dry-run'], cwd=ROOT, env=environment,
                text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(str(selected), result.stderr)
            self.assertFalse(ignored.exists())

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
                [str(BASH), "-c", INSTALLER.read_text(), "--", "--developer-latest", "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            transfers = [shlex.split(line) for line in (Path(directory) / 'curl.calls').read_text().splitlines()][1:]
            self.assertEqual(6, len(transfers))
            for arguments in transfers:
                self.assertIn('--silent', arguments)
                self.assertNotIn('--progress-bar', arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"selected developer build 0.0.123 from {source}", result.stderr)
        self.assertIn("profile=persistent mode=plan", result.stderr)

    def test_developer_downloads_show_progress_on_a_terminal(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-progress-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.123")
            for key in ("KAST_VERSION", "KAST_INSTALL_ASSETS_DIRECTORY", "KAST_INSTALL_ROOT", "KAST_BIN_DIR"):
                environment.pop(key)
            environment.update(self.developer_curl(directory, assets, "developer-v0.0.123 0.0.123 " + "a" * 40))
            master, slave = os.openpty()
            chunks, failures = [], []
            def drain_terminal():
                try:
                    while data := os.read(master, 4096):
                        chunks.append(data)
                except OSError as error:
                    if error.errno != errno.EIO:
                        failures.append(error)
            reader = threading.Thread(target=drain_terminal)
            reader.start()
            try:
                result = subprocess.run(
                    [str(BASH), "-c", INSTALLER.read_text(), "--", "--developer-latest", "--idea-home", str(idea), "--dry-run"],
                    cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE, stderr=slave, timeout=10,
                )
            finally:
                os.close(slave)
                reader.join(timeout=10)
                os.close(master)
            self.assertFalse(reader.is_alive())
            self.assertEqual([], failures)
            output = b''.join(chunks).decode()
            self.assertEqual(0, result.returncode, output)
            transfers = [shlex.split(line) for line in (Path(directory) / 'curl.calls').read_text().splitlines()][1:]
            self.assertEqual(6, len(transfers))
            for arguments in transfers:
                self.assertIn('--progress-bar', arguments)
                self.assertNotIn('--silent', arguments)

    def test_public_curl_developer_selection_is_independent_of_local_checkout(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-stale-checkout-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.3336")
            for key in ("KAST_VERSION", "KAST_INSTALL_ASSETS_DIRECTORY", "KAST_INSTALL_ROOT", "KAST_BIN_DIR"):
                environment.pop(key)
            checkout = Path(directory) / 'old-checkout'
            checkout.mkdir()
            (checkout / 'gradle.properties').write_text('version=0.0.3331\n')
            (checkout / 'install.sh').write_text('exit 91\n')
            source = 'b' * 40
            environment.update(self.developer_curl(directory, assets,
                f"developer-v0.0.3336 0.0.3336 {source}", version='0.0.3336'))
            command = ('/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"'
                       ' -- --developer-latest --dry-run --skip-codex-mcp --idea-home ' + shlex.quote(str(idea)))
            result = subprocess.run([str(BASH), '-c', command], cwd=checkout, env=environment,
                text=True, capture_output=True, timeout=10)
            calls = [shlex.split(line) for line in (Path(directory) / 'curl.calls').read_text().splitlines()]
            self.assertEqual(8, len(calls))
            self.assertEqual('https://raw.githubusercontent.com/amichne/kast/main/install.sh', calls[0][-1])
            self.assertEqual('https://api.github.com/repos/amichne/kast/contents/latest.txt?ref=developer-latest', calls[1][-1])
            self.assertIn('Accept: application/vnd.github.raw+json', calls[1])
            self.assertIn('Cache-Control: no-cache', calls[1])
            for arguments in calls[2:]:
                self.assertIn('/developer-v0.0.3336/', arguments[-1])
            self.assertEqual('version=0.0.3331\n', (checkout / 'gradle.properties').read_text())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f'selected developer build 0.0.3336 from {source}', result.stderr)
        self.assertNotIn('0.0.3331', result.stderr)

    def test_developer_latest_rejects_inherited_version_before_network_or_installation(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-inherited-version-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.3331")
            environment.pop('KAST_INSTALL_ASSETS_DIRECTORY')
            environment.update(self.developer_curl(directory, assets, 'developer-v0.0.3336 0.0.3336 ' + 'a' * 40))
            result = subprocess.run([str(BASH), '-c', INSTALLER.read_text(), '--', '--developer-latest'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10)
            self.assertFalse((Path(directory) / 'curl.calls').exists())
            self.assertFalse((Path(environment['HOME']) / '.local/share/kast').exists())
        self.assertNotEqual(0, result.returncode)
        self.assertIn('--developer-latest cannot be combined with --version or KAST_VERSION', result.stderr)

    def test_developer_latest_unavailable_pointer_stops_before_asset_downloads(self):
        with tempfile.TemporaryDirectory(prefix="kast-developer-pointer-unavailable-") as directory:
            idea, assets, environment = self.installer_fixture(directory, version="0.0.123")
            for key in ("KAST_VERSION", "KAST_INSTALL_ASSETS_DIRECTORY", "KAST_INSTALL_ROOT", "KAST_BIN_DIR"):
                environment.pop(key)
            environment.update(self.developer_curl(directory, assets, None))
            result = subprocess.run(
                [str(BASH), "-c", INSTALLER.read_text(), "--", "--developer-latest", "--idea-home", str(idea), "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(1, len((Path(directory) / 'curl.calls').read_text().splitlines()))
            self.assertFalse((Path(environment['HOME']) / '.local/share/kast').exists())
            self.assertFalse((Path(environment['HOME']) / 'Library').exists())
        self.assertEqual(1, result.returncode)
        self.assertIn("developer-latest pointer is unavailable", result.stderr)
        self.assertNotIn("version must be", result.stderr)
        self.assertNotIn("downloading", result.stderr)

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
            self.assertEqual(1, len((Path(directory) / 'curl.calls').read_text().splitlines()))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("developer-latest pointer is invalid", result.stderr)
        self.assertNotIn("version must be", result.stderr)
        self.assertNotIn("downloading", result.stderr)

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
            install = root / ".local/share/kast"
            selected = install / "installation"
            control = selected / "share/kast/installation-lifecycle.py"
            control.parent.mkdir(parents=True)
            control.write_text("import json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            environment = {
                "HOME": str(root), "PATH": TOOL_PATH, "NO_COLOR": "1",
                "XDG_DATA_HOME": str(root / "ignored-data"),
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
