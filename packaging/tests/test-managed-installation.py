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
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, override
from unittest.mock import patch

import generate_bootstrap as generator
import managed_installation as install
import managed_update_contract as contract
import managed_update_journal as journal


@dataclass(frozen=True)
class LegacyRegistrationFixture:
    connection: Literal["PI"]
    destination: str
    payloadSha256: str


@dataclass(frozen=True)
class ReceiptFixture:
    schemaVersion: int
    installationRoot: str
    executable: str
    executableSha256: str
    channel: str
    registrations: tuple[install.Registration | LegacyRegistrationFixture, ...]
    updatePolicy: journal.TrackStable
    installedBuild: journal.BuildIdentity
    guidance: tuple[install.Guidance, ...] = ()


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
                "product_mcp_removal.py",
                "mcp_environment.py",
                "private_interpreter.py",
                "bootstrap_entry.py",
                "composed_bundle.py",
                "composition_platform.py",
                "managed_headless.py",
                "runtime_archive.py",
                "runtime_base.py",
                "managed_update_contract.py",
                "managed_update_journal.py",
                "host-installation.py",
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


class ManagementRegistrationTest(unittest.TestCase):
    def receipt(self, registration: install.Registration | LegacyRegistrationFixture) -> bytes:
        return install.encoded(
            ReceiptFixture(
                2,
                "/fixture/kast",
                "/fixture/bin/kast",
                "a" * 64,
                "STABLE",
                (registration,),
                journal.TrackStable(),
                journal.BuildIdentity("v1.0.0", "1.0.0", "b" * 40, "c" * 40),
            )
        )

    def test_legacy_registration_keeps_the_historical_mcp_composition(self):
        raw = self.receipt(
            LegacyRegistrationFixture("PI", "/fixture/pi/extensions/kast.ts", "d" * 64)
        )
        parsed = install.parse_management(raw)
        self.assertEqual("CliWithMcp", parsed.registrations[0].composition)

    def test_both_pi_compositions_survive_installer_receipt_admission_and_serialization(self):
        registrations = (
            install.Registration(
                "PI", "/fixture/pi/extensions/kast.ts", "d" * 64, "/fixture/pi", "CliOnly"
            ),
            install.Registration(
                "PI", "/fixture/pi/extensions/kast.ts", "d" * 64, "/fixture/pi", "CliWithMcp"
            ),
        )
        for registration in registrations:
            with self.subTest(composition=registration.composition):
                parsed = install.parse_management(self.receipt(registration))
                self.assertEqual((registration,), parsed.registrations)
                encoded = json.loads(install.encoded(parsed))
                self.assertEqual(
                    registration.composition, encoded["registrations"][0]["composition"]
                )

    def test_unknown_pi_composition_rejects_before_installer_effects(self):
        registration = install.Registration(
            "PI", "/fixture/pi/extensions/kast.ts", "d" * 64, "/fixture/pi", "CliOnly"
        )
        raw = self.receipt(registration).replace(b'"CliOnly"', b'"UnknownComposition"')
        with self.assertRaises(install.StageRejected) as rejected:
            _ = install.parse_management(raw)
        self.assertEqual(contract.Stage.RE_ADMISSION, rejected.exception.failure.stage)
        self.assertEqual(contract.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure.reason)


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


if __name__ == "__main__":
    _ = unittest.main()
