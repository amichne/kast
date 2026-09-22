#!/usr/bin/env python3
"""Public installer invocation contract; no network or machine state changes."""

from pathlib import Path
import hashlib
import io
import json
import os
import shlex
import subprocess
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
BASH = Path('/bin/bash').resolve(strict=True)


class InstallerEntrypointTest(unittest.TestCase):
    def installer_fixture(self, directory: str, *, plugin_line: str = "262"):
        root = Path(directory)
        home = root / "home"
        idea = root / "IntelliJ IDEA.app/Contents"
        assets = root / "assets"
        bin_directory = root / "bin"
        for path in (home, assets, bin_directory, idea / "plugins/Kotlin", idea / "jbr/Contents/Home/bin"):
            path.mkdir(parents=True, exist_ok=True)
        (idea / "Resources").mkdir()
        (idea / "Resources/build.txt").write_text("IU-262.1234\n")
        (idea / "Resources/product-info.json").write_text(json.dumps({
            "buildNumber": "262.1234", "dataDirectoryName": "IntelliJIdea2026.2", "version": "2026.2.1",
        }))
        (idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25.0.1"\nOS_ARCH="aarch64"\n')
        (idea / "jbr/Contents/Home/bin/java").write_text("")
        (idea / "jbr/Contents/Home/bin/java").chmod(0o755)

        version = "1.2.3"
        control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
        control = assets / control_name
        executable = b"#!/bin/sh\nprintf 'profile=%s mode=%s force=%s\\n' \"$KAST_INSTALL_PROFILE\" \"$KAST_INSTALL_MODE\" \"$KAST_INSTALL_FORCE\" >&2\n"
        info = tarfile.TarInfo("bin/kast")
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
            "HOME": str(home), "PATH": "/usr/bin:/bin", "NO_COLOR": "1",
            "KAST_VERSION": version, "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
            "KAST_INSTALL_ROOT": str(root / "install"), "KAST_BIN_DIR": str(bin_directory),
        }
        return idea, assets, environment

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
                    env={"HOME": directory, "PATH": "/usr/bin:/bin"}, text=True, capture_output=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("unknown argument: " + option, result.stderr)
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_retired_environment_rejects_with_removal_instruction(self):
        for key in ("KAST_ENABLE_APP_SERVER", "KAST_APP_SERVER_TOOLS", "KAST_ENABLE_LAUNCHD", "KAST_INSTALL_REFRESH_APP_SERVER"):
            with tempfile.TemporaryDirectory(prefix="kast-retired-setting-") as directory:
                result = subprocess.run([str(BASH), str(INSTALLER)],
                    env={"HOME": directory, "PATH": "/usr/bin:/bin", key: "0"}, text=True, capture_output=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(key + " is retired; remove it", result.stderr)
                self.assertEqual([], list(Path(directory).iterdir()))

    def test_double_dash_delivers_help_to_downloaded_script(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            environment = {
                "HOME": directory,
                "PATH": "/usr/bin:/bin",
                "NO_COLOR": "1",
            }
            result = subprocess.run(
                ["/bin/bash", "-c", INSTALLER.read_text(), "--", "--help"],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Usage:", result.stdout)
        self.assertNotIn("--clean-collisions", result.stdout)

    def test_noninteractive_collision_fails_closed_at_selected_command_directory(self):
        # Budget: one private tree and Bash built-ins only on this regular-file branch.
        # PATH sentinels record forbidden effects; this is not an OS security sandbox.
        with tempfile.TemporaryDirectory(prefix="kast-installer-collision-") as directory:
            root = Path(directory).resolve()
            commands = root / ".local/bin"
            commands.mkdir(parents=True)
            collision = commands / "kast"
            foreign_bytes = b"foreign command\n"
            collision.write_bytes(foreign_bytes)
            tools = root / "path"
            tools.mkdir()
            transcript = root / "forbidden-commands"
            for name in ('curl', 'java', 'launchctl', 'open', 'shasum', 'awk', 'sed', 'find',
                         'python3', 'cp', 'mktemp', 'uname', 'readlink', 'rm'):
                sentinel = tools / name
                sentinel.write_text(f'#!{BASH}\n'
                    f'printf "%s\\n" {shlex.quote(name)} >> {shlex.quote(str(transcript))}\nexit 97\n')
                sentinel.chmod(0o700)
            environment = {"HOME": str(root), "PATH": str(tools), "NO_COLOR": "1"}
            result = subprocess.run(
                [
                    str(BASH),
                    "-c",
                    INSTALLER.read_text(),
                    "--",
                    "--version",
                    "1.2.3",
                ],
                cwd=root,
                env=environment,
                stdin=subprocess.DEVNULL,
                text=True,
                capture_output=True,
                timeout=10,
            )
            self.assertFalse(transcript.exists(), transcript.read_text() if transcript.exists() else "")
            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn(str(collision), result.stderr)
            self.assertIn("--force", result.stderr)
            self.assertIn("command collisions require --force", result.stderr)
            self.assertEqual(foreign_bytes, collision.read_bytes())
            self.assertFalse((root / "custom data").exists())
            self.assertEqual({root / ".local", tools}, set(root.iterdir()))
            self.assertEqual([collision], list(commands.iterdir()))

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
        self.assertNotIn("[y/N]", result.stderr)
        self.assertIn("profile=persistent mode=plan", result.stderr)

    def test_force_dry_run_enables_suite_and_preserves_state(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            idea, _, environment = self.installer_fixture(directory)
            result = subprocess.run(
                ["bash", str(INSTALLER), "--idea-home", str(idea), "--dry-run", "--force"],
                cwd=ROOT, env=environment, stdin=subprocess.DEVNULL, text=True, capture_output=True, timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("[y/N]", result.stderr)
        self.assertIn("profile=persistent mode=plan force=1", result.stderr)

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


if __name__ == "__main__":
    unittest.main()
