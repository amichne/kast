"""Release orchestration rejects unqualified inputs before creating output."""

import io
import tarfile
import tempfile
from pathlib import Path

import pytest

import composed_release
from composed_bundle import BundleFailure, BundleRejected, sha256
from composed_release import ReleaseFailure, ReleaseRejected, assemble_release, qualify_release
from managed_headless import Failure, Rejected


def test_linux_support_includes_the_cpp_soname_required_by_the_arm_host() -> None:
    assert composed_release.LINUX_PACKAGES.get("libstdc++6") == ("libstdc++.so.6",)


@pytest.mark.parametrize(
    ("version", "revision", "failure"),
    [
        ("latest", "a" * 40, ReleaseFailure.INVALID_IDENTITY),
        ("0.1.2", "main", ReleaseFailure.INVALID_IDENTITY),
        ("0.1.2", "0" * 40, ReleaseFailure.INVALID_IDENTITY),
    ],
)
def test_invalid_release_identity_has_no_filesystem_effects(
    tmp_path: Path, version: str, revision: str, failure: ReleaseFailure
) -> None:
    result = assemble_release(
        archive=tmp_path / "missing.tar.gz",
        bootstrap=tmp_path / "missing.zip",
        assets=tmp_path / "missing-assets",
        root=tmp_path / "producer",
        output=tmp_path / "output",
        version=version,
        revision=revision,
    )
    assert result == ReleaseRejected(failure, "identity")
    assert list(tmp_path.iterdir()) == []


def test_existing_work_is_preserved_before_reading_inputs(tmp_path: Path) -> None:
    root = tmp_path / "producer"
    root.mkdir()
    sentinel = root / "sentinel"
    _ = sentinel.write_text("preserve")
    result = assemble_release(
        archive=tmp_path / "missing.tar.gz",
        bootstrap=tmp_path / "missing.zip",
        assets=tmp_path / "missing-assets",
        root=root,
        output=tmp_path / "output",
        version="0.1.2",
        revision="a" * 40,
    )
    assert result == ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "root or output")
    assert sentinel.read_text() == "preserve"
    assert not (tmp_path / "output").exists()


@pytest.mark.parametrize(("system", "machine"), [("Linux", "x86_64"), ("Darwin", "arm64")])
def test_wrong_donor_digest_rejects_before_unpacking(
    monkeypatch: pytest.MonkeyPatch,
    system: str,
    machine: str,
) -> None:
    # The only virtualized input is the external machine observation. Admission
    # hashes actual files and must not create a donor or invoke an installer.
    monkeypatch.setattr(composed_release.platform, "system", lambda: system)
    monkeypatch.setattr(composed_release.platform, "machine", lambda: machine)
    # macOS resolves /tmp to /private/tmp; leave room for the native socket suffix.
    with tempfile.TemporaryDirectory(prefix="", dir="/tmp") as temporary:
        owned = Path(temporary).resolve()
        archive, bootstrap, assets = (
            owned / "donor.tar.gz",
            owned / "bootstrap.zip",
            owned / "assets",
        )
        _ = archive.write_bytes(b"not the pinned vendor archive")
        _ = bootstrap.write_bytes(b"unused bootstrap")
        assets.mkdir()
        result = assemble_release(
            archive=archive,
            bootstrap=bootstrap,
            assets=assets,
            root=owned / "r",
            output=owned / "out",
            version="0.1.2",
            revision="a" * 40,
        )
        assert result == Rejected(Failure.DIGEST_MISMATCH)
        assert not (owned / "r").exists()
        assert not (owned / "out").exists()


def test_wrong_bundle_digest_never_creates_native_qualification(tmp_path: Path) -> None:
    archive = tmp_path / "bundle.tar.gz"
    _ = archive.write_bytes(b"changed bundle")
    result = qualify_release(
        archive=archive,
        expected="b" * 64,
        version="0.1.2",
        revision="a" * 40,
        root=tmp_path / "r",
        fixture=tmp_path / "workspace",
        evidence=tmp_path / "evidence",
    )
    assert result == BundleRejected(BundleFailure.DIGEST_MISMATCH, "bundle archive")
    assert not (tmp_path / "r").exists()
    assert not (tmp_path / "evidence").exists()
    assert not (tmp_path / "workspace").exists()


@pytest.mark.parametrize("entry_count", [0, 2])
def test_authenticated_archive_rejects_missing_or_duplicate_zipapp(
    tmp_path: Path, entry_count: int
) -> None:
    archive = tmp_path / "candidate.tar.gz"
    entry = "setup-kast/bin/setup-kast.pyz"
    with tarfile.open(archive, "w:gz") as bundle:
        for _ in range(entry_count):
            info = tarfile.TarInfo(entry)
            info.size = 1
            bundle.addfile(info, io.BytesIO(b"x"))
    result = qualify_release(
        archive=archive,
        expected=sha256(archive),
        version="0.1.2",
        revision="a" * 40,
        root=tmp_path / "r",
        fixture=tmp_path / "workspace",
        evidence=tmp_path / "evidence",
    )
    reason = BundleFailure.INVALID_MANIFEST if entry_count == 0 else BundleFailure.UNSAFE_PATH
    assert result == BundleRejected(reason, entry)
    assert list(tmp_path.iterdir()) == [archive]
