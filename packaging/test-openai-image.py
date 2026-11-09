"""Image lifecycle checks prove receipt admission and activation, not native semantics."""

import importlib.util
import json
import os
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
            self.assertEqual("INCOMPLETE_INSTALLATION", json.loads(result.stderr)["reason"])

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
