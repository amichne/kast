#!/usr/bin/env python3
"""The published marketplace comes only from the bytes a stable release mapping proves."""

from __future__ import annotations

import hashlib
import io
import json
import stat
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".github/scripts/release"))
import managed_release as product
import publish_marketplace as publisher

VERSION = "0.0.7"
PUBLIC = "1.2.3"
RECIPE = "compositions/mcp/plugins/kast/skills/kast/scripts/kast-recipe"
CODEX = "compositions/mcp/.agents/plugins/marketplace.json"
CLAUDE = "compositions/mcp/.claude-plugin/marketplace.json"


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def marketplace_zip(entries: dict[str, tuple[bytes, int]]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, (content, mode) in entries.items():
            info = zipfile.ZipInfo(name)
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, content)
    return buffer.getvalue()


def default_entries() -> dict[str, tuple[bytes, int]]:
    return {
        CODEX: (b'{"name":"kast"}\n', 0o644),
        CLAUDE: (b'{"name":"kast"}\n', 0o644),
        RECIPE: (b"#!/bin/sh\n", 0o755),
        "plugins/kast/skills/kast/scripts/kast-recipe": (b"other composition\n", 0o755),
    }


def setup_bundle(archive: bytes, sidecar: str | None = None) -> bytes:
    name = f"setup-kast/assets/kast-marketplace-v{VERSION}.zip"
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as bundle:
        recorded = (sidecar or f"{digest(archive)}  kast-marketplace-v{VERSION}.zip\n").encode()
        for member, content in ((name, archive), (f"{name}.sha256", recorded)):
            info = tarfile.TarInfo(member)
            info.size = len(content)
            bundle.addfile(info, io.BytesIO(content))
    return buffer.getvalue()


def target(platform: str, bundle: bytes) -> dict[str, object]:
    runtime = "b" * 64
    return {
        "target": platform,
        "inputs": {
            "javaReleaseSha256": "c" * 64,
            "dependencyLocksSha256": "c" * 64,
            "compositionRecipeSha256": "c" * 64,
            "donorSha256": "c" * 64,
        },
        "bundle": {
            "name": f"kast-composed-setup-v{VERSION}-{platform}.tar.gz",
            "sha256": digest(bundle),
            "bytes": len(bundle),
        },
        "runtime": {
            "name": f"kast-runtime-v{VERSION}-{platform}.zip",
            "sha256": runtime,
            "bytes": 1,
        },
        "qualification": {
            "receipt": {"name": f"qualification-{platform}.json", "sha256": "d" * 64, "bytes": 1},
            "producer": {
                "runId": 1,
                "runAttempt": 1,
                "workflow": ".github/workflows/composed-linux.yml",
            },
            "bundleSha256": digest(bundle),
            "runtimeSha256": runtime,
            "controlSha256": "e" * 64,
            "kind": "NATIVE",
            "outcome": "COMPLETE",
            "composition": {
                "baseSha256": "f" * 64,
                "nativeRuntimeSha256": "f" * 64,
                "nativeBundleSha256": "f" * 64,
                "runtimeSha256": runtime,
            },
        },
        "runtimeBase": {
            "name": f"kast-runtime-base-{platform}-{'f' * 64}.zip",
            "sha256": "f" * 64,
            "bytes": 1,
        },
    }


class ScriptedRelease:
    """Serves exactly the files of one release tag and records each request."""

    def __init__(self, files: dict[str, bytes]) -> None:
        self.files = files
        self.requests: list[str] = []

    def name(self, url: str) -> str | None:
        prefix = f"{product.RELEASE_URL}/v{PUBLIC}/"
        return url.removeprefix(prefix) if url.startswith(prefix) else None

    def read(self, url: str, limit: int) -> bytes | product.TransferFailure:
        self.requests.append(url)
        raw = self.files.get(self.name(url) or "")
        if raw is None:
            return product.TransferFailure.NOT_FOUND
        return raw if len(raw) <= limit else product.TransferFailure.LIMIT_EXCEEDED

    def download(
        self, url: str, destination: Path, limit: int
    ) -> product.DownloadedFile | product.TransferFailure:
        raw = self.read(url, limit)
        if isinstance(raw, product.TransferFailure):
            return raw
        _ = destination.write_bytes(raw)
        return product.DownloadedFile(destination, digest(raw), len(raw))


def release(bundle: bytes, served: bytes | None = None) -> ScriptedRelease:
    record = {
        "schemaVersion": 3,
        "repository": "amichne/kast",
        "sourceRevision": "1" * 40,
        "sourceTree": "2" * 40,
        "version": VERSION,
        "producer": {"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/ci.yml"},
        "targets": [target("linux-x86_64", bundle), target("macos-aarch64", b"mac")],
    }
    raw = json.dumps(record).encode()
    mapping = {"schemaVersion": 1, "publicVersion": PUBLIC, "buildRecordSha256": digest(raw)}
    return ScriptedRelease(
        {
            "release-mapping.json": json.dumps(mapping).encode(),
            "build-record.json": raw,
            f"kast-composed-setup-v{VERSION}-linux-x86_64.tar.gz": served or bundle,
        }
    )


class AdmissionTest(unittest.TestCase):
    def admit(self, source: ScriptedRelease) -> publisher.Marketplace | publisher.Rejected:
        with tempfile.TemporaryDirectory() as directory:
            return publisher.admit(PUBLIC, source, Path(directory))

    def test_mapped_release_yields_the_mcp_composition_with_its_identity(self) -> None:
        archive = marketplace_zip(default_entries())
        source = release(setup_bundle(archive))
        admitted = self.admit(source)
        assert isinstance(admitted, publisher.Marketplace)
        self.assertEqual(
            [(item.path.as_posix(), item.executable) for item in admitted.files],
            [
                (".agents/plugins/marketplace.json", False),
                (".claude-plugin/marketplace.json", False),
                ("plugins/kast/skills/kast/scripts/kast-recipe", True),
            ],
        )
        self.assertEqual(
            (admitted.identity.publicVersion, admitted.identity.buildVersion),
            (PUBLIC, VERSION),
        )
        self.assertEqual(admitted.identity.marketplaceSha256, digest(archive))
        # Only the Linux bundle is fetched; the other target's archives stay untouched.
        self.assertEqual(
            [url.rsplit("/", 1)[-1] for url in source.requests],
            [
                "release-mapping.json",
                "build-record.json",
                f"kast-composed-setup-v{VERSION}-linux-x86_64.tar.gz",
            ],
        )

    def test_bundle_bytes_other_than_the_recorded_digest_are_rejected(self) -> None:
        bundle = setup_bundle(marketplace_zip(default_entries()))
        served = bundle[:-1] + bytes([bundle[-1] ^ 1])
        admitted = self.admit(release(bundle, served))
        self.assertEqual(
            admitted,
            publisher.Rejected(
                publisher.Failure.BUNDLE_MISMATCH,
                f"kast-composed-setup-v{VERSION}-linux-x86_64.tar.gz",
            ),
        )

    def test_a_sidecar_that_names_other_bytes_is_rejected(self) -> None:
        bundle = setup_bundle(marketplace_zip(default_entries()), sidecar="0" * 64)
        admitted = self.admit(release(bundle))
        assert isinstance(admitted, publisher.Rejected)
        self.assertIs(admitted.failure, publisher.Failure.MARKETPLACE_MISMATCH)

    def test_a_skill_command_without_its_executable_mode_is_rejected(self) -> None:
        entries = {**default_entries(), RECIPE: (b"#!/bin/sh\n", 0o644)}
        admitted = self.admit(release(setup_bundle(marketplace_zip(entries))))
        self.assertEqual(
            admitted,
            publisher.Rejected(
                publisher.Failure.COMMAND_NOT_EXECUTABLE,
                "plugins/kast/skills/kast/scripts/kast-recipe",
            ),
        )

    def test_an_entry_that_escapes_the_composition_is_rejected(self) -> None:
        entries = {**default_entries(), "compositions/mcp/../../outside": (b"x", 0o644)}
        admitted = self.admit(release(setup_bundle(marketplace_zip(entries))))
        assert isinstance(admitted, publisher.Rejected)
        self.assertIs(admitted.failure, publisher.Failure.ENTRY_INVALID)

    def test_an_unmapped_release_has_no_bundle_request(self) -> None:
        source = ScriptedRelease({})
        admitted = self.admit(source)
        assert isinstance(admitted, publisher.Rejected)
        self.assertIs(admitted.failure, publisher.Failure.RELEASE_UNAVAILABLE)
        self.assertEqual(len(source.requests), 1)


class WriteTest(unittest.TestCase):
    def test_write_replaces_everything_except_git_metadata(self) -> None:
        archive = marketplace_zip(default_entries())
        with tempfile.TemporaryDirectory() as directory:
            admitted = publisher.admit(PUBLIC, release(setup_bundle(archive)), Path(directory))
        assert isinstance(admitted, publisher.Marketplace)
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            (checkout / ".git").mkdir()
            _ = (checkout / ".git/HEAD").write_text("ref: refs/heads/main\n")
            (checkout / "plugins/retired").mkdir(parents=True)
            _ = (checkout / "plugins/retired/file").write_text("old")
            self.assertIsNone(publisher.write(admitted, checkout))
            files = sorted(
                path.relative_to(checkout).as_posix()
                for path in checkout.rglob("*")
                if path.is_file() and ".git" not in path.parts
            )
            self.assertEqual(
                files,
                [
                    ".agents/plugins/marketplace.json",
                    ".claude-plugin/marketplace.json",
                    "README.md",
                    "kast-release.json",
                    "plugins/kast/skills/kast/scripts/kast-recipe",
                ],
            )
            recipe = checkout / "plugins/kast/skills/kast/scripts/kast-recipe"
            self.assertTrue(recipe.stat().st_mode & stat.S_IXUSR)
            self.assertEqual((checkout / ".git/HEAD").read_text(), "ref: refs/heads/main\n")
            self.assertEqual(
                json.loads((checkout / "kast-release.json").read_text())["publicVersion"], PUBLIC
            )

    def test_a_directory_without_git_metadata_is_not_a_checkout(self) -> None:
        marketplace = publisher.Marketplace(
            publisher.ReleaseIdentity(PUBLIC, VERSION, "1" * 40, "a" * 64, "b" * 64), ()
        )
        with tempfile.TemporaryDirectory() as directory:
            _ = (Path(directory) / "keep").write_text("x")
            rejected = publisher.write(marketplace, Path(directory))
            self.assertIsNotNone(rejected)
            self.assertTrue((Path(directory) / "keep").exists())


if __name__ == "__main__":
    unittest.main()
