"""Coordinator effects use private files and explicit raw process observations."""

import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import override
from unittest.mock import patch

import generate_bootstrap as generator
import managed_installation as install


class BootstrapImportTest(unittest.TestCase):
    def test_first_bootstrap_import_preserves_the_exact_payload_inventory(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast-bootstrap-import-") as temporary:
            root = Path(temporary).resolve()
            bundle = root / "payload"
            bundle.mkdir()
            names = (
                "managed_installation.py",
                "managed_release.py",
                "managed_product.py",
                "managed_product_installation.py",
                "managed_product_removal.py",
                "immutable_store.py",
                "product_removal_recovery.py",
                "product_state_removal.py",
                "private_interpreter.py",
                "bootstrap_entry.py",
                "composed_bundle.py",
                "composition_platform.py",
                "managed_headless.py",
                "runtime_archive.py",
                "runtime_base.py",
                "managed_update_contract.py",
                "managed_update_journal.py",
                "installation-recovery.py",
                "installation-lifecycle.py",
                "host_directory_exchange.py",
            )
            for name in names:
                source = generator.source_file(name)
                _ = shutil.copyfile(source, bundle / name)
            before = {
                name: hashlib.sha256((bundle / name).read_bytes()).hexdigest() for name in names
            }
            environment = dict(os.environ)
            _ = environment.pop("PYTHONDONTWRITEBYTECODE", None)
            _ = environment.pop("PYTHONPYCACHEPREFIX", None)
            result = subprocess.run(
                [sys.executable, str(bundle / "managed_installation.py"), "--help"],
                cwd=root,
                env=environment,
                capture_output=True,
                timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            environment["HOME"] = str(root / "home")
            imported = subprocess.run(
                [
                    sys.executable,
                    str(bundle / "managed_installation.py"),
                    "--recover-only",
                    "--dry-run",
                    "--json",
                ],
                cwd=bundle,
                env=environment,
                capture_output=True,
                timeout=10,
            )
            # No installation exists, but the real script must import the product path
            # before rejecting recovery, without creating bytecode in its payload.
            self.assertEqual(1, imported.returncode, imported.stderr)
            self.assertNotIn(b"Traceback", imported.stderr)
            removed = subprocess.run(
                [
                    sys.executable,
                    str(bundle / "bootstrap_entry.py"),
                    "--remove-product",
                    "--install-root",
                    str(root / "absent"),
                    "--uninstall-journal",
                    str(root / "absent.json"),
                ],
                cwd=root,
                env=environment,
                capture_output=True,
                timeout=10,
            )
            self.assertEqual(1, removed.returncode, removed.stderr)
            self.assertNotIn(b"Traceback", removed.stderr)
            self.assertEqual("REMOVAL_REJECTED", json.loads(removed.stdout)["status"])
            after = {
                path.relative_to(bundle).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in bundle.rglob("*")
                if path.is_file()
            }
            self.assertEqual(before, after)


class UntrustedReleaseSource:
    """Scripted transfer observation; production admission owns the rejection."""

    def __init__(self) -> None:
        self.reads: list[str] = []

    def read(self, url: str, limit: int) -> bytes | install.release.TransferRejection:
        self.reads.append(url.rsplit("/", 1)[-1])
        return install.release.TransferRejected(
            install.release.TransferFailure.TLS_CERTIFICATE_UNTRUSTED,
            install.release.TrustRoots.KEYCHAIN,
        )

    def download(
        self, url: str, destination: Path, limit: int
    ) -> install.release.DownloadedFile | install.release.TransferRejection:
        raise AssertionError("unexpected download")


class TransferRejectionPresentationTest(unittest.TestCase):
    def run_install(self, arguments: list[str]) -> tuple[int, str, str, list[str]]:
        source = UntrustedReleaseSource()
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            tempfile.TemporaryDirectory(prefix="kast-transfer-") as temporary,
            patch.dict(os.environ, {"HOME": str(Path(temporary).resolve())}),
            patch.object(install.platform, "system", return_value="Darwin"),
            patch.object(install.platform, "machine", return_value="arm64"),
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            code = install.main(arguments, source)
        return code, stdout.getvalue(), stderr.getvalue(), source.reads

    def test_json_rejection_carries_the_tls_cause_and_trust_outcome(self) -> None:
        code, stdout, _, reads = self.run_install(["--version", "0.52.0", "--json"])
        self.assertNotEqual(0, code)
        self.assertEqual(["release-mapping.json"], reads)
        self.assertEqual(
            {
                "reason": "BINARY_UNAVAILABLE",
                "stage": "RECORDS",
                "requested": "0.52.0",
                "platform": "macos-aarch64",
                "selectable": [],
                "transfer": {
                    "cause": "TLS_CERTIFICATE_UNTRUSTED",
                    "trust": "KEYCHAIN",
                    "httpStatus": None,
                },
                "type": "REJECTED",
            },
            json.loads(stdout),
        )

    def test_human_rejection_names_the_certificate_bundle_remedy(self) -> None:
        code, stdout, stderr, _ = self.run_install(["--channel", "preview"])
        self.assertNotEqual(0, code)
        self.assertEqual("REJECTED\n", stdout)
        self.assertIn('"cause":"TLS_CERTIFICATE_UNTRUSTED"', stderr)
        self.assertIn("TLS certificate verification failed", stderr)
        self.assertIn("trust roots: KEYCHAIN", stderr)
        self.assertIn("SSL_CERT_FILE", stderr)
        self.assertNotIn("https://", stderr)

    def test_http_status_rejection_keeps_its_code_in_the_message(self) -> None:
        failure = install.release.TransferRejected(
            install.release.TransferFailure.HTTP_STATUS, install.release.TrustRoots.KEYCHAIN, 403
        )
        message = install.transfer_message(failure)
        self.assertIn("HTTP status 403", message)
        self.assertNotIn("SSL_CERT_FILE", message)

    def test_configured_certificate_bundle_is_named_as_the_bundle_to_extend(self) -> None:
        message = install.transfer_message(
            install.release.TransferRejected(
                install.release.TransferFailure.TLS_CERTIFICATE_UNTRUSTED,
                install.release.TrustRoots.ENVIRONMENT,
            )
        )
        self.assertIn("trust roots: ENVIRONMENT", message)
        self.assertIn("configured SSL_CERT_FILE or SSL_CERT_DIR must include", message)


class RecordReadTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="kast-record-read-")
        self.addCleanup(temporary.cleanup)
        self.record = Path(temporary.name).resolve() / "record.json"
        _ = self.record.write_text("{}")
        self.record.chmod(0o600)

    def test_a_foreign_owner_does_not_weaken_managed_record_ownership(self) -> None:
        with patch.object(install.os, "getuid", return_value=os.getuid() + 1):
            with self.assertRaises(OSError):
                _ = install.read_record(self.record)


class ManagementLockTest(unittest.TestCase):
    """management.lock must use the record-lock family that Java's FileChannel.tryLock takes.

    Linux keeps flock and fcntl record locks independent, so a flock-based management_lock
    neither sees a record-lock holder nor appears to one; the holder test fails there without
    the fix. macOS lets the two families conflict, so the same test passes there with either
    primitive and proves only the rejection mapping. The F_GETLK probe distinguishes the
    families on both platforms: Linux reports no lock for flock, and macOS reports an flock
    holder as pid -1. Only a record lock reports the holding process.
    """

    HOLDER = (
        "import fcntl,sys; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX); "
        "print('locked',flush=True); sys.stdin.read()"
    )
    # Reports the owner of a conflicting write lock as "<type> <pid>", or "UNLOCKED".
    PROBE = (
        "import fcntl,os,struct,sys\n"
        "fd=os.open(sys.argv[1],os.O_RDWR)\n"
        "if sys.platform=='darwin':\n"
        "    shape='qqihh'; request=struct.pack(shape,0,0,0,fcntl.F_WRLCK,0)\n"
        "    _,_,pid,kind,_=struct.unpack(shape,fcntl.fcntl(fd,fcntl.F_GETLK,request))\n"
        "else:\n"
        "    shape='hhqqi4x'; request=struct.pack(shape,fcntl.F_WRLCK,0,0,0,0)\n"
        "    kind,_,_,_,pid=struct.unpack(shape,fcntl.fcntl(fd,fcntl.F_GETLK,request))\n"
        "print('UNLOCKED' if kind==fcntl.F_UNLCK else f'{kind} {pid}')\n"
    )
    JAVA_HOLDER = (
        "import java.nio.channels.FileChannel;\n"
        "import java.nio.file.*;\n"
        "public class Holder { public static void main(String[] a) throws Exception {\n"
        "  try (FileChannel c = FileChannel.open(Path.of(a[0]), StandardOpenOption.CREATE,"
        " StandardOpenOption.WRITE); var l = c.tryLock()) {\n"
        '    System.out.println(l == null ? "busy" : "locked"); System.out.flush();'
        " System.in.read(); } } }\n"
    )

    @override
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="kast-management-lock-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve() / "kast"
        self.root.mkdir(mode=0o700)
        self.lock = self.root / "management.lock"
        _ = self.lock.write_bytes(b"")
        self.lock.chmod(0o600)

    def held_by(self, arguments: list[str]) -> subprocess.Popen[str]:
        child = subprocess.Popen(
            arguments, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True
        )
        self.addCleanup(child.wait, 30)
        self.addCleanup(child.communicate, "", 30)
        assert child.stdout is not None
        self.assertEqual("locked\n", child.stdout.readline())
        return child

    def assert_rejected_by_holder(self) -> None:
        with self.assertRaises(BlockingIOError) as raised:
            with install.management_lock(self.root):
                self.fail("management_lock admitted a second owner")
        self.assertEqual(
            install.contract.Rejected(
                install.contract.Stage.PREPARATION,
                install.contract.Failure.FILESYSTEM_REJECTED,
                "EAGAIN",
            ),
            install.rejection(raised.exception),
        )

    def test_a_record_lock_holder_in_another_process_rejects_management(self) -> None:
        _ = self.held_by([sys.executable, "-c", self.HOLDER, str(self.lock)])
        self.assert_rejected_by_holder()

    def test_the_held_lock_is_a_record_lock_owned_by_this_process(self) -> None:
        with install.management_lock(self.root):
            probe = subprocess.run(
                [sys.executable, "-c", self.PROBE, str(self.lock)],
                capture_output=True,
                text=True,
                timeout=10,
                check=True,
            )
        self.assertEqual(f"{install.fcntl.F_WRLCK} {os.getpid()}", probe.stdout.strip())

    @unittest.skipIf(shutil.which("java") is None, "no java on PATH for the FileLock holder")
    def test_a_java_file_lock_holder_rejects_management(self) -> None:
        source = self.root.parent / "Holder.java"
        _ = source.write_text(self.JAVA_HOLDER)
        java = shutil.which("java")
        assert java is not None
        _ = self.held_by([java, str(source), str(self.lock)])
        self.assert_rejected_by_holder()


if __name__ == "__main__":
    _ = unittest.main()
