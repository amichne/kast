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


class InstallerEntrypointTest(unittest.TestCase):
    def installer_fixture(self, directory: str, *, plugin_line: str = "262", version: str = "1.2.3"):
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
        info = tarfile.TarInfo("share/kast/libexec/kast-service")
        info.mode = 0o755
        info.size = len(executable)
        with tarfile.open(control, "w:gz") as archive:
            archive.addfile(info, io.BytesIO(executable))
        plugin_name = f"kast-ide-hosted-v{version}-idea-{plugin_line}.zip"
        plugin = assets / plugin_name
        descriptor = (f'<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version>'
                      f'<idea-version since-build="{plugin_line}" until-build="{plugin_line}.*"/></idea-plugin>').encode()
        jar_buffer = io.BytesIO()
        with zipfile.ZipFile(jar_buffer, "w") as jar:
            jar.writestr("META-INF/plugin.xml", descriptor)
        with zipfile.ZipFile(plugin, "w") as archive:
            archive.writestr("kast-ide-hosted/lib/plugin.jar", jar_buffer.getvalue())
        for asset in (control, plugin):
            digest = hashlib.sha256(asset.read_bytes()).hexdigest()
            asset.with_name(asset.name + ".sha256").write_text(f"{digest}  {asset.name}\n")
        environment = {
            "HOME": str(home), "PATH": str(bin_directory) + os.pathsep + TOOL_PATH, "NO_COLOR": "1",
            "KAST_VERSION": version, "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
            "KAST_INSTALL_ROOT": str(root / "install"), "KAST_BIN_DIR": str(bin_directory),
        }
        return idea, assets, environment

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

    def upgrade_fixture(self, directory):
        idea, assets, environment = self.installer_fixture(directory, version='1.2.4')
        root = Path(directory)
        install = root / 'install'
        prior = install / 'versions/1.2.3-prior'
        prior.mkdir(parents=True)
        (install / 'current').symlink_to('versions/' + prior.name)
        log = root / 'upgrade.log'
        environment.update(TEST_LOG=str(log), TMPDIR=str(root / 'tmp'))
        (root / 'tmp').mkdir()
        scripts = {
            'share/kast/libexec/kast-service': b'''#!/bin/sh
set -eu
[ "$1" = install ] || exit 91
mkdir -p "$KAST_INSTALL_ROOT/versions/1.2.4-candidate"
rm "$KAST_INSTALL_ROOT/current"
ln -s versions/1.2.4-candidate "$KAST_INSTALL_ROOT/current"
printf 'service\\n' >> "$TEST_LOG"
''',
            'share/kast/installation-recovery.py': b'''import os,sys
operation = sys.argv[1]
assert operation in ('activate-plugin', 'seal-upgrade')
with open(os.environ['TEST_LOG'], 'a') as log: log.write(('plugin' if operation == 'activate-plugin' else 'seal') + '\\n')
sys.exit(int(os.environ.get('FAIL_PLUGIN' if operation == 'activate-plugin' else 'FAIL_SEAL', '0')))
''',
            'share/kast/installation-lifecycle.py': b'''import os,sys
assert sys.argv[1:3] == ['--installation', os.path.realpath(os.environ['KAST_INSTALL_ROOT'] + '/versions/1.2.4-candidate')]
assert sys.argv[3:] == ['prune', '--json']
with open(os.environ['TEST_LOG'], 'a') as log: log.write('prune\\n')
sys.exit(int(os.environ.get('FAIL_PRUNE', '0')))
''',
        }
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

    def test_upgrade_finalizes_only_after_plugin_activation(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['service', 'plugin', 'seal', 'prune'], log.read_text().splitlines())
            self.assertTrue(prior.exists())  # The scripted prune effect is proved separately by lifecycle tests.

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

    def test_failed_prune_rejects_upgrade_without_claiming_success(self):
        with tempfile.TemporaryDirectory(prefix='kast-upgrade-trap-') as directory:
            idea, environment, prior, log = self.upgrade_fixture(directory)
            environment['FAIL_PRUNE'] = '19'
            result = subprocess.run(
                [str(BASH), str(INSTALLER), '--idea-home', str(idea), '--skip-codex-mcp'],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['service', 'plugin', 'seal', 'prune'], log.read_text().splitlines())
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
        for option in ("--local", "--install-root", "--bin-dir", "--clean-collisions", "--no-interactive"):
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
            root = Path(directory)
            install = root / "data/kast"
            selected = install / "versions/release"
            control = selected / "share/kast/installation-lifecycle.py"
            control.parent.mkdir(parents=True)
            control.write_text("import json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            (install / "current").symlink_to(selected)
            environment = {
                "HOME": str(root), "PATH": TOOL_PATH, "NO_COLOR": "1",
                "XDG_DATA_HOME": str(root / "data"),
            }
            result = subprocess.run(
                ["bash", str(INSTALLER), "uninstall", "--dry-run"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["--installation", str(selected.resolve()), "remove", "--dry-run", "--json"],
                json.loads(result.stdout),
            )


if __name__ == "__main__":
    unittest.main()
