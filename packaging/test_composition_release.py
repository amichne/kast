"""Release orchestration rejects unqualified inputs before creating output."""

import tempfile
from pathlib import Path

import pytest

import composed_release
from composed_bundle import BundleFailure, BundleRejected
from composed_release import ReleaseFailure, ReleaseRejected, assemble_release, qualify_release
from managed_headless import Failure, Rejected


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


def test_wrong_donor_digest_rejects_before_unpacking(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The only virtualized input is the external machine observation. Admission
    # hashes actual files and must not create a donor or invoke an installer.
    monkeypatch.setattr(composed_release.platform, "system", lambda: "Linux")
    monkeypatch.setattr(composed_release.platform, "machine", lambda: "x86_64")
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
    # Fixture preparation is an allowed local effect, not native evidence.
    assert (tmp_path / "workspace/gradlew").is_file()
