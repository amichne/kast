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


SCRIPT = Path(__file__).with_name("install-checkout.sh").resolve()


class CheckoutInstallTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kast checkout ' ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.checkout = self.root / "checkout"
        self.checkout.mkdir()
        (self.checkout / "packaging").mkdir()
        (self.checkout / "packaging/install-local.sh").touch()
        (self.checkout / "build.gradle.kts").touch()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("KAST_", "XDG_"))}
        self.env.update(HOME=str(self.root), TMPDIR=str(self.root),
                        TEST_LOG=str(self.root / "calls"))
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
[[ $KAST_RUNTIME_DIRECTORY == "$KAST_SESSION_ROOT/run" ]] || exit 3
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
class ServiceRefreshTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-refresh-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("KAST_", "XDG_"))}
        self.env.update(HOME=str(self.root), TMPDIR=str(self.root),
                        TEST_LOG=str(self.root / "calls"))
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

    def install(self, version, refresh=False):
        assets = self.root / version
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
        for name in ("kast", "kast-codex"):
            launcher = product / "bin" / name
            launcher.write_text(f'''#!/bin/bash
case "$*" in
  --version) echo 'kast {version} (IntelliJ sidecar)' ;;
  --schema) echo '{{}}' ;;
  --heap) printf '%s' "${{KAST_INDEXER_MAX_HEAP-unset}}" ;;
  'app-server stop') echo '{version} stop' >> "$TEST_LOG"; exit "${{TEST_STOP_STATUS:-0}}" ;;
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
        return subprocess.run(args, env=self.env, capture_output=True, text=True)

    def test_heap_saved_literal_and_environment_precedence_survive_upgrade(self):
        self.env["KAST_INDEXER_MAX_HEAP"] = "8g"
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        del self.env["KAST_INDEXER_MAX_HEAP"]
        second = self.install("1.0.1")
        self.assertEqual(second.returncode, 0, second.stderr)
        command = self.root / ".local/bin/kast"
        for overrides, expected in (({}, "8g"), ({"KAST_INDEXER_MAX_HEAP": "4g"}, "4g"),
                                    ({"KAST_INDEXER_MAX_HEAP": ""}, "")):
            result = subprocess.run([str(command), "--heap"], env=self.env | overrides,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, expected)

    def test_old_identity_stops_before_new_identity_enables(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.install("1.0.1", refresh=True)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual((self.root / "calls").read_text(), "1.0.0 stop\n1.0.1 enable\n")

    def test_rejected_stop_preserves_active_installation(self):
        first = self.install("1.0.0")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.env["TEST_STOP_STATUS"] = "18"
        second = self.install("1.0.1", refresh=True)
        self.assertEqual(second.returncode, 18, second.stderr)
        self.assertEqual(os.readlink(self.root / ".local/share/kast/current"), "versions/1.0.0")
        self.assertEqual((self.root / "calls").read_text(), "1.0.0 stop\n")

    def test_rejected_enable_reports_installed_but_service_failed(self):
        self.env["TEST_ENABLE_STATUS"] = "20"
        result = self.install("1.0.0", refresh=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Kast is installed, but App Server enablement failed", result.stderr)
        self.assertEqual(os.readlink(self.root / ".local/share/kast/current"), "versions/1.0.0")


if __name__ == "__main__":
    unittest.main()
