"""Checkout installer orchestration tests; never touch real installed services."""
import os
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

from acceptance_environment import AcceptanceEnvironment, admitted_tools


SCRIPT = Path(__file__).with_name("install-checkout.sh").resolve()


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
''')
        self.installer = self.root / "installer"
        self.write_script(self.installer, '''#!/bin/bash
set -eu
echo install >> "$TEST_LOG"
bin=${KAST_BIN_DIR:-$HOME/.local/bin}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bin-dir) bin=$2 ;;
    --refresh-app-server) echo refresh-requested >> "$TEST_LOG"; shift; continue ;;
  esac
  shift 2
done
if [[ -n ${KAST_INSTALL_ROOT:-} ]]; then
  mkdir -p "$KAST_INSTALL_ROOT/versions/fixture"
  ln -s versions/fixture "$KAST_INSTALL_ROOT/current"
fi
mkdir -p "$bin"
printf '#!/bin/bash\nprintf "%%s\\n" "$*" >> "$TEST_LOG"\n' > "$bin/kast"
chmod +x "$bin/kast"
''')

    def write_script(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_install(self, *args):
        return subprocess.run(["/bin/bash", str(SCRIPT), str(self.installer), *args],
                              cwd=self.checkout, env=self.env, capture_output=True, text=True)

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
            checked = subprocess.run([shell, "-c", code, "activation-test", str(activation)],
                                     env=self.env, capture_output=True, text=True)
            self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_persistent_selected_bin_and_daemons(self):
        result = self.run_install("persistent", "--bin-dir", str(self.root / "custom bin"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual((self.root / "calls").read_text(),
                         "build\ninstall\nrefresh-requested\n")
        self.assertTrue((self.root / "custom bin/kast").is_file())

    def test_invalid_options_have_no_effects(self):
        for args in (("unknown",), ("session", "--bin-dir", "/somewhere"),
                     ("persistent", "--purge-existing"), ("session", "--idea-home")):
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


@unittest.skipUnless(platform.system() == "Darwin" and platform.machine() == "arm64",
                     "release installer supports macOS Apple silicon")
class ServiceRefreshTest(IsolatedInstallerTest):
    def setUp(self):
        super().setUp()
        self.asset_serial = 0
        # These checks exercise installer defaults, inside the same private HOME.
        for name in ("KAST_RUNTIME_STORE", "KAST_RUNTIME_DIRECTORY", "KAST_CACHE_ROOT", "KAST_ENABLE_APP_SERVER"):
            self.env.pop(name)
        for name in tuple(self.env):
            if name.startswith("XDG_"):
                self.env.pop(name)
        self.idea = self.root / "IDEA.app/Contents"
        for child in ("Resources", "plugins/Kotlin", "jbr/Contents/Home/bin"):
            (self.idea / child).mkdir(parents=True)
        (self.idea / "Resources/build.txt").write_text("IU-262.1")
        (self.idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25"\nOS_ARCH="aarch64"\n')
        java = self.idea / "jbr/Contents/Home/bin/java"
        java.write_text('''#!/bin/bash
echo 'openjdk version "25"' >&2
echo "    java.home = $(cd "$(dirname "$0")/.." && pwd -P)" >&2
''')
        java.chmod(0o755)

    def install(self, version, refresh=False, migration=None):
        self.asset_serial += 1
        assets = self.root / f"{version}-assets-{self.asset_serial}"
        assets.mkdir()
        runtime = assets / f"kast-semantic-runtime-{version}-macos-aarch64.zip"
        with zipfile.ZipFile(runtime, "w") as archive:
            for name in ("kast-indexer", "runtime-libs/test.jar", "private-plugins/kast-indexer/lib/test.jar"):
                archive.writestr(name, "fixture")
        product = assets / "product"
        (product / "bin").mkdir(parents=True)
        metadata = product / "share/kast"
        metadata.mkdir(parents=True)
        manifest = {"fileName": runtime.name,
                    "url": f"https://github.com/amichne/kast/releases/download/v{version}/{runtime.name}",
                    "sha256": "sha256:" + hashlib.sha256(runtime.read_bytes()).hexdigest(),
                    "bytes": runtime.stat().st_size}
        (metadata / "semantic-runtime.json").write_text(json.dumps(manifest, separators=(",", ":")))
        for name in ("operation-registry.json", "wire-schema.json"):
            (metadata / name).write_text("{}")
        # Minimal generated-catalogue fixture; semantic validation remains the staged CLI's job.
        keys = ("KAST_RUNTIME_STORE", "KAST_RUNTIME_DIRECTORY", "KAST_CACHE_ROOT", "KAST_ENABLE_LAUNCHD",
                "KAST_ENABLE_APP_SERVER", "KAST_APP_SERVER_TOOLS", "KAST_INDEXER_MAX_HEAP",
                "KAST_WORKER_RESIDENT_LIMIT", "KAST_WORKER_STARTUP_LIMIT", "KAST_WORKER_AGGREGATE_MIB",
                "KAST_WORKER_NATIVE_MIB", "KAST_WORKER_GRADLE_MIB")
        (metadata / "configuration-schema.json").write_text(json.dumps({"parameters": [
            {"key": key, "mutability": "USER_SETTING", "sources": ["PROCESS_ENVIRONMENT", "SAVED_INSTALLATION"]}
            for key in keys
        ]}))
        (metadata / "installation-lifecycle.py").write_text("import json, sys\nprint(json.dumps(sys.argv[1:]))\n")
        for name in ("kast", "kast-codex"):
            launcher = product / "bin" / name
            launcher.write_text(f'''#!/bin/bash
# fixture asset payload {self.asset_serial}
case "$*" in
  --version) echo 'kast {version} (IntelliJ sidecar)' ;;
  --schema) echo '{{}}' ;;
  --configuration) printf '%s' "${{KAST_CONFIGURATION_FILE-unset}}" ;;
  --configuration-failure) printf '%s' "${{KAST_SAVED_CONFIGURATION_FAILURE-unset}}" ;;
  "config validate --file "*) exit "{self.env.get('TEST_CONFIG_STATUS', '0')}" ;;
  'app-server disable') echo '{version} disable' >> "$TEST_LOG"; exit "${{TEST_STOP_STATUS:-0}}" ;;
  'app-server enable') echo '{version} enable' >> "$TEST_LOG"; exit "${{TEST_ENABLE_STATUS:-0}}" ;;
  *) exit 90 ;;
esac
''')
            launcher.chmod(0o755)
        control = assets / f"kast-control-v{version}-macos-aarch64.tar.gz"
        with tarfile.open(control, "w:gz") as archive:
            archive.add(product / "bin", arcname="bin")
            archive.add(product / "share", arcname="share")
        for path in (runtime, control):
            path.with_name(path.name + ".sha256").write_text(
                f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
        args = ["bash", str(SCRIPT.parent.parent / "install.sh"), "--version", version,
                "--assets-directory", str(assets), "--idea-home", str(self.idea)]
        if refresh:
            args.append("--refresh-app-server")
        if migration is not None:
            args.extend(["--migrate-configuration", str(migration)])
        self.last_arguments = args
        self.last_assets = assets
        return subprocess.run(args, env=self.env, capture_output=True, text=True)

    def selected_root(self):
        return (self.root / ".local/share/kast/current").resolve()

    def test_generated_catalogue_preserves_worker_migration_and_process_precedence(self):
        migration = self.root / "worker-environment"
        migration.write_text("KAST_WORKER_RESIDENT_LIMIT=2\nKAST_WORKER_STARTUP_LIMIT=1\n")
        self.env["KAST_WORKER_RESIDENT_LIMIT"] = "3"
        result = self.install("1.0.0", migration=migration)
        self.assertEqual(result.returncode, 0, result.stderr)
        saved = (self.selected_root() / "config/environment").read_text()
        self.assertIn("KAST_WORKER_RESIDENT_LIMIT=3\n", saved)
        self.assertIn("KAST_WORKER_STARTUP_LIMIT=1\n", saved)

    def test_generated_catalogue_persists_explicit_worker_policy_without_migration(self):
        self.env["KAST_WORKER_RESIDENT_LIMIT"] = "2"
        result = self.install("1.0.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("KAST_WORKER_RESIDENT_LIMIT=2\n", (self.selected_root() / "config/environment").read_text())

    def test_saved_configuration_is_version_local_and_migration_is_explicit(self):
        legacy = self.root / ".config/kast/environment"
        legacy.parent.mkdir(parents=True)
        legacy.write_text("KAST_INDEXER_MAX_HEAP=8g\n")
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        first_root = self.selected_root()
        first_config = first_root / "config/environment"
        self.assertNotIn("KAST_INDEXER_MAX_HEAP", first_config.read_text())
        self.assertIn(str(first_root / "state/cache"), first_config.read_text())
        second = self.install("1.0.1", migration=legacy)
        self.assertEqual(second.returncode, 0, second.stderr)
        second_config = self.selected_root() / "config/environment"
        self.assertIn("KAST_INDEXER_MAX_HEAP=8g", second_config.read_text())
        self.assertEqual(legacy.read_text(), "KAST_INDEXER_MAX_HEAP=8g\n")
        command = self.root / ".local/bin/kast"
        checked = subprocess.run([str(command), "--configuration"], env=self.env, capture_output=True, text=True)
        self.assertEqual(checked.stdout, str(second_config))
        conflict = subprocess.run([str(command), "--configuration-failure"],
            env=self.env | {"KAST_CONFIGURATION_FILE": str(legacy)}, capture_output=True, text=True)
        self.assertEqual(conflict.stdout, "configuration-selector-conflict")

    def test_distinct_payloads_at_one_version_have_distinct_immutable_roots(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        previous = self.selected_root()
        original_launcher = (previous / "bin/kast-complete").read_bytes()
        second = self.install("1.0.0")
        self.assertEqual(second.returncode, 0, second.stderr)
        current = self.selected_root()
        self.assertNotEqual(previous, current)
        self.assertEqual((previous / "bin/kast-complete").read_bytes(), original_launcher)
        manifest = json.loads((current / "installation.json").read_text())
        self.assertEqual(manifest["installationRoot"], str(current))
        self.assertEqual(manifest["semanticVersion"], "1.0.0")
        self.assertEqual(manifest["configuration"], str(current / "config/environment"))
        self.assertTrue(all(anchor["ownership"] == "declared-not-observed" for anchor in manifest["externalAnchors"]))
        control = self.last_assets / "kast-control-v1.0.0-macos-aarch64.tar.gz"
        runtime = self.last_assets / "kast-semantic-runtime-1.0.0-macos-aarch64.zip"
        self.assertEqual(manifest["controlSha256"], "sha256:" + hashlib.sha256(control.read_bytes()).hexdigest())
        self.assertEqual(manifest["runtimeSha256"], "sha256:" + hashlib.sha256(runtime.read_bytes()).hexdigest())

    def test_same_payload_reinstallation_preserves_binary_and_activation_lock_inodes(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        selected = self.selected_root()
        launcher = selected / "bin/kast-complete"
        lock = self.root / ".local/share/kast/activation.lock"
        launcher_identity = launcher.stat().st_ino, launcher.stat().st_mtime_ns
        lock_identity = lock.stat().st_ino
        repeated = subprocess.run(self.last_arguments, env=self.env, capture_output=True, text=True)
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        self.assertEqual(self.selected_root(), selected)
        self.assertEqual((launcher.stat().st_ino, launcher.stat().st_mtime_ns), launcher_identity)
        self.assertEqual(lock.stat().st_ino, lock_identity)

    def test_modified_immutable_launcher_is_rejected_before_it_can_execute(self):
        installed = self.install("1.0.0")
        self.assertEqual(installed.returncode, 0, installed.stderr)
        launcher = self.selected_root() / "bin/kast-complete"
        marker = self.root / "unexpected-execution"
        launcher.write_text(f"#!/bin/bash\ntouch '{marker}'\nexit 0\n")
        repeated = subprocess.run(self.last_arguments, env=self.env, capture_output=True, text=True)
        self.assertNotEqual(repeated.returncode, 0)
        self.assertIn("manifest identity differs", repeated.stderr)
        self.assertFalse(marker.exists())

    def test_installation_dispatch_is_bound_to_the_selected_physical_release(self):
        installed = self.install("1.0.0")
        self.assertEqual(installed.returncode, 0, installed.stderr)
        invocation = subprocess.run([str(self.root / ".local/bin/kast"), "installation", "inspect"],
                                    env=self.env, capture_output=True, text=True)
        self.assertEqual(invocation.returncode, 0, invocation.stderr)
        self.assertEqual(json.loads(invocation.stdout), ["--installation", str(self.selected_root()), "inspect"])

    def test_foreign_activation_lock_is_rejected_without_touching_its_target(self):
        installed = self.install("1.0.0")
        self.assertEqual(installed.returncode, 0, installed.stderr)
        original = self.selected_root()
        lock = self.root / ".local/share/kast/activation.lock"
        foreign = self.root / "foreign"
        foreign.write_text("foreign state")
        lock.unlink()
        lock.symlink_to(foreign)
        rejected = self.install("1.0.1")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertEqual(self.selected_root(), original)
        self.assertEqual(foreign.read_text(), "foreign state")

    def test_external_runtime_state_configuration_is_rejected_before_activation(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        previous = self.selected_root()
        foreign = self.root / "foreign-cache"
        foreign.mkdir()
        sentinel = foreign / "sentinel"
        sentinel.write_text("foreign owner")
        self.env["KAST_CACHE_ROOT"] = str(foreign)
        second = self.install("1.0.1")
        self.assertNotEqual(second.returncode, 0)
        self.assertEqual(self.selected_root(), previous)
        self.assertEqual(sentinel.read_text(), "foreign owner")

    def test_configuration_rejection_never_switches_the_selected_payload(self):
        installed = self.install("1.0.0")
        self.assertEqual(installed.returncode, 0, installed.stderr)
        original = self.selected_root()
        self.env["TEST_CONFIG_STATUS"] = "23"
        rejected = self.install("1.0.1")
        self.assertEqual(rejected.returncode, 23, rejected.stderr)
        self.assertEqual(self.selected_root(), original)
        self.assertEqual((original / "config/environment").exists(), True)

    def test_old_identity_stops_before_new_identity_enables(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.install("1.0.1", refresh=True)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual((self.root / "calls").read_text(), "1.0.0 disable\n1.0.1 enable\n")

    def test_activation_always_retires_the_previous_physical_identity(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.install("1.0.1")
        self.assertEqual(second.returncode, 0, second.stderr)
        calls = self.root / "calls"
        self.assertEqual(calls.read_text() if calls.exists() else "", "1.0.0 disable\n")

    def test_rejected_stop_preserves_active_installation(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.env["TEST_STOP_STATUS"] = "18"
        second = self.install("1.0.1", refresh=False)
        self.assertEqual(second.returncode, 18, second.stderr)
        self.assertTrue(os.readlink(self.root / ".local/share/kast/current").startswith("versions/1.0.0-"))
        self.assertEqual((self.root / "calls").read_text(), "1.0.0 disable\n")

    def test_rejected_enable_reports_installed_but_service_failed(self):
        self.env["TEST_ENABLE_STATUS"] = "20"
        result = self.install("1.0.0", refresh=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Kast is installed, but App Server enablement failed", result.stderr)
        self.assertTrue(os.readlink(self.root / ".local/share/kast/current").startswith("versions/1.0.0-"))


if __name__ == "__main__":
    unittest.main()
