"""Image lifecycle checks prove receipt admission and activation, not native semantics."""

import importlib.util
import json
import os
import platform
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "cloud/openai-image.py"
spec = importlib.util.spec_from_file_location("openai_image", SCRIPT)
image = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = image
spec.loader.exec_module(image)


class OpenAIImageTest(unittest.TestCase):
    def test_proxy_admission_rejects_credentials_and_malformed_endpoints(self):
        with tempfile.TemporaryDirectory() as directory:
            trust = Path(directory).resolve() / "cacerts"
            trust.write_bytes(b"public trust fixture")
            for proxy in (
                "http://user:secret@proxy:8080",
                "http://proxy:invalid",
                "https://proxy:8080",
                "http://proxy:8080\nother",
            ):
                result = image.network_properties(
                    {"CLOUD_IMAGE_JAVA_TRUSTSTORE": str(trust), "HTTPS_PROXY": proxy}
                )
                self.assertEqual(image.ImageRejected(image.Failure.INVALID_PROXY), result)
            missing = image.network_properties(
                {"CLOUD_IMAGE_JAVA_TRUSTSTORE": str(trust / "absent")}
            )
            self.assertEqual(image.ImageRejected(image.Failure.TRUSTSTORE_MISSING), missing)

    def test_current_proxy_replaces_only_its_owned_section(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = self.runtime(Path(directory).resolve() / "runtime")
            path = pin.root / "state/home/.gradle/gradle.properties"
            path.parent.mkdir(parents=True)
            path.write_text("org.gradle.parallel=true\n")
            for host in ("first", "second"):
                settings = image.NetworkProperties((("https.proxyHost", host),))
                result = image.configure_network(pin, settings)
                self.assertIsInstance(result, image.ConfiguredNetwork)
                text = path.read_text()
                self.assertIn("org.gradle.parallel=true", text)
                self.assertEqual(1, text.count("systemProp.https.proxyHost="))
                self.assertIn("systemProp.https.proxyHost=" + host, text)
            path.write_text("# BEGIN Kast cloud task network\nunsettled\n")
            before = path.read_bytes()
            rejected = image.configure_network(pin, settings)
            self.assertEqual(image.ImageRejected(image.Failure.NETWORK_STATE_CONFLICT), rejected)
            self.assertEqual(before, path.read_bytes())

    def test_network_configuration_rejects_a_symlinked_private_home(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            pin = self.runtime(root / "runtime")
            (pin.root / "state").mkdir()
            foreign = root / "foreign"
            foreign.mkdir()
            (pin.root / "state/home").symlink_to(foreign, target_is_directory=True)
            rejected = image.configure_network(pin, image.NetworkProperties(()))
            self.assertEqual(image.ImageRejected(image.Failure.NETWORK_STATE_CONFLICT), rejected)
            self.assertEqual([], list(foreign.iterdir()))

    def test_failed_activation_emits_no_shell_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "activate",
                    "--root",
                    str(Path(directory).resolve() / "absent"),
                    "--version",
                    "1.2.3",
                    "--sha256",
                    "a" * 64,
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            expected = (
                "INCOMPLETE_INSTALLATION"
                if (platform.system(), platform.machine()) == ("Linux", "x86_64")
                else "UNSUPPORTED_PLATFORM"
            )
            self.assertEqual(expected, json.loads(result.stderr)["reason"])

    def runtime(self, root):
        from composed_bundle import BundleInstallationReceipt

        root.mkdir()
        (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
        receipt = BundleInstallationReceipt("a" * 64, "b" * 64, "c" * 64, None)
        (root / "bundle-installation.json").write_text(json.dumps(asdict(receipt)))
        (root / "bin").mkdir()
        command = root / "bin/kast"
        command.write_text('#!/bin/sh\n[ "$1" = --version ] || exit 9\nprintf "1.2.3\\n"\n')
        command.chmod(0o755)
        return image.ImagePin(root, "1.2.3", "a" * 64)

    def test_wrong_archive_digest_cannot_create_an_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory).resolve()
            archive = parent / "bundle.tar.gz"
            archive.write_bytes(b"unverified archive")
            pin = image.ImagePin(parent / "runtime", "1.2.3", "a" * 64)
            result = image.prepare(pin, archive)
            self.assertNotIsInstance(result, image.VerifiedImage)
            self.assertFalse(pin.root.exists())

    def test_pin_mismatch_rejects_before_the_existing_command_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = self.runtime(Path(directory).resolve() / "runtime")
            sentinel = pin.root / "executed"
            (pin.root / "bin/kast").write_text(f"#!/bin/sh\ntouch '{sentinel}'\nexit 0\n")
            mismatch = image.ImagePin(pin.root, pin.version, "b" * 64)
            self.assertEqual(
                image.ImageRejected(image.Failure.PIN_MISMATCH), image.verify(mismatch)
            )
            self.assertFalse(sentinel.exists())

    def test_repeat_setup_preserves_files_and_task_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = self.runtime(Path(directory).resolve() / "runtime with spaces")
            before = {
                p.relative_to(pin.root): p.read_bytes() for p in pin.root.rglob("*") if p.is_file()
            }
            environment = os.environ.copy()
            result = image.prepare(pin, pin.root / "absent-archive")
            self.assertIsInstance(result, image.VerifiedImage)
            self.assertEqual("NOT_RUN", result.semanticReadiness)
            self.assertEqual(environment, dict(os.environ))
            self.assertEqual(
                before,
                {
                    p.relative_to(pin.root): p.read_bytes()
                    for p in pin.root.rglob("*")
                    if p.is_file()
                },
            )
            shell = subprocess.run(
                [
                    "/bin/sh",
                    "-c",
                    image.activation(result)
                    + 'printf "%s\\n%s\\n%s\\n%s\\n" "$PATH" "$HOME" "$JAVA_HOME" "$HTTPS_PROXY"',
                ],
                env={
                    "PATH": "/usr/bin:/bin",
                    "HOME": "/caller",
                    "JAVA_HOME": "/caller-jdk",
                    "HTTPS_PROXY": "http://task-proxy:8080",
                },
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(
                [
                    str(pin.root / "bin") + ":/usr/bin:/bin",
                    "/caller",
                    "/caller-jdk",
                    "http://task-proxy:8080",
                ],
                shell.stdout.splitlines(),
            )

    def test_incomplete_or_different_version_is_not_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = self.runtime(Path(directory).resolve() / "runtime")
            mismatch = image.ImagePin(pin.root, "9.9.9", pin.sha256)
            self.assertEqual(
                image.ImageRejected(image.Failure.VERSION_FAILED), image.verify(mismatch)
            )
            (pin.root / "bundle-installation.json").unlink()
            self.assertEqual(
                image.ImageRejected(image.Failure.INCOMPLETE_INSTALLATION),
                image.prepare(pin, pin.root / "absent"),
            )

    def test_invalid_pin_or_symlink_root_is_rejected(self):
        self.assertEqual(
            image.ImageRejected(image.Failure.INVALID_PIN),
            image.admit_pin(Path("/unused"), "latest", "a" * 64),
        )
        self.assertEqual(
            image.ImageRejected(image.Failure.INVALID_PIN),
            image.admit_pin(Path("/unused"), "1.2.3", "unknown"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            link = root / "link"
            link.symlink_to(root, target_is_directory=True)
            self.assertEqual(
                image.ImageRejected(image.Failure.UNSAFE_ROOT),
                image.admit_pin(link, "1.2.3", "a" * 64),
            )


if __name__ == "__main__":
    unittest.main()
