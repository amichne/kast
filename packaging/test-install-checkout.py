"""Checkout and public bootstrap tests; never touch real installed services."""

import hashlib
import io
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import unittest
import zipfile

from acceptance_environment import AcceptanceEnvironment, admitted_tools


CHECKOUT_INSTALLER = Path(__file__).with_name("install-checkout.sh").resolve()
PUBLIC_INSTALLER = CHECKOUT_INSTALLER.parent.parent / "install.sh"


class IsolatedInstallerTest(unittest.TestCase):
    def setUp(self):
        self.fixture = AcceptanceEnvironment(admitted_tools())
        self.addCleanup(self.cleanup_fixture)
        self.root = Path(self.fixture.environment["HOME"])
        self.env = dict(self.fixture.environment)
        self.env["TEST_LOG"] = str(self.root / "calls")

    def cleanup_fixture(self):
        result = self._outcome.result
        if not any(test is self for test, _ in result.failures + result.errors):
            self.fixture.mark_passed()
        self.fixture.__exit__(None, None, None)

    @staticmethod
    def write_script(path, content):
        path.write_text(content)
        path.chmod(0o755)


class CheckoutInstallTest(IsolatedInstallerTest):
    def setUp(self):
        super().setUp()
        self.checkout = self.root / "checkout"
        self.checkout.mkdir()
        (self.checkout / "packaging").mkdir()
        (self.checkout / "packaging/install-local.sh").touch()
        (self.checkout / "build.gradle.kts").touch()
        self.write_script(self.checkout / "gradlew", '''#!/bin/bash
set -eu
echo build >> "$TEST_LOG"
for arg in "$@"; do case "$arg" in -Pversion=*) version=${arg#*=} ;; esac; done
mkdir -p build/distributions
touch "build/distributions/kast-control-v$version-macos-aarch64.tar.gz"
touch "build/distributions/kast-semantic-runtime-$version-macos-aarch64.zip"
for arg in "$@"; do
  if [[ $arg == :runtime:hosted:hostedPlugin ]]; then
    mkdir -p runtime/hosted/build/distributions
    touch "runtime/hosted/build/distributions/kast-ide-hosted-v$version-idea-262.zip"
  fi
done
''')
        self.idea = self.root / "IDEA.app/Contents"
        (self.idea / "Resources").mkdir(parents=True)
        (self.idea / "Resources/product-info.json").write_text(json.dumps({"buildNumber": "262.1"}))
        self.env["KAST_INSTALL_IDEA_HOME"] = str(self.idea)
        self.installer = self.root / "installer"
        self.write_script(self.installer, '''#!/bin/bash
set -eu
echo install >> "$TEST_LOG"
plugin="kast-ide-hosted-v$KAST_VERSION-idea-262.zip"
[[ -f "$KAST_INSTALL_ASSETS_DIRECTORY/$plugin" && -f "$KAST_INSTALL_ASSETS_DIRECTORY/$plugin.sha256" ]] || exit 32
(cd "$KAST_INSTALL_ASSETS_DIRECTORY" && shasum -a 256 -c "$plugin.sha256") >&2
bin=${KAST_BIN_DIR:-$HOME/.local/bin}
[[ ${KAST_INSTALL_REFRESH_APP_SERVER:-0} == 1 ]] && echo refresh-requested >> "$TEST_LOG"
[[ -n ${KAST_VERSION:-} && -n ${KAST_RELEASE_BASE_URL:-} && -n ${KAST_INSTALL_ASSETS_DIRECTORY:-} ]] || exit 31
if [[ -n ${KAST_INSTALL_ROOT:-} ]]; then
  mkdir -p "$KAST_INSTALL_ROOT/versions/fixture"
  ln -s versions/fixture "$KAST_INSTALL_ROOT/current"
fi
mkdir -p "$bin"
printf '#!/bin/bash\nprintf "%%s\\n" "$*" >> "$TEST_LOG"\n' > "$bin/kast"
chmod +x "$bin/kast"
''')

    def run_install(self, *args):
        return subprocess.run(
            ["/bin/bash", str(CHECKOUT_INSTALLER), str(self.installer), *args],
            cwd=self.checkout,
            env=self.env,
            capture_output=True,
            text=True,
        )

    def test_session_isolated_and_activation_idempotent(self):
        self.env.update(KAST_RUNTIME_DIRECTORY="/persistent/run", KAST_ENABLE_APP_SERVER="1")
        result = self.run_install("session")
        self.assertEqual(result.returncode, 0, result.stderr)
        activation = Path(result.stdout.strip())
        self.assertTrue(activation.is_file())
        self.assertEqual((self.root / "calls").read_text(), "build\ninstall\n")
        self.assertFalse((self.root / ".local").exists())
        for shell in ("bash", "zsh"):
            if not shutil.which(shell):
                continue
            code = '''source "$1"
first=$PATH
source "$1"
[[ $PATH == "$first" && $KAST_ENABLE_APP_SERVER == 0 && $KAST_ENABLE_LAUNCHD == 0 ]] || exit 2
physical=$(cd "$KAST_INSTALL_ROOT/current" && pwd -P)
[[ $KAST_RUNTIME_DIRECTORY == "$physical/state/run" && $KAST_CACHE_ROOT == "$physical/state/cache" && $KAST_RUNTIME_STORE == "$physical/runtime-payloads" ]] || exit 3
kast 'argument with spaces'
'''
            checked = subprocess.run(
                [shell, "-c", code, "activation-test", str(activation)],
                env=self.env,
                capture_output=True,
                text=True,
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_session_canonicalizes_a_symlinked_temporary_root(self):
        temporary_alias = self.root / "tmp-alias"
        temporary_alias.symlink_to(self.fixture.root / "tmp", target_is_directory=True)
        self.env["TMPDIR"] = str(temporary_alias)
        result = self.run_install("session")
        self.assertEqual(result.returncode, 0, result.stderr)
        activation = Path(result.stdout.strip())
        self.assertEqual(activation, activation.resolve())
        self.assertTrue(activation.is_file())

    def test_persistent_uses_environment_selected_bin_and_refresh(self):
        self.env["KAST_BIN_DIR"] = str(self.root / "custom bin")
        result = self.run_install("persistent")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual((self.root / "calls").read_text(), "build\ninstall\nrefresh-requested\n")
        self.assertTrue((self.root / "custom bin/kast").is_file())

    def test_non_public_checkout_options_have_no_effects(self):
        for args in (
            ("unknown",),
            ("session", "--bin-dir", "/somewhere"),
            ("persistent", "--purge-existing"),
            ("session", "--idea-home"),
        ):
            result = self.run_install(*args)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / "calls").exists())

    def test_build_failure_never_installs_or_emits_activation(self):
        self.write_script(self.checkout / "gradlew", "#!/bin/bash\nexit 17\n")
        result = self.run_install("session")
        self.assertEqual(result.returncode, 17)
        self.assertEqual(result.stdout, "")
        self.assertFalse((self.root / "calls").exists())

    def test_install_failure_never_activates_or_starts_services(self):
        self.write_script(self.installer, "#!/bin/bash\nexit 19\n")
        for mode in ("session", "persistent"):
            result = self.run_install(mode)
            self.assertEqual(result.returncode, 19)
            self.assertEqual(result.stdout, "")


@unittest.skipUnless(
    platform.system() == "Darwin" and platform.machine() == "arm64",
    "release installer supports macOS Apple silicon",
)
class BootstrapInstallTest(IsolatedInstallerTest):
    def setUp(self):
        super().setUp()
        self.idea = self.root / "IDEA.app/Contents"
        for child in ("Resources", "plugins/Kotlin", "jbr/Contents/Home/bin"):
            (self.idea / child).mkdir(parents=True)
        (self.idea / "Resources/build.txt").write_text("IU-262.1")
        (self.idea / "Resources/product-info.json").write_text(json.dumps({
            "buildNumber": "262.1",
            "dataDirectoryName": "IntelliJIdea2026.2",
        }))
        (self.idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25"\nOS_ARCH="aarch64"\n')
        self.write_script(self.idea / "jbr/Contents/Home/bin/java", "#!/bin/bash\nexit 0\n")
        self.version = "1.2.3"
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.runtime = self.assets / f"kast-semantic-runtime-{self.version}-macos-aarch64.zip"
        with zipfile.ZipFile(self.runtime, "w") as archive:
            archive.writestr("kast-indexer", "fixture")
        self.plugin = self.assets / f"kast-ide-hosted-v{self.version}-idea-262.zip"
        self.write_plugin("262.*")

        product = self.root / "product"
        (product / "bin").mkdir(parents=True)
        self.write_script(product / "bin/kast", '''#!/bin/bash
python3 - <<'PYTHON'
import json, os
keys = ["KAST_INSTALL_CONTROL_ROOT", "KAST_INSTALL_CONTROL_SHA256", "KAST_INSTALL_RUNTIME_ARCHIVE",
        "KAST_INSTALL_RUNTIME_SHA256", "KAST_INSTALL_VERSION", "KAST_INSTALL_IDEA_HOME",
        "KAST_INSTALL_JAVA_HOME", "KAST_INSTALL_ROOT", "KAST_BIN_DIR", "KAST_INSTALL_MODE"]
with open(os.environ["TEST_LOG"], "w") as output:
    json.dump({key: os.environ[key] for key in keys}, output)
PYTHON
''')
        self.control = self.assets / f"kast-control-v{self.version}-macos-aarch64.tar.gz"
        with tarfile.open(self.control, "w:gz") as archive:
            archive.add(product / "bin", arcname="bin")
        for asset in (self.control, self.runtime, self.plugin):
            asset.with_name(asset.name + ".sha256").write_text(
                f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {asset.name}\n",
            )
        self.env.update(
            KAST_VERSION=self.version,
            KAST_INSTALL_ASSETS_DIRECTORY=str(self.assets),
            KAST_INSTALL_ROOT=str(self.root / "install"),
            KAST_BIN_DIR=str(self.root / "bin"),
        )

    def write_plugin(self, until_build, since_build="262"):
        descriptor = """<idea-plugin>
  <id>io.github.amichne.kast.ide-hosted</id>
  <version>1.2.3</version>
  <idea-version since-build="{since}" until-build="{until}"/>
</idea-plugin>
""".format(since=since_build, until=until_build)
        plugin_jar = io.BytesIO()
        with zipfile.ZipFile(plugin_jar, "w") as archive:
            archive.writestr("META-INF/plugin.xml", descriptor)
        with zipfile.ZipFile(self.plugin, "w") as archive:
            archive.writestr("kast-ide-hosted/lib/kast-ide-hosted-1.2.3.jar", plugin_jar.getvalue())

    def run_installer(self, *args):
        return subprocess.run(
            ["/bin/bash", str(PUBLIC_INSTALLER), "--idea-home", str(self.idea), *args],
            env=self.env,
            capture_output=True,
            text=True,
        )

    def test_verified_assets_are_delivered_to_staged_typed_installer(self):
        result = self.run_installer("--dry-run")
        self.assertEqual(0, result.returncode, result.stderr)
        contract = json.loads((self.root / "calls").read_text())
        self.assertEqual(self.version, contract["KAST_INSTALL_VERSION"])
        self.assertEqual("plan", contract["KAST_INSTALL_MODE"])
        self.assertEqual(hashlib.sha256(self.control.read_bytes()).hexdigest(), contract["KAST_INSTALL_CONTROL_SHA256"])
        self.assertEqual(hashlib.sha256(self.runtime.read_bytes()).hexdigest(), contract["KAST_INSTALL_RUNTIME_SHA256"])
        self.assertEqual(str(self.idea), contract["KAST_INSTALL_IDEA_HOME"])
        self.assertFalse((self.root / "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins").exists())

    def test_programmatic_plugin_install_uses_verified_release_line_archive(self):
        result = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr)
        installed = self.root / "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted"
        self.assertTrue((installed / "lib/kast-ide-hosted-1.2.3.jar").is_file())
        self.assertIn("restart IntelliJ IDEA", result.stderr)

    def test_other_262_patch_uses_the_same_release_line_archive(self):
        (self.idea / "Resources/product-info.json").write_text(json.dumps({
            "buildNumber": "262.20000.200",
            "dataDirectoryName": "IntelliJIdea2026.2",
        }))
        result = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr)
        installed = self.root / "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-ide-hosted"
        self.assertTrue((installed / "lib/kast-ide-hosted-1.2.3.jar").is_file())

    def test_other_release_lines_reject_the_262_descriptor_before_installation(self):
        for line in ("261", "263"):
            with self.subTest(line=line):
                (self.idea / "Resources/product-info.json").write_text(json.dumps({
                    "buildNumber": line + ".10315.125",
                    "dataDirectoryName": "IntelliJIdea2026.2",
                }))
                wrong_asset = self.assets / f"kast-ide-hosted-v{self.version}-idea-{line}.zip"
                wrong_asset.write_bytes(self.plugin.read_bytes())
                wrong_asset.with_name(wrong_asset.name + ".sha256").write_text(
                    f"{hashlib.sha256(wrong_asset.read_bytes()).hexdigest()}  {wrong_asset.name}\n",
                )
                result = self.run_installer()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("hosted plugin IDEA release line is mismatched", result.stderr)
                self.assertFalse((self.root / "calls").exists())

    def test_programmatic_plugin_update_replaces_only_owned_directory(self):
        plugin_root = self.root / "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins"
        (plugin_root / "kast-ide-hosted").mkdir(parents=True)
        (plugin_root / "kast-ide-hosted/old").write_text("old")
        (plugin_root / "other-plugin").mkdir()
        (plugin_root / "other-plugin/keep").write_text("keep")
        result = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((plugin_root / "kast-ide-hosted/old").exists())
        self.assertEqual("keep", (plugin_root / "other-plugin/keep").read_text())

    def test_plugin_build_mismatch_precedes_staged_installer(self):
        self.write_plugin("263.*", since_build="263")
        self.plugin.with_name(self.plugin.name + ".sha256").write_text(
            f"{hashlib.sha256(self.plugin.read_bytes()).hexdigest()}  {self.plugin.name}\n",
        )
        result = self.run_installer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("hosted plugin IDEA release line is mismatched", result.stderr)
        self.assertFalse((self.root / "calls").exists())
        self.assertFalse((self.root / "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins").exists())

    def test_checksum_rejection_precedes_staged_installer(self):
        self.runtime.with_name(self.runtime.name + ".sha256").write_text(f"{'0' * 64}  {self.runtime.name}\n")
        result = self.run_installer()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "calls").exists())

    def test_control_link_rejection_precedes_staged_installer(self):
        product = self.root / "linked-product"
        (product / "bin").mkdir(parents=True)
        (product / "bin/kast").symlink_to("/usr/bin/true")
        with tarfile.open(self.control, "w:gz") as archive:
            archive.add(product / "bin", arcname="bin")
        self.control.with_name(self.control.name + ".sha256").write_text(
            f"{hashlib.sha256(self.control.read_bytes()).hexdigest()}  {self.control.name}\n",
        )
        result = self.run_installer()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "calls").exists())


if __name__ == "__main__":
    unittest.main()
